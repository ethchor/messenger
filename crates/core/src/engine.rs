//! Outbox, sync and message processing.
//!
//! Ordering protocol (see docs/ARCHITECTURE.md):
//! - Each epoch of a group has its own mailbox (`mls::delivery_id`).
//! - Every post carries `prev_commit_seq`, the latest commit we know of in that
//!   mailbox. The server accepts it only if that matches, so at most one
//!   commit wins per epoch and nothing is accepted after it from members who
//!   haven't processed it.
//! - We merge our own commit only when we read it back from the mailbox, after
//!   everything ordered before it. A commit that loses the race is discarded
//!   and its operation retried in the next epoch.

use messenger_protocol::{
    api::{Cursor, FetchRequest, MailboxBatch, MessageKind, PostMessage},
    padding,
    v1::{content::Body, group_meta::Kind, Content, MemberIdentity},
    CONTENT_ID_LEN, MAX_TEXT_BYTES,
};
use openmls::prelude::{
    MlsGroup, MlsMessageBodyIn, MlsMessageIn, ProcessedMessageContent, Proposal, Sender, StagedCommit, StagedWelcome,
};
use openmls_traits::OpenMlsProvider;
use prost::Message;
use rusqlite::Connection;
use tls_codec::{DeserializeBytes, Serialize as _};

use crate::{
    api::PostResult,
    client::{leaving, meta_key, privacy_settings, Client, Ctx, Op, COMMIT_PROPOSALS_OP, LEAVE_OP, TYPING_TTL_MS},
    error::mls,
    keys,
    mls::{self as group_ops},
    store::{self, conversation_kind, message_status, op_state, outbox_state, ConversationRow, NewMessage, OutboxRow},
    views::SyncReport,
    Error, Result,
};

/// Give up on a group change after this many lost races.
const MAX_OP_ATTEMPTS: u32 = 8;
/// Fetch rounds per sync (each round can move groups to new mailboxes).
const MAX_ROUNDS: usize = 16;
/// Typing indicators older than this when they arrive are ignored.
const TYPING_MAX_AGE_MS: i64 = 30_000;
/// Most targets of one read receipt that are looked at.
const MAX_RECEIPT_TARGETS: usize = 1000;

/// What processing one message changed, besides the conversation's position.
#[derive(Default)]
struct Effects {
    new_messages: u32,
    /// Something visible changed: a reaction, edit, delete or read receipt.
    changed: bool,
    /// (sender account, username, typing)
    typing: Option<(Vec<u8>, String, bool)>,
}

impl Effects {
    fn new_messages(n: u32) -> Self {
        Self { new_messages: n, ..Self::default() }
    }

    fn changed() -> Self {
        Self { changed: true, ..Self::default() }
    }
}

/// Fails (or exits the process) at a named point, to test crash recovery.
/// Inside a transaction the error rolls it back; `Client::with_tx` then
/// latches the client as crashed (see `Client::note_fault`).
pub(crate) fn checkpoint(fault: Option<&str>, name: &str) -> Result<()> {
    #[cfg(feature = "fault-injection")]
    if std::env::var("MESSENGER_CRASH_AT").ok().as_deref() == Some(name) {
        eprintln!("MESSENGER_CRASH_AT={name}: exiting without cleanup");
        std::process::exit(86);
    }
    if fault == Some(name) {
        return Err(Error::InjectedFault(name.to_string()));
    }
    Ok(())
}

enum Step {
    Continue,
    /// The group moved to a new epoch (and mailbox), or we left it.
    Moved,
}

impl Client {
    /// Sends what's queued, fetches new messages and processes them. With
    /// `wait_ms > 0` the first fetch long-polls until something arrives.
    pub fn sync(&self, wait_ms: u32) -> Result<SyncReport> {
        let mut report = SyncReport::default();
        self.retry_ops()?;
        self.flush()?;
        let mut wait = wait_ms;
        for _ in 0..MAX_ROUNDS {
            let (intro, cursors) = self.cursors()?;
            let response = self.api.fetch(&FetchRequest { cursors, wait_ms: wait })?;
            wait = 0;
            if response.batches.is_empty() {
                break;
            }
            let mut again = false;
            for batch in response.batches {
                again |= batch.more;
                if batch.mailbox == intro {
                    // A new group's mailbox is fetched in the next round.
                    again |= self.process_intro(batch, &mut report)?;
                } else {
                    again |= self.process_mailbox(batch, &mut report)?;
                }
            }
            self.with_tx(|ctx| store::release_conflicts(ctx.conn).map(|_| ()))?;
            self.retry_ops()?;
            self.flush()?;
            if !again {
                break;
            }
        }
        // While an account is being removed, its invitation keys must stay gone.
        let removing = store::meta_get(&self.lock()?.conn, meta_key::PENDING_REMOVAL)?.is_some();
        if !removing {
            if let Err(e) = self.top_up_key_packages(!report.joined_conversations.is_empty()) {
                log::warn!("key package top-up failed: {e}");
            }
        }
        report.pending_outbox = store::outbox_len(&self.lock()?.conn)?;
        for list in [&mut report.updated_conversations, &mut report.typing_changed] {
            list.sort();
            list.dedup();
        }
        Ok(report)
    }

