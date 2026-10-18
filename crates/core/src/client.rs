//! The client: one account on one device, backed by one SQLite file.
//!
//! Locking: all state lives behind one mutex. Network calls are made without
//! holding it, so a long-poll in `sync` never blocks `send_text`. Every state
//! change happens in a SQLite transaction together with the OpenMLS writes it
//! depends on (see `provider.rs`), so a crash at any point leaves the client
//! in a state it can resume from.

use std::{
    path::Path,
    sync::{Arc, Mutex, MutexGuard},
};

use messenger_protocol::{
    api::{MessageKind, RecoverRequest, RegisterRequest, UploadKeyPackagesRequest},
    auth::{recover_payload, register_payload},
    normalize_username, padding,
    v1::{content::Body, group_meta::Kind, Content, Delete, Edit, Reaction, Text},
    CONTENT_ID_LEN, MAX_TEXT_BYTES,
};
use openmls_basic_credential::SignatureKeyPair;
use openmls_libcrux_crypto::CryptoProvider;
use prost::Message;
use rusqlite::{Connection, TransactionBehavior};
use serde::{Deserialize, Serialize};

use crate::{
    api::{Api, RequestSigner},
    error::mls,
    keys::{self, RecoveryKey, CIPHERSUITE},
    mls::{self as group_ops},
    provider::Provider,
    store::{self, conversation_kind, message_status, outbox_state, ConversationRow, NewMessage, OutboxRow},
    views::{AccountInfo, Registration},
    Error, Result,
};

pub(crate) mod meta_key {
    pub const ACCOUNT_ID: &str = "account_id";
    pub const USERNAME: &str = "username";
    pub const IDENTITY_KEY: &str = "identity_key";
    pub const INTRO_INBOX: &str = "intro_inbox";
    pub const SERVER_URL: &str = "server_url";
    pub const INTRO_CURSOR: &str = "intro_cursor";
    pub const KP_CHECKED_AT: &str = "kp_checked_at";
    pub const LAST_RESORT_AT: &str = "last_resort_at";
    /// Recovery key kept until the user confirms they wrote it down.
    pub const PENDING_RECOVERY: &str = "pending_recovery";
    /// Username of a registration that was sent but not yet confirmed.
    pub const PENDING_USERNAME: &str = "pending_username";
}

#[derive(Debug, Clone)]
pub(crate) struct Account {
    pub account_id: Vec<u8>,
    pub username: String,
    pub intro_inbox: Vec<u8>,
}

pub(crate) struct State {
    pub conn: Connection,
    pub account: Option<Account>,
    pub signer: Option<Arc<SignatureKeyPair>>,
}

/// Everything a state change needs, valid for one transaction.
pub(crate) struct Ctx<'a> {
    pub conn: &'a Connection,
    pub provider: &'a Provider<'a>,
    pub signer: &'a SignatureKeyPair,
    pub account: &'a Account,
    pub fault: Option<&'a str>,
}

/// A group change that is retried in the next epoch if another member's
/// commit wins the race.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) enum Op {
    Add { members: Vec<Invitee> },
    Remove { account_ids: Vec<Vec<u8>> },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct Invitee {
    pub username: String,
    pub account_id: Vec<u8>,
    pub identity_key: Vec<u8>,
    pub intro_inbox: Vec<u8>,
    pub key_package: Vec<u8>,
}

pub struct Client {
    pub(crate) state: Mutex<State>,
    pub(crate) api: Api,
    pub(crate) crypto: CryptoProvider,
    pub(crate) fault: Mutex<Option<String>>,
    /// Set once an injected fault fires: like a crashed process, the client
    /// refuses all further work until it is reopened.
    pub(crate) crashed: Mutex<Option<String>>,
}

impl Client {
    /// Opens (or creates) the client stored in `data_dir`. `server_url` is
    /// used until an account is registered; after that the stored one wins.
    pub fn open(data_dir: impl AsRef<Path>, server_url: &str) -> Result<Self> {
        let dir = data_dir.as_ref();
        std::fs::create_dir_all(dir).map_err(|e| Error::Storage(format!("create {}: {e}", dir.display())))?;
        let conn = store::open(&dir.join("messenger.db"))?;
        let crypto = CryptoProvider::new().map_err(mls)?;
        let url = store::meta_get_string(&conn, meta_key::SERVER_URL)?.unwrap_or_else(|| server_url.to_string());
        let (account, signer) = load_account(&conn, &crypto)?;
        Ok(Self {
            state: Mutex::new(State { conn, account, signer: signer.map(Arc::new) }),
            api: Api::new(&url),
            crypto,
            fault: Mutex::new(None),
            crashed: Mutex::new(None),
        })
    }

