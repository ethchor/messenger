//! Keeping one person's devices in step (docs/LINKED-DEVICES.md).
//!
//! - **The device group:** a private MLS group of the account's own devices,
//!   never listed as a chat. What each device does with its chats (pin,
//!   archive, mute, lock, mark unread), who it blocks, and which chats it
//!   read go there as [`DeviceSync`] messages, and the others do the same.
//! - **History:** the device that approves a link uploads an encrypted
//!   archive of its chats; the new device imports it, so its chat list and
//!   messages are there before the chats themselves bring it in. Files come
//!   across as keys and server ids, and are fetched when opened.

use messenger_protocol::{
    api::NewBlobRequest,
    b64,
    v1::{content::Body, group_meta::Kind, ChatPrefs, Content, DeviceSync},
    BLOB_ID_LEN,
};
use rusqlite::{types::Value, Connection};
use serde::{Deserialize, Serialize};

use crate::{
    api::RequestSigner,
    client::{queue_content, Client, Ctx},
    keys,
    media::{decrypt_file, encrypt_file, encrypted_len},
    store::{self, attachment_state, conversation_kind, ConversationRow},
    Error, Result,
};

// ---- the device group ----

/// Our devices' group, once there is one.
pub(crate) fn device_group(conn: &Connection) -> Result<Option<ConversationRow>> {
    Ok(store::conversations(conn)?.into_iter().find(|c| c.kind == conversation_kind::DEVICES && c.active))
}

fn prefs_message(conn: &Connection, conversation_id: &[u8]) -> Result<ChatPrefs> {
    let p = store::conversation_prefs(conn, conversation_id)?;
    Ok(ChatPrefs {
        conversation_id: conversation_id.to_vec(),
        pinned_at: p.pinned_at.unwrap_or(0),
        archived: p.archived,
        muted_until: p.muted_until.unwrap_or(0),
        locked: p.locked,
        marked_unread: p.marked_unread,
    })
}

/// Tells our other devices, if we have any. Queued in the same transaction
/// as the change itself.
pub(crate) fn queue_sync(ctx: &Ctx<'_>, sync: DeviceSync) -> Result<bool> {
    let Some(group) = device_group(ctx.conn)? else {
        return Ok(false);
    };
    let content = Content {
        id: keys::random_bytes::<16>()?.to_vec(),
        sent_at_ms: store::now_ms().max(0) as u64,
        body: Some(Body::DeviceSync(sync)),
    };
    queue_content(ctx, &group, &content, false)?;
    Ok(true)
}

/// A chat's settings changed here.
pub(crate) fn queue_prefs_sync(ctx: &Ctx<'_>, conversation_id: &[u8]) -> Result<bool> {
    let prefs = prefs_message(ctx.conn, conversation_id)?;
    queue_sync(ctx, DeviceSync { prefs: vec![prefs], ..Default::default() })
}

/// Applies what another of our devices did. Only our own devices' group
/// carries these.
pub(crate) fn apply_sync(ctx: &Ctx<'_>, conv: &ConversationRow, sync: &DeviceSync) -> Result<()> {
    if conv.kind != conversation_kind::DEVICES {
        return Ok(());
    }
    for p in &sync.prefs {
        if store::conversation(ctx.conn, &p.conversation_id)?.is_none() {
            continue;
        }
        let mut prefs = store::conversation_prefs(ctx.conn, &p.conversation_id)?;
        prefs.pinned_at = (p.pinned_at > 0).then_some(p.pinned_at);
        prefs.archived = p.archived;
        prefs.muted_until = (p.muted_until > 0).then_some(p.muted_until);
        prefs.locked = p.locked;
        prefs.marked_unread = p.marked_unread;
        store::set_conversation_prefs(ctx.conn, &p.conversation_id, &prefs)?;
    }
    for name in sync.blocked.iter().filter_map(|n| messenger_protocol::normalize_username(n).ok()) {
        store::set_blocked(ctx.conn, &name, true)?;
    }
    for name in sync.unblocked.iter().filter_map(|n| messenger_protocol::normalize_username(n).ok()) {
        store::set_blocked(ctx.conn, &name, false)?;
    }
    for id in &sync.read {
        if store::conversation(ctx.conn, id)?.is_some() {
            store::mark_read(ctx.conn, id)?;
            let mut prefs = store::conversation_prefs(ctx.conn, id)?;
            if prefs.marked_unread {
                prefs.marked_unread = false;
                store::set_conversation_prefs(ctx.conn, id, &prefs)?;
            }
        }
    }
    Ok(())
}

impl Client {
    /// Makes our devices' group if we have none yet; the devices we link are
    /// then added to it like to any chat.
    pub(crate) fn ensure_device_group(&self) -> Result<()> {
        if device_group(&self.lock()?.conn)?.is_some() {
            return Ok(());
        }
        self.create_conversation(Kind::Devices, "", "", None, Vec::new())?;
        Ok(())
    }