    /// Records someone starting or stopping typing. Returns whether that
    /// changes what the app shows.
    fn note_typing(&self, conversation_id: &[u8], account: Vec<u8>, username: String, typing: bool) -> bool {
        let Ok(mut state) = self.typing.lock() else { return false };
        let key = (conversation_id.to_vec(), account);
        if typing {
            let expires = store::now_ms() + TYPING_TTL_MS;
            state.incoming.insert(key, (username, expires)).is_none()
        } else {
            state.incoming.remove(&key).is_some()
        }
    }

    fn cursors(&self) -> Result<(Vec<u8>, Vec<Cursor>)> {
        let state = self.lock()?;
        let account = state.account.as_ref().ok_or(Error::NotRegistered)?;
        let mut cursors = vec![Cursor {
            mailbox: account.intro_inbox.clone(),
            after: store::meta_get_u64(&state.conn, meta_key::INTRO_CURSOR)?,
        }];
        for conv in store::conversations(&state.conn)? {
            if conv.active {
                cursors.push(Cursor { mailbox: conv.mailbox, after: conv.cursor });
            }
        }
        Ok((account.intro_inbox.clone(), cursors))
    }

    // ---- outbox ----

    /// Posts everything that is ready, until nothing more can be posted.
    pub(crate) fn flush(&self) -> Result<()> {
        loop {
            self.encrypt_queued()?;
            let rows = store::outbox_in_state(&self.lock()?.conn, outbox_state::READY)?;
            if rows.is_empty() {
                return Ok(());
            }
            let mut accepted_any = false;
            for row in rows {
                let (Some(mailbox), Some(payload)) = (&row.mailbox, &row.payload) else { continue };
                let kind = MessageKind::from_i64(row.kind).ok_or_else(|| Error::Internal("bad outbox kind".into()))?;
                let post = PostMessage {
                    client_msg_id: row.client_msg_id.clone(),
                    kind,
                    prev_commit_seq: row.prev_commit_seq,
                    payload: payload.clone(),
                };
                match self.api.post(mailbox, &post)? {
                    PostResult::Accepted(seq) => {
                        if kind == MessageKind::Commit {
                            self.checkpoint("commit-accepted")?;
                        }
                        self.with_tx(|ctx| on_accepted(ctx, row.id, seq))?;
                        accepted_any = true;
                    }
                    PostResult::Conflict => self.with_tx(|ctx| on_conflict(ctx, row.id))?,
                }
            }
            if !accepted_any {
                return Ok(());
            }
        }
    }

    /// Encrypts queued application messages for groups with no commit in flight.
    fn encrypt_queued(&self) -> Result<()> {
        self.with_tx(|ctx| {
            for row in store::outbox_in_state(ctx.conn, outbox_state::QUEUED)? {
                let Some(conv) = store::conversation(ctx.conn, &row.conversation_id)? else {
                    store::delete_outbox(ctx.conn, row.id)?;
                    continue;
                };
                if !conv.active {
                    store::delete_outbox(ctx.conn, row.id)?;
                    if let Some(content_id) = &row.content_id {
                        store::set_message_status(ctx.conn, &conv.id, content_id, message_status::FAILED)?;
                    }
                    continue;
                }
                if store::pending_commit_row(ctx.conn, &conv.id)?.is_some() {
                    continue;
                }
                let mut group = group_ops::load_group(ctx.provider, &conv.id)?;
                // MLS takes no application messages while proposals wait for
                // a commit; ours go out once someone commits them.
                if group.pending_commit().is_some() || group.has_pending_proposals() {
                    continue;
                }
                let plaintext =
                    row.plaintext.as_deref().ok_or_else(|| Error::Internal("queued row without content".into()))?;
                let message = group.create_message(ctx.provider, ctx.signer, plaintext).map_err(mls)?;
                let bytes = message.tls_serialize_detached().map_err(mls)?;
                store::set_outbox_ready(ctx.conn, row.id, &conv.mailbox, conv.epoch, conv.prev_commit_seq, &bytes)?;
            }
            Ok(())
        })
    }

