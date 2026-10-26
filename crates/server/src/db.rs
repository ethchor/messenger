//! SQLite storage. Every function runs synchronously on a connection the caller
//! holds; handlers call them through `AppState::with_db`.

use std::path::Path;

use messenger_protocol::api::{Envelope, MailboxBatch, MessageKind, PushTarget};
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS accounts (
    account_id   BLOB PRIMARY KEY,
    username     TEXT NOT NULL UNIQUE,
    identity_key BLOB NOT NULL,
    recovery_key BLOB NOT NULL,
    intro_inbox  BLOB NOT NULL,
    created_at   INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS key_packages (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id  BLOB NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
    data        BLOB NOT NULL,
    last_resort INTEGER NOT NULL DEFAULT 0,
    created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS key_packages_by_account ON key_packages(account_id, last_resort, id);

-- One row per mailbox: the next sequence number and the latest commit, which
-- the compare-and-swap ordering rule checks against.
CREATE TABLE IF NOT EXISTS mailboxes (
    mailbox           BLOB PRIMARY KEY,
    next_seq          INTEGER NOT NULL,
    latest_commit_seq INTEGER,
    updated_at        INTEGER NOT NULL
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS messages (
    mailbox       BLOB NOT NULL,
    seq           INTEGER NOT NULL,
    client_msg_id BLOB NOT NULL,
    kind          INTEGER NOT NULL,
    payload       BLOB NOT NULL,
    created_at    INTEGER NOT NULL,
    PRIMARY KEY (mailbox, seq)
) WITHOUT ROWID;
CREATE UNIQUE INDEX IF NOT EXISTS messages_dedup ON messages(mailbox, client_msg_id);
CREATE INDEX IF NOT EXISTS messages_by_age ON messages(created_at);

-- Where to push each account's device (see push/mod.rs). One per account.
CREATE TABLE IF NOT EXISTS push_targets (
    account_id BLOB PRIMARY KEY REFERENCES accounts(account_id) ON DELETE CASCADE,
    kind       TEXT NOT NULL,
    target     TEXT NOT NULL,
    updated_at INTEGER NOT NULL
) WITHOUT ROWID;

-- Each account's wake token. A post naming it wakes the account's device.
CREATE TABLE IF NOT EXISTS wake_tokens (
    token      BLOB PRIMARY KEY,
    account_id BLOB NOT NULL UNIQUE REFERENCES accounts(account_id) ON DELETE CASCADE,
    created_at INTEGER NOT NULL
) WITHOUT ROWID;

-- Keys the server makes for itself, like its VAPID key.
CREATE TABLE IF NOT EXISTS server_keys (
    name  TEXT PRIMARY KEY,
    value BLOB NOT NULL
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS accounts_by_intro_inbox ON accounts(intro_inbox);

-- Encrypted files (see blobs.rs). `completed_at` stays NULL until the file
-- has arrived and matched its digest. Files live on disk, by id.
CREATE TABLE IF NOT EXISTS blobs (
    id           BLOB PRIMARY KEY,
    account_id   BLOB NOT NULL,
    size         INTEGER NOT NULL,
    digest       BLOB NOT NULL,
    created_at   INTEGER NOT NULL,
    completed_at INTEGER
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS blobs_by_account ON blobs(account_id, created_at);
CREATE INDEX IF NOT EXISTS blobs_by_age ON blobs(created_at);
"#;

/// Messages returned per mailbox per fetch.
pub const PAGE_SIZE: usize = 100;
/// Soft cap on payload bytes in one fetch response.
pub const MAX_FETCH_BYTES: usize = 8 * 1024 * 1024;
/// Most key packages one account may have stored.
pub const MAX_KEY_PACKAGES: i64 = 200;

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub fn open(path: &Path) -> rusqlite::Result<Connection> {
    let conn = Connection::open(path)?;
    conn.pragma_update(None, "journal_mode", "WAL")?;
    conn.pragma_update(None, "synchronous", "NORMAL")?;
    conn.pragma_update(None, "foreign_keys", "ON")?;
    conn.busy_timeout(std::time::Duration::from_secs(5))?;
    conn.execute_batch(SCHEMA)?;
    Ok(conn)
}

#[derive(Debug, Clone)]
pub struct Account {
    pub account_id: Vec<u8>,
    pub username: String,
    pub identity_key: Vec<u8>,
    pub recovery_key: Vec<u8>,
    pub intro_inbox: Vec<u8>,
}

fn account_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<Account> {
    Ok(Account {
        account_id: row.get(0)?,
        username: row.get(1)?,
        identity_key: row.get(2)?,
        recovery_key: row.get(3)?,
        intro_inbox: row.get(4)?,
    })
}

const ACCOUNT_COLUMNS: &str = "account_id, username, identity_key, recovery_key, intro_inbox";

/// Inserts an account; returns `false` if the username is taken.
pub fn insert_account(conn: &Connection, account: &Account) -> rusqlite::Result<bool> {
    let result = conn.execute(
        "INSERT INTO accounts (account_id, username, identity_key, recovery_key, intro_inbox, created_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![
            account.account_id,
            account.username,
            account.identity_key,
            account.recovery_key,
            account.intro_inbox,
            now_ms()
        ],
    );
    match result {
        Ok(_) => Ok(true),
        Err(rusqlite::Error::SqliteFailure(e, _)) if e.code == rusqlite::ErrorCode::ConstraintViolation => Ok(false),
        Err(e) => Err(e),
    }
}

pub fn account_by_username(conn: &Connection, username: &str) -> rusqlite::Result<Option<Account>> {
    conn.query_row(
        &format!("SELECT {ACCOUNT_COLUMNS} FROM accounts WHERE username = ?1"),
        params![username],
        account_from_row,
    )
    .optional()
}

pub fn account_by_id(conn: &Connection, account_id: &[u8]) -> rusqlite::Result<Option<Account>> {
    conn.query_row(
        &format!("SELECT {ACCOUNT_COLUMNS} FROM accounts WHERE account_id = ?1"),
        params![account_id],
        account_from_row,
    )
    .optional()
}

/// Points the account at a new device key and invitation inbox, and drops key
/// packages made with the old key.
pub fn replace_device(
    conn: &mut Connection,
    account_id: &[u8],
    identity_key: &[u8],
    intro_inbox: &[u8],
) -> rusqlite::Result<()> {
    let tx = conn.transaction()?;
    tx.execute(
        "UPDATE accounts SET identity_key = ?2, intro_inbox = ?3 WHERE account_id = ?1",
        params![account_id, identity_key, intro_inbox],
    )?;
    tx.execute("DELETE FROM key_packages WHERE account_id = ?1", params![account_id])?;
    // The old device's pushes and wake token go with it.
    tx.execute("DELETE FROM push_targets WHERE account_id = ?1", params![account_id])?;
    tx.execute("DELETE FROM wake_tokens WHERE account_id = ?1", params![account_id])?;
    tx.commit()
}

/// Deletes an account, its key packages and its invitation inbox. The
/// username is free again afterwards. Group mailboxes can't be tied to an
/// account and age out with the retention sweep.
pub fn delete_account(conn: &mut Connection, account: &Account) -> rusqlite::Result<()> {
    let tx = conn.transaction()?;
    tx.execute("DELETE FROM key_packages WHERE account_id = ?1", params![account.account_id])?;
    tx.execute("DELETE FROM push_targets WHERE account_id = ?1", params![account.account_id])?;
    tx.execute("DELETE FROM wake_tokens WHERE account_id = ?1", params![account.account_id])?;
    tx.execute("DELETE FROM accounts WHERE account_id = ?1", params![account.account_id])?;
    tx.execute("DELETE FROM messages WHERE mailbox = ?1", params![account.intro_inbox])?;
    tx.execute("DELETE FROM mailboxes WHERE mailbox = ?1", params![account.intro_inbox])?;
    tx.commit()
}

/// Removes every key package of an account, so nobody can invite it until a
/// device uploads new ones.
pub fn delete_key_packages(conn: &Connection, account_id: &[u8]) -> rusqlite::Result<usize> {
    conn.execute("DELETE FROM key_packages WHERE account_id = ?1", params![account_id])
}

pub fn set_recovery_key(conn: &Connection, account_id: &[u8], recovery_key: &[u8]) -> rusqlite::Result<()> {
    conn.execute("UPDATE accounts SET recovery_key = ?2 WHERE account_id = ?1", params![account_id, recovery_key])?;
    Ok(())
}

pub fn store_key_packages(
    conn: &mut Connection,
    account_id: &[u8],
    key_packages: &[Vec<u8>],
    last_resort: Option<&[u8]>,
) -> rusqlite::Result<Result<(), String>> {
    let tx = conn.transaction()?;
    let existing: i64 = tx.query_row(
        "SELECT COUNT(*) FROM key_packages WHERE account_id = ?1 AND last_resort = 0",
        params![account_id],
        |row| row.get(0),
    )?;
    if existing + key_packages.len() as i64 > MAX_KEY_PACKAGES {
        return Ok(Err(format!("at most {MAX_KEY_PACKAGES} key packages may be stored")));
    }
    let now = now_ms();
    for data in key_packages {
        tx.execute(
            "INSERT INTO key_packages (account_id, data, last_resort, created_at) VALUES (?1, ?2, 0, ?3)",
            params![account_id, data, now],
        )?;
    }
    if let Some(data) = last_resort {
        tx.execute("DELETE FROM key_packages WHERE account_id = ?1 AND last_resort = 1", params![account_id])?;
        tx.execute(
            "INSERT INTO key_packages (account_id, data, last_resort, created_at) VALUES (?1, ?2, 1, ?3)",
            params![account_id, data, now],
        )?;
    }
    tx.commit()?;
    Ok(Ok(()))
}

/// (regular key packages stored, whether a last-resort one exists)
pub fn key_package_count(conn: &Connection, account_id: &[u8]) -> rusqlite::Result<(u32, bool)> {
    conn.query_row(
        "SELECT COALESCE(SUM(last_resort = 0), 0), COALESCE(MAX(last_resort), 0) FROM key_packages WHERE account_id = ?1",
        params![account_id],
        |row| Ok((row.get::<_, i64>(0)? as u32, row.get::<_, i64>(1)? == 1)),
    )
}

/// Hands out the oldest regular key package and deletes it, or the last-resort
/// one (kept) when none are left.
pub fn claim_key_package(conn: &mut Connection, account_id: &[u8]) -> rusqlite::Result<Option<Vec<u8>>> {
    let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let regular: Option<(i64, Vec<u8>)> = tx
        .query_row(
            "SELECT id, data FROM key_packages WHERE account_id = ?1 AND last_resort = 0 ORDER BY id LIMIT 1",
            params![account_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let data = match regular {
        Some((id, data)) => {
            tx.execute("DELETE FROM key_packages WHERE id = ?1", params![id])?;
            Some(data)
        }
        None => tx
            .query_row(
                "SELECT data FROM key_packages WHERE account_id = ?1 AND last_resort = 1 ORDER BY id DESC LIMIT 1",
                params![account_id],
                |row| row.get(0),
            )
            .optional()?,
    };
    tx.commit()?;
    Ok(data)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PostOutcome {
    Accepted {
        seq: u64,
    },
    /// A retry of a post already accepted, at this sequence number.
    Duplicate {
        seq: u64,
    },
    /// `prev_commit_seq` did not match; carries the mailbox's latest commit.
    Conflict {
        latest_commit_seq: Option<u64>,
    },
}

/// Appends a message under the compare-and-swap ordering rule. Idempotent per
/// `client_msg_id`.
pub fn post_message(
    conn: &mut Connection,
    mailbox: &[u8],
    client_msg_id: &[u8],
    kind: MessageKind,
    prev_commit_seq: Option<u64>,
    payload: &[u8],
) -> rusqlite::Result<PostOutcome> {
    let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let existing: Option<i64> = tx
        .query_row(
            "SELECT seq FROM messages WHERE mailbox = ?1 AND client_msg_id = ?2",
            params![mailbox, client_msg_id],
            |row| row.get(0),
        )
        .optional()?;
    if let Some(seq) = existing {
        return Ok(PostOutcome::Duplicate { seq: seq as u64 });
    }
    let (next_seq, latest_commit): (i64, Option<i64>) = tx
        .query_row("SELECT next_seq, latest_commit_seq FROM mailboxes WHERE mailbox = ?1", params![mailbox], |row| {
            Ok((row.get(0)?, row.get(1)?))
        })
        .optional()?
        .unwrap_or((1, None));
    let latest_commit = latest_commit.map(|s| s as u64);
    if prev_commit_seq != latest_commit {
        return Ok(PostOutcome::Conflict { latest_commit_seq: latest_commit });
    }
    let now = now_ms();
    tx.execute(
        "INSERT INTO messages (mailbox, seq, client_msg_id, kind, payload, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![mailbox, next_seq, client_msg_id, kind.as_i64(), payload, now],
    )?;
    let new_latest = if kind == MessageKind::Commit { Some(next_seq) } else { latest_commit.map(|s| s as i64) };
    tx.execute(
        "INSERT INTO mailboxes (mailbox, next_seq, latest_commit_seq, updated_at) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(mailbox) DO UPDATE SET next_seq = excluded.next_seq,
             latest_commit_seq = excluded.latest_commit_seq, updated_at = excluded.updated_at",
        params![mailbox, next_seq + 1, new_latest, now],
    )?;
    tx.commit()?;
    Ok(PostOutcome::Accepted { seq: next_seq as u64 })
}

// ---- push ----

pub fn server_key(conn: &Connection, name: &str) -> rusqlite::Result<Option<Vec<u8>>> {
    conn.query_row("SELECT value FROM server_keys WHERE name = ?1", params![name], |row| row.get(0)).optional()
}

pub fn set_server_key(conn: &Connection, name: &str, value: &[u8]) -> rusqlite::Result<()> {
    conn.execute(
        "INSERT INTO server_keys (name, value) VALUES (?1, ?2) ON CONFLICT(name) DO UPDATE SET value = excluded.value",
        params![name, value],
    )?;
    Ok(())
}

/// Sets where to push an account's device, replacing what was there.
pub fn set_push_target(conn: &Connection, account_id: &[u8], kind: &str, target: &PushTarget) -> rusqlite::Result<()> {
    let json = serde_json::to_string(target).map_err(|e| rusqlite::Error::ToSqlConversionFailure(Box::new(e)))?;
    conn.execute(
        "INSERT INTO push_targets (account_id, kind, target, updated_at) VALUES (?1, ?2, ?3, ?4)
         ON CONFLICT(account_id) DO UPDATE SET kind = excluded.kind, target = excluded.target,
             updated_at = excluded.updated_at",
        params![account_id, kind, json, now_ms()],
    )?;
    Ok(())
}

pub fn push_targets(conn: &Connection, account_id: &[u8]) -> rusqlite::Result<Vec<PushTarget>> {
    let mut stmt = conn.prepare_cached("SELECT target FROM push_targets WHERE account_id = ?1")?;
    let rows = stmt.query_map(params![account_id], |row| row.get::<_, String>(0))?;
    let mut targets = Vec::new();
    for json in rows {
        if let Ok(target) = serde_json::from_str(&json?) {
            targets.push(target);
        }
    }
    Ok(targets)
}

pub fn delete_push_target(conn: &Connection, account_id: &[u8], kind: &str) -> rusqlite::Result<()> {
    conn.execute("DELETE FROM push_targets WHERE account_id = ?1 AND kind = ?2", params![account_id, kind])?;
    Ok(())
}

/// Stops all pushes to an account: its target and its wake token go.
pub fn delete_push(conn: &Connection, account_id: &[u8]) -> rusqlite::Result<()> {
    conn.execute("DELETE FROM push_targets WHERE account_id = ?1", params![account_id])?;
    conn.execute("DELETE FROM wake_tokens WHERE account_id = ?1", params![account_id])?;
    Ok(())
}

pub fn wake_token(conn: &Connection, account_id: &[u8]) -> rusqlite::Result<Option<Vec<u8>>> {
    conn.query_row("SELECT token FROM wake_tokens WHERE account_id = ?1", params![account_id], |row| row.get(0))
        .optional()
}

/// Gives an account a new wake token; the old one stops working.
pub fn set_wake_token(conn: &Connection, account_id: &[u8], token: &[u8]) -> rusqlite::Result<()> {
    conn.execute("DELETE FROM wake_tokens WHERE account_id = ?1", params![account_id])?;
    conn.execute(
        "INSERT INTO wake_tokens (token, account_id, created_at) VALUES (?1, ?2, ?3)",
        params![token, account_id, now_ms()],
    )?;
    Ok(())
}

/// The accounts behind these wake tokens (unknown tokens are skipped).
pub fn accounts_by_wake_tokens(conn: &Connection, tokens: &[Vec<u8>]) -> rusqlite::Result<Vec<Vec<u8>>> {
    let mut stmt = conn.prepare_cached("SELECT account_id FROM wake_tokens WHERE token = ?1")?;
    let mut accounts = Vec::new();
    for token in tokens {
        if let Some(account) = stmt.query_row(params![token], |row| row.get::<_, Vec<u8>>(0)).optional()? {
            if !accounts.contains(&account) {
                accounts.push(account);
            }
        }
    }
    Ok(accounts)
}

pub fn account_by_intro_inbox(conn: &Connection, mailbox: &[u8]) -> rusqlite::Result<Option<Vec<u8>>> {
    conn.query_row("SELECT account_id FROM accounts WHERE intro_inbox = ?1", params![mailbox], |row| row.get(0))
        .optional()
}

/// Reads new messages for each cursor. Mailboxes with nothing new are left out.
pub fn fetch(conn: &Connection, cursors: &[(Vec<u8>, u64)]) -> rusqlite::Result<Vec<MailboxBatch>> {
    let mut batches = Vec::new();
    let mut total_bytes = 0usize;
    let mut select = conn.prepare_cached(
        "SELECT seq, client_msg_id, kind, payload FROM messages WHERE mailbox = ?1 AND seq > ?2 ORDER BY seq LIMIT ?3",
    )?;
    let mut latest = conn.prepare_cached("SELECT latest_commit_seq FROM mailboxes WHERE mailbox = ?1")?;
    for (mailbox, after) in cursors {
        if total_bytes >= MAX_FETCH_BYTES {
            break;
        }
        let mut messages = Vec::new();
        let mut more = false;
        let rows = select.query_map(params![mailbox, *after as i64, PAGE_SIZE as i64 + 1], |row| {
            Ok((row.get::<_, i64>(0)?, row.get::<_, Vec<u8>>(1)?, row.get::<_, i64>(2)?, row.get::<_, Vec<u8>>(3)?))
        })?;
        for row in rows {
            let (seq, client_msg_id, kind, payload) = row?;
            if messages.len() == PAGE_SIZE || total_bytes >= MAX_FETCH_BYTES {
                more = true;
                break;
            }
            let Some(kind) = MessageKind::from_i64(kind) else { continue };
            total_bytes += payload.len();
            messages.push(Envelope { seq: seq as u64, client_msg_id, kind, payload });
        }
        if messages.is_empty() {
            continue;
        }
        let latest_commit_seq: Option<i64> = latest.query_row(params![mailbox], |row| row.get(0)).optional()?.flatten();
        batches.push(MailboxBatch {
            mailbox: mailbox.clone(),
            messages,
            latest_commit_seq: latest_commit_seq.map(|s| s as u64),
            more,
        });
    }
    Ok(batches)
}

#[derive(Debug, Clone)]
pub struct Blob {
    pub size: u64,
    pub digest: Vec<u8>,
    pub created_at: i64,
    pub completed: bool,
}

/// Records a coming upload, unless it would take the account past
/// `quota` bytes since `since_ms`. Returns whether it was recorded.
pub fn reserve_blob(
    conn: &mut Connection,
    id: &[u8],
    account_id: &[u8],
    size: u64,
    digest: &[u8],
    since_ms: i64,
    quota: u64,
) -> rusqlite::Result<bool> {
    let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let used: i64 = tx.query_row(
        "SELECT COALESCE(SUM(size), 0) FROM blobs WHERE account_id = ?1 AND created_at >= ?2",
        params![account_id, since_ms],
        |r| r.get(0),
    )?;
    if (used.max(0) as u64).saturating_add(size) > quota {
        return Ok(false);
    }
    tx.execute(
        "INSERT INTO blobs (id, account_id, size, digest, created_at) VALUES (?1, ?2, ?3, ?4, ?5)",
        params![id, account_id, size as i64, digest, now_ms()],
    )?;
    tx.commit()?;
    Ok(true)
}

pub fn blob(conn: &Connection, id: &[u8]) -> rusqlite::Result<Option<Blob>> {
    conn.query_row(
        "SELECT size, digest, created_at, completed_at IS NOT NULL FROM blobs WHERE id = ?1",
        params![id],
        |r| {
            Ok(Blob {
                size: r.get::<_, i64>(0)?.max(0) as u64,
                digest: r.get(1)?,
                created_at: r.get(2)?,
                completed: r.get(3)?,
            })
        },
    )
    .optional()
}

pub fn complete_blob(conn: &Connection, id: &[u8]) -> rusqlite::Result<()> {
    conn.execute("UPDATE blobs SET completed_at = ?2 WHERE id = ?1", params![id, now_ms()])?;
    Ok(())
}

/// Forgets files older than `cutoff_ms`, and uploads never finished since
/// `pending_cutoff_ms`. Returns their ids, for deleting the files.
pub fn delete_blobs_before(
    conn: &mut Connection,
    cutoff_ms: i64,
    pending_cutoff_ms: i64,
) -> rusqlite::Result<Vec<Vec<u8>>> {
    let tx = conn.transaction()?;
    let ids = {
        let mut stmt =
            tx.prepare("SELECT id FROM blobs WHERE created_at < ?1 OR (completed_at IS NULL AND created_at < ?2)")?;
        let rows = stmt.query_map(params![cutoff_ms, pending_cutoff_ms], |r| r.get(0))?;
        rows.collect::<rusqlite::Result<Vec<Vec<u8>>>>()?
    };
    tx.execute(
        "DELETE FROM blobs WHERE created_at < ?1 OR (completed_at IS NULL AND created_at < ?2)",
        params![cutoff_ms, pending_cutoff_ms],
    )?;
    tx.commit()?;
    Ok(ids)
}

/// Forgets every file an account uploaded. Returns their ids.
pub fn delete_account_blobs(conn: &mut Connection, account_id: &[u8]) -> rusqlite::Result<Vec<Vec<u8>>> {
    let tx = conn.transaction()?;
    let ids = {
        let mut stmt = tx.prepare("SELECT id FROM blobs WHERE account_id = ?1")?;
        let rows = stmt.query_map(params![account_id], |r| r.get(0))?;
        rows.collect::<rusqlite::Result<Vec<Vec<u8>>>>()?
    };
    tx.execute("DELETE FROM blobs WHERE account_id = ?1", params![account_id])?;
    tx.commit()?;
    Ok(ids)
}

/// Deletes message bodies older than `cutoff_ms`. Mailbox rows stay, so the
/// ordering rule keeps working for quiet groups.
pub fn delete_messages_before(conn: &mut Connection, cutoff_ms: i64) -> rusqlite::Result<usize> {
    conn.execute("DELETE FROM messages WHERE created_at < ?1", params![cutoff_ms])
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    fn memory() -> Connection {
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(SCHEMA).unwrap();
        conn
    }

    #[test]
    fn compare_and_swap_ordering() {
        let mut conn = memory();
        let mb = [7u8; 32];
        // Two clients race to commit on top of "no commit yet": one wins.
        assert_eq!(
            post_message(&mut conn, &mb, &[1; 16], MessageKind::Commit, None, b"c1").unwrap(),
            PostOutcome::Accepted { seq: 1 }
        );
        assert_eq!(
            post_message(&mut conn, &mb, &[2; 16], MessageKind::Commit, None, b"c2").unwrap(),
            PostOutcome::Conflict { latest_commit_seq: Some(1) }
        );
        // A stale application message is refused too.
        assert_eq!(
            post_message(&mut conn, &mb, &[3; 16], MessageKind::Application, None, b"a").unwrap(),
            PostOutcome::Conflict { latest_commit_seq: Some(1) }
        );
        // Retrying the winning post returns its original sequence number.
        assert_eq!(
            post_message(&mut conn, &mb, &[1; 16], MessageKind::Commit, None, b"c1").unwrap(),
            PostOutcome::Duplicate { seq: 1 }
        );
        // A client that skipped commit 1 as invalid builds on top of it.
        assert_eq!(
            post_message(&mut conn, &mb, &[4; 16], MessageKind::Commit, Some(1), b"c3").unwrap(),
            PostOutcome::Accepted { seq: 2 }
        );
        let batches = fetch(&conn, &[(mb.to_vec(), 0)]).unwrap();
        assert_eq!(batches.len(), 1);
        assert_eq!(batches[0].messages.iter().map(|m| m.seq).collect::<Vec<_>>(), vec![1, 2]);
        assert_eq!(batches[0].latest_commit_seq, Some(2));
    }

    #[test]
    fn key_package_claims_fall_back_to_last_resort() {
        let mut conn = memory();
        let account = Account {
            account_id: vec![1; 16],
            username: "alice".into(),
            identity_key: vec![2; 32],
            recovery_key: vec![3; 32],
            intro_inbox: vec![4; 32],
        };
        assert!(insert_account(&conn, &account).unwrap());
        assert!(!insert_account(&conn, &Account { account_id: vec![9; 16], ..account.clone() }).unwrap());
        store_key_packages(&mut conn, &account.account_id, &[b"kp1".to_vec(), b"kp2".to_vec()], Some(b"lr"))
            .unwrap()
            .unwrap();
        assert_eq!(key_package_count(&conn, &account.account_id).unwrap(), (2, true));
        assert_eq!(claim_key_package(&mut conn, &account.account_id).unwrap().unwrap(), b"kp1");
        assert_eq!(claim_key_package(&mut conn, &account.account_id).unwrap().unwrap(), b"kp2");
        assert_eq!(claim_key_package(&mut conn, &account.account_id).unwrap().unwrap(), b"lr");
        assert_eq!(claim_key_package(&mut conn, &account.account_id).unwrap().unwrap(), b"lr");
        assert_eq!(key_package_count(&conn, &account.account_id).unwrap(), (0, true));
    }

    #[test]
    fn deleting_an_account_frees_the_username() {
        let mut conn = memory();
        let account = Account {
            account_id: vec![1; 16],
            username: "alice".into(),
            identity_key: vec![2; 32],
            recovery_key: vec![3; 32],
            intro_inbox: vec![4; 32],
        };
        assert!(insert_account(&conn, &account).unwrap());
        store_key_packages(&mut conn, &account.account_id, &[b"kp".to_vec()], Some(b"lr")).unwrap().unwrap();
        post_message(&mut conn, &account.intro_inbox, &[5; 16], MessageKind::Welcome, None, b"welcome").unwrap();

        assert_eq!(delete_key_packages(&conn, &account.account_id).unwrap(), 2);
        assert_eq!(key_package_count(&conn, &account.account_id).unwrap(), (0, false));

        delete_account(&mut conn, &account).unwrap();
        assert!(account_by_username(&conn, "alice").unwrap().is_none());
        assert!(fetch(&conn, &[(account.intro_inbox.clone(), 0)]).unwrap().is_empty());
        let again = Account { account_id: vec![9; 16], ..account };
        assert!(insert_account(&conn, &again).unwrap());
    }

    #[test]
    fn push_targets_and_wake_tokens() {
        let mut conn = memory();
        let account = Account {
            account_id: vec![1; 16],
            username: "alice".into(),
            identity_key: vec![2; 32],
            recovery_key: vec![3; 32],
            intro_inbox: vec![4; 32],
        };
        assert!(insert_account(&conn, &account).unwrap());
        let id = &account.account_id;
        let apns = PushTarget::Apns { token: vec![5; 32], sandbox: false };
        set_push_target(&conn, id, "apns", &apns).unwrap();
        let webpush =
            PushTarget::Webpush { endpoint: "https://ntfy.sh/up".into(), p256dh: vec![4; 65], auth: vec![6; 16] };
        set_push_target(&conn, id, "webpush", &webpush).unwrap();
        assert_eq!(push_targets(&conn, id).unwrap(), vec![webpush]);

        set_wake_token(&conn, id, &[7; 16]).unwrap();
        set_wake_token(&conn, id, &[8; 16]).unwrap();
        assert_eq!(wake_token(&conn, id).unwrap(), Some(vec![8; 16]));
        assert_eq!(accounts_by_wake_tokens(&conn, &[vec![7; 16], vec![8; 16], vec![8; 16]]).unwrap(), vec![id.clone()]);
        assert_eq!(account_by_intro_inbox(&conn, &[4; 32]).unwrap(), Some(id.clone()));

        // A new device starts without pushes.
        replace_device(&mut conn, id, &[9; 32], &[10; 32]).unwrap();
        assert!(push_targets(&conn, id).unwrap().is_empty());
        assert!(wake_token(&conn, id).unwrap().is_none());

        set_push_target(&conn, id, "apns", &apns).unwrap();
        set_wake_token(&conn, id, &[11; 16]).unwrap();
        delete_account(&mut conn, &account).unwrap();
        assert!(push_targets(&conn, id).unwrap().is_empty());
        assert!(accounts_by_wake_tokens(&conn, &[vec![11; 16]]).unwrap().is_empty());
    }

    #[derive(Debug, Clone)]
    struct Op {
        id: u8,
        commit: bool,
        prev: Option<u8>,
    }

    proptest! {
        /// Against a simple model: the server accepts exactly the posts whose
        /// `prev_commit_seq` matches the latest accepted commit, numbers them
        /// densely, and retries never create duplicates.
        #[test]
        fn ordering_matches_model(ops in proptest::collection::vec(
            (0u8..12, any::<bool>(), proptest::option::of(1u8..10)).prop_map(|(id, commit, prev)| Op { id, commit, prev }),
            1..60,
        )) {
            let mut conn = memory();
            let mb = [1u8; 32];
            let mut accepted: Vec<(u8, bool)> = Vec::new(); // (client id, is commit), index = seq - 1
            let mut latest_commit: Option<u64> = None;
            for op in ops {
                let kind = if op.commit { MessageKind::Commit } else { MessageKind::Application };
                let outcome = post_message(&mut conn, &mb, &[op.id; 16], kind, op.prev.map(u64::from), &[op.id]).unwrap();
                if let Some(pos) = accepted.iter().position(|(id, _)| *id == op.id) {
                    prop_assert_eq!(outcome, PostOutcome::Duplicate { seq: pos as u64 + 1 });
                } else if op.prev.map(u64::from) == latest_commit {
                    accepted.push((op.id, op.commit));
                    let seq = accepted.len() as u64;
                    prop_assert_eq!(outcome, PostOutcome::Accepted { seq });
                    if op.commit { latest_commit = Some(seq); }
                } else {
                    prop_assert_eq!(outcome, PostOutcome::Conflict { latest_commit_seq: latest_commit });
                }
            }
            let batches = fetch(&conn, &[(mb.to_vec(), 0)]).unwrap();
            let seqs: Vec<u64> = batches.iter().flat_map(|b| b.messages.iter().map(|m| m.seq)).collect();
            prop_assert_eq!(seqs, (1..=accepted.len() as u64).collect::<Vec<_>>());
        }
    }
}
