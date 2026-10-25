//! Push notifications.
//!
//! The server pushes a device only to make its app fetch; a push carries
//! nothing. Here we:
//! - register where to push this device, and get back our wake token;
//! - share that token inside each of our groups (a `Wake` message), and keep
//!   the other members' tokens;
//! - name those tokens with messages worth a notification, so the server
//!   wakes the members' devices (see `engine::Client::flush`);
//! - hand the app the incoming messages to show notifications for.
//!
//! A new member gets everyone's token because members share theirs again
//! whenever someone joins.

use messenger_protocol::{
    api::{PushInfo, PushRegistration, PushTarget},
    v1::{content::Body, Content, MemberIdentity, Wake},
    CONTENT_ID_LEN, WAKE_TOKEN_LEN,
};
use rusqlite::Connection;

use crate::{
    api::RequestSigner,
    client::{leaving, meta_key, queue_content, Client, Ctx},
    keys,
    store::{self, conversation_kind, latest_only, ConversationRow},
    views::MessageNotification,
    Result,
};

impl Client {
    /// What the server can push to, and its VAPID key for Web Push
    /// (UnifiedPush) registrations.
    pub fn push_info(&self) -> Result<PushInfo> {
        self.api.push_info()
    }

    /// Where pushes for this device go now, if anywhere.
    pub fn push_target(&self) -> Result<Option<PushTarget>> {
        let state = self.lock()?;
        let Some(json) = store::meta_get(&state.conn, meta_key::PUSH_TARGET)? else { return Ok(None) };
        Ok(serde_json::from_slice(&json).ok())
    }

    /// Registers where to push this device (call it again whenever the
    /// platform hands out a new token or endpoint). The first registration
    /// shares our wake token with every conversation.
    pub fn set_push_target(&self, target: PushTarget) -> Result<()> {
        let (account, signer) = self.identity()?;
        let sign = |payload: &[u8]| keys::sign(&signer, payload);
        let request_signer = RequestSigner { account_id: &account.account_id, sign: &sign };
        let registered = self.api.register_push(&PushRegistration { target: target.clone() }, &request_signer)?;
        let shared = self.with_tx(|ctx| {
            store::meta_set(ctx.conn, meta_key::PUSH_TARGET, &serde_json::to_vec(&target)?)?;
            if store::meta_get(ctx.conn, meta_key::WAKE_TOKEN)?.as_deref() == Some(registered.wake_token.as_slice()) {
                return Ok(false);
            }
            store::meta_set(ctx.conn, meta_key::WAKE_TOKEN, &registered.wake_token)?;
            share_everywhere(ctx, &registered.wake_token)?;
            Ok(true)
        })?;
        if shared {
            self.flush_quietly();
        }
        Ok(())
    }

    /// Stops pushes to this device. The others are told to stop naming our
    /// wake token, which stops working anyway.
    pub fn clear_push_target(&self) -> Result<()> {
        let (account, signer) = self.identity()?;
        let sign = |payload: &[u8]| keys::sign(&signer, payload);
        self.api.delete_push(&RequestSigner { account_id: &account.account_id, sign: &sign })?;
        let told = self.with_tx(|ctx| {
            store::meta_delete(ctx.conn, meta_key::PUSH_TARGET)?;
            if store::meta_get(ctx.conn, meta_key::WAKE_TOKEN)?.is_none() {
                return Ok(false);
            }
            store::meta_delete(ctx.conn, meta_key::WAKE_TOKEN)?;
            share_everywhere(ctx, &[])?;
            Ok(true)
        })?;
        if told {
            self.flush_quietly();
        }
        Ok(())
    }

    /// Incoming messages to show notifications for, oldest first: each one
    /// is handed out once. Messages already read in the app are skipped.
    pub fn take_notifications(&self, limit: u32) -> Result<Vec<MessageNotification>> {
        let rows = self.with_tx(|ctx| store::take_notifications(ctx.conn, limit))?;
        Ok(rows
            .into_iter()
            .map(|row| {
                let group = row.conversation_kind == conversation_kind::GROUP;
                MessageNotification {
                    message_id: hex::encode(&row.content_id),
                    conversation_id: hex::encode(&row.conversation_id),
                    title: if group { row.title } else { row.peer_username.unwrap_or(row.sender_username.clone()) },
                    is_group: group,
                    sender: row.sender_username,
                    text: row.text,
                    sent_at_ms: row.sent_at,
                    thread_root: row.thread_root.map(hex::encode),
                }
            })
            .collect())
    }
}

fn share_everywhere(ctx: &Ctx<'_>, token: &[u8]) -> Result<()> {
    for conv in store::conversations(ctx.conn)? {
        if conv.active && !leaving(ctx.conn, &conv.id)? {
            queue_wake(ctx, &conv, token)?;
        }
    }
    Ok(())
}

/// Queues our wake token for a conversation (empty: "don't wake me"),
/// replacing one that hasn't gone out yet.
fn queue_wake(ctx: &Ctx<'_>, conv: &ConversationRow, token: &[u8]) -> Result<()> {
    store::delete_unsent_latest_only(ctx.conn, &conv.id, latest_only::WAKE)?;
    let content = Content {
        id: keys::random_bytes::<CONTENT_ID_LEN>()?.to_vec(),
        sent_at_ms: store::now_ms() as u64,
        body: Some(Body::Wake(Wake { token: token.to_vec() })),
    };
    let row = queue_content(ctx, conv, &content, false)?;
    store::set_outbox_latest_only(ctx.conn, row, latest_only::WAKE)
}

/// Shares our wake token in a conversation, if we have one: when we join,
/// and when someone else joins.
pub(crate) fn share_wake_token(ctx: &Ctx<'_>, conv: &ConversationRow) -> Result<()> {
    match store::meta_get(ctx.conn, meta_key::WAKE_TOKEN)? {
        Some(token) => queue_wake(ctx, conv, &token),
        None => Ok(()),
    }
}

/// Applies a member's `Wake` message.
pub(crate) fn apply_wake(
    conn: &Connection,
    conv: &ConversationRow,
    sender: &MemberIdentity,
    wake: &Wake,
) -> Result<()> {
    if wake.token.len() == WAKE_TOKEN_LEN {
        store::set_wake_token(conn, &conv.id, &sender.account_id, &sender.username, &wake.token)
    } else {
        store::delete_wake_token(conn, &conv.id, &sender.account_id)
    }
}