    /// Turns pending group changes into commits, one per group at a time.
    pub(crate) fn retry_ops(&self) -> Result<()> {
        let ops = store::pending_ops(&self.lock()?.conn)?;
        for op in ops {
            let result = self.with_tx(|ctx| {
                // Re-read inside the transaction: another thread may have run it.
                let still_pending = store::pending_ops(ctx.conn)?.into_iter().any(|o| o.id == op.id);
                if !still_pending {
                    return Ok(());
                }
                let Some(conv) = store::conversation(ctx.conn, &op.conversation_id)? else {
                    return store::set_op_state(ctx.conn, op.id, op_state::FAILED);
                };
                if !conv.active {
                    return store::set_op_state(ctx.conn, op.id, op_state::FAILED);
                }
                if store::pending_commit_row(ctx.conn, &conv.id)?.is_some() {
                    return Ok(()); // one commit in flight per group
                }
                let parsed: Op = serde_json::from_str(&op.op)?;
                // While we're leaving we make no commits: they would carry our
                // own removal, which MLS doesn't allow.
                if !matches!(parsed, Op::Leave) && leaving(ctx.conn, &conv.id)? {
                    return store::set_op_state(ctx.conn, op.id, op_state::DONE);
                }
                // Leaving never gives up: it is proposed again in every epoch.
                if op.attempts >= MAX_OP_ATTEMPTS && !matches!(parsed, Op::Leave) {
                    store::set_op_state(ctx.conn, op.id, op_state::FAILED)?;
                    let text = "A group change kept conflicting with others and was dropped";
                    return system_message(ctx, &conv.id, None, text, None);
                }
                let mut group = group_ops::load_group(ctx.provider, &conv.id)?;
                if group.pending_commit().is_some() {
                    group.clear_pending_commit(ctx.provider.storage()).map_err(mls)?;
                }
                let members = group_ops::members(&group);
                if let Op::Leave = parsed {
                    return propose_leaving(ctx, &conv, &mut group, members.len(), op.id);
                }
                let (commit, welcome, invitees) = match parsed {
                    Op::Add { members: wanted } => {
                        let new: Vec<_> = wanted
                            .into_iter()
                            .filter(|w| !members.iter().any(|m| m.identity.account_id == w.account_id))
                            .collect();
                        if new.is_empty() {
                            return store::set_op_state(ctx.conn, op.id, op_state::DONE);
                        }
                        let key_packages = new
                            .iter()
                            .map(|w| {
                                group_ops::check_key_package(
                                    ctx.provider.crypto_ref(),
                                    &w.key_package,
                                    &w.account_id,
                                    &w.username,
                                    &w.identity_key,
                                )
                            })
                            .collect::<Result<Vec<_>>>()?;
                        let (commit, welcome, _) =
                            group.add_members(ctx.provider, ctx.signer, &key_packages).map_err(mls)?;
                        (commit, Some(welcome), new)
                    }
                    Op::Remove { account_ids } => {
                        let indices: Vec<_> = members
                            .iter()
                            .filter(|m| account_ids.contains(&m.identity.account_id))
                            .map(|m| m.index)
                            .collect();
                        if indices.is_empty() {
                            return store::set_op_state(ctx.conn, op.id, op_state::DONE);
                        }
                        let (commit, _, _) = group.remove_members(ctx.provider, ctx.signer, &indices).map_err(mls)?;
                        (commit, None, Vec::new())
                    }
                    Op::CommitProposals => {
                        // Someone else may have committed them already.
                        if !group.has_pending_proposals() {
                            return store::set_op_state(ctx.conn, op.id, op_state::DONE);
                        }
                        let (commit, _, _) =
                            group.commit_to_pending_proposals(ctx.provider, ctx.signer).map_err(mls)?;
                        (commit, None, Vec::new())
                    }
                    Op::Leave => unreachable!("handled above"),
                };
                let commit_id = store::insert_outbox(
                    ctx.conn,
                    &OutboxRow {
                        id: 0,
                        conversation_id: conv.id.clone(),
                        kind: MessageKind::Commit.as_i64(),
                        state: outbox_state::READY,
                        mailbox: Some(conv.mailbox.clone()),
                        client_msg_id: keys::random_bytes::<16>()?.to_vec(),
                        prev_commit_seq: conv.prev_commit_seq,
                        payload: Some(commit.tls_serialize_detached().map_err(mls)?),
                        plaintext: None,
                        content_id: None,
                        depends_on: None,
                        op_id: Some(op.id),
                        epoch: Some(conv.epoch),
                        seq: None,
                        ephemeral: false,
                    },
                )?;
                if let Some(welcome) = welcome {
                    let bytes = welcome.tls_serialize_detached().map_err(mls)?;
                    for invitee in invitees {
                        store::insert_outbox(
                            ctx.conn,
                            &OutboxRow {
                                id: 0,
                                conversation_id: conv.id.clone(),
                                kind: MessageKind::Welcome.as_i64(),
                                state: outbox_state::BLOCKED,
                                mailbox: Some(invitee.intro_inbox),
                                client_msg_id: keys::random_bytes::<16>()?.to_vec(),
                                prev_commit_seq: None,
                                payload: Some(bytes.clone()),
                                plaintext: None,
                                content_id: None,
                                depends_on: Some(commit_id),
                                op_id: None,
                                epoch: None,
                                seq: None,
                                ephemeral: false,
                            },
                        )?;
                    }
                }
                store::bump_op(ctx.conn, op.id)
            });
            match result {
                // The change can't be made in this group (say, a key package
                // that no longer validates). Drop it rather than block sync.
                Err(Error::Mls(e)) => {
                    log::warn!("dropping a group change that can't be made: {e}");
                    self.with_tx(|ctx| store::set_op_state(ctx.conn, op.id, op_state::FAILED))?;
                }
                other => other?,
            }
            self.checkpoint("commit-created")?;
        }
        Ok(())
    }

