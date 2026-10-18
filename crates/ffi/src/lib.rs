//! UniFFI surface of the messenger core. Swift and Kotlin bindings are
//! generated from this crate (see `scripts/bindings.sh`).
//!
//! Every method blocks: call it off the main thread (a background `Task` in
//! Swift, `Dispatchers.IO` in Kotlin). `sync(wait_ms)` long-polls, so run it
//! in its own loop; other calls don't wait for it.

use std::sync::Arc;

use messenger_core as core;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum MessengerError {
    #[error("{0}")]
    NotRegistered(String),
    #[error("{0}")]
    AlreadyRegistered(String),
    #[error("{0}")]
    UsernameTaken(String),
    #[error("{0}")]
    UserNotFound(String),
    #[error("{0}")]
    NoKeyPackages(String),
    #[error("{0}")]
    InvalidInput(String),
    #[error("{0}")]
    ConversationNotFound(String),
    #[error("{0}")]
    NotAMember(String),
    #[error("{0}")]
    BadRecoveryKey(String),
    #[error("{0}")]
    Network(String),
    #[error("{0}")]
    Server(String),
    #[error("{0}")]
    Storage(String),
    #[error("{0}")]
    Encryption(String),
    #[error("{0}")]
    Internal(String),
}

impl From<core::Error> for MessengerError {
    fn from(e: core::Error) -> Self {
        let message = e.to_string();
        match e {
            core::Error::NotRegistered => Self::NotRegistered(message),
            core::Error::AlreadyRegistered(_) => Self::AlreadyRegistered(message),
            core::Error::UsernameTaken => Self::UsernameTaken(message),
            core::Error::UserNotFound(_) => Self::UserNotFound(message),
            core::Error::NoKeyPackages(_) => Self::NoKeyPackages(message),
            core::Error::InvalidInput(_) => Self::InvalidInput(message),
            core::Error::ConversationNotFound => Self::ConversationNotFound(message),
            core::Error::NotAMember => Self::NotAMember(message),
            core::Error::BadRecoveryKey => Self::BadRecoveryKey(message),
            core::Error::Network(_) => Self::Network(message),
            core::Error::Server { .. } => Self::Server(message),
            core::Error::Storage(_) => Self::Storage(message),
            core::Error::Mls(_) => Self::Encryption(message),
            core::Error::InjectedFault(_) | core::Error::Internal(_) => Self::Internal(message),
        }
    }
}

type Result<T> = std::result::Result<T, MessengerError>;

