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
    // 6: threads.
    r#"
    -- Replies in a thread: the content id of the thread's first message.
    ALTER TABLE messages ADD COLUMN thread_root BLOB;
    -- Replies in a thread not yet seen there.
    ALTER TABLE messages ADD COLUMN thread_unread INTEGER NOT NULL DEFAULT 0;
    CREATE INDEX IF NOT EXISTS messages_by_thread ON messages(conversation_id, thread_root) WHERE thread_root IS NOT NULL;
    "#,
    // 7: photos, videos, voice notes and files (see media.rs).
    r#"
    CREATE TABLE IF NOT EXISTS attachments (
        conversation_id BLOB NOT NULL,
        content_id      BLOB NOT NULL,         -- the message it came with
        position        INTEGER NOT NULL,      -- its place in the message
        kind            INTEGER NOT NULL,      -- messenger_protocol::v1::attachment::Kind
        blob_id         BLOB,                  -- NULL until ours is uploaded
        key             BLOB NOT NULL,
        digest          BLOB,                  -- NULL until ours is encrypted
        size            INTEGER NOT NULL,
        content_type    TEXT NOT NULL,
        file_name       TEXT NOT NULL,
        width           INTEGER NOT NULL DEFAULT 0,
        height          INTEGER NOT NULL DEFAULT 0,
        duration_ms     INTEGER NOT NULL DEFAULT 0,
        thumbnail       BLOB,
        waveform        BLOB,
        state           INTEGER NOT NULL,      -- see attachment_state
        path            TEXT,                  -- the plain file on this device, once here
        error           TEXT,                  -- why it can't be fetched
        PRIMARY KEY (conversation_id, content_id, position)
    );
    "#,
    // 8: what this device does with each chat, and who it blocks. Nothing
    // here is sent anywhere.
    r#"
    CREATE TABLE IF NOT EXISTS conversation_prefs (
        conversation_id BLOB PRIMARY KEY,
        pinned_at       INTEGER,               -- when it was pinned; NULL if not
        archived        INTEGER NOT NULL DEFAULT 0,
        muted_until     INTEGER,               -- ms; i64::MAX for always
        locked          INTEGER NOT NULL DEFAULT 0,
        marked_unread   INTEGER NOT NULL DEFAULT 0,
        hidden          INTEGER NOT NULL DEFAULT 0  -- deleted, waiting to finish leaving
    );
    CREATE TABLE IF NOT EXISTS blocked (
        username   TEXT PRIMARY KEY,
        blocked_at INTEGER NOT NULL
    );
    "#,
    // 9: vanish mode, as on Instagram: messages that go once seen.
    r#"
    ALTER TABLE messages ADD COLUMN vanish INTEGER NOT NULL DEFAULT 0;
    CREATE TABLE IF NOT EXISTS vanish_mode (
        conversation_id BLOB PRIMARY KEY,
        since           INTEGER NOT NULL
    );
    "#,
    // 10: linked devices: a wake token for each device of each member.
    r#"
    CREATE TABLE wake_tokens_by_device (
        conversation_id BLOB NOT NULL,
        account_id      BLOB NOT NULL,
        device_id       BLOB NOT NULL DEFAULT x'',
        username        TEXT NOT NULL,
        token           BLOB NOT NULL,
        updated_at      INTEGER NOT NULL,
        PRIMARY KEY (conversation_id, account_id, device_id)
    );
    INSERT INTO wake_tokens_by_device (conversation_id, account_id, username, token, updated_at)
        SELECT conversation_id, account_id, username, token, updated_at FROM wake_tokens;
    DROP TABLE wake_tokens;
    ALTER TABLE wake_tokens_by_device RENAME TO wake_tokens;
    "#,
    // 11: profile photos: the sealed photo as shared (an encoded Attachment),
    // and where its plain copy is once fetched.
    r#"
    ALTER TABLE profiles ADD COLUMN photo BLOB;
    ALTER TABLE profiles ADD COLUMN photo_path TEXT;
    "#,
    // 12: a group's colour, and for 1:1 chats the other person's account key
    // as last seen, to tell when their safety number changes.
    r#"
    ALTER TABLE conversations ADD COLUMN colour TEXT NOT NULL DEFAULT '';
    ALTER TABLE conversations ADD COLUMN peer_key BLOB;
    ALTER TABLE conversations ADD COLUMN safety_changed_at INTEGER;
    ALTER TABLE messages ADD COLUMN forwarded INTEGER NOT NULL DEFAULT 0;
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