    // ---- inbound ----

    /// Joins groups from invitations. Returns true if it joined any.
    fn process_intro(&self, batch: MailboxBatch, report: &mut SyncReport) -> Result<bool> {
        let mut joined_any = false;
        for envelope in batch.messages {
            let joined = self.with_tx(|ctx| {
                if envelope.seq <= store::meta_get_u64(ctx.conn, meta_key::INTRO_CURSOR)? {
                    return Ok(None);
                }
                store::meta_set_u64(ctx.conn, meta_key::INTRO_CURSOR, envelope.seq)?;
                if envelope.kind != MessageKind::Welcome {
                    return Ok(None);
                }
                savepoint(ctx.conn, |conn| join_from_welcome(ctx, conn, &envelope.payload))
                    .map_err(|e| log::warn!("ignoring invitation: {e}"))
                    .map(Some)
                    .or(Ok(None))
            })?;
            if let Some(id) = joined {
                report.joined_conversations.push(hex::encode(id));
                joined_any = true;
            }
        }
        Ok(joined_any)
    }

    /// Processes one mailbox's messages in order. Returns true if the group
    /// moved to a new epoch (so its next mailbox should be fetched).
    fn process_mailbox(&self, batch: MailboxBatch, report: &mut SyncReport) -> Result<bool> {
        for envelope in batch.messages {
            let outcome = self.with_tx(|ctx| {
                let Some(mut conv) = store::conversation_by_mailbox(ctx.conn, &batch.mailbox)? else {
                    return Ok(None); // moved on already, or not ours
                };
                if envelope.seq <= conv.cursor {
                    return Ok(Some((Step::Continue, conv.id, Effects::default())));
                }
                let (step, effects) = process_envelope(ctx, &mut conv, &envelope)?;
                Ok(Some((step, conv.id, effects)))
            })?;
            match outcome {
                None => return Ok(false),
                Some((step, id, effects)) => {
                    let hex_id = hex::encode(&id);
                    if effects.new_messages > 0 || effects.changed {
                        report.new_messages += effects.new_messages;
                        report.updated_conversations.push(hex_id.clone());
                    }
                    // Typing state lives in memory, so it changes only once
                    // the transaction has committed.
                    if let Some((account, username, typing)) = effects.typing {
                        if self.note_typing(&id, account, username, typing) {
                            report.typing_changed.push(hex_id.clone());
                        }
                    }
                    if let Step::Moved = step {
                        report.updated_conversations.push(hex_id);
                        return Ok(true);
                    }
                }
            }
        }
        Ok(false)
    }
}

/// Runs `f` inside a SQLite savepoint, rolling back its writes on error.
fn savepoint<T>(conn: &Connection, f: impl FnOnce(&Connection) -> Result<T>) -> Result<T> {
    conn.execute_batch("SAVEPOINT step")?;
    match f(conn) {
        Ok(value) => {
            conn.execute_batch("RELEASE step")?;
            Ok(value)
        }
        Err(e) => {
            conn.execute_batch("ROLLBACK TO step; RELEASE step")?;
            Err(e)
        }
    }
}

fn on_accepted(ctx: &Ctx<'_>, row_id: i64, seq: u64) -> Result<()> {
    let Some(row) = store::outbox_row(ctx.conn, row_id)? else { return Ok(()) };
    if row.state != outbox_state::READY {
        return Ok(()); // handled by a concurrent flush
    }
    match MessageKind::from_i64(row.kind) {
        Some(MessageKind::Commit) => {
            store::set_outbox_accepted(ctx.conn, row.id, seq)?;
            store::release_dependents(ctx.conn, row.id)?;
        }
        Some(MessageKind::Application) => {
            store::set_outbox_accepted(ctx.conn, row.id, seq)?;
            if let Some(content_id) = &row.content_id {
                store::set_message_status(ctx.conn, &row.conversation_id, content_id, message_status::SENT)?;
                if let Some(epoch) = row.epoch {
                    store::set_message_position(ctx.conn, &row.conversation_id, content_id, epoch, seq)?;
                }
            }
        }
        // Kept until it comes back, so the echo isn't processed as someone
        // else's proposal.
        Some(MessageKind::Proposal) => store::set_outbox_accepted(ctx.conn, row.id, seq)?,
        _ => store::delete_outbox(ctx.conn, row.id)?,
    }
    Ok(())
}