    // ---- history ----

    /// Writes this device's chats into an encrypted archive and uploads it.
    pub(crate) fn upload_history(&self) -> Result<HistoryRef> {
        let archive = {
            let state = self.lock()?;
            export(&state.conn)?
        };
        let transit = self.transit_dir()?;
        let id = hex::encode(keys::random_bytes::<8>()?);
        let plain = transit.join(format!("history-{id}.json"));
        let sealed = transit.join(format!("history-{id}.sealed"));
        let result = (|| {
            std::fs::write(&plain, serde_json::to_vec(&archive)?)
                .map_err(|e| Error::Storage(format!("writing the history: {e}")))?;
            let size = std::fs::metadata(&plain).map_err(|e| Error::Storage(e.to_string()))?.len();
            let key = keys::random_bytes::<32>()?.to_vec();
            let digest = encrypt_file(&self.crypto, &key, &plain, size, &sealed)?;
            let length = encrypted_len(size);
            let (account, signer) = self.identity()?;
            let sign = |payload: &[u8]| keys::sign(&signer, payload);
            let request_signer =
                RequestSigner { account_id: &account.account_id, device_id: account.device_id(), sign: &sign };
            let blob = self.api.new_blob(&NewBlobRequest { size: length, digest: digest.to_vec() }, &request_signer)?;
            if blob.id.len() != BLOB_ID_LEN {
                return Err(Error::Internal("the server gave a bad file id".into()));
            }
            self.api.put_blob(&blob, &sealed, length)?;
            Ok(HistoryRef { blob_id: blob.id, key, digest: digest.to_vec(), size })
        })();
        let _ = std::fs::remove_file(&plain);
        let _ = std::fs::remove_file(&sealed);
        result
    }

    /// Fetches the archive another device left us and adds it to what's
    /// here. Running it twice changes nothing.
    pub(crate) fn import_history(&self, history: &HistoryRef) -> Result<()> {
        let transit = self.transit_dir()?;
        let sealed = transit.join(format!("history-{}.sealed", hex::encode(&history.blob_id)));
        let plain = sealed.with_extension("json");
        let result = (|| {
            self.api.get_blob(&history.blob_id, &sealed, encrypted_len(history.size))?;
            decrypt_file(&self.crypto, &history.key, &sealed, &history.digest, history.size, &plain)?;
            let bytes = std::fs::read(&plain).map_err(|e| Error::Storage(format!("reading the history: {e}")))?;
            let archive: Archive = serde_json::from_slice(&bytes)?;
            self.with_tx(|ctx| import(ctx.conn, &archive))
        })();
        let _ = std::fs::remove_file(&sealed);
        let _ = std::fs::remove_file(&plain);
        result
    }
}

/// Where the history archive is, and how to open it.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct HistoryRef {
    #[serde(with = "b64")]
    blob_id: Vec<u8>,
    #[serde(with = "b64")]
    key: Vec<u8>,
    #[serde(with = "b64")]
    digest: Vec<u8>,
    size: u64,
}

/// One table's rows, column by column.
#[derive(Debug, Default, Serialize, Deserialize)]
struct Rows {
    columns: Vec<String>,
    rows: Vec<Vec<Cell>>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(tag = "t", content = "v")]
enum Cell {
    Null,
    Int(i64),
    Real(f64),
    Text(String),
    Blob(#[serde(with = "b64")] Vec<u8>),
}

impl From<Value> for Cell {
    fn from(v: Value) -> Self {
        match v {
            Value::Null => Cell::Null,
            Value::Integer(i) => Cell::Int(i),
            Value::Real(r) => Cell::Real(r),
            Value::Text(t) => Cell::Text(t),
            Value::Blob(b) => Cell::Blob(b),
        }
    }
}

impl rusqlite::ToSql for Cell {
    fn to_sql(&self) -> rusqlite::Result<rusqlite::types::ToSqlOutput<'_>> {
        use rusqlite::types::{ToSqlOutput, ValueRef};
        Ok(ToSqlOutput::Borrowed(match self {
            Cell::Null => ValueRef::Null,
            Cell::Int(i) => ValueRef::Integer(*i),
            Cell::Real(r) => ValueRef::Real(*r),
            Cell::Text(t) => ValueRef::Text(t.as_bytes()),
            Cell::Blob(b) => ValueRef::Blob(b),
        }))
    }
}

/// The archive: the tables that make up the chats, as they are on the
/// device that wrote it.
#[derive(Debug, Default, Serialize, Deserialize)]
struct Archive {
    version: u32,
    tables: Vec<(String, Rows)>,
}

const ARCHIVE_VERSION: u32 = 1;

