//! Client core of the messenger.
//!
//! Owns all client state: MLS groups (OpenMLS, X-Wing ciphersuite), the
//! local SQLite store, the outbox and sync. The apps (SwiftUI, Compose) and
//! the CLI are thin layers over [`Client`].
//!
//! ```no_run
//! let alice = messenger_core::Client::open("./alice", "http://127.0.0.1:8080")?;
//! let registration = alice.register("alice")?;
//! println!("write this down: {}", registration.recovery_key);
//! let chat = alice.start_direct_chat("bob")?;
//! alice.send_text(&chat, "hi bob", None)?;
//! let report = alice.sync(25_000)?; // long-poll for replies
//! # Ok::<(), messenger_core::Error>(())
//! ```

mod api;
mod client;
mod engine;
mod error;
mod keys;
mod mls;
mod provider;
mod store;
mod views;

pub use client::Client;
pub use error::{Error, Result};
pub use views::{
    AccountInfo, ConversationKind, ConversationSummary, MemberView, MessageStatus, MessageView, PrivacySettings,
    ReactionSummary, Registration, ReplyPreview, SafetyNumber, SyncReport,
};