fn on_conflict(ctx: &Ctx<'_>, row_id: i64) -> Result<()> {
    let Some(row) = store::outbox_row(ctx.conn, row_id)? else { return Ok(()) };
    if row.state != outbox_state::READY {
        return Ok(());
    }
    match MessageKind::from_i64(row.kind) {
        // A stale typing indicator is worthless.
        Some(MessageKind::Application) if row.ephemeral => store::delete_outbox(ctx.conn, row.id),
        Some(MessageKind::Application) => store::requeue_outbox(ctx.conn, row.id),
        Some(MessageKind::Commit) => lose_commit(ctx, &row),
        // A proposal belongs to its epoch: make a new one in the next.
        Some(MessageKind::Proposal) => {
            store::delete_outbox(ctx.conn, row.id)?;
            if let Some(op_id) = row.op_id {
                store::set_op_state(ctx.conn, op_id, op_state::PENDING)?;
            }
            Ok(())
        }
        _ => store::delete_outbox(ctx.conn, row.id),
    }
}

/// Posts a proposal to remove ourselves, for another member to commit. With
/// nobody left to commit it, we just stop.
fn propose_leaving(
    ctx: &Ctx<'_>,
    conv: &ConversationRow,
    group: &mut MlsGroup,
    members: usize,
    op_id: i64,
) -> Result<()> {
    store::delete_proposals(ctx.conn, &conv.id)?;
    if members <= 1 {
        store::set_op_state(ctx.conn, op_id, op_state::DONE)?;
        store::set_conversation_active(ctx.conn, &conv.id, false)?;
        return system_message(ctx, &conv.id, None, "You left", None);
    }
    let proposal = group.leave_group(ctx.provider, ctx.signer).map_err(mls)?;
    store::insert_outbox(
        ctx.conn,
        &OutboxRow {
            id: 0,
            conversation_id: conv.id.clone(),
            kind: MessageKind::Proposal.as_i64(),
            state: outbox_state::READY,
            mailbox: Some(conv.mailbox.clone()),
            client_msg_id: keys::random_bytes::<16>()?.to_vec(),
            prev_commit_seq: conv.prev_commit_seq,
            payload: Some(proposal.tls_serialize_detached().map_err(mls)?),
            plaintext: None,
            content_id: None,
            depends_on: None,
            op_id: Some(op_id),
            epoch: Some(conv.epoch),
            seq: None,
            ephemeral: false,
        },
    )?;
    store::bump_op(ctx.conn, op_id)
}

/// Members a commit removes because they asked to leave (self-removal
/// proposals), as opposed to being removed by someone else.
fn leavers(group: &MlsGroup, staged: &StagedCommit) -> Vec<String> {
    let members = group_ops::members(group);
    staged
        .remove_proposals()
        .filter_map(|p| match p.sender() {
            Sender::Member(index) if *index == p.remove_proposal().removed() => {
                members.iter().find(|m| m.index == *index).map(|m| m.identity.username.clone())
            }
            _ => None,
        })
        .collect()
}

/// Our commit lost the race (or the server refused it): drop it and retry
/// its operation once we've caught up.
fn lose_commit(ctx: &Ctx<'_>, row: &OutboxRow) -> Result<()> {
    let mut group = group_ops::load_group(ctx.provider, &row.conversation_id)?;
    group.clear_pending_commit(ctx.provider.storage()).map_err(mls)?;
    store::discard_commit(ctx.conn, row.id)?;
    if let Some(op_id) = row.op_id {
        store::set_op_state(ctx.conn, op_id, op_state::PENDING)?;
    }
    Ok(())
}

fn join_from_welcome(ctx: &Ctx<'_>, conn: &Connection, payload: &[u8]) -> Result<Vec<u8>> {
    let message = MlsMessageIn::tls_deserialize_exact_bytes(payload).map_err(mls)?;
    let MlsMessageBodyIn::Welcome(welcome) = message.extract() else {
        return Err(Error::Mls("not a Welcome".into()));
    };
    let staged =
        StagedWelcome::new_from_welcome(ctx.provider, &group_ops::join_config(), welcome, None).map_err(mls)?;
    let inviter = staged.welcome_sender().ok().and_then(|leaf| keys::identity_of(leaf.credential()));
    let group = staged.into_group(ctx.provider).map_err(mls)?;
    if group.ciphersuite() != keys::CIPHERSUITE {
        return Err(Error::Mls("group uses another ciphersuite".into()));
    }
    let id = group.group_id().as_slice().to_vec();
    if store::conversation(conn, &id)?.is_some() {
        return Err(Error::Mls("already in this group".into()));
    }
    let meta = group_ops::group_meta(&group);
    let members = group_ops::members(&group);
    let me = &ctx.account.account_id;
    let direct = meta.kind == Kind::Direct as i32;
    let peer = members.iter().find(|m| &m.identity.account_id != me).map(|m| m.identity.username.clone());
    if direct && members.len() != 2 {
        return Err(Error::Mls("a 1:1 chat must have two members".into()));
    }
    let now = store::now_ms();
    store::insert_conversation(
        conn,
        &ConversationRow {
            id: id.clone(),
            kind: if direct { conversation_kind::DIRECT } else { conversation_kind::GROUP },
            title: meta.title.chars().take(128).collect(),
            peer_username: if direct { peer } else { None },
            mailbox: group_ops::delivery_id(ctx.provider, &group)?,
            cursor: 0,
            prev_commit_seq: None,
            epoch: group.epoch().as_u64(),
            member_count: members.len() as u32,
            active: true,
            unread: 0,
            created_at: now,
            updated_at: now,
        },
    )?;
    let who = inviter.as_ref().map(|i| i.username.as_str()).unwrap_or("Someone");
    let text = if direct { format!("{who} started this chat") } else { format!("{who} added you") };
    system_message(ctx, &id, inviter.as_ref(), &text, Some((group.epoch().as_u64(), 0)))?;
    store::touch_conversation(conn, &id, 1)?;
    Ok(id)
}