/// Chats, not our devices' own group.
const IN_CHATS: &str = "conversation_id IN (SELECT id FROM conversations WHERE kind != 3)";

/// What goes into the archive: (table, columns, which rows). Messages that
/// vanish once seen stay behind, and so do files that never got uploaded.
fn archived_tables() -> Vec<(&'static str, &'static str, String)> {
    vec![
        (
            "conversations",
            "id, kind, title, peer_username, member_count, unread, created_at, updated_at",
            "kind != 3".into(),
        ),
        (
            "messages",
            "conversation_id, content_id, sender_account, sender_username, outgoing, system, sent_at, \
             received_at, text, reply_to, edited_at, deleted, status, epoch, seq, recipients, thread_root",
            format!("vanish = 0 AND {IN_CHATS}"),
        ),
        ("reactions", "conversation_id, target, sender_account, sender_username, emoji, updated_at", IN_CHATS.into()),
        ("read_receipts", "conversation_id, target, reader_account, reader_username, read_at", IN_CHATS.into()),
        (
            "attachments",
            "conversation_id, content_id, position, kind, blob_id, key, digest, size, content_type, file_name, \
             width, height, duration_ms, thumbnail, waveform",
            format!("blob_id IS NOT NULL AND digest IS NOT NULL AND {IN_CHATS}"),
        ),
        ("profiles", "account_id, username, avatar, ink, sent_at", "1".into()),
        (
            "conversation_prefs",
            "conversation_id, pinned_at, archived, muted_until, locked, marked_unread",
            format!("hidden = 0 AND {IN_CHATS}"),
        ),
        ("blocked", "username, blocked_at", "1".into()),
        ("vanish_mode", "conversation_id, since", IN_CHATS.into()),
    ]
}

fn export(conn: &Connection) -> Result<Archive> {
    let mut tables = Vec::new();
    for (table, columns, filter) in archived_tables() {
        let mut stmt = conn.prepare(&format!("SELECT {columns} FROM {table} WHERE {filter}"))?;
        let names: Vec<String> = stmt.column_names().into_iter().map(str::to_string).collect();
        let count = names.len();
        let rows = stmt
            .query_map([], |row| (0..count).map(|i| row.get::<_, Value>(i).map(Cell::from)).collect())?
            .collect::<rusqlite::Result<Vec<Vec<Cell>>>>()?;
        tables.push((table.to_string(), Rows { columns: names, rows }));
    }
    Ok(Archive { version: ARCHIVE_VERSION, tables })
}

/// Columns each table may be given, and the values filled in for the rest:
/// a chat from the archive is listed but not yet joined (no mailbox, epoch
/// 0, ended) until its group brings this device in; messages from it are
/// read and need no notification or receipt; files are fetched on demand.
fn import_extras(table: &str) -> Option<(&'static str, &'static str)> {
    match table {
        "conversations" => Some(("mailbox, epoch, active, cursor", "x'', 0, 0, 0")),
        "messages" => Some(("receipt_sent, notified", "1, 1")),
        "attachments" => Some(("state", "2")),
        "reactions" | "read_receipts" | "profiles" | "conversation_prefs" | "blocked" | "vanish_mode" => Some(("", "")),
        _ => None,
    }
}

fn import(conn: &Connection, archive: &Archive) -> Result<()> {
    if archive.version != ARCHIVE_VERSION {
        return Err(Error::InvalidInput("this history comes from a newer app; update the app".into()));
    }
    debug_assert_eq!(attachment_state::REMOTE, 2);
    for (table, rows) in &archive.tables {
        let Some((extra_columns, extra_values)) = import_extras(table) else {
            continue;
        };
        let allowed: Vec<&str> = archived_tables()
            .into_iter()
            .find(|(t, _, _)| t == table)
            .map(|(_, columns, _)| columns.split(',').map(str::trim).collect())
            .unwrap_or_default();
        if rows.columns.iter().any(|c| !allowed.contains(&c.as_str())) {
            return Err(Error::InvalidInput("the history has columns this app doesn't know".into()));
        }
        let placeholders = (1..=rows.columns.len()).map(|i| format!("?{i}")).collect::<Vec<_>>().join(", ");
        let (columns, values) = if extra_columns.is_empty() {
            (rows.columns.join(", "), placeholders)
        } else {
            (format!("{}, {extra_columns}", rows.columns.join(", ")), format!("{placeholders}, {extra_values}"))
        };
        let mut stmt = conn.prepare(&format!("INSERT OR IGNORE INTO {table} ({columns}) VALUES ({values})"))?;
        for row in &rows.rows {
            if row.len() != rows.columns.len() {
                return Err(Error::InvalidInput("the history is damaged".into()));
            }
            stmt.execute(rusqlite::params_from_iter(row.iter()))?;
        }
    }
    Ok(())
}