#[derive(Debug, Clone, uniffi::Record)]
pub struct AccountInfo {
    pub account_id: String,
    pub username: String,
    pub server_url: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct Registration {
    pub account: AccountInfo,
    pub recovery_key: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SyncReport {
    pub new_messages: u32,
    pub updated_conversations: Vec<String>,
    pub joined_conversations: Vec<String>,
    pub pending_outbox: u32,
    /// Conversations where someone started or stopped typing.
    pub typing_changed: Vec<String>,
}

/// Read receipts and typing indicators work both ways: with one off, we
/// neither send ours nor show other people's.
#[derive(Debug, Clone, Copy, uniffi::Record)]
pub struct PrivacySettings {
    pub read_receipts: bool,
    pub typing_indicators: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum ConversationKind {
    Direct,
    Group,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ConversationSummary {
    pub id: String,
    pub kind: ConversationKind,
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

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum MessageStatus {
    Sending,
    Sent,
    /// Read by everyone else in the conversation.
    Read,
    Failed,
    Received,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReplyPreview {
    pub id: String,
    pub sender: String,
    pub text: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct ReactionSummary {
    pub emoji: String,
    pub count: u32,
    pub mine: bool,
    pub usernames: Vec<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MessageView {
    pub id: String,
    pub sender: String,
    pub outgoing: bool,
    pub system: bool,
    pub text: String,
    pub sent_at_ms: i64,
    pub status: MessageStatus,
    pub reply_to: Option<ReplyPreview>,
    pub edited: bool,
    pub deleted: bool,
    pub reactions: Vec<ReactionSummary>,
    /// Our own messages: who has read them.
    pub read_by: Vec<String>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct MemberView {
    pub username: String,
    pub account_id: String,
    pub is_me: bool,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SafetyNumber {
    pub digits: String,
    pub qr_payload: String,
}

impl From<core::AccountInfo> for AccountInfo {
    fn from(a: core::AccountInfo) -> Self {
        Self { account_id: a.account_id, username: a.username, server_url: a.server_url }
    }
}

impl From<core::ConversationSummary> for ConversationSummary {
    fn from(c: core::ConversationSummary) -> Self {
        Self {
            id: c.id,
            kind: match c.kind {
                core::ConversationKind::Direct => ConversationKind::Direct,
                core::ConversationKind::Group => ConversationKind::Group,
            },
            title: c.title,
            last_message: c.last_message,
            last_sender: c.last_sender,
            last_activity_ms: c.last_activity_ms,
            unread: c.unread,
            member_count: c.member_count,
            active: c.active,
            leaving: c.leaving,
        }
    }
}

impl From<core::MessageView> for MessageView {
    fn from(m: core::MessageView) -> Self {
        Self {
            id: m.id,
            sender: m.sender,
            outgoing: m.outgoing,
            system: m.system,
            text: m.text,
            sent_at_ms: m.sent_at_ms,
            status: match m.status {
                core::MessageStatus::Sending => MessageStatus::Sending,
                core::MessageStatus::Sent => MessageStatus::Sent,
                core::MessageStatus::Read => MessageStatus::Read,
                core::MessageStatus::Failed => MessageStatus::Failed,
                core::MessageStatus::Received => MessageStatus::Received,
            },
            reply_to: m.reply_to.map(|r| ReplyPreview { id: r.id, sender: r.sender, text: r.text }),
            edited: m.edited,
            deleted: m.deleted,
            reactions: m
                .reactions
                .into_iter()
                .map(|r| ReactionSummary { emoji: r.emoji, count: r.count, mine: r.mine, usernames: r.usernames })
                .collect(),
            read_by: m.read_by,
        }
    }
}

/// One account on this device.
#[derive(uniffi::Object)]
pub struct MessengerClient {
    inner: core::Client,
}

#[uniffi::export]
impl MessengerClient {
    /// Opens the client stored in `data_dir` (created if missing).
    #[uniffi::constructor]
    pub fn new(data_dir: String, server_url: String) -> Result<Arc<Self>> {
        Ok(Arc::new(Self { inner: core::Client::open(data_dir, &server_url)? }))
    }

    pub fn account(&self) -> Result<Option<AccountInfo>> {
        Ok(self.inner.account()?.map(Into::into))
    }

    pub fn register(&self, username: String) -> Result<Registration> {
        let r = self.inner.register(&username)?;
        Ok(Registration { account: r.account.into(), recovery_key: r.recovery_key })
    }

    pub fn recover(&self, username: String, recovery_key: String) -> Result<AccountInfo> {
        Ok(self.inner.recover(&username, &recovery_key)?.into())
    }

    pub fn pending_recovery_key(&self) -> Result<Option<String>> {
        Ok(self.inner.pending_recovery_key()?)
    }

    pub fn confirm_recovery_key_saved(&self) -> Result<()> {
        Ok(self.inner.confirm_recovery_key_saved()?)
    }

    /// Replaces the recovery key; the old one stops working. Show the new
    /// one, then call `confirm_recovery_key_saved`.
    pub fn new_recovery_key(&self) -> Result<String> {
        Ok(self.inner.new_recovery_key()?)
    }

    /// Leaves every conversation, deletes the account on the server (the
    /// username becomes free) and erases this device. Afterwards the client
    /// is empty, as on a fresh install.
    pub fn delete_account(&self) -> Result<()> {
        Ok(self.inner.delete_account()?)
    }

    /// Leaves every conversation, withdraws this device's invitation keys and
    /// erases this device. The account remains; the recovery key brings it
    /// back on any device, without the old messages.
    pub fn remove_from_device(&self) -> Result<()> {
        Ok(self.inner.remove_from_device()?)
    }

    /// Panic wipe: erases everything on this device at once, with no network
    /// and no goodbyes. The account remains for the recovery key.
    pub fn wipe_this_device(&self) -> Result<()> {
        Ok(self.inner.wipe_this_device()?)
    }

    pub fn leave_conversation(&self, conversation_id: String) -> Result<()> {
        Ok(self.inner.leave_conversation(&conversation_id)?)
    }

    pub fn privacy_settings(&self) -> Result<PrivacySettings> {
        let p = self.inner.privacy_settings()?;
        Ok(PrivacySettings { read_receipts: p.read_receipts, typing_indicators: p.typing_indicators })
    }

    pub fn set_privacy_settings(&self, settings: PrivacySettings) -> Result<()> {
        Ok(self.inner.set_privacy_settings(core::PrivacySettings {
            read_receipts: settings.read_receipts,
            typing_indicators: settings.typing_indicators,
        })?)
    }

    /// Call as the draft changes: true while there's text, false when it's
    /// cleared. Throttled; sending a message also ends it.
    pub fn set_typing(&self, conversation_id: String, typing: bool) -> Result<()> {
        Ok(self.inner.set_typing(&conversation_id, typing)?)
    }

    /// Who is typing in a conversation right now.
    pub fn typing_users(&self, conversation_id: String) -> Result<Vec<String>> {
        Ok(self.inner.typing_users(&conversation_id)?)
    }

    pub fn start_direct_chat(&self, username: String) -> Result<String> {
        Ok(self.inner.start_direct_chat(&username)?)
    }

    pub fn create_group(&self, title: String, usernames: Vec<String>) -> Result<String> {
        Ok(self.inner.create_group(&title, &usernames)?)
    }

    pub fn add_members(&self, conversation_id: String, usernames: Vec<String>) -> Result<()> {
        Ok(self.inner.add_members(&conversation_id, &usernames)?)
    }

    pub fn remove_member(&self, conversation_id: String, username: String) -> Result<()> {
        Ok(self.inner.remove_member(&conversation_id, &username)?)
    }

    pub fn send_text(&self, conversation_id: String, text: String, reply_to: Option<String>) -> Result<String> {
        Ok(self.inner.send_text(&conversation_id, &text, reply_to.as_deref())?)
    }

    pub fn react(&self, conversation_id: String, message_id: String, emoji: Option<String>) -> Result<()> {
        Ok(self.inner.react(&conversation_id, &message_id, emoji.as_deref())?)
    }

    pub fn edit_message(&self, conversation_id: String, message_id: String, text: String) -> Result<()> {
        Ok(self.inner.edit_message(&conversation_id, &message_id, &text)?)
    }

    pub fn delete_message(&self, conversation_id: String, message_id: String) -> Result<()> {
        Ok(self.inner.delete_message(&conversation_id, &message_id)?)
    }

    pub fn mark_read(&self, conversation_id: String) -> Result<()> {
        Ok(self.inner.mark_read(&conversation_id)?)
    }

    /// Sends queued work and fetches new messages; waits up to `wait_ms`
    /// for something to arrive.
    pub fn sync(&self, wait_ms: u32) -> Result<SyncReport> {
        let r = self.inner.sync(wait_ms)?;
        Ok(SyncReport {
            new_messages: r.new_messages,
            updated_conversations: r.updated_conversations,
            joined_conversations: r.joined_conversations,
            pending_outbox: r.pending_outbox,
            typing_changed: r.typing_changed,
        })
    }

    pub fn conversations(&self) -> Result<Vec<ConversationSummary>> {
        Ok(self.inner.conversations()?.into_iter().map(Into::into).collect())
    }

    pub fn messages(&self, conversation_id: String, limit: u32) -> Result<Vec<MessageView>> {
        Ok(self.inner.messages(&conversation_id, limit)?.into_iter().map(Into::into).collect())
    }

    pub fn members(&self, conversation_id: String) -> Result<Vec<MemberView>> {
        Ok(self
            .inner
            .members(&conversation_id)?
            .into_iter()
            .map(|m| MemberView { username: m.username, account_id: m.account_id, is_me: m.is_me })
            .collect())
    }

    pub fn safety_number(&self, conversation_id: String) -> Result<SafetyNumber> {
        let n = self.inner.safety_number(&conversation_id)?;
        Ok(SafetyNumber { digits: n.digits, qr_payload: n.qr_payload })
    }
}

/// Version of the Rust core, for the app's About screen.
#[uniffi::export]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}