/// Handles one message from a group mailbox. Returns how processing should
/// continue and what it changed.
fn process_envelope(
    ctx: &Ctx<'_>,
    conv: &mut ConversationRow,
    envelope: &messenger_protocol::api::Envelope,
) -> Result<(Step, Effects)> {
    // Our own message coming back.
    if let Some(own) = store::own_outbox_row(ctx.conn, &conv.mailbox, &envelope.client_msg_id)? {
        if own.kind == MessageKind::Commit.as_i64() {
            let mut group = group_ops::load_group(ctx.provider, &conv.id)?;
            let before = group_ops::member_names(&group);
            let left = group.pending_commit().map(|staged| leavers(&group, staged)).unwrap_or_default();
            group.merge_pending_commit(ctx.provider).map_err(mls)?;
            checkpoint(ctx.fault, "commit-merge")?;
            store::delete_outbox(ctx.conn, own.id)?;
            if let Some(op_id) = own.op_id {
                store::set_op_state(ctx.conn, op_id, op_state::DONE)?;
            }
            let me =
                MemberIdentity { account_id: ctx.account.account_id.clone(), username: ctx.account.username.clone() };
            enter_new_epoch(ctx, conv, &group, &before, &left, Some(&me), envelope.seq)?;
            return Ok((Step::Moved, Effects::default()));
        }
        store::delete_outbox(ctx.conn, own.id)?;
        if let Some(content_id) = &own.content_id {
            store::set_message_status(ctx.conn, &conv.id, content_id, message_status::SENT)?;
            store::set_message_position(ctx.conn, &conv.id, content_id, conv.epoch, envelope.seq)?;
        }
        conv.cursor = envelope.seq;
        store::update_position(ctx.conn, conv)?;
        return Ok((Step::Continue, Effects::default()));
    }

    let processed = savepoint(ctx.conn, |_| {
        let message = MlsMessageIn::tls_deserialize_exact_bytes(&envelope.payload).map_err(mls)?;
        let protocol = message.try_into_protocol_message().map_err(mls)?;
        let mut group = group_ops::load_group(ctx.provider, &conv.id)?;
        let processed = group.process_message(ctx.provider, protocol).map_err(mls)?;
        let sender = keys::identity_of(processed.credential());
        match processed.into_content() {
            ProcessedMessageContent::ApplicationMessage(app) => {
                let sender = sender.ok_or_else(|| Error::Mls("sender has no identity".into()))?;
                let effects = apply_content(ctx, conv, &sender, &app.into_bytes(), envelope.seq)?;
                Ok((Step::Continue, effects))
            }
            ProcessedMessageContent::StagedCommitMessage(staged) => {
                // A commit from someone else won this epoch; ours (if any) lost.
                if let Some(row) = store::pending_commit_row(ctx.conn, &conv.id)? {
                    group.clear_pending_commit(ctx.provider.storage()).map_err(mls)?;
                    store::discard_commit(ctx.conn, row.id)?;
                    if let Some(op_id) = row.op_id {
                        store::set_op_state(ctx.conn, op_id, op_state::PENDING)?;
                    }
                }
                let before = group_ops::member_names(&group);
                let left = leavers(&group, &staged);
                let removed = staged.self_removed();
                group.merge_staged_commit(ctx.provider, *staged).map_err(mls)?;
                if removed {
                    conv.active = false;
                    conv.cursor = envelope.seq;
                    store::update_position(ctx.conn, conv)?;
                    store::delete_proposals(ctx.conn, &conv.id)?;
                    // Did we ask for this?
                    let asked = store::move_ops(
                        ctx.conn,
                        &conv.id,
                        LEAVE_OP,
                        &[op_state::PENDING, op_state::COMMITTED],
                        op_state::DONE,
                    )? > 0;
                    let position = Some((conv.epoch, envelope.seq));
                    if asked {
                        system_message(ctx, &conv.id, None, "You left", position)?;
                        return Ok((Step::Moved, Effects::changed()));
                    }
                    let who = sender.as_ref().map(|s| s.username.as_str()).unwrap_or("Someone");
                    system_message(ctx, &conv.id, sender.as_ref(), &format!("{who} removed you"), position)?;
                    store::touch_conversation(ctx.conn, &conv.id, 1)?;
                    return Ok((Step::Moved, Effects::new_messages(1)));
                }
                enter_new_epoch(ctx, conv, &group, &before, &left, sender.as_ref(), envelope.seq)?;
                Ok((Step::Moved, Effects::default()))
            }
            ProcessedMessageContent::ProposalMessage(queued) => {
                // The only proposals we act on: members asking to leave. Any
                // of us commits them; storing them holds back our own
                // messages until that commit lands, which is immediate.
                let self_removal = match (queued.proposal(), queued.sender()) {
                    (Proposal::Remove(remove), Sender::Member(index)) => remove.removed() == *index,
                    _ => false,
                };
                if self_removal {
                    group.store_pending_proposal(ctx.provider.storage(), *queued).map_err(mls)?;
                    if !store::has_open_op(ctx.conn, &conv.id, COMMIT_PROPOSALS_OP)? {
                        store::insert_op(ctx.conn, &conv.id, COMMIT_PROPOSALS_OP)?;
                    }
                }
                Ok((Step::Continue, Effects::default()))
            }
            ProcessedMessageContent::ExternalJoinProposalMessage(_)
            | ProcessedMessageContent::OwnPendingCommit
            | ProcessedMessageContent::OwnPrivateMessage => Ok((Step::Continue, Effects::default())),
        }
    });

    match processed {
        Ok((Step::Moved, effects)) => Ok((Step::Moved, effects)),
        Ok((Step::Continue, effects)) => {
            conv.cursor = envelope.seq;
            store::update_position(ctx.conn, conv)?;
            Ok((Step::Continue, effects))
        }
        // Only protocol-level failures are skipped; storage and other local
        // errors abort the transaction so the message is retried.
        Err(Error::Mls(e)) => {
            // Undecryptable or invalid. Every honest member skips the same
            // message; a skipped commit still counts for the ordering rule.
            log::warn!("skipping message {} in a group: {e}", envelope.seq);
            if envelope.kind == MessageKind::Commit {
                conv.prev_commit_seq = Some(envelope.seq);
            }
            conv.cursor = envelope.seq;
            store::update_position(ctx.conn, conv)?;
            Ok((Step::Continue, Effects::default()))
        }
        Err(e) => Err(e),
    }
}

