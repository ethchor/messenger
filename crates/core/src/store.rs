//! App tables next to OpenMLS's own tables in the same SQLite file.

use rusqlite::{params, Connection, OptionalExtension, Row};

use crate::Result;

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value BLOB NOT NULL
);

-- One row per MLS group. 1:1 chats are two-member groups.
CREATE TABLE IF NOT EXISTS conversations (
    id              BLOB PRIMARY KEY,      -- MLS group id
    kind            INTEGER NOT NULL,      -- 1 direct, 2 group
    title           TEXT NOT NULL,
    peer_username   TEXT,                  -- direct chats
    mailbox         BLOB NOT NULL,         -- delivery id of the current epoch
    cursor          INTEGER NOT NULL DEFAULT 0,
    prev_commit_seq INTEGER,               -- latest (skipped) commit in this mailbox
    epoch           INTEGER NOT NULL,
    member_count    INTEGER NOT NULL DEFAULT 1,
    active          INTEGER NOT NULL DEFAULT 1,
    unread          INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS messages (
    local_id        INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id BLOB NOT NULL,
    content_id      BLOB NOT NULL,
    sender_account  BLOB NOT NULL,
    sender_username TEXT NOT NULL,
    outgoing        INTEGER NOT NULL,
    system          INTEGER NOT NULL DEFAULT 0,
    sent_at         INTEGER NOT NULL,
    received_at     INTEGER NOT NULL,
    text            TEXT NOT NULL DEFAULT '',
    reply_to        BLOB,
    edited_at       INTEGER,
    deleted         INTEGER NOT NULL DEFAULT 0,
    status          INTEGER NOT NULL,
    -- Position in the server's order: the epoch of the mailbox it was read
    -- from (or accepted into) and its sequence number there. NULL while our
    -- own message is unsent. Every member sees the same order.
    epoch           INTEGER,
    seq             INTEGER,
    UNIQUE (conversation_id, content_id)
);
CREATE INDEX IF NOT EXISTS messages_by_position ON messages(conversation_id, epoch, seq, local_id);

CREATE TABLE IF NOT EXISTS reactions (
    conversation_id BLOB NOT NULL,
    target          BLOB NOT NULL,
    sender_account  BLOB NOT NULL,
    sender_username TEXT NOT NULL,
    emoji           TEXT NOT NULL,
    updated_at      INTEGER NOT NULL,
    PRIMARY KEY (conversation_id, target, sender_account)
);

-- Everything waiting to be posted, or posted and waiting for its echo.
CREATE TABLE IF NOT EXISTS outbox (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id BLOB NOT NULL,
    kind            INTEGER NOT NULL,      -- messenger_protocol::api::MessageKind code
    state           INTEGER NOT NULL,      -- see outbox_state
    mailbox         BLOB,
    client_msg_id   BLOB NOT NULL,
    prev_commit_seq INTEGER,
    payload         BLOB,                  -- MLS message, once encrypted
    plaintext       BLOB,                  -- padded content of application messages
    content_id      BLOB,
    depends_on      INTEGER,               -- Welcome rows: the commit they wait for
    op_id           INTEGER,               -- commit rows: the operation they carry out
    epoch           INTEGER,               -- epoch the payload was encrypted in
    seq             INTEGER,
    created_at      INTEGER NOT NULL
);

-- Group changes (add, remove) that must be retried if another member's
-- commit wins the race for the epoch.
CREATE TABLE IF NOT EXISTS ops (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id BLOB NOT NULL,
    op              TEXT NOT NULL,         -- JSON of crate::client::Op
    state           INTEGER NOT NULL,      -- see op_state
    attempts        INTEGER NOT NULL DEFAULT 0,
    created_at      INTEGER NOT NULL
);
"#;

/// Schema changes after the first release, applied in order on open. `PRAGMA
/// user_version` records how many have run. Append only.
const MIGRATIONS: &[&str] = &[
    // 1: read receipts, typing indicators.
    r#"
    ALTER TABLE messages ADD COLUMN receipt_sent INTEGER NOT NULL DEFAULT 0;
    ALTER TABLE outbox ADD COLUMN ephemeral INTEGER NOT NULL DEFAULT 0;
    -- Who has read our messages.
    CREATE TABLE IF NOT EXISTS read_receipts (
        conversation_id BLOB NOT NULL,
        target          BLOB NOT NULL,
        reader_account  BLOB NOT NULL,
        reader_username TEXT NOT NULL,
        read_at         INTEGER NOT NULL,
        PRIMARY KEY (conversation_id, target, reader_account)
    );
    "#,
    // 2: how many people each of our messages went to, so "read by everyone"
    // doesn't change when people join or leave later.
    r#"
    ALTER TABLE messages ADD COLUMN recipients INTEGER;
    "#,
    // 3: live location sharing.
    r#"
    -- Location updates: only the newest unsent one per conversation matters.
    ALTER TABLE outbox ADD COLUMN latest_only INTEGER NOT NULL DEFAULT 0;
    -- The latest location of each person sharing with us, per conversation.
    -- No history is kept.
    CREATE TABLE IF NOT EXISTS locations (
        conversation_id BLOB NOT NULL,
        sender_account  BLOB NOT NULL,
        sender_username TEXT NOT NULL,
        latitude        REAL NOT NULL,
        longitude       REAL NOT NULL,
        accuracy_m      REAL NOT NULL,
        speed_mps       REAL,
        course_deg      REAL,
        battery_percent INTEGER,
        measured_at     INTEGER NOT NULL,
        received_at     INTEGER NOT NULL,
        until_ms        INTEGER,               -- NULL: until they stop
        PRIMARY KEY (conversation_id, sender_account)
    );
    -- Where we share our own location, and until when.
    CREATE TABLE IF NOT EXISTS location_shares (
        conversation_id BLOB PRIMARY KEY,
        started_at      INTEGER NOT NULL,
        until_ms        INTEGER,               -- NULL: until we stop
        last_sent_at    INTEGER
    );
    "#,
    // 4: push notifications.
    r#"
    -- The other members' wake tokens: naming one in a post makes the server
    -- push that member's device (see crate::push).
    CREATE TABLE IF NOT EXISTS wake_tokens (
        conversation_id BLOB NOT NULL,
        account_id      BLOB NOT NULL,
        username        TEXT NOT NULL,
        token           BLOB NOT NULL,
        updated_at      INTEGER NOT NULL,
        PRIMARY KEY (conversation_id, account_id)
    );
    -- Outgoing messages that wake the people they go to.
    ALTER TABLE outbox ADD COLUMN notify INTEGER NOT NULL DEFAULT 0;
    -- Incoming messages the app has been given to notify about (or that were
    -- read first). Everything from before this version counts as done.
    ALTER TABLE messages ADD COLUMN notified INTEGER NOT NULL DEFAULT 0;
    UPDATE messages SET notified = 1;
    CREATE INDEX IF NOT EXISTS messages_to_notify ON messages(local_id) WHERE notified = 0;
    "#,
    // 5: profiles (avatar and ink), ours included.
    r#"
    CREATE TABLE IF NOT EXISTS profiles (
        account_id BLOB PRIMARY KEY,
        username   TEXT NOT NULL,
        avatar     TEXT NOT NULL,
        ink        TEXT NOT NULL,
        sent_at    INTEGER NOT NULL      -- the sender's clock, so older ones lose
    );
    "#,
];

fn migrate(conn: &mut Connection) -> Result<()> {
    let version: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
    for (index, sql) in MIGRATIONS.iter().enumerate().skip(version.max(0) as usize) {
        let tx = conn.transaction()?;
        tx.execute_batch(sql)?;
        tx.pragma_update(None, "user_version", (index + 1) as i64)?;
        tx.commit()?;
    }
    Ok(())
}

pub mod outbox_state {
    /// Application message not yet encrypted (waiting for a pending commit to
    /// settle, or re-queued after a conflict).
    pub const QUEUED: i64 = 0;
    /// Encrypted and addressed; to be posted.
    pub const READY: i64 = 1;
    /// Welcome waiting for its commit to be accepted.
    pub const BLOCKED: i64 = 2;
    /// Accepted by the server; waiting for the echo in the mailbox.
    pub const ACCEPTED: i64 = 3;
    /// Rejected as stale; re-queued after the next fetch is processed.
    pub const CONFLICT: i64 = 4;
}

pub mod op_state {
    /// A commit for this op is in the outbox.
    pub const COMMITTED: i64 = 0;
    /// Needs a (new) commit.
    pub const PENDING: i64 = 1;
    pub const DONE: i64 = 2;
    pub const FAILED: i64 = 3;
}

pub mod message_status {
    pub const SENDING: i64 = 0;
    pub const SENT: i64 = 1;
    pub const FAILED: i64 = 2;
    pub const RECEIVED: i64 = 3;
}

pub mod conversation_kind {
    pub const DIRECT: i64 = 1;
    pub const GROUP: i64 = 2;
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub fn open(path: &std::path::Path) -> Result<Connection> {
    let mut conn = Connection::open(path)?;
    conn.pragma_update(None, "journal_mode", "WAL")?;
    // FULL: a commit acknowledged to the server must survive power loss.
    conn.pragma_update(None, "synchronous", "FULL")?;
    conn.busy_timeout(std::time::Duration::from_secs(10))?;
    crate::provider::migrate(&mut conn)?;
    conn.execute_batch(SCHEMA)?;
    migrate(&mut conn)?;
    Ok(conn)
}

/// A boolean setting; `default` when never set.
pub fn meta_get_bool(conn: &Connection, key: &str, default: bool) -> Result<bool> {
    Ok(meta_get(conn, key)?.map(|v| v.first() == Some(&1)).unwrap_or(default))
}

pub fn meta_set_bool(conn: &Connection, key: &str, value: bool) -> Result<()> {
    meta_set(conn, key, &[u8::from(value)])
}

// ---- meta ----

pub fn meta_get(conn: &Connection, key: &str) -> Result<Option<Vec<u8>>> {
    Ok(conn.query_row("SELECT value FROM meta WHERE key = ?1", params![key], |r| r.get(0)).optional()?)
}

pub fn meta_get_string(conn: &Connection, key: &str) -> Result<Option<String>> {
    Ok(meta_get(conn, key)?.and_then(|v| String::from_utf8(v).ok()))
}

pub fn meta_set(conn: &Connection, key: &str, value: &[u8]) -> Result<()> {
    conn.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        params![key, value],
    )?;
    Ok(())
}

pub fn meta_delete(conn: &Connection, key: &str) -> Result<()> {
    conn.execute("DELETE FROM meta WHERE key = ?1", params![key])?;
    Ok(())
}

pub fn meta_get_u64(conn: &Connection, key: &str) -> Result<u64> {
    Ok(meta_get(conn, key)?.and_then(|v| <[u8; 8]>::try_from(v.as_slice()).ok()).map(u64::from_be_bytes).unwrap_or(0))
}

pub fn meta_set_u64(conn: &Connection, key: &str, value: u64) -> Result<()> {
    meta_set(conn, key, &value.to_be_bytes())
}

// ---- conversations ----

#[derive(Debug, Clone)]
pub struct ConversationRow {
    pub id: Vec<u8>,
    pub kind: i64,
    pub title: String,
    pub peer_username: Option<String>,
    pub mailbox: Vec<u8>,
    pub cursor: u64,
    pub prev_commit_seq: Option<u64>,
    pub epoch: u64,
    pub member_count: u32,
    pub active: bool,
    pub unread: u32,
    pub created_at: i64,
    pub updated_at: i64,
}

const CONVERSATION_COLUMNS: &str = "id, kind, title, peer_username, mailbox, cursor, prev_commit_seq, epoch, \
     member_count, active, unread, created_at, updated_at";

fn conversation_from_row(r: &Row<'_>) -> rusqlite::Result<ConversationRow> {
    Ok(ConversationRow {
        id: r.get(0)?,
        kind: r.get(1)?,
        title: r.get(2)?,
        peer_username: r.get(3)?,
        mailbox: r.get(4)?,
        cursor: r.get::<_, i64>(5)? as u64,
        prev_commit_seq: r.get::<_, Option<i64>>(6)?.map(|v| v as u64),
        epoch: r.get::<_, i64>(7)? as u64,
        member_count: r.get::<_, i64>(8)? as u32,
        active: r.get::<_, i64>(9)? != 0,
        unread: r.get::<_, i64>(10)? as u32,
        created_at: r.get(11)?,
        updated_at: r.get(12)?,
    })
}

pub fn insert_conversation(conn: &Connection, c: &ConversationRow) -> Result<()> {
    conn.execute(
        &format!("INSERT INTO conversations ({CONVERSATION_COLUMNS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)"),
        params![
            c.id,
            c.kind,
            c.title,
            c.peer_username,
            c.mailbox,
            c.cursor as i64,
            c.prev_commit_seq.map(|v| v as i64),
            c.epoch as i64,
            c.member_count as i64,
            c.active as i64,
            c.unread as i64,
            c.created_at,
            c.updated_at
        ],
    )?;
    Ok(())
}

pub fn conversation(conn: &Connection, id: &[u8]) -> Result<Option<ConversationRow>> {
    Ok(conn
        .query_row(
            &format!("SELECT {CONVERSATION_COLUMNS} FROM conversations WHERE id = ?1"),
            params![id],
            conversation_from_row,
        )
        .optional()?)
}

pub fn conversation_by_mailbox(conn: &Connection, mailbox: &[u8]) -> Result<Option<ConversationRow>> {
    Ok(conn
        .query_row(
            &format!("SELECT {CONVERSATION_COLUMNS} FROM conversations WHERE mailbox = ?1 AND active = 1"),
            params![mailbox],
            conversation_from_row,
        )
        .optional()?)
}

pub fn conversations(conn: &Connection) -> Result<Vec<ConversationRow>> {
    let mut stmt =
        conn.prepare(&format!("SELECT {CONVERSATION_COLUMNS} FROM conversations ORDER BY updated_at DESC"))?;
    let rows = stmt.query_map([], conversation_from_row)?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn active_direct_with(conn: &Connection, username: &str) -> Result<Option<ConversationRow>> {
    Ok(conn
        .query_row(
            &format!(
                "SELECT {CONVERSATION_COLUMNS} FROM conversations WHERE kind = 1 AND active = 1 AND peer_username = ?1 \
                 ORDER BY created_at LIMIT 1"
            ),
            params![username],
            conversation_from_row,
        )
        .optional()?)
}

/// Saves the sync position fields of a conversation.
pub fn update_position(conn: &Connection, c: &ConversationRow) -> Result<()> {
    conn.execute(
        "UPDATE conversations SET mailbox = ?2, cursor = ?3, prev_commit_seq = ?4, epoch = ?5, member_count = ?6, \
         active = ?7 WHERE id = ?1",
        params![
            c.id,
            c.mailbox,
            c.cursor as i64,
            c.prev_commit_seq.map(|v| v as i64),
            c.epoch as i64,
            c.member_count as i64,
            c.active as i64
        ],
    )?;
    Ok(())
}

pub fn touch_conversation(conn: &Connection, id: &[u8], unread_delta: i64) -> Result<()> {
    conn.execute(
        "UPDATE conversations SET updated_at = ?2, unread = unread + ?3 WHERE id = ?1",
        params![id, now_ms(), unread_delta],
    )?;
    Ok(())
}

pub fn mark_read(conn: &Connection, id: &[u8]) -> Result<()> {
    conn.execute("UPDATE conversations SET unread = 0 WHERE id = ?1", params![id])?;
    // Read messages need no notification any more.
    conn.execute("UPDATE messages SET notified = 1 WHERE conversation_id = ?1 AND notified = 0", params![id])?;
    Ok(())
}

pub fn set_conversation_active(conn: &Connection, id: &[u8], active: bool) -> Result<()> {
    conn.execute("UPDATE conversations SET active = ?2 WHERE id = ?1", params![id, active as i64])?;
    Ok(())
}

// ---- read receipts ----

/// Incoming messages we haven't sent (or decided not to send) a read receipt
/// for, oldest first.
pub fn unreceipted_incoming(conn: &Connection, conversation_id: &[u8], limit: u32) -> Result<Vec<Vec<u8>>> {
    let mut stmt = conn.prepare(
        "SELECT content_id FROM messages WHERE conversation_id = ?1 AND outgoing = 0 AND system = 0 \
         AND deleted = 0 AND receipt_sent = 0 ORDER BY local_id LIMIT ?2",
    )?;
    let rows = stmt.query_map(params![conversation_id, limit], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn set_receipt_sent(conn: &Connection, conversation_id: &[u8], content_ids: &[Vec<u8>]) -> Result<()> {
    let mut stmt =
        conn.prepare_cached("UPDATE messages SET receipt_sent = 1 WHERE conversation_id = ?1 AND content_id = ?2")?;
    for id in content_ids {
        stmt.execute(params![conversation_id, id])?;
    }
    Ok(())
}

/// Records that `reader` read one of our own messages. Returns whether it
/// was new.
pub fn add_read_receipt(
    conn: &Connection,
    conversation_id: &[u8],
    target: &[u8],
    reader: &[u8],
    reader_username: &str,
) -> Result<bool> {
    let changed = conn.execute(
        "INSERT OR IGNORE INTO read_receipts (conversation_id, target, reader_account, reader_username, read_at) \
         SELECT ?1, ?2, ?3, ?4, ?5 WHERE EXISTS \
             (SELECT 1 FROM messages WHERE conversation_id = ?1 AND content_id = ?2 AND outgoing = 1)",
        params![conversation_id, target, reader, reader_username, now_ms()],
    )?;
    Ok(changed > 0)
}

// ---- messages ----

/// Records how many people one of our messages went to.
pub fn set_message_recipients(conn: &Connection, conversation_id: &[u8], content_id: &[u8], n: u32) -> Result<()> {
    conn.execute(
        "UPDATE messages SET recipients = ?3 WHERE conversation_id = ?1 AND content_id = ?2",
        params![conversation_id, content_id, n],
    )?;
    Ok(())
}

#[derive(Debug, Clone)]
pub struct NewMessage<'a> {
    pub conversation_id: &'a [u8],
    pub content_id: &'a [u8],
    pub sender_account: &'a [u8],
    pub sender_username: &'a str,
    pub outgoing: bool,
    pub system: bool,
    pub sent_at: i64,
    pub text: &'a str,
    pub reply_to: Option<&'a [u8]>,
    pub status: i64,
    /// (epoch, seq) in the server's order; `None` for unsent messages.
    pub position: Option<(u64, u64)>,
}

/// Newest first in the server's order; unsent messages count as newest.
pub const MESSAGE_ORDER_DESC: &str = "(m.seq IS NULL) DESC, m.epoch DESC, m.seq DESC, m.local_id DESC";

/// Inserts a message; returns `false` if one with the same content id exists.
pub fn insert_message(conn: &Connection, m: &NewMessage<'_>) -> Result<bool> {
    let changed = conn.execute(
        "INSERT OR IGNORE INTO messages (conversation_id, content_id, sender_account, sender_username, outgoing, \
         system, sent_at, received_at, text, reply_to, status, epoch, seq) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13)",
        params![
            m.conversation_id,
            m.content_id,
            m.sender_account,
            m.sender_username,
            m.outgoing as i64,
            m.system as i64,
            m.sent_at,
            now_ms(),
            m.text,
            m.reply_to,
            m.status,
            m.position.map(|(e, _)| e as i64),
            m.position.map(|(_, s)| s as i64)
        ],
    )?;
    Ok(changed > 0)
}

/// Records where the server put one of our messages.
pub fn set_message_position(
    conn: &Connection,
    conversation_id: &[u8],
    content_id: &[u8],
    epoch: u64,
    seq: u64,
) -> Result<()> {
    conn.execute(
        "UPDATE messages SET epoch = ?3, seq = ?4 WHERE conversation_id = ?1 AND content_id = ?2",
        params![conversation_id, content_id, epoch as i64, seq as i64],
    )?;
    Ok(())
}

pub fn set_message_status(conn: &Connection, conversation_id: &[u8], content_id: &[u8], status: i64) -> Result<()> {
    conn.execute(
        "UPDATE messages SET status = ?3 WHERE conversation_id = ?1 AND content_id = ?2",
        params![conversation_id, content_id, status],
    )?;
    Ok(())
}

/// Applies an edit if `sender` wrote the target message. Returns whether it applied.
pub fn edit_message(
    conn: &Connection,
    conversation_id: &[u8],
    target: &[u8],
    sender: &[u8],
    text: &str,
) -> Result<bool> {
    Ok(conn.execute(
        "UPDATE messages SET text = ?4, edited_at = ?5 WHERE conversation_id = ?1 AND content_id = ?2 \
         AND sender_account = ?3 AND deleted = 0 AND system = 0",
        params![conversation_id, target, sender, text, now_ms()],
    )? > 0)
}

/// Deletes for everyone if `sender` wrote the target message.
pub fn delete_message(conn: &Connection, conversation_id: &[u8], target: &[u8], sender: &[u8]) -> Result<bool> {
    let changed = conn.execute(
        "UPDATE messages SET text = '', deleted = 1 WHERE conversation_id = ?1 AND content_id = ?2 \
         AND sender_account = ?3 AND system = 0",
        params![conversation_id, target, sender],
    )?;
    conn.execute("DELETE FROM reactions WHERE conversation_id = ?1 AND target = ?2", params![conversation_id, target])?;
    Ok(changed > 0)
}

pub fn set_reaction(
    conn: &Connection,
    conversation_id: &[u8],
    target: &[u8],
    sender: &[u8],
    username: &str,
    emoji: Option<&str>,
) -> Result<()> {
    match emoji {
        Some(emoji) => conn.execute(
            "INSERT INTO reactions (conversation_id, target, sender_account, sender_username, emoji, updated_at) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6) ON CONFLICT(conversation_id, target, sender_account) \
             DO UPDATE SET emoji = excluded.emoji, updated_at = excluded.updated_at",
            params![conversation_id, target, sender, username, emoji, now_ms()],
        )?,
        None => conn.execute(
            "DELETE FROM reactions WHERE conversation_id = ?1 AND target = ?2 AND sender_account = ?3",
            params![conversation_id, target, sender],
        )?,
    };
    Ok(())
}

// ---- outbox ----

#[derive(Debug, Clone)]
pub struct OutboxRow {
    pub id: i64,
    pub conversation_id: Vec<u8>,
    pub kind: i64,
    pub state: i64,
    pub mailbox: Option<Vec<u8>>,
    pub client_msg_id: Vec<u8>,
    pub prev_commit_seq: Option<u64>,
    pub payload: Option<Vec<u8>>,
    pub plaintext: Option<Vec<u8>>,
    pub content_id: Option<Vec<u8>>,
    pub depends_on: Option<i64>,
    pub op_id: Option<i64>,
    pub epoch: Option<u64>,
    pub seq: Option<u64>,
    /// Typing indicators: dropped instead of re-sent when they go stale.
    pub ephemeral: bool,
    /// Wakes the other members' devices when posted (messages worth a
    /// notification).
    pub notify: bool,
}

const OUTBOX_COLUMNS: &str = "id, conversation_id, kind, state, mailbox, client_msg_id, prev_commit_seq, payload, \
     plaintext, content_id, depends_on, op_id, epoch, seq, ephemeral, notify";

fn outbox_from_row(r: &Row<'_>) -> rusqlite::Result<OutboxRow> {
    Ok(OutboxRow {
        id: r.get(0)?,
        conversation_id: r.get(1)?,
        kind: r.get(2)?,
        state: r.get(3)?,
        mailbox: r.get(4)?,
        client_msg_id: r.get(5)?,
        prev_commit_seq: r.get::<_, Option<i64>>(6)?.map(|v| v as u64),
        payload: r.get(7)?,
        plaintext: r.get(8)?,
        content_id: r.get(9)?,
        depends_on: r.get(10)?,
        op_id: r.get(11)?,
        epoch: r.get::<_, Option<i64>>(12)?.map(|v| v as u64),
        seq: r.get::<_, Option<i64>>(13)?.map(|v| v as u64),
        ephemeral: r.get::<_, i64>(14)? != 0,
        notify: r.get::<_, i64>(15)? != 0,
    })
}

pub fn insert_outbox(conn: &Connection, row: &OutboxRow) -> Result<i64> {
    conn.execute(
        "INSERT INTO outbox (conversation_id, kind, state, mailbox, client_msg_id, prev_commit_seq, payload, plaintext, \
         content_id, depends_on, op_id, epoch, seq, ephemeral, notify, created_at) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16)",
        params![
            row.conversation_id,
            row.kind,
            row.state,
            row.mailbox,
            row.client_msg_id,
            row.prev_commit_seq.map(|v| v as i64),
            row.payload,
            row.plaintext,
            row.content_id,
            row.depends_on,
            row.op_id,
            row.epoch.map(|v| v as i64),
            row.seq.map(|v| v as i64),
            row.ephemeral as i64,
            row.notify as i64,
            now_ms()
        ],
    )?;
    Ok(conn.last_insert_rowid())
}

pub fn outbox_in_state(conn: &Connection, state: i64) -> Result<Vec<OutboxRow>> {
    let mut stmt = conn.prepare(&format!("SELECT {OUTBOX_COLUMNS} FROM outbox WHERE state = ?1 ORDER BY id"))?;
    let rows = stmt.query_map(params![state], outbox_from_row)?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn outbox_row(conn: &Connection, id: i64) -> Result<Option<OutboxRow>> {
    Ok(conn
        .query_row(&format!("SELECT {OUTBOX_COLUMNS} FROM outbox WHERE id = ?1"), params![id], outbox_from_row)
        .optional()?)
}

/// Our own posted message with this id in this mailbox, if any.
pub fn own_outbox_row(conn: &Connection, mailbox: &[u8], client_msg_id: &[u8]) -> Result<Option<OutboxRow>> {
    Ok(conn
        .query_row(
            &format!("SELECT {OUTBOX_COLUMNS} FROM outbox WHERE mailbox = ?1 AND client_msg_id = ?2"),
            params![mailbox, client_msg_id],
            outbox_from_row,
        )
        .optional()?)
}

pub fn pending_commit_row(conn: &Connection, conversation_id: &[u8]) -> Result<Option<OutboxRow>> {
    Ok(conn
        .query_row(
            &format!("SELECT {OUTBOX_COLUMNS} FROM outbox WHERE conversation_id = ?1 AND kind = 1 ORDER BY id LIMIT 1"),
            params![conversation_id],
            outbox_from_row,
        )
        .optional()?)
}

pub fn set_outbox_ready(
    conn: &Connection,
    id: i64,
    mailbox: &[u8],
    epoch: u64,
    prev_commit_seq: Option<u64>,
    payload: &[u8],
) -> Result<()> {
    conn.execute(
        "UPDATE outbox SET state = ?2, mailbox = ?3, epoch = ?4, prev_commit_seq = ?5, payload = ?6 WHERE id = ?1",
        params![id, outbox_state::READY, mailbox, epoch as i64, prev_commit_seq.map(|v| v as i64), payload],
    )?;
    Ok(())
}

pub fn set_outbox_accepted(conn: &Connection, id: i64, seq: u64) -> Result<()> {
    conn.execute(
        "UPDATE outbox SET state = ?2, seq = ?3 WHERE id = ?1",
        params![id, outbox_state::ACCEPTED, seq as i64],
    )?;
    Ok(())
}

/// Makes a queued message wake the other members' devices when it goes out.
pub fn set_outbox_notify(conn: &Connection, id: i64) -> Result<()> {
    conn.execute("UPDATE outbox SET notify = 1 WHERE id = ?1", params![id])?;
    Ok(())
}

/// Puts a rejected application message back in the queue to be re-encrypted.
pub fn requeue_outbox(conn: &Connection, id: i64) -> Result<()> {
    conn.execute(
        "UPDATE outbox SET state = ?2, mailbox = NULL, epoch = NULL, prev_commit_seq = NULL, payload = NULL, seq = NULL \
         WHERE id = ?1",
        params![id, outbox_state::CONFLICT],
    )?;
    Ok(())
}

pub fn release_conflicts(conn: &Connection) -> Result<usize> {
    Ok(conn.execute(
        "UPDATE outbox SET state = ?1 WHERE state = ?2",
        params![outbox_state::QUEUED, outbox_state::CONFLICT],
    )?)
}

pub fn release_dependents(conn: &Connection, commit_row_id: i64) -> Result<()> {
    conn.execute(
        "UPDATE outbox SET state = ?2 WHERE depends_on = ?1 AND state = ?3",
        params![commit_row_id, outbox_state::READY, outbox_state::BLOCKED],
    )?;
    Ok(())
}

pub fn delete_outbox(conn: &Connection, id: i64) -> Result<()> {
    conn.execute("DELETE FROM outbox WHERE id = ?1", params![id])?;
    Ok(())
}

/// Drops a commit that lost its race, together with the Welcomes that were
/// waiting for it.
pub fn discard_commit(conn: &Connection, id: i64) -> Result<()> {
    conn.execute("DELETE FROM outbox WHERE id = ?1 OR depends_on = ?1", params![id])?;
    Ok(())
}

/// Drops accepted application rows left in a mailbox the group has moved on from.
pub fn delete_accepted_in_mailbox(conn: &Connection, mailbox: &[u8]) -> Result<()> {
    conn.execute(
        "DELETE FROM outbox WHERE mailbox = ?1 AND state = ?2 AND kind = 2",
        params![mailbox, outbox_state::ACCEPTED],
    )?;
    Ok(())
}

/// Drops our proposals for a conversation; they only count in the epoch
/// they were made in.
pub fn delete_proposals(conn: &Connection, conversation_id: &[u8]) -> Result<()> {
    conn.execute("DELETE FROM outbox WHERE conversation_id = ?1 AND kind = 3", params![conversation_id])?;
    Ok(())
}

pub fn outbox_len(conn: &Connection) -> Result<u32> {
    Ok(conn.query_row("SELECT COUNT(*) FROM outbox", [], |r| r.get::<_, i64>(0))? as u32)
}

// ---- ops ----

#[derive(Debug, Clone)]
pub struct OpRow {
    pub id: i64,
    pub conversation_id: Vec<u8>,
    pub op: String,
    pub attempts: u32,
}

pub fn insert_op(conn: &Connection, conversation_id: &[u8], op: &str) -> Result<i64> {
    conn.execute(
        "INSERT INTO ops (conversation_id, op, state, created_at) VALUES (?1, ?2, ?3, ?4)",
        params![conversation_id, op, op_state::PENDING, now_ms()],
    )?;
    Ok(conn.last_insert_rowid())
}

pub fn pending_ops(conn: &Connection) -> Result<Vec<OpRow>> {
    let mut stmt = conn.prepare("SELECT id, conversation_id, op, attempts FROM ops WHERE state = ?1 ORDER BY id")?;
    let rows = stmt
        .query_map(params![op_state::PENDING], |r| {
            Ok(OpRow { id: r.get(0)?, conversation_id: r.get(1)?, op: r.get(2)?, attempts: r.get::<_, i64>(3)? as u32 })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// Whether a conversation has an unfinished op with exactly this JSON.
pub fn has_open_op(conn: &Connection, conversation_id: &[u8], op_json: &str) -> Result<bool> {
    Ok(conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM ops WHERE conversation_id = ?1 AND op = ?2 AND state IN (?3, ?4))",
        params![conversation_id, op_json, op_state::PENDING, op_state::COMMITTED],
        |r| r.get::<_, i64>(0),
    )? != 0)
}

/// Moves a conversation's ops with this JSON from state `from` to `to`.
/// Returns how many moved.
pub fn move_ops(conn: &Connection, conversation_id: &[u8], op_json: &str, from: &[i64], to: i64) -> Result<usize> {
    let mut moved = 0;
    for state in from {
        moved += conn.execute(
            "UPDATE ops SET state = ?4 WHERE conversation_id = ?1 AND op = ?2 AND state = ?3",
            params![conversation_id, op_json, state, to],
        )?;
    }
    Ok(moved)
}

/// Drops unfinished ops of a conversation.
pub fn delete_open_ops(conn: &Connection, ids: &[i64]) -> Result<()> {
    let mut stmt = conn.prepare_cached("DELETE FROM ops WHERE id = ?1")?;
    for id in ids {
        stmt.execute(params![id])?;
    }
    Ok(())
}

pub fn set_op_state(conn: &Connection, id: i64, state: i64) -> Result<()> {
    conn.execute("UPDATE ops SET state = ?2 WHERE id = ?1", params![id, state])?;
    Ok(())
}

pub fn bump_op(conn: &Connection, id: i64) -> Result<()> {
    conn.execute("UPDATE ops SET state = ?2, attempts = attempts + 1 WHERE id = ?1", params![id, op_state::COMMITTED])?;
    Ok(())
}

// ---- location sharing ----

/// Kinds of outbox rows that a newer row of the same kind for the same
/// conversation makes pointless, so an unsent one is replaced.
pub mod latest_only {
    pub const LOCATION: i64 = 1;
    pub const WAKE: i64 = 2;
    pub const PROFILE: i64 = 3;
}

/// Marks an outbox row as replaceable by a newer one of the same kind.
pub fn set_outbox_latest_only(conn: &Connection, id: i64, kind: i64) -> Result<()> {
    conn.execute("UPDATE outbox SET latest_only = ?2 WHERE id = ?1", params![id, kind])?;
    Ok(())
}

/// Drops rows of one replaceable kind for a conversation that haven't gone out yet.
pub fn delete_unsent_latest_only(conn: &Connection, conversation_id: &[u8], kind: i64) -> Result<usize> {
    Ok(conn.execute(
        "DELETE FROM outbox WHERE conversation_id = ?1 AND latest_only = ?5 AND state IN (?2, ?3, ?4)",
        params![conversation_id, outbox_state::QUEUED, outbox_state::READY, outbox_state::CONFLICT, kind],
    )?)
}

// ---- push notifications ----

/// Stores a member's wake token (replacing the one we had).
pub fn set_wake_token(
    conn: &Connection,
    conversation_id: &[u8],
    account_id: &[u8],
    username: &str,
    token: &[u8],
) -> Result<()> {
    conn.execute(
        "INSERT INTO wake_tokens (conversation_id, account_id, username, token, updated_at) \
         VALUES (?1, ?2, ?3, ?4, ?5) ON CONFLICT (conversation_id, account_id) DO UPDATE SET \
         username = excluded.username, token = excluded.token, updated_at = excluded.updated_at",
        params![conversation_id, account_id, username, token, now_ms()],
    )?;
    Ok(())
}

pub fn delete_wake_token(conn: &Connection, conversation_id: &[u8], account_id: &[u8]) -> Result<()> {
    conn.execute(
        "DELETE FROM wake_tokens WHERE conversation_id = ?1 AND account_id = ?2",
        params![conversation_id, account_id],
    )?;
    Ok(())
}

#[derive(Debug, Clone)]
pub struct ProfileRow {
    pub username: String,
    pub avatar: String,
    pub ink: String,
}

/// Keeps someone's profile unless we have a newer one. Returns whether what
/// they look like changed.
pub fn set_profile(
    conn: &Connection,
    account_id: &[u8],
    username: &str,
    avatar: &str,
    ink: &str,
    sent_at: i64,
) -> Result<bool> {
    let existing: Option<(String, String, String, i64)> = conn
        .query_row(
            "SELECT username, avatar, ink, sent_at FROM profiles WHERE account_id = ?1",
            params![account_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)),
        )
        .optional()?;
    if existing.as_ref().is_some_and(|(_, _, _, at)| sent_at < *at) {
        return Ok(false);
    }
    conn.execute(
        "INSERT INTO profiles (account_id, username, avatar, ink, sent_at) VALUES (?1, ?2, ?3, ?4, ?5) \
         ON CONFLICT (account_id) DO UPDATE SET username = excluded.username, avatar = excluded.avatar, \
         ink = excluded.ink, sent_at = excluded.sent_at",
        params![account_id, username, avatar, ink, sent_at],
    )?;
    Ok(existing.is_none_or(|(u, a, i, _)| u != username || a != avatar || i != ink))
}

pub fn profiles(conn: &Connection) -> Result<Vec<ProfileRow>> {
    let mut stmt = conn.prepare_cached("SELECT username, avatar, ink FROM profiles ORDER BY username")?;
    let rows = stmt.query_map([], |r| Ok(ProfileRow { username: r.get(0)?, avatar: r.get(1)?, ink: r.get(2)? }))?;
    Ok(rows.collect::<std::result::Result<_, _>>()?)
}

/// Forgets the wake token of someone who left or was removed.
pub fn delete_member_wake_token(conn: &Connection, conversation_id: &[u8], username: &str) -> Result<()> {
    conn.execute(
        "DELETE FROM wake_tokens WHERE conversation_id = ?1 AND username = ?2",
        params![conversation_id, username],
    )?;
    Ok(())
}

pub fn delete_conversation_wake_tokens(conn: &Connection, conversation_id: &[u8]) -> Result<()> {
    conn.execute("DELETE FROM wake_tokens WHERE conversation_id = ?1", params![conversation_id])?;
    Ok(())
}

/// The other members' wake tokens in a conversation.
pub fn wake_tokens(conn: &Connection, conversation_id: &[u8]) -> Result<Vec<Vec<u8>>> {
    let mut stmt =
        conn.prepare_cached("SELECT token FROM wake_tokens WHERE conversation_id = ?1 ORDER BY account_id")?;
    let rows = stmt.query_map(params![conversation_id], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// An incoming message to notify about.
#[derive(Debug, Clone)]
pub struct NotificationRow {
    pub content_id: Vec<u8>,
    pub conversation_id: Vec<u8>,
    pub conversation_kind: i64,
    pub title: String,
    pub peer_username: Option<String>,
    pub sender_username: String,
    pub text: String,
    pub sent_at: i64,
}

/// Incoming messages not handed out for a notification yet, oldest first,
/// at most `limit`. Everything looked at is marked done, including messages
/// that need none (ours, system notes, deleted, or already read).
pub fn take_notifications(conn: &Connection, limit: u32) -> Result<Vec<NotificationRow>> {
    let mut out = Vec::new();
    let mut select = conn.prepare_cached(
        "SELECT m.local_id, m.content_id, m.conversation_id, c.kind, c.title, c.peer_username, m.sender_username, \
         m.text, m.sent_at, (m.outgoing = 0 AND m.system = 0 AND m.deleted = 0 AND c.unread > 0 AND c.active = 1) \
         FROM messages m JOIN conversations c ON c.id = m.conversation_id \
         WHERE m.notified = 0 ORDER BY m.local_id LIMIT 200",
    )?;
    let mut done = conn.prepare_cached("UPDATE messages SET notified = 1 WHERE local_id = ?1")?;
    while (out.len() as u32) < limit {
        let rows = select
            .query_map([], |r| {
                Ok((
                    r.get::<_, i64>(0)?,
                    NotificationRow {
                        content_id: r.get(1)?,
                        conversation_id: r.get(2)?,
                        conversation_kind: r.get(3)?,
                        title: r.get(4)?,
                        peer_username: r.get(5)?,
                        sender_username: r.get(6)?,
                        text: r.get(7)?,
                        sent_at: r.get(8)?,
                    },
                    r.get::<_, bool>(9)?,
                ))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        if rows.is_empty() {
            break;
        }
        for (local_id, row, wanted) in rows {
            if (out.len() as u32) >= limit {
                break;
            }
            done.execute(params![local_id])?;
            if wanted {
                out.push(row);
            }
        }
    }
    Ok(out)
}

/// Someone's position as last received.
#[derive(Debug, Clone, PartialEq)]
pub struct LocationRow {
    pub conversation_id: Vec<u8>,
    pub sender_account: Vec<u8>,
    pub sender_username: String,
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy_m: f64,
    pub speed_mps: Option<f64>,
    pub course_deg: Option<f64>,
    pub battery_percent: Option<u8>,
    pub measured_at: i64,
    pub received_at: i64,
    pub until_ms: Option<i64>,
}

/// Records a position unless a newer one from the same person is already
/// known (updates can arrive out of order). Returns whether it was stored.
pub fn upsert_location(conn: &Connection, l: &LocationRow) -> Result<bool> {
    let changed = conn.execute(
        "INSERT INTO locations (conversation_id, sender_account, sender_username, latitude, longitude, accuracy_m, \
         speed_mps, course_deg, battery_percent, measured_at, received_at, until_ms) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12) \
         ON CONFLICT (conversation_id, sender_account) DO UPDATE SET \
           sender_username = excluded.sender_username, latitude = excluded.latitude, \
           longitude = excluded.longitude, accuracy_m = excluded.accuracy_m, speed_mps = excluded.speed_mps, \
           course_deg = excluded.course_deg, battery_percent = excluded.battery_percent, \
           measured_at = excluded.measured_at, received_at = excluded.received_at, until_ms = excluded.until_ms \
         WHERE excluded.measured_at >= locations.measured_at",
        params![
            l.conversation_id,
            l.sender_account,
            l.sender_username,
            l.latitude,
            l.longitude,
            l.accuracy_m,
            l.speed_mps,
            l.course_deg,
            l.battery_percent,
            l.measured_at,
            l.received_at,
            l.until_ms,
        ],
    )?;
    Ok(changed > 0)
}

pub fn delete_location(conn: &Connection, conversation_id: &[u8], sender_account: &[u8]) -> Result<bool> {
    Ok(conn.execute(
        "DELETE FROM locations WHERE conversation_id = ?1 AND sender_account = ?2",
        params![conversation_id, sender_account],
    )? > 0)
}

/// Forgets the location of someone who left a conversation.
pub fn delete_member_location(conn: &Connection, conversation_id: &[u8], username: &str) -> Result<()> {
    conn.execute(
        "DELETE FROM locations WHERE conversation_id = ?1 AND sender_username = ?2",
        params![conversation_id, username],
    )?;
    Ok(())
}

/// Forgets every location in a conversation (we left it, or it ended).
pub fn delete_conversation_locations(conn: &Connection, conversation_id: &[u8]) -> Result<()> {
    conn.execute("DELETE FROM locations WHERE conversation_id = ?1", params![conversation_id])?;
    conn.execute("DELETE FROM location_shares WHERE conversation_id = ?1", params![conversation_id])?;
    Ok(())
}

/// Locations shared with us that haven't expired, in active conversations,
/// with each conversation's title and kind.
pub fn shared_locations(conn: &Connection, now: i64) -> Result<Vec<(LocationRow, String, i64)>> {
    conn.execute("DELETE FROM locations WHERE until_ms IS NOT NULL AND until_ms <= ?1", params![now])?;
    let mut stmt = conn.prepare(
        "SELECT l.conversation_id, l.sender_account, l.sender_username, l.latitude, l.longitude, l.accuracy_m, \
           l.speed_mps, l.course_deg, l.battery_percent, l.measured_at, l.received_at, l.until_ms, c.title, c.kind \
         FROM locations l JOIN conversations c ON c.id = l.conversation_id \
         WHERE c.active = 1 \
         ORDER BY l.sender_username, l.measured_at DESC",
    )?;
    let rows = stmt
        .query_map([], |r| {
            Ok((
                LocationRow {
                    conversation_id: r.get(0)?,
                    sender_account: r.get(1)?,
                    sender_username: r.get(2)?,
                    latitude: r.get(3)?,
                    longitude: r.get(4)?,
                    accuracy_m: r.get(5)?,
                    speed_mps: r.get(6)?,
                    course_deg: r.get(7)?,
                    battery_percent: r.get(8)?,
                    measured_at: r.get(9)?,
                    received_at: r.get(10)?,
                    until_ms: r.get(11)?,
                },
                r.get::<_, String>(12)?,
                r.get::<_, i64>(13)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// One conversation we share our location in.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LocationShareRow {
    pub conversation_id: Vec<u8>,
    pub started_at: i64,
    pub until_ms: Option<i64>,
    pub last_sent_at: Option<i64>,
}

pub fn set_location_share(conn: &Connection, conversation_id: &[u8], until_ms: Option<i64>) -> Result<()> {
    conn.execute(
        "INSERT INTO location_shares (conversation_id, started_at, until_ms) VALUES (?1, ?2, ?3) \
         ON CONFLICT (conversation_id) DO UPDATE SET until_ms = excluded.until_ms",
        params![conversation_id, now_ms(), until_ms],
    )?;
    Ok(())
}

pub fn delete_location_share(conn: &Connection, conversation_id: &[u8]) -> Result<bool> {
    Ok(conn.execute("DELETE FROM location_shares WHERE conversation_id = ?1", params![conversation_id])? > 0)
}

/// Where we share our location now. Expired shares are dropped.
pub fn location_shares(conn: &Connection, now: i64) -> Result<Vec<LocationShareRow>> {
    conn.execute("DELETE FROM location_shares WHERE until_ms IS NOT NULL AND until_ms <= ?1", params![now])?;
    let mut stmt = conn.prepare(
        "SELECT s.conversation_id, s.started_at, s.until_ms, s.last_sent_at FROM location_shares s \
         JOIN conversations c ON c.id = s.conversation_id WHERE c.active = 1 ORDER BY s.started_at",
    )?;
    let rows = stmt
        .query_map([], |r| {
            Ok(LocationShareRow {
                conversation_id: r.get(0)?,
                started_at: r.get(1)?,
                until_ms: r.get(2)?,
                last_sent_at: r.get(3)?,
            })
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

pub fn set_location_sent(conn: &Connection, conversation_id: &[u8], at: i64) -> Result<()> {
    conn.execute(
        "UPDATE location_shares SET last_sent_at = ?2 WHERE conversation_id = ?1",
        params![conversation_id, at],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn migrations_upgrade_a_first_release_database() {
        let mut conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(SCHEMA).unwrap();
        migrate(&mut conn).unwrap();
        let version: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap();
        assert_eq!(version, MIGRATIONS.len() as i64);
        // Running again changes nothing.
        migrate(&mut conn).unwrap();
        assert!(add_read_receipt(&conn, b"c", b"t", b"r", "bob").is_ok());
        assert!(unreceipted_incoming(&conn, b"c", 10).unwrap().is_empty());
    }
}