pub mod attachment_state {
    /// Ours, waiting to be encrypted and uploaded.
    pub const UPLOADING: i64 = 0;
    /// Ours, uploaded.
    pub const SENT: i64 = 1;
    /// Someone else's, not fetched yet.
    pub const REMOTE: i64 = 2;
    /// Someone else's, fetched and decrypted.
    pub const DOWNLOADED: i64 = 3;
    /// Someone else's, and it can't be fetched: gone from the server, or damaged.
    pub const FAILED: i64 = 4;
}

pub mod conversation_kind {
    pub const DIRECT: i64 = 1;
    pub const GROUP: i64 = 2;
    /// Our own devices' private group, never listed as a chat.
    pub const DEVICES: i64 = 3;
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

/// Drops just the conversation's row, keeping its messages: for replacing
/// it with a fresh one.
pub fn delete_conversation_row(conn: &Connection, id: &[u8]) -> Result<()> {
    conn.execute("DELETE FROM conversations WHERE id = ?1", params![id])?;
    Ok(())
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

/// Whether a chat is in vanish mode.
pub fn is_vanish(conn: &Connection, id: &[u8]) -> Result<bool> {
    Ok(conn
        .query_row("SELECT EXISTS (SELECT 1 FROM vanish_mode WHERE conversation_id = ?1)", params![id], |r| r.get(0))?)
}

pub fn set_vanish(conn: &Connection, id: &[u8], on: bool) -> Result<()> {
    if on {
        conn.execute(
            "INSERT OR IGNORE INTO vanish_mode (conversation_id, since) VALUES (?1, ?2)",
            params![id, now_ms()],
        )?;
    } else {
        conn.execute("DELETE FROM vanish_mode WHERE conversation_id = ?1", params![id])?;
    }
    Ok(())
}

pub fn mark_vanish_message(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<()> {
    conn.execute(
        "UPDATE messages SET vanish = 1 WHERE conversation_id = ?1 AND content_id = ?2",
        params![conversation_id, content_id],
    )?;
    Ok(())
}

pub fn is_vanish_message(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<bool> {
    Ok(conn
        .query_row(
            "SELECT vanish FROM messages WHERE conversation_id = ?1 AND content_id = ?2",
            params![conversation_id, content_id],
            |r| r.get(0),
        )
        .optional()?
        .unwrap_or(false))
}

/// Deletes a chat's vanish-mode messages: only those seen (everything that
/// came in, and ours once sent) when `seen_only`, otherwise all of them.
/// Returns the paths of their files.
pub fn delete_vanished(conn: &Connection, conversation_id: &[u8], seen_only: bool) -> Result<Vec<String>> {
    let which = if seen_only { "AND (outgoing = 0 OR seq IS NOT NULL)" } else { "" };
    let ids: Vec<Vec<u8>> = {
        let mut stmt = conn
            .prepare(&format!("SELECT content_id FROM messages WHERE conversation_id = ?1 AND vanish = 1 {which}"))?;
        let rows = stmt.query_map(params![conversation_id], |r| r.get(0))?;
        rows.collect::<rusqlite::Result<_>>()?
    };
    let mut paths = Vec::new();
    for content_id in &ids {
        let mut stmt = conn.prepare_cached(
            "SELECT path FROM attachments WHERE conversation_id = ?1 AND content_id = ?2 AND path IS NOT NULL",
        )?;
        let found = stmt.query_map(params![conversation_id, content_id], |r| r.get::<_, String>(0))?;
        paths.extend(found.collect::<rusqlite::Result<Vec<_>>>()?);
        conn.execute(
            "DELETE FROM attachments WHERE conversation_id = ?1 AND content_id = ?2",
            params![conversation_id, content_id],
        )?;
        conn.execute(
            "DELETE FROM reactions WHERE conversation_id = ?1 AND target = ?2",
            params![conversation_id, content_id],
        )?;
        conn.execute(
            "DELETE FROM read_receipts WHERE conversation_id = ?1 AND target = ?2",
            params![conversation_id, content_id],
        )?;
        conn.execute(
            "DELETE FROM messages WHERE conversation_id = ?1 AND content_id = ?2",
            params![conversation_id, content_id],
        )?;
    }
    Ok(paths)
}

/// What this device does with one chat.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ConversationPrefs {
    pub pinned_at: Option<i64>,
    pub archived: bool,
    pub muted_until: Option<i64>,
    pub locked: bool,
    pub marked_unread: bool,
    pub hidden: bool,
}

pub fn conversation_prefs(conn: &Connection, id: &[u8]) -> Result<ConversationPrefs> {
    Ok(conn
        .query_row(
            "SELECT pinned_at, archived, muted_until, locked, marked_unread, hidden FROM conversation_prefs \
             WHERE conversation_id = ?1",
            params![id],
            |r| {
                Ok(ConversationPrefs {
                    pinned_at: r.get(0)?,
                    archived: r.get(1)?,
                    muted_until: r.get(2)?,
                    locked: r.get(3)?,
                    marked_unread: r.get(4)?,
                    hidden: r.get(5)?,
                })
            },
        )
        .optional()?
        .unwrap_or_default())
}

pub fn set_conversation_prefs(conn: &Connection, id: &[u8], p: &ConversationPrefs) -> Result<()> {
    conn.execute(
        "INSERT INTO conversation_prefs (conversation_id, pinned_at, archived, muted_until, locked, marked_unread, hidden) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7) ON CONFLICT(conversation_id) DO UPDATE SET pinned_at = ?2, archived = ?3, \
         muted_until = ?4, locked = ?5, marked_unread = ?6, hidden = ?7",
        params![id, p.pinned_at, p.archived, p.muted_until, p.locked, p.marked_unread, p.hidden],
    )?;
    Ok(())
}

pub fn pinned_count(conn: &Connection) -> Result<u32> {
    Ok(conn.query_row(
        "SELECT COUNT(*) FROM conversation_prefs p JOIN conversations c ON c.id = p.conversation_id \
         WHERE p.pinned_at IS NOT NULL AND p.hidden = 0",
        [],
        |r| r.get(0),
    )?)
}

pub fn is_blocked(conn: &Connection, username: &str) -> Result<bool> {
    Ok(conn.query_row("SELECT EXISTS (SELECT 1 FROM blocked WHERE username = ?1)", params![username], |r| r.get(0))?)
}

pub fn set_blocked(conn: &Connection, username: &str, blocked: bool) -> Result<()> {
    if blocked {
        conn.execute(
            "INSERT OR IGNORE INTO blocked (username, blocked_at) VALUES (?1, ?2)",
            params![username, now_ms()],
        )?;
    } else {
        conn.execute("DELETE FROM blocked WHERE username = ?1", params![username])?;
    }
    Ok(())
}

pub fn blocked_users(conn: &Connection) -> Result<Vec<String>> {
    let mut stmt = conn.prepare("SELECT username FROM blocked ORDER BY username")?;
    let rows = stmt.query_map([], |r| r.get(0))?;
    Ok(rows.collect::<rusqlite::Result<_>>()?)
}

/// Deletes a chat's messages on this device and keeps the chat. Returns the
/// paths of its files, for deleting them too.
pub fn clear_conversation(conn: &Connection, id: &[u8]) -> Result<Vec<String>> {
    let paths: Vec<String> = {
        let mut stmt = conn.prepare("SELECT path FROM attachments WHERE conversation_id = ?1 AND path IS NOT NULL")?;
        let rows = stmt.query_map(params![id], |r| r.get(0))?;
        rows.collect::<rusqlite::Result<_>>()?
    };
    for table in ["messages", "reactions", "read_receipts", "attachments"] {
        conn.execute(&format!("DELETE FROM {table} WHERE conversation_id = ?1"), params![id])?;
    }
    conn.execute("UPDATE conversations SET unread = 0 WHERE id = ?1", params![id])?;
    Ok(paths)
}

/// Removes an ended chat from this device altogether. Returns its files' paths.
pub fn purge_conversation(conn: &Connection, id: &[u8]) -> Result<Vec<String>> {
    let paths = clear_conversation(conn, id)?;
    for table in ["outbox", "ops", "locations", "location_shares", "wake_tokens", "conversation_prefs", "vanish_mode"] {
        conn.execute(&format!("DELETE FROM {table} WHERE conversation_id = ?1"), params![id])?;
    }
    conn.execute("DELETE FROM conversations WHERE id = ?1", params![id])?;
    Ok(paths)
}

/// Chats deleted while still leaving, now that they've ended.
pub fn hidden_ended(conn: &Connection) -> Result<Vec<Vec<u8>>> {
    let mut stmt = conn.prepare(
        "SELECT c.id FROM conversations c JOIN conversation_prefs p ON p.conversation_id = c.id \
         WHERE p.hidden = 1 AND c.active = 0",
    )?;
    let rows = stmt.query_map([], |r| r.get(0))?;
    Ok(rows.collect::<rusqlite::Result<_>>()?)
}

/// Folds every ended 1:1 chat with `peer` into `into`, its new chat, so a
/// person has one chat that carries on where it stopped: the old messages,
/// reactions, receipts and files move over, then the old chat goes.
///
/// Messages are ordered by epoch, and the new chat's epochs start again from
/// zero, so the old ones are shifted below zero to stay first. Returns how
/// many chats were folded in.
pub fn absorb_ended_direct(conn: &Connection, peer: &str, into: &[u8]) -> Result<usize> {
    let old: Vec<Vec<u8>> = {
        let mut stmt = conn.prepare(
            "SELECT id FROM conversations WHERE kind = 1 AND active = 0 AND peer_username = ?1 AND id != ?2 \
             AND id NOT IN (SELECT conversation_id FROM conversation_prefs WHERE hidden = 1) ORDER BY created_at DESC",
        )?;
        let rows = stmt.query_map(params![peer, into], |r| r.get(0))?;
        rows.collect::<rusqlite::Result<_>>()?
    };
    for id in &old {
        // Their account key as last seen, so a changed one is noticed.
        conn.execute(
            "UPDATE conversations SET peer_key = (SELECT peer_key FROM conversations WHERE id = ?1) \
             WHERE id = ?2 AND peer_key IS NULL",
            params![id, into],
        )?;
        // Below everything already in `into`, including chats folded earlier.
        let floor: i64 = conn.query_row(
            "SELECT COALESCE(MIN(epoch), 0) FROM messages WHERE conversation_id = ?1",
            params![into],
            |r| r.get(0),
        )?;
        let top: i64 = conn.query_row(
            "SELECT COALESCE(MAX(epoch), 0) FROM messages WHERE conversation_id = ?1",
            params![id],
            |r| r.get(0),
        )?;
        let shift = top - floor.min(0) + 1;
        conn.execute(
            "UPDATE messages SET conversation_id = ?2, epoch = epoch - ?3 WHERE conversation_id = ?1",
            params![id, into, shift],
        )?;
        for table in ["reactions", "read_receipts", "attachments", "conversation_prefs"] {
            conn.execute(
                &format!("UPDATE OR IGNORE {table} SET conversation_id = ?2 WHERE conversation_id = ?1"),
                params![id, into],
            )?;
        }
        for table in [
            "reactions",
            "read_receipts",
            "attachments",
            "outbox",
            "ops",
            "locations",
            "location_shares",
            "wake_tokens",
            "conversation_prefs",
            "vanish_mode",
        ] {
            conn.execute(&format!("DELETE FROM {table} WHERE conversation_id = ?1"), params![id])?;
        }
        conn.execute("DELETE FROM conversations WHERE id = ?1", params![id])?;
    }
    Ok(old.len())
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
    conn.execute("UPDATE conversation_prefs SET marked_unread = 0 WHERE conversation_id = ?1", params![id])?;
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
    let mut stmt = conn.prepare(&format!(
        "SELECT m.content_id FROM messages m WHERE m.conversation_id = ?1 AND m.outgoing = 0 AND m.system = 0 \
             AND m.deleted = 0 AND m.receipt_sent = 0 AND NOT ({IN_KNOWN_THREAD}) ORDER BY m.local_id LIMIT ?2"
    ))?;
    let rows = stmt.query_map(params![conversation_id, limit], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(rows)
}

/// Incoming replies in one thread we haven't sent a read receipt for.
pub fn unreceipted_in_thread(
    conn: &Connection,
    conversation_id: &[u8],
    root: &[u8],
    limit: u32,
) -> Result<Vec<Vec<u8>>> {
    let mut stmt = conn.prepare(
        "SELECT content_id FROM messages WHERE conversation_id = ?1 AND thread_root = ?2 AND outgoing = 0 \
         AND deleted = 0 AND receipt_sent = 0 ORDER BY local_id LIMIT ?3",
    )?;
    let rows =
        stmt.query_map(params![conversation_id, root, limit], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
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
    /// Replies in a thread: the thread's first message.
    pub thread_root: Option<&'a [u8]>,
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
         system, sent_at, received_at, text, reply_to, status, epoch, seq, thread_root, thread_unread) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
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
            m.position.map(|(_, s)| s as i64),
            m.thread_root,
            (m.thread_root.is_some() && !m.outgoing) as i64
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

// ---- attachments ----

#[derive(Debug, Clone, Default)]
pub struct AttachmentRow {
    pub conversation_id: Vec<u8>,
    pub content_id: Vec<u8>,
    pub position: i64,
    pub kind: i64,
    pub blob_id: Option<Vec<u8>>,
    pub key: Vec<u8>,
    pub digest: Option<Vec<u8>>,
    pub size: i64,
    pub content_type: String,
    pub file_name: String,
    pub width: i64,
    pub height: i64,
    pub duration_ms: i64,
    pub thumbnail: Option<Vec<u8>>,
    pub waveform: Option<Vec<u8>>,
    pub state: i64,
    pub path: Option<String>,
    pub error: Option<String>,
}

const ATTACHMENT_COLUMNS: &str = "conversation_id, content_id, position, kind, blob_id, key, digest, size, \
     content_type, file_name, width, height, duration_ms, thumbnail, waveform, state, path, error";

fn attachment_from_row(r: &Row<'_>) -> rusqlite::Result<AttachmentRow> {
    Ok(AttachmentRow {
        conversation_id: r.get(0)?,
        content_id: r.get(1)?,
        position: r.get(2)?,
        kind: r.get(3)?,
        blob_id: r.get(4)?,
        key: r.get(5)?,
        digest: r.get(6)?,
        size: r.get(7)?,
        content_type: r.get(8)?,
        file_name: r.get(9)?,
        width: r.get(10)?,
        height: r.get(11)?,
        duration_ms: r.get(12)?,
        thumbnail: r.get(13)?,
        waveform: r.get(14)?,
        state: r.get(15)?,
        path: r.get(16)?,
        error: r.get(17)?,
    })
}

pub fn insert_attachment(conn: &Connection, a: &AttachmentRow) -> Result<()> {
    conn.execute(
        &format!(
            "INSERT OR IGNORE INTO attachments ({ATTACHMENT_COLUMNS}) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)"
        ),
        params![
            a.conversation_id,
            a.content_id,
            a.position,
            a.kind,
            a.blob_id,
            a.key,
            a.digest,
            a.size,
            a.content_type,
            a.file_name,
            a.width,
            a.height,
            a.duration_ms,
            a.thumbnail,
            a.waveform,
            a.state,
            a.path,
            a.error
        ],
    )?;
    Ok(())
}

/// A message's attachments, in order.
pub fn attachments(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<Vec<AttachmentRow>> {
    let mut stmt = conn.prepare_cached(&format!(
        "SELECT {ATTACHMENT_COLUMNS} FROM attachments WHERE conversation_id = ?1 AND content_id = ?2 ORDER BY position"
    ))?;
    let rows = stmt.query_map(params![conversation_id, content_id], attachment_from_row)?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

pub fn attachment(
    conn: &Connection,
    conversation_id: &[u8],
    content_id: &[u8],
    position: i64,
) -> Result<Option<AttachmentRow>> {
    Ok(conn
        .query_row(
            &format!(
                "SELECT {ATTACHMENT_COLUMNS} FROM attachments \
                 WHERE conversation_id = ?1 AND content_id = ?2 AND position = ?3"
            ),
            params![conversation_id, content_id, position],
            attachment_from_row,
        )
        .optional()?)
}

pub fn set_attachment_uploaded(
    conn: &Connection,
    conversation_id: &[u8],
    content_id: &[u8],
    position: i64,
    blob_id: &[u8],
    digest: &[u8],
) -> Result<()> {
    conn.execute(
        "UPDATE attachments SET blob_id = ?4, digest = ?5, state = ?6 \
         WHERE conversation_id = ?1 AND content_id = ?2 AND position = ?3",
        params![conversation_id, content_id, position, blob_id, digest, attachment_state::SENT],
    )?;
    Ok(())
}

pub fn set_attachment_state(
    conn: &Connection,
    conversation_id: &[u8],
    content_id: &[u8],
    position: i64,
    state: i64,
    path: Option<&str>,
    error: Option<&str>,
) -> Result<()> {
    conn.execute(
        "UPDATE attachments SET state = ?4, path = ?5, error = ?6 \
         WHERE conversation_id = ?1 AND content_id = ?2 AND position = ?3",
        params![conversation_id, content_id, position, state, path, error],
    )?;
    Ok(())
}

/// Our messages with files still to upload, or uploaded but not yet sent.
pub fn unsent_media(conn: &Connection) -> Result<Vec<(Vec<u8>, Vec<u8>)>> {
    let mut stmt = conn.prepare_cached(
        "SELECT DISTINCT m.conversation_id, m.content_id FROM messages m \
         JOIN attachments a ON a.conversation_id = m.conversation_id AND a.content_id = m.content_id \
         WHERE m.outgoing = 1 AND m.status = ?1 \
         AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.conversation_id = m.conversation_id AND o.content_id = m.content_id) \
         ORDER BY m.local_id",
    )?;
    let rows = stmt.query_map(params![message_status::SENDING], |r| Ok((r.get(0)?, r.get(1)?)))?;
    Ok(rows.collect::<rusqlite::Result<Vec<_>>>()?)
}

/// One of our messages, as needed to send it.
pub struct OutgoingMessage {
    pub text: String,
    pub reply_to: Option<Vec<u8>>,
    pub thread_root: Option<Vec<u8>>,
    pub sent_at: i64,
}

pub fn outgoing_message(
    conn: &Connection,
    conversation_id: &[u8],
    content_id: &[u8],
) -> Result<Option<OutgoingMessage>> {
    Ok(conn
        .query_row(
            "SELECT text, reply_to, thread_root, sent_at FROM messages \
             WHERE conversation_id = ?1 AND content_id = ?2 AND outgoing = 1",
            params![conversation_id, content_id],
            |r| {
                Ok(OutgoingMessage { text: r.get(0)?, reply_to: r.get(1)?, thread_root: r.get(2)?, sent_at: r.get(3)? })
            },
        )
        .optional()?)
}

/// Forgets a message's attachments; returns the files to delete.
pub fn take_attachments(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<Vec<String>> {
    let paths = attachments(conn, conversation_id, content_id)?.into_iter().filter_map(|a| a.path).collect();
    conn.execute(
        "DELETE FROM attachments WHERE conversation_id = ?1 AND content_id = ?2",
        params![conversation_id, content_id],
    )?;
    Ok(paths)
}

// ---- threads ----

/// SQL condition, on a message `m`: a reply in a thread whose first message we
/// have. Those show in their thread; replies under a message we never got
/// (sent before we joined) show in the chat like any reply.
pub const IN_KNOWN_THREAD: &str = "m.thread_root IS NOT NULL AND EXISTS (SELECT 1 FROM messages t \
     WHERE t.conversation_id = m.conversation_id AND t.content_id = m.thread_root)";

/// The thread a reply to `target` goes in: the thread `target` is in, or the
/// one it starts. `None` if we don't have `target`.
pub fn thread_root_of(conn: &Connection, conversation_id: &[u8], target: &[u8]) -> Result<Option<Vec<u8>>> {
    Ok(conn
        .query_row(
            "SELECT COALESCE(thread_root, content_id) FROM messages WHERE conversation_id = ?1 AND content_id = ?2",
            params![conversation_id, target],
            |r| r.get(0),
        )
        .optional()?)
}

/// Whether we follow a thread: we wrote its first message or replied in it.
pub fn follows_thread(conn: &Connection, conversation_id: &[u8], root: &[u8]) -> Result<bool> {
    Ok(conn.query_row(
        "SELECT EXISTS (SELECT 1 FROM messages WHERE conversation_id = ?1 AND outgoing = 1 \
         AND (content_id = ?2 OR thread_root = ?2))",
        params![conversation_id, root],
        |r| r.get(0),
    )?)
}

/// Marks a thread's replies seen; they need no notification any more.
pub fn mark_thread_read(conn: &Connection, conversation_id: &[u8], root: &[u8]) -> Result<()> {
    conn.execute(
        "UPDATE messages SET thread_unread = 0, notified = 1 WHERE conversation_id = ?1 AND thread_root = ?2 \
         AND (thread_unread = 1 OR notified = 0)",
        params![conversation_id, root],
    )?;
    Ok(())
}

/// Hands a message no notification, as if it had been seen.
pub fn set_notified(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<()> {
    conn.execute(
        "UPDATE messages SET notified = 1 WHERE conversation_id = ?1 AND content_id = ?2",
        params![conversation_id, content_id],
    )?;
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
    device_id: &[u8],
    username: &str,
    token: &[u8],
) -> Result<()> {
    conn.execute(
        "INSERT INTO wake_tokens (conversation_id, account_id, device_id, username, token, updated_at) \
         VALUES (?1, ?2, ?3, ?4, ?5, ?6) ON CONFLICT (conversation_id, account_id, device_id) DO UPDATE SET \
         username = excluded.username, token = excluded.token, updated_at = excluded.updated_at",
        params![conversation_id, account_id, device_id, username, token, now_ms()],
    )?;
    Ok(())
}

pub fn delete_wake_token(conn: &Connection, conversation_id: &[u8], account_id: &[u8], device_id: &[u8]) -> Result<()> {
    conn.execute(
        "DELETE FROM wake_tokens WHERE conversation_id = ?1 AND account_id = ?2 AND device_id = ?3",
        params![conversation_id, account_id, device_id],
    )?;
    Ok(())
}

#[derive(Debug, Clone)]
pub struct ProfileRow {
    pub username: String,
    pub avatar: String,
    pub ink: String,
    pub has_photo: bool,
}

/// Keeps someone's profile unless we have a newer one. Returns whether what
/// they look like changed.
pub fn set_profile(
    conn: &Connection,
    account_id: &[u8],
    username: &str,
    avatar: &str,
    ink: &str,
    photo: Option<&[u8]>,
    sent_at: i64,
) -> Result<bool> {
    type Existing = (String, String, String, Option<Vec<u8>>, i64);
    let existing: Option<Existing> = conn
        .query_row(
            "SELECT username, avatar, ink, photo, sent_at FROM profiles WHERE account_id = ?1",
            params![account_id],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?, r.get(4)?)),
        )
        .optional()?;
    if existing.as_ref().is_some_and(|(_, _, _, _, at)| sent_at < *at) {
        return Ok(false);
    }
    let photo_changed = existing.as_ref().is_none_or(|(_, _, _, p, _)| p.as_deref() != photo);
    conn.execute(
        "INSERT INTO profiles (account_id, username, avatar, ink, photo, sent_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6) \
         ON CONFLICT (account_id) DO UPDATE SET username = excluded.username, avatar = excluded.avatar, \
         ink = excluded.ink, photo = excluded.photo, sent_at = excluded.sent_at",
        params![account_id, username, avatar, ink, photo, sent_at],
    )?;
    if photo_changed {
        // A new photo (or none): the copy we had is stale.
        conn.execute("UPDATE profiles SET photo_path = NULL WHERE account_id = ?1", params![account_id])?;
    }
    Ok(photo_changed || existing.is_none_or(|(u, a, i, _, _)| u != username || a != avatar || i != ink))
}

pub fn profiles(conn: &Connection) -> Result<Vec<ProfileRow>> {
    let mut stmt =
        conn.prepare_cached("SELECT username, avatar, ink, photo IS NOT NULL FROM profiles ORDER BY username")?;
    let rows = stmt.query_map([], |r| {
        Ok(ProfileRow { username: r.get(0)?, avatar: r.get(1)?, ink: r.get(2)?, has_photo: r.get(3)? })
    })?;
    Ok(rows.collect::<std::result::Result<_, _>>()?)
}

/// Someone's profile photo: their account, the sealed photo as shared, and
/// the plain copy if we have it.
pub fn profile_photo(conn: &Connection, username: &str) -> Result<Option<(Vec<u8>, Vec<u8>, Option<String>)>> {
    Ok(conn
        .query_row(
            "SELECT account_id, photo, photo_path FROM profiles WHERE username = ?1 AND photo IS NOT NULL",
            params![username],
            |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
        )
        .optional()?)
}

/// Where the plain copy of someone's photo is, if it's still the photo `photo`.
pub fn set_profile_photo_path(conn: &Connection, account_id: &[u8], photo: &[u8], path: &str) -> Result<bool> {
    let n = conn.execute(
        "UPDATE profiles SET photo_path = ?3 WHERE account_id = ?1 AND photo = ?2",
        params![account_id, photo, path],
    )?;
    Ok(n > 0)
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

/// The wake tokens to name when posting one of our messages: everyone's,
/// except that a reply in a group thread wakes only the people following it
/// (who wrote its first message or replied in it).
pub fn wake_tokens_for_message(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<Vec<Vec<u8>>> {
    let thread: Option<Vec<u8>> = conn
        .query_row(
            &format!(
                "SELECT m.thread_root FROM messages m JOIN conversations c ON c.id = m.conversation_id \
                 WHERE m.conversation_id = ?1 AND m.content_id = ?2 AND c.kind = {} AND {IN_KNOWN_THREAD}",
                conversation_kind::GROUP
            ),
            params![conversation_id, content_id],
            |r| r.get(0),
        )
        .optional()?;
    let Some(root) = thread else { return wake_tokens(conn, conversation_id) };
    let mut stmt = conn.prepare_cached(
        "SELECT token FROM wake_tokens WHERE conversation_id = ?1 AND account_id IN \
         (SELECT sender_account FROM messages WHERE conversation_id = ?1 AND (content_id = ?2 OR thread_root = ?2)) \
         ORDER BY account_id",
    )?;
    let rows = stmt.query_map(params![conversation_id, root], |r| r.get(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
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
    /// Replies in a thread we have: the thread's first message.
    pub thread_root: Option<Vec<u8>>,
    /// The chat is locked: the notification shouldn't say what's in it.
    pub locked: bool,
}

/// Incoming messages not handed out for a notification yet, oldest first,
/// at most `limit`. Everything looked at is marked done, including messages
/// that need none (ours, system notes, deleted, or already read).
pub fn take_notifications(conn: &Connection, limit: u32) -> Result<Vec<NotificationRow>> {
    let mut out = Vec::new();
    let mut select = conn.prepare_cached(&format!(
        "SELECT m.local_id, m.content_id, m.conversation_id, c.kind, c.title, c.peer_username, m.sender_username, \
         m.text, m.sent_at, (m.outgoing = 0 AND m.system = 0 AND m.deleted = 0 AND c.unread > 0 AND c.active = 1 \
           AND COALESCE(p.muted_until, 0) <= ?1 AND COALESCE(p.hidden, 0) = 0), \
         CASE WHEN {IN_KNOWN_THREAD} THEN m.thread_root END, COALESCE(p.locked, 0) \
         FROM messages m JOIN conversations c ON c.id = m.conversation_id \
         LEFT JOIN conversation_prefs p ON p.conversation_id = c.id \
         WHERE m.notified = 0 ORDER BY m.local_id LIMIT 200"
    ))?;
    let mut done = conn.prepare_cached("UPDATE messages SET notified = 1 WHERE local_id = ?1")?;
    while (out.len() as u32) < limit {
        let rows = select
            .query_map(params![now_ms()], |r| {
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
                        thread_root: r.get(10)?,
                        locked: r.get(11)?,
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

// ---- names, colours and safety numbers ----

/// A group's title and colour, as its MLS group context says.
pub fn set_conversation_meta(conn: &Connection, id: &[u8], title: &str, colour: &str) -> Result<()> {
    conn.execute("UPDATE conversations SET title = ?2, colour = ?3 WHERE id = ?1", params![id, title, colour])?;
    Ok(())
}

/// A conversation's colour, and when its safety number last changed (if not since checked).
pub fn conversation_extras(conn: &Connection, id: &[u8]) -> Result<(String, Option<i64>)> {
    Ok(conn
        .query_row("SELECT colour, safety_changed_at FROM conversations WHERE id = ?1", params![id], |r| {
            Ok((r.get::<_, String>(0)?, r.get::<_, Option<i64>>(1)?))
        })
        .optional()?
        .unwrap_or_default())
}

/// The other person's account key as last seen in a 1:1 chat.
pub fn peer_key(conn: &Connection, id: &[u8]) -> Result<Option<Vec<u8>>> {
    Ok(conn
        .query_row("SELECT peer_key FROM conversations WHERE id = ?1", params![id], |r| r.get::<_, Option<Vec<u8>>>(0))
        .optional()?
        .flatten())
}

/// Records the other person's account key; `changed_at` marks the safety
/// number as changed, `None` leaves the mark as it is.
pub fn set_peer_key(conn: &Connection, id: &[u8], key: &[u8], changed_at: Option<i64>) -> Result<()> {
    match changed_at {
        Some(at) => conn.execute(
            "UPDATE conversations SET peer_key = ?2, safety_changed_at = ?3 WHERE id = ?1",
            params![id, key, at],
        )?,
        None => conn.execute("UPDATE conversations SET peer_key = ?2 WHERE id = ?1", params![id, key])?,
    };
    Ok(())
}

/// The safety number was compared: it no longer shows as changed.
pub fn clear_safety_changed(conn: &Connection, id: &[u8]) -> Result<()> {
    conn.execute("UPDATE conversations SET safety_changed_at = NULL WHERE id = ?1", params![id])?;
    Ok(())
}

/// The newest account key seen for this person in another 1:1 chat: a chat
/// that ended and started again still knows their old safety number.
pub fn peer_key_elsewhere(conn: &Connection, peer: &str, except: &[u8]) -> Result<Option<Vec<u8>>> {
    Ok(conn
        .query_row(
            "SELECT peer_key FROM conversations WHERE kind = 1 AND peer_username = ?1 AND id != ?2 \
             AND peer_key IS NOT NULL ORDER BY updated_at DESC LIMIT 1",
            params![peer, except],
            |r| r.get::<_, Vec<u8>>(0),
        )
        .optional()?)
}

/// A message's text, if it can be forwarded: not deleted, not a system
/// line, and not one that vanishes once seen.
pub fn forwardable_text(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<Option<String>> {
    Ok(conn
        .query_row(
            "SELECT text FROM messages WHERE conversation_id = ?1 AND content_id = ?2 \
             AND deleted = 0 AND system = 0 AND vanish = 0",
            params![conversation_id, content_id],
            |r| r.get::<_, String>(0),
        )
        .optional()?)
}

/// A message is a forwarded copy.
pub fn mark_forwarded_message(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<()> {
    conn.execute(
        "UPDATE messages SET forwarded = 1 WHERE conversation_id = ?1 AND content_id = ?2",
        params![conversation_id, content_id],
    )?;
    Ok(())
}

pub fn is_forwarded_message(conn: &Connection, conversation_id: &[u8], content_id: &[u8]) -> Result<bool> {
    Ok(conn
        .query_row(
            "SELECT forwarded FROM messages WHERE conversation_id = ?1 AND content_id = ?2",
            params![conversation_id, content_id],
            |r| r.get::<_, i64>(0),
        )
        .optional()?
        .unwrap_or(0)
        != 0)
}