/// Moves a conversation to the group's new epoch and mailbox, and records who
/// joined or left. `left` are members who asked to leave; `commit_seq` is
/// where the commit sat in the old mailbox.
fn enter_new_epoch(
    ctx: &Ctx<'_>,
    conv: &mut ConversationRow,
    group: &MlsGroup,
    before: &[String],
    left: &[String],
    actor: Option<&MemberIdentity>,
    commit_seq: u64,
) -> Result<()> {
    let position = Some((conv.epoch, commit_seq));
    let old_mailbox = std::mem::replace(&mut conv.mailbox, group_ops::delivery_id(ctx.provider, group)?);
    conv.cursor = 0;
    conv.prev_commit_seq = None;
    conv.epoch = group.epoch().as_u64();
    let after = group_ops::member_names(group);
    conv.member_count = after.len() as u32;
    // A 1:1 chat ends when the other person leaves.
    let ended = conv.kind == conversation_kind::DIRECT && after.len() < 2 && before.len() >= 2;
    if ended {
        conv.active = false;
    }
    store::update_position(ctx.conn, conv)?;
    store::delete_accepted_in_mailbox(ctx.conn, &old_mailbox)?;
    // Our leave proposal (if any) didn't make it into this epoch; it is
    // proposed again in the new one.
    store::delete_proposals(ctx.conn, &conv.id)?;
    store::move_ops(ctx.conn, &conv.id, LEAVE_OP, &[op_state::COMMITTED], op_state::PENDING)?;

    let who = actor.map(|a| a.username.as_str()).unwrap_or("Someone");
    let mine = actor.is_some_and(|a| a.account_id == ctx.account.account_id);
    let who = if mine { "You" } else { who };
    let added: Vec<_> = after.iter().filter(|n| !before.contains(n)).cloned().collect();
    let removed: Vec<_> = before.iter().filter(|n| !after.contains(n) && !left.contains(n)).cloned().collect();
    let gone: Vec<_> = before.iter().filter(|n| !after.contains(n) && left.contains(n)).cloned().collect();
    // A 1:1 chat's first commit just brings in the other person.
    let quiet = conv.kind == conversation_kind::DIRECT && removed.is_empty() && gone.is_empty();
    if !added.is_empty() && !quiet {
        system_message(ctx, &conv.id, actor, &format!("{who} added {}", added.join(", ")), position)?;
    }
    if !removed.is_empty() {
        system_message(ctx, &conv.id, actor, &format!("{who} removed {}", removed.join(", ")), position)?;
    }
    if !gone.is_empty() {
        system_message(ctx, &conv.id, None, &format!("{} left", gone.join(", ")), position)?;
    }
    if ended {
        store::touch_conversation(ctx.conn, &conv.id, 0)?;
    }
    Ok(())
}