    pub(crate) fn lock(&self) -> Result<MutexGuard<'_, State>> {
        self.state.lock().map_err(|_| Error::Internal("state lock poisoned".into()))
    }

    /// Makes the next pass through checkpoint `name` fail (tests only).
    #[cfg(feature = "fault-injection")]
    pub fn inject_fault(&self, name: Option<&str>) {
        *self.fault.lock().unwrap() = name.map(str::to_string);
    }

    /// Latches the client as crashed when `result` is an injected fault.
    pub(crate) fn note_fault<T>(&self, result: Result<T>) -> Result<T> {
        if let Err(Error::InjectedFault(point)) = &result {
            if let Ok(mut crashed) = self.crashed.lock() {
                crashed.get_or_insert_with(|| point.clone());
            }
        }
        result
    }

    /// Named crash point outside a transaction.
    pub(crate) fn checkpoint(&self, name: &str) -> Result<()> {
        let fault = self.fault.lock().map_err(|_| Error::Internal("fault lock".into()))?.clone();
        self.note_fault(crate::engine::checkpoint(fault.as_deref(), name))
    }

    fn check_crashed(&self) -> Result<()> {
        match self.crashed.lock().map_err(|_| Error::Internal("fault lock".into()))?.as_ref() {
            Some(point) => Err(Error::InjectedFault(point.clone())),
            None => Ok(()),
        }
    }

    /// Runs `f` in one immediate SQLite transaction with an OpenMLS provider
    /// bound to it. Any error rolls back everything `f` wrote.
    pub(crate) fn with_tx<T>(&self, f: impl FnOnce(&Ctx<'_>) -> Result<T>) -> Result<T> {
        self.check_crashed()?;
        let result = self.with_tx_inner(f);
        self.note_fault(result)
    }

    fn with_tx_inner<T>(&self, f: impl FnOnce(&Ctx<'_>) -> Result<T>) -> Result<T> {
        let fault = self.fault.lock().map_err(|_| Error::Internal("fault lock".into()))?.clone();
        let mut guard = self.lock()?;
        let State { conn, account, signer } = &mut *guard;
        let account = account.as_ref().ok_or(Error::NotRegistered)?;
        let signer = signer.as_deref().ok_or(Error::NotRegistered)?;
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let value = {
            let provider = Provider::new(&self.crypto, &tx);
            let ctx = Ctx { conn: &tx, provider: &provider, signer, account, fault: fault.as_deref() };
            f(&ctx)?
        };
        tx.commit()?;
        Ok(value)
    }

    pub(crate) fn identity(&self) -> Result<(Account, Arc<SignatureKeyPair>)> {
        let state = self.lock()?;
        match (&state.account, &state.signer) {
            (Some(account), Some(signer)) => Ok((account.clone(), signer.clone())),
            _ => Err(Error::NotRegistered),
        }
    }

    pub fn account(&self) -> Result<Option<AccountInfo>> {
        let state = self.lock()?;
        Ok(state.account.as_ref().map(|a| AccountInfo {
            account_id: hex::encode(&a.account_id),
            username: a.username.clone(),
            server_url: self.api.base_url().to_string(),
        }))
    }

    // ---- registration ----

    /// Creates an account. Returns the recovery key, which the app must show
    /// once; it stays available from [`Client::pending_recovery_key`] until
    /// [`Client::confirm_recovery_key_saved`] is called.
    pub fn register(&self, username: &str) -> Result<Registration> {
        let username = normalize_username(username).map_err(|e| Error::InvalidInput(e.to_string()))?;

        // Reuse the keys of an earlier attempt for the same name, so a lost
        // response can be recovered from instead of burning the username.
        let (signer, recovery, intro_inbox, retrying) = {
            let state = self.lock()?;
            if let Some(account) = &state.account {
                return Err(Error::AlreadyRegistered(account.username.clone()));
            }
            let conn = &state.conn;
            let pending = store::meta_get_string(conn, meta_key::PENDING_USERNAME)?;
            let previous = match (pending, store::meta_get_string(conn, meta_key::PENDING_RECOVERY)?) {
                (Some(name), Some(recovery)) if name == username => {
                    let identity = store::meta_get(conn, meta_key::IDENTITY_KEY)?;
                    let inbox = store::meta_get(conn, meta_key::INTRO_INBOX)?;
                    let provider = Provider::new(&self.crypto, conn);
                    match (identity, inbox) {
                        (Some(identity), Some(inbox)) => SignatureKeyPair::read(
                            openmls_traits::OpenMlsProvider::storage(&provider),
                            &identity,
                            CIPHERSUITE.signature_algorithm(),
                        )
                        .map(|signer| (signer, RecoveryKey::parse(&recovery), inbox)),
                        _ => None,
                    }
                }
                _ => None,
            };
            match previous {
                Some((signer, Ok(recovery), inbox)) => (signer, recovery, inbox, true),
                _ => {
                    let signer = keys::new_signer()?;
                    let recovery = RecoveryKey::generate()?;
                    let inbox = keys::random_bytes::<32>()?.to_vec();
                    let provider = Provider::new(&self.crypto, conn);
                    signer.store(openmls_traits::OpenMlsProvider::storage(&provider)).map_err(mls)?;
                    store::meta_set(conn, meta_key::PENDING_USERNAME, username.as_bytes())?;
                    store::meta_set(conn, meta_key::PENDING_RECOVERY, recovery.display().as_bytes())?;
                    store::meta_set(conn, meta_key::IDENTITY_KEY, signer.public())?;
                    store::meta_set(conn, meta_key::INTRO_INBOX, &inbox)?;
                    (signer, recovery, inbox, false)
                }
            }
        };

        let timestamp_ms = store::now_ms() as u64;
        let payload = register_payload(&username, signer.public(), &recovery.public_key(), &intro_inbox, timestamp_ms);
        let request = RegisterRequest {
            username: username.clone(),
            identity_key: signer.public().to_vec(),
            recovery_key: recovery.public_key(),
            intro_inbox: intro_inbox.clone(),
            timestamp_ms,
            identity_signature: keys::sign(&signer, &payload)?,
            recovery_signature: recovery.sign(&payload),
        };
        let account_id = match self.api.register(&request) {
            Ok(response) => response.account_id,
            // Our earlier attempt may have gone through; the recovery key proves it.
            Err(Error::UsernameTaken) if retrying => {
                self.recover_request(&username, &recovery, &signer, &intro_inbox).map_err(|_| Error::UsernameTaken)?
            }
            Err(e) => return Err(e),
        };

        let account = Account { account_id, username, intro_inbox };
        {
            let mut state = self.lock()?;
            let conn = &state.conn;
            store::meta_set(conn, meta_key::ACCOUNT_ID, &account.account_id)?;
            store::meta_set(conn, meta_key::USERNAME, account.username.as_bytes())?;
            store::meta_set(conn, meta_key::SERVER_URL, self.api.base_url().as_bytes())?;
            conn.execute("DELETE FROM meta WHERE key = ?1", [meta_key::PENDING_USERNAME])?;
            state.account = Some(account);
            state.signer = Some(Arc::new(signer));
        }
        if let Err(e) = self.top_up_key_packages(true) {
            log::warn!("key package upload failed, will retry on sync: {e}");
        }
        Ok(Registration { account: self.account()?.expect("just registered"), recovery_key: recovery.display() })
    }

    fn recover_request(
        &self,
        username: &str,
        recovery: &RecoveryKey,
        signer: &SignatureKeyPair,
        intro_inbox: &[u8],
    ) -> Result<Vec<u8>> {
        let timestamp_ms = store::now_ms() as u64;
        let payload = recover_payload(username, signer.public(), intro_inbox, timestamp_ms);
        let request = RecoverRequest {
            username: username.to_string(),
            identity_key: signer.public().to_vec(),
            intro_inbox: intro_inbox.to_vec(),
            timestamp_ms,
            identity_signature: keys::sign(signer, &payload)?,
            recovery_signature: recovery.sign(&payload),
        };
        Ok(self.api.recover(&request)?.account_id)
    }

    /// Moves an account to this (fresh) device with its recovery key. Old
    /// conversations are not restored; contacts see a new safety number.
    pub fn recover(&self, username: &str, recovery_key: &str) -> Result<AccountInfo> {
        let username = normalize_username(username).map_err(|e| Error::InvalidInput(e.to_string()))?;
        let recovery = RecoveryKey::parse(recovery_key)?;
        if self.lock()?.account.is_some() {
            return Err(Error::InvalidInput("this device already has an account".into()));
        }
        let signer = keys::new_signer()?;
        let intro_inbox = keys::random_bytes::<32>()?.to_vec();
        let account_id = self.recover_request(&username, &recovery, &signer, &intro_inbox)?;
        {
            let mut state = self.lock()?;
            let conn = &state.conn;
            let provider = Provider::new(&self.crypto, conn);
            signer.store(openmls_traits::OpenMlsProvider::storage(&provider)).map_err(mls)?;
            store::meta_set(conn, meta_key::IDENTITY_KEY, signer.public())?;
            store::meta_set(conn, meta_key::INTRO_INBOX, &intro_inbox)?;
            store::meta_set(conn, meta_key::ACCOUNT_ID, &account_id)?;
            store::meta_set(conn, meta_key::USERNAME, username.as_bytes())?;
            store::meta_set(conn, meta_key::SERVER_URL, self.api.base_url().as_bytes())?;
            state.account = Some(Account { account_id, username, intro_inbox });
            state.signer = Some(Arc::new(signer));
        }
        if let Err(e) = self.top_up_key_packages(true) {
            log::warn!("key package upload failed, will retry on sync: {e}");
        }
        Ok(self.account()?.expect("just recovered"))
    }

    /// The recovery key from sign-up, until the user confirms they saved it.
    pub fn pending_recovery_key(&self) -> Result<Option<String>> {
        let state = self.lock()?;
        if state.account.is_none() {
            return Ok(None);
        }
        store::meta_get_string(&state.conn, meta_key::PENDING_RECOVERY)
    }

    pub fn confirm_recovery_key_saved(&self) -> Result<()> {
        let state = self.lock()?;
        state.conn.execute("DELETE FROM meta WHERE key = ?1", [meta_key::PENDING_RECOVERY])?;
        Ok(())
    }

    // ---- key packages ----

    /// Keeps enough key packages on the server for others to add us.
    pub(crate) fn top_up_key_packages(&self, force: bool) -> Result<()> {
        let (account, signer) = self.identity()?;
        let now = store::now_ms() as u64;
        let (checked_at, last_resort_at) = {
            let state = self.lock()?;
            (
                store::meta_get_u64(&state.conn, meta_key::KP_CHECKED_AT)?,
                store::meta_get_u64(&state.conn, meta_key::LAST_RESORT_AT)?,
            )
        };
        if !force && now.saturating_sub(checked_at) < 6 * 3600 * 1000 {
            return Ok(());
        }
        let sign = |payload: &[u8]| keys::sign(&signer, payload);
        let request_signer = RequestSigner { account_id: &account.account_id, sign: &sign };
        let count = self.api.key_package_count(&request_signer)?;
        let refresh_last_resort = !count.has_last_resort || now.saturating_sub(last_resort_at) > 14 * 24 * 3600 * 1000;
        let missing = keys::KEY_PACKAGE_TARGET.saturating_sub(count.count as usize);
        if count.count >= keys::KEY_PACKAGE_LOW_WATER && !refresh_last_resort {
            self.with_tx(|ctx| store::meta_set_u64(ctx.conn, meta_key::KP_CHECKED_AT, now))?;
            return Ok(());
        }
        let (key_packages, last_resort) = self.with_tx(|ctx| {
            let credential = keys::credential(&ctx.account.account_id, &ctx.account.username, ctx.signer);
            keys::generate_key_packages(ctx.provider, ctx.signer, &credential, missing, refresh_last_resort)
        })?;
        self.api.upload_key_packages(&UploadKeyPackagesRequest { key_packages, last_resort }, &request_signer)?;
        self.with_tx(|ctx| {
            store::meta_set_u64(ctx.conn, meta_key::KP_CHECKED_AT, now)?;
            if refresh_last_resort {
                store::meta_set_u64(ctx.conn, meta_key::LAST_RESORT_AT, now)?;
            }
            Ok(())
        })
    }

    // ---- conversations ----

    /// Looks a user up and claims one of their key packages.
    fn invitee(&self, username: &str) -> Result<Invitee> {
        let entry = self.api.directory(username)?;
        let key_package = self.api.claim_key_package(username)?;
        group_ops::check_key_package(
            &self.crypto,
            &key_package,
            &entry.account_id,
            &entry.username,
            &entry.identity_key,
        )?;
        Ok(Invitee {
            username: entry.username,
            account_id: entry.account_id,
            identity_key: entry.identity_key,
            intro_inbox: entry.intro_inbox,
            key_package,
        })
    }

    /// Opens the 1:1 chat with `username`, creating it if needed. Returns the
    /// conversation id.
    pub fn start_direct_chat(&self, username: &str) -> Result<String> {
        let username = normalize_username(username).map_err(|e| Error::InvalidInput(e.to_string()))?;
        let (account, _) = self.identity()?;
        if username == account.username {
            return Err(Error::InvalidInput("that's you".into()));
        }
        if let Some(existing) = store::active_direct_with(&self.lock()?.conn, &username)? {
            return Ok(hex::encode(existing.id));
        }
        let invitee = self.invitee(&username)?;
        let id = self.create_conversation(Kind::Direct, "", Some(&username), vec![invitee])?;
        self.settle();
        Ok(hex::encode(id))
    }

    /// Creates a group with the given members. Returns the conversation id.
    pub fn create_group(&self, title: &str, usernames: &[String]) -> Result<String> {
        let title = title.trim();
        if title.is_empty() || title.len() > 128 {
            return Err(Error::InvalidInput("group names are 1 to 128 characters".into()));
        }
        let (account, _) = self.identity()?;
        let mut invitees = Vec::new();
        for name in dedupe_usernames(usernames)? {
            if name != account.username {
                invitees.push(self.invitee(&name)?);
            }
        }
        let id = self.create_conversation(Kind::Group, title, None, invitees)?;
        self.settle();
        Ok(hex::encode(id))
    }

    fn create_conversation(
        &self,
        kind: Kind,
        title: &str,
        peer: Option<&str>,
        invitees: Vec<Invitee>,
    ) -> Result<Vec<u8>> {
        self.with_tx(|ctx| {
            let group = group_ops::create_group(
                ctx.provider,
                ctx.signer,
                &ctx.account.account_id,
                &ctx.account.username,
                kind,
                title,
            )?;
            let now = store::now_ms();
            let id = group.group_id().as_slice().to_vec();
            store::insert_conversation(
                ctx.conn,
                &ConversationRow {
                    id: id.clone(),
                    kind: if kind == Kind::Direct { conversation_kind::DIRECT } else { conversation_kind::GROUP },
                    title: title.to_string(),
                    peer_username: peer.map(str::to_string),
                    mailbox: group_ops::delivery_id(ctx.provider, &group)?,
                    cursor: 0,
                    prev_commit_seq: None,
                    epoch: group.epoch().as_u64(),
                    member_count: 1,
                    active: true,
                    unread: 0,
                    created_at: now,
                    updated_at: now,
                },
            )?;
            if !invitees.is_empty() {
                store::insert_op(ctx.conn, &id, &serde_json::to_string(&Op::Add { members: invitees })?)?;
            }
            Ok(id)
        })
    }

    pub fn add_members(&self, conversation_id: &str, usernames: &[String]) -> Result<()> {
        let id = parse_id(conversation_id)?;
        let conv = self.conversation_row(&id)?;
        if conv.kind == conversation_kind::DIRECT {
            return Err(Error::InvalidInput("start a group to chat with more people".into()));
        }
        let mut invitees = Vec::new();
        for name in dedupe_usernames(usernames)? {
            invitees.push(self.invitee(&name)?);
        }
        if invitees.is_empty() {
            return Ok(());
        }
        self.with_tx(|ctx| {
            store::insert_op(ctx.conn, &id, &serde_json::to_string(&Op::Add { members: invitees })?)?;
            Ok(())
        })?;
        self.settle();
        Ok(())
    }

    pub fn remove_member(&self, conversation_id: &str, username: &str) -> Result<()> {
        let id = parse_id(conversation_id)?;
        let username = normalize_username(username).map_err(|e| Error::InvalidInput(e.to_string()))?;
        self.with_tx(|ctx| {
            let conv = store::conversation(ctx.conn, &id)?.ok_or(Error::ConversationNotFound)?;
            if !conv.active {
                return Err(Error::NotAMember);
            }
            if conv.kind == conversation_kind::DIRECT {
                return Err(Error::InvalidInput("can't remove people from a 1:1 chat".into()));
            }
            let group = group_ops::load_group(ctx.provider, &id)?;
            let member = group_ops::members(&group)
                .into_iter()
                .find(|m| m.identity.username == username)
                .ok_or_else(|| Error::InvalidInput(format!("{username} is not in this group")))?;
            if member.identity.account_id == ctx.account.account_id {
                return Err(Error::InvalidInput("leaving a group is not supported yet".into()));
            }
            let op = Op::Remove { account_ids: vec![member.identity.account_id] };
            store::insert_op(ctx.conn, &id, &serde_json::to_string(&op)?)?;
            Ok(())
        })?;
        self.settle();
        Ok(())
    }

    pub(crate) fn conversation_row(&self, id: &[u8]) -> Result<ConversationRow> {
        store::conversation(&self.lock()?.conn, id)?.ok_or(Error::ConversationNotFound)
    }

    // ---- sending ----

    /// Queues a text message and tries to send it. Returns the message id.
    /// Offline sends stay queued and go out on the next successful sync.
    pub fn send_text(&self, conversation_id: &str, text: &str, reply_to: Option<&str>) -> Result<String> {
        let text = text.trim_end();
        if text.trim().is_empty() {
            return Err(Error::InvalidInput("message is empty".into()));
        }
        if text.len() > MAX_TEXT_BYTES {
            return Err(Error::InvalidInput("message is too long".into()));
        }
        let reply_to = reply_to.map(parse_id).transpose()?;
        let id = parse_id(conversation_id)?;
        let content_id = keys::random_bytes::<CONTENT_ID_LEN>()?.to_vec();
        let content = Content {
            id: content_id.clone(),
            sent_at_ms: store::now_ms() as u64,
            body: Some(Body::Text(Text { text: text.to_string(), reply_to: reply_to.clone().unwrap_or_default() })),
        };
        self.with_tx(|ctx| {
            let conv = active_conversation(ctx.conn, &id)?;
            store::insert_message(
                ctx.conn,
                &NewMessage {
                    conversation_id: &conv.id,
                    content_id: &content_id,
                    sender_account: &ctx.account.account_id,
                    sender_username: &ctx.account.username,
                    outgoing: true,
                    system: false,
                    sent_at: content.sent_at_ms as i64,
                    text,
                    reply_to: reply_to.as_deref(),
                    status: message_status::SENDING,
                    position: None,
                },
            )?;
            store::touch_conversation(ctx.conn, &conv.id, 0)?;
            queue_content(ctx, &conv, &content)
        })?;
        self.flush_quietly();
        Ok(hex::encode(content_id))
    }

    /// Adds a reaction, or removes ours when `emoji` is `None`.
    pub fn react(&self, conversation_id: &str, message_id: &str, emoji: Option<&str>) -> Result<()> {
        if let Some(e) = emoji {
            if e.is_empty() || e.len() > 32 {
                return Err(Error::InvalidInput("reactions are one emoji".into()));
            }
        }
        let target = parse_id(message_id)?;
        let body = Body::Reaction(Reaction {
            target: target.clone(),
            emoji: emoji.unwrap_or_default().to_string(),
            remove: emoji.is_none(),
        });
        self.send_control(conversation_id, body, |ctx, conv| {
            store::set_reaction(ctx.conn, &conv.id, &target, &ctx.account.account_id, &ctx.account.username, emoji)
        })
    }

    pub fn edit_message(&self, conversation_id: &str, message_id: &str, text: &str) -> Result<()> {
        let text = text.trim_end();
        if text.trim().is_empty() || text.len() > MAX_TEXT_BYTES {
            return Err(Error::InvalidInput("message is empty or too long".into()));
        }
        let target = parse_id(message_id)?;
        let body = Body::Edit(Edit { target: target.clone(), text: text.to_string() });
        self.send_control(conversation_id, body, |ctx, conv| {
            if !store::edit_message(ctx.conn, &conv.id, &target, &ctx.account.account_id, text)? {
                return Err(Error::InvalidInput("you can only edit your own messages".into()));
            }
            Ok(())
        })
    }

    /// Deletes one of our messages for everyone in the conversation.
    pub fn delete_message(&self, conversation_id: &str, message_id: &str) -> Result<()> {
        let target = parse_id(message_id)?;
        let body = Body::Delete(Delete { target: target.clone() });
        self.send_control(conversation_id, body, |ctx, conv| {
            if !store::delete_message(ctx.conn, &conv.id, &target, &ctx.account.account_id)? {
                return Err(Error::InvalidInput("you can only delete your own messages".into()));
            }
            Ok(())
        })
    }

    fn send_control(
        &self,
        conversation_id: &str,
        body: Body,
        apply_locally: impl FnOnce(&Ctx<'_>, &ConversationRow) -> Result<()>,
    ) -> Result<()> {
        let id = parse_id(conversation_id)?;
        let content = Content {
            id: keys::random_bytes::<CONTENT_ID_LEN>()?.to_vec(),
            sent_at_ms: store::now_ms() as u64,
            body: Some(body),
        };
        self.with_tx(|ctx| {
            let conv = active_conversation(ctx.conn, &id)?;
            apply_locally(ctx, &conv)?;
            queue_content(ctx, &conv, &content)
        })?;
        self.flush_quietly();
        Ok(())
    }

    pub fn mark_read(&self, conversation_id: &str) -> Result<()> {
        store::mark_read(&self.lock()?.conn, &parse_id(conversation_id)?)
    }

    /// Runs the outbox and a non-waiting sync, ignoring network errors: the
    /// work is saved and the next sync picks it up.
    pub(crate) fn settle(&self) {
        if let Err(e) = self.sync(0) {
            log::info!("sync after change failed, will retry: {e}");
        }
    }

    fn flush_quietly(&self) {
        if let Err(e) = self.flush() {
            log::info!("send deferred: {e}");
        }
    }
}

fn load_account(conn: &Connection, crypto: &CryptoProvider) -> Result<(Option<Account>, Option<SignatureKeyPair>)> {
    let Some(account_id) = store::meta_get(conn, meta_key::ACCOUNT_ID)? else {
        return Ok((None, None));
    };
    let username =
        store::meta_get_string(conn, meta_key::USERNAME)?.ok_or_else(|| Error::Storage("username missing".into()))?;
    let identity_key =
        store::meta_get(conn, meta_key::IDENTITY_KEY)?.ok_or_else(|| Error::Storage("identity key missing".into()))?;
    let intro_inbox =
        store::meta_get(conn, meta_key::INTRO_INBOX)?.ok_or_else(|| Error::Storage("intro inbox missing".into()))?;
    let provider = Provider::new(crypto, conn);
    let signer = SignatureKeyPair::read(
        openmls_traits::OpenMlsProvider::storage(&provider),
        &identity_key,
        CIPHERSUITE.signature_algorithm(),
    )
    .ok_or_else(|| Error::Storage("identity key pair missing".into()))?;
    Ok((Some(Account { account_id, username, intro_inbox }), Some(signer)))
}

pub(crate) fn parse_id(text: &str) -> Result<Vec<u8>> {
    let bytes = hex::decode(text.trim()).map_err(|_| Error::InvalidInput(format!("not an id: {text}")))?;
    if bytes.is_empty() || bytes.len() > 64 {
        return Err(Error::InvalidInput(format!("not an id: {text}")));
    }
    Ok(bytes)
}

fn dedupe_usernames(usernames: &[String]) -> Result<Vec<String>> {
    let mut out: Vec<String> = Vec::new();
    for name in usernames {
        let name = normalize_username(name).map_err(|e| Error::InvalidInput(format!("{name}: {e}")))?;
        if !out.contains(&name) {
            out.push(name);
        }
    }
    Ok(out)
}

fn active_conversation(conn: &Connection, id: &[u8]) -> Result<ConversationRow> {
    let conv = store::conversation(conn, id)?.ok_or(Error::ConversationNotFound)?;
    if !conv.active {
        return Err(Error::NotAMember);
    }
    Ok(conv)
}

/// Puts content in the outbox; it is encrypted when the group has no commit
/// in flight (see `engine::encrypt_queued`).
fn queue_content(ctx: &Ctx<'_>, conv: &ConversationRow, content: &Content) -> Result<()> {
    store::insert_outbox(
        ctx.conn,
        &OutboxRow {
            id: 0,
            conversation_id: conv.id.clone(),
            kind: MessageKind::Application.as_i64(),
            state: outbox_state::QUEUED,
            mailbox: None,
            client_msg_id: keys::random_bytes::<16>()?.to_vec(),
            prev_commit_seq: None,
            payload: None,
            plaintext: Some(padding::pad(&content.encode_to_vec())),
            content_id: Some(content.id.clone()),
            depends_on: None,
            op_id: None,
            epoch: None,
            seq: None,
        },
    )?;
    Ok(())
}
