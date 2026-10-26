//! Read models for the apps. The apps render these and keep no state of
//! their own, so iPhone, Mac and Android show the same thing.

use rusqlite::params;
use sha2::{Digest, Sha512};

use crate::{
    client::{leaving, parse_id, Client},
    mls::{self as group_ops},
    provider::Provider,
    store::{self, conversation_kind, message_status},
    Error, Result,
};

/// Privacy settings kept by the core, because they decide what it sends.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PrivacySettings {
    /// Send read receipts, and show other people's.
    pub read_receipts: bool,
    /// Send typing indicators, and show other people's.
    pub typing_indicators: bool,
}

/// An incoming message to show a notification for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MessageNotification {
    pub message_id: String,
    pub conversation_id: String,
    /// The other person in a 1:1 chat, or the group's name.
    pub title: String,
    pub is_group: bool,
    pub sender: String,
    pub text: String,
    pub sent_at_ms: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AccountInfo {
    /// Hex of the random 128-bit account id.
    pub account_id: String,
    pub username: String,
    pub server_url: String,
}

#[derive(Debug, Clone)]
pub struct Registration {
    pub account: AccountInfo,
    /// 64 characters in groups of four. Show once; never stored by the server.
    pub recovery_key: String,
}

#[derive(Debug, Clone, Default)]
pub struct SyncReport {
    pub new_messages: u32,
    /// Conversations with new messages or membership changes (hex ids).
    pub updated_conversations: Vec<String>,
    /// Conversations we were invited to in this sync (hex ids).
    pub joined_conversations: Vec<String>,
    /// Messages and group changes still waiting to be sent.
    pub pending_outbox: u32,
    /// Conversations where someone started or stopped typing (hex ids).
    pub typing_changed: Vec<String>,
    /// Conversations where someone's shared location changed (hex ids).
    pub locations_changed: Vec<String>,
    /// Someone's avatar or ink changed (see `Client::profiles`).
    pub profiles_changed: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConversationKind {
    Direct,
    Group,
}

#[derive(Debug, Clone)]
pub struct ConversationSummary {
    pub id: String,
    pub kind: ConversationKind,
    /// Group name, or the other person's username for 1:1 chats.
    pub title: String,
    pub last_message: Option<String>,
    pub last_sender: Option<String>,
    pub last_activity_ms: i64,
    pub unread: u32,
    pub member_count: u32,
    /// False once we've left or been removed, or the other person in a 1:1
    /// chat has left.
    pub active: bool,
    /// We asked to leave and are waiting for the others to process it.
    pub leaving: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MessageStatus {
    Sending,
    Sent,
    /// Read by everyone else in the conversation (needs read receipts on).
    Read,
    Failed,
    Received,
}

#[derive(Debug, Clone)]
pub struct ReplyPreview {
    pub id: String,
    pub sender: String,
    pub text: String,
}

#[derive(Debug, Clone)]
pub struct ReactionSummary {
    pub emoji: String,
    pub count: u32,
    /// Includes our own reaction.
    pub mine: bool,
    pub usernames: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct MessageView {
    pub id: String,
    pub sender: String,
    pub outgoing: bool,
    /// Membership notices ("alice added bob").
    pub system: bool,
    pub text: String,
    pub sent_at_ms: i64,
    pub status: MessageStatus,
    pub reply_to: Option<ReplyPreview>,
    pub edited: bool,
    pub deleted: bool,
    pub reactions: Vec<ReactionSummary>,
    /// Our own messages: who has read them (needs read receipts on).
    pub read_by: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct MemberView {
    pub username: String,
    pub account_id: String,
    pub is_me: bool,
}

#[derive(Debug, Clone)]
pub struct SafetyNumber {
    /// 60 digits in 12 groups of five. Both people see the same number.
    pub digits: String,
    /// What to put in the QR code: the digits without spaces.
    pub qr_payload: String,
}

fn status_of(code: i64) -> MessageStatus {
    match code {
        message_status::SENDING => MessageStatus::Sending,
        message_status::SENT => MessageStatus::Sent,
        message_status::FAILED => MessageStatus::Failed,
        _ => MessageStatus::Received,
    }
}

impl Client {
    pub fn conversations(&self) -> Result<Vec<ConversationSummary>> {
        let state = self.lock()?;
        let conn = &state.conn;
        let mut last = conn.prepare_cached(&format!(
            "SELECT m.text, m.sender_username, m.deleted, m.system FROM messages m WHERE m.conversation_id = ?1 \
             ORDER BY {} LIMIT 1",
            store::MESSAGE_ORDER_DESC
        ))?;
        let mut out = Vec::new();
        for c in store::conversations(conn)? {
            let preview: Option<(String, String, bool, bool)> = last
                .query_row(params![c.id], |r| {
                    Ok((r.get(0)?, r.get(1)?, r.get::<_, i64>(2)? != 0, r.get::<_, i64>(3)? != 0))
                })
                .ok();
            let (last_message, last_sender) = match preview {
                Some((_, sender, true, _)) => (Some("Message deleted".to_string()), Some(sender)),
                Some((text, _, _, true)) => (Some(text), None),
                Some((text, sender, _, _)) => (Some(text), Some(sender)),
                None => (None, None),
            };
            let direct = c.kind == conversation_kind::DIRECT;
            out.push(ConversationSummary {
                id: hex::encode(&c.id),
                kind: if direct { ConversationKind::Direct } else { ConversationKind::Group },
                title: if direct {
                    c.peer_username.clone().unwrap_or_else(|| c.title.clone())
                } else {
                    c.title.clone()
                },
                last_message,
                last_sender,
                last_activity_ms: c.updated_at,
                unread: c.unread,
                member_count: c.member_count,
                active: c.active,
                leaving: c.active && leaving(conn, &c.id)?,
            });
        }
        Ok(out)
    }

    pub fn conversation(&self, conversation_id: &str) -> Result<ConversationSummary> {
        self.conversations()?
            .into_iter()
            .find(|c| c.id == conversation_id.trim().to_ascii_lowercase())
            .ok_or(Error::ConversationNotFound)
    }

    /// The latest `limit` messages, oldest first.
    pub fn messages(&self, conversation_id: &str, limit: u32) -> Result<Vec<MessageView>> {
        let id = parse_id(conversation_id)?;
        let state = self.lock()?;
        let conn = &state.conn;
        let me = state.account.as_ref().map(|a| a.account_id.clone()).unwrap_or_default();

        // Read receipts: who read each of our messages, and (for messages
        // without a recorded count) how many readers make it "read by everyone".
        let others = store::conversation(conn, &id)?.map(|c| c.member_count.saturating_sub(1)).unwrap_or(0);
        let mut readers_by_target: std::collections::HashMap<Vec<u8>, Vec<String>> = Default::default();
        {
            let mut stmt = conn.prepare(
                "SELECT target, reader_username FROM read_receipts WHERE conversation_id = ?1 ORDER BY read_at",
            )?;
            let rows = stmt.query_map(params![id], |r| Ok((r.get::<_, Vec<u8>>(0)?, r.get::<_, String>(1)?)))?;
            for row in rows {
                let (target, username) = row?;
                readers_by_target.entry(target).or_default().push(username);
            }
        }

        let mut reactions_by_target: std::collections::HashMap<Vec<u8>, Vec<(String, String, bool)>> =
            Default::default();
        {
            let mut stmt = conn.prepare(
                "SELECT target, emoji, sender_username, sender_account FROM reactions WHERE conversation_id = ?1 ORDER BY updated_at",
            )?;
            let rows = stmt.query_map(params![id], |r| {
                Ok((r.get::<_, Vec<u8>>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, Vec<u8>>(3)?))
            })?;
            for row in rows {
                let (target, emoji, username, account) = row?;
                reactions_by_target.entry(target).or_default().push((emoji, username, account == me));
            }
        }

        let mut stmt = conn.prepare(&format!(
            "SELECT m.content_id, m.sender_username, m.outgoing, m.system, m.text, m.sent_at, m.status, m.reply_to, \
                    m.edited_at, m.deleted, r.sender_username, r.text, r.deleted, m.recipients \
             FROM messages m LEFT JOIN messages r ON r.conversation_id = m.conversation_id AND r.content_id = m.reply_to \
             WHERE m.conversation_id = ?1 ORDER BY {} LIMIT ?2",
            store::MESSAGE_ORDER_DESC
        ))?;
        let rows = stmt.query_map(params![id, limit.clamp(1, 10_000)], |r| {
            let content_id: Vec<u8> = r.get(0)?;
            let reply_id: Option<Vec<u8>> = r.get(7)?;
            let reply_sender: Option<String> = r.get(10)?;
            let reply_text: Option<String> = r.get(11)?;
            let reply_deleted: Option<i64> = r.get(12)?;
            Ok((
                content_id,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)? != 0,
                r.get::<_, i64>(3)? != 0,
                r.get::<_, String>(4)?,
                r.get::<_, i64>(5)?,
                r.get::<_, i64>(6)?,
                reply_id.map(|rid| ReplyPreview {
                    id: hex::encode(rid),
                    sender: reply_sender.unwrap_or_default(),
                    text: match reply_deleted {
                        Some(1) => "Message deleted".to_string(),
                        None => "Message not available".to_string(),
                        _ => reply_text.unwrap_or_default(),
                    },
                }),
                r.get::<_, Option<i64>>(8)?.is_some(),
                r.get::<_, i64>(9)? != 0,
                r.get::<_, Option<i64>>(13)?,
            ))
        })?;
        let mut out = Vec::new();
        for row in rows {
            let (content_id, sender, outgoing, system, text, sent_at, status, reply_to, edited, deleted, recipients) =
                row?;
            let mut reactions: Vec<ReactionSummary> = Vec::new();
            for (emoji, username, mine) in reactions_by_target.remove(&content_id).unwrap_or_default() {
                match reactions.iter_mut().find(|r| r.emoji == emoji) {
                    Some(r) => {
                        r.count += 1;
                        r.mine |= mine;
                        r.usernames.push(username);
                    }
                    None => reactions.push(ReactionSummary { emoji, count: 1, mine, usernames: vec![username] }),
                }
            }
            let read_by = if outgoing { readers_by_target.remove(&content_id).unwrap_or_default() } else { Vec::new() };
            let mut status = status_of(status);
            // Everyone it went to has read it. Messages from before this was
            // recorded fall back to the current members.
            let needed = recipients.map(|n| n.max(0) as usize).unwrap_or(others as usize);
            if status == MessageStatus::Sent && !read_by.is_empty() && read_by.len() >= needed {
                status = MessageStatus::Read;
            }
            out.push(MessageView {
                id: hex::encode(&content_id),
                sender,
                outgoing,
                system,
                text,
                sent_at_ms: sent_at,
                status,
                reply_to,
                edited,
                deleted,
                reactions,
                read_by,
            });
        }
        out.reverse();
        Ok(out)
    }

    pub fn members(&self, conversation_id: &str) -> Result<Vec<MemberView>> {
        let id = parse_id(conversation_id)?;
        let state = self.lock()?;
        let me = state.account.as_ref().ok_or(Error::NotRegistered)?.account_id.clone();
        let conv = store::conversation(&state.conn, &id)?.ok_or(Error::ConversationNotFound)?;
        if !conv.active {
            return Ok(Vec::new());
        }
        let provider = Provider::new(&self.crypto, &state.conn);
        let group = group_ops::load_group(&provider, &id)?;
        let mut members: Vec<MemberView> = group_ops::members(&group)
            .into_iter()
            .map(|m| MemberView {
                is_me: m.identity.account_id == me,
                account_id: hex::encode(&m.identity.account_id),
                username: m.identity.username,
            })
            .collect();
        members.sort_by(|a, b| b.is_me.cmp(&a.is_me).then(a.username.cmp(&b.username)));
        Ok(members)
    }

    /// The safety number of a 1:1 chat. If both people see the same digits
    /// (compare in person, or scan the QR code), no one is in the middle.
    pub fn safety_number(&self, conversation_id: &str) -> Result<SafetyNumber> {
        let id = parse_id(conversation_id)?;
        let state = self.lock()?;
        let me = state.account.as_ref().ok_or(Error::NotRegistered)?.account_id.clone();
        let conv = store::conversation(&state.conn, &id)?.ok_or(Error::ConversationNotFound)?;
        if conv.kind != conversation_kind::DIRECT || !conv.active {
            return Err(Error::InvalidInput("safety numbers are for active 1:1 chats".into()));
        }
        let provider = Provider::new(&self.crypto, &state.conn);
        let group = group_ops::load_group(&provider, &id)?;
        let members = group_ops::members(&group);
        let mine = members.iter().find(|m| m.identity.account_id == me);
        let theirs = members.iter().find(|m| m.identity.account_id != me);
        let (Some(mine), Some(theirs)) = (mine, theirs) else {
            return Err(Error::InvalidInput("the other person hasn't joined yet".into()));
        };
        let mut halves = [
            fingerprint(&mine.signature_key, &mine.identity.account_id),
            fingerprint(&theirs.signature_key, &theirs.identity.account_id),
        ];
        halves.sort();
        let all = halves.concat();
        let digits = all.as_bytes().chunks(5).map(|c| std::str::from_utf8(c).unwrap()).collect::<Vec<_>>().join(" ");
        Ok(SafetyNumber { digits, qr_payload: all })
    }
}

/// 30 digits identifying one person's key. Iterated hashing makes finding a
/// key with a matching number expensive.
fn fingerprint(public_key: &[u8], account_id: &[u8]) -> String {
    let mut hash = Sha512::new().chain_update([0u8, 1]).chain_update(public_key).chain_update(account_id).finalize();
    for _ in 0..5200 {
        hash = Sha512::new().chain_update(hash).chain_update(public_key).finalize();
    }
    hash[..30]
        .chunks(5)
        .map(|chunk| {
            let value = chunk.iter().fold(0u64, |acc, &b| (acc << 8) | u64::from(b));
            format!("{:05}", value % 100_000)
        })
        .collect()
}