fn system_message(
    ctx: &Ctx<'_>,
    conversation_id: &[u8],
    actor: Option<&MemberIdentity>,
    text: &str,
    position: Option<(u64, u64)>,
) -> Result<()> {
    let content_id = keys::random_bytes::<CONTENT_ID_LEN>()?;
    store::insert_message(
        ctx.conn,
        &NewMessage {
            conversation_id,
            content_id: &content_id,
            sender_account: actor.map(|a| a.account_id.as_slice()).unwrap_or(&[]),
            sender_username: actor.map(|a| a.username.as_str()).unwrap_or(""),
            outgoing: false,
            system: true,
            sent_at: store::now_ms(),
            text,
            reply_to: None,
            status: message_status::RECEIVED,
            position,
        },
    )?;
    Ok(())
}

/// Applies decrypted content from `sender`, read at `seq` in the current
/// epoch's mailbox.
fn apply_content(
    ctx: &Ctx<'_>,
    conv: &ConversationRow,
    sender: &MemberIdentity,
    framed: &[u8],
    seq: u64,
) -> Result<Effects> {
    let bytes = padding::unpad(framed).map_err(|e| Error::Mls(format!("bad padding: {e}")))?;
    let content = Content::decode(bytes).map_err(|e| Error::Mls(format!("bad content: {e}")))?;
    if content.id.len() != CONTENT_ID_LEN {
        return Err(Error::Mls("bad content id".into()));
    }
    let valid_target = |target: &[u8]| target.len() == CONTENT_ID_LEN;
    match content.body {
        Some(Body::Text(text)) => {
            if text.text.len() > MAX_TEXT_BYTES {
                return Err(Error::Mls("text too long".into()));
            }
            let reply_to =
                (!text.reply_to.is_empty() && valid_target(&text.reply_to)).then_some(text.reply_to.as_slice());
            let inserted = store::insert_message(
                ctx.conn,
                &NewMessage {
                    conversation_id: &conv.id,
                    content_id: &content.id,
                    sender_account: &sender.account_id,
                    sender_username: &sender.username,
                    outgoing: false,
                    system: false,
                    sent_at: content.sent_at_ms.min(i64::MAX as u64) as i64,
                    text: &text.text,
                    reply_to,
                    status: message_status::RECEIVED,
                    position: Some((conv.epoch, seq)),
                },
            )?;
            if inserted {
                store::touch_conversation(ctx.conn, &conv.id, 1)?;
            }
            // Sending a message ends their typing indicator.
            Ok(Effects {
                new_messages: inserted as u32,
                changed: false,
                typing: Some((sender.account_id.clone(), sender.username.clone(), false)),
            })
        }
        Some(Body::Reaction(reaction)) if valid_target(&reaction.target) && reaction.emoji.len() <= 32 => {
            let emoji = (!reaction.remove && !reaction.emoji.is_empty()).then_some(reaction.emoji.as_str());
            store::set_reaction(ctx.conn, &conv.id, &reaction.target, &sender.account_id, &sender.username, emoji)?;
            Ok(Effects::changed())
        }
        Some(Body::Edit(edit)) if valid_target(&edit.target) && edit.text.len() <= MAX_TEXT_BYTES => {
            store::edit_message(ctx.conn, &conv.id, &edit.target, &sender.account_id, &edit.text)?;
            Ok(Effects::changed())
        }
        Some(Body::Delete(delete)) if valid_target(&delete.target) => {
            store::delete_message(ctx.conn, &conv.id, &delete.target, &sender.account_id)?;
            Ok(Effects::changed())
        }
        Some(Body::Receipt(receipt)) => {
            // Read receipts work both ways: with ours off, we don't record theirs.
            if !privacy_settings(ctx.conn)?.read_receipts {
                return Ok(Effects::default());
            }
            let mut changed = false;
            for target in receipt.targets.iter().filter(|t| valid_target(t)).take(MAX_RECEIPT_TARGETS) {
                changed |= store::add_read_receipt(ctx.conn, &conv.id, target, &sender.account_id, &sender.username)?;
            }
            Ok(Effects { changed, ..Effects::default() })
        }
        Some(Body::Typing(typing)) => {
            let fresh =
                store::now_ms().saturating_sub(content.sent_at_ms.min(i64::MAX as u64) as i64) < TYPING_MAX_AGE_MS;
            if !fresh || !privacy_settings(ctx.conn)?.typing_indicators {
                return Ok(Effects::default());
            }
            Ok(Effects {
                typing: Some((sender.account_id.clone(), sender.username.clone(), typing.typing)),
                ..Effects::default()
            })
        }
        // Malformed, or a kind of content from a newer version.
        _ => Ok(Effects::default()),
    }
}
