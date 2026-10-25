//! Wire formats shared by the client core and the server.
//!
//! - [`api`]: JSON bodies of the HTTP API (`/v1/...`).
//! - [`auth`]: canonical byte strings that clients sign and the server verifies.
//! - [`v1`]: protobuf messages that travel inside MLS (generated from `proto/`).
//! - [`padding`]: length hiding for application messages (Padmé).

pub mod api;
pub mod auth;
pub mod b64;
pub mod padding;

/// Protobuf types generated from `proto/messenger/v1/content.proto`.
#[allow(clippy::all, missing_docs)]
pub mod v1 {
    include!(concat!(env!("OUT_DIR"), "/messenger.v1.rs"));
}

/// MLS ciphersuite used for every group: X-Wing (ML-KEM-768 + X25519) hybrid
/// KEM, ChaCha20-Poly1305, SHA-256, Ed25519.
/// `MLS_256_XWING_CHACHA20POLY1305_SHA256_Ed25519` in OpenMLS.
pub const CIPHERSUITE_ID: u16 = 0x004D;

/// Private-use group context extension holding [`v1::GroupMeta`].
pub const GROUP_META_EXTENSION_TYPE: u16 = 0xF5A0;

/// MLS exporter label for the per-epoch delivery id (the group's mailbox).
pub const DELIVERY_ID_LABEL: &str = "messenger delivery id v1";

pub const ACCOUNT_ID_LEN: usize = 16;
pub const MAILBOX_ID_LEN: usize = 32;
pub const CLIENT_MSG_ID_LEN: usize = 16;
pub const CONTENT_ID_LEN: usize = 16;
pub const WAKE_TOKEN_LEN: usize = 16;
pub const BLOB_ID_LEN: usize = 32;

/// Largest encrypted file a server accepts, unless it's set lower.
pub const MAX_BLOB_BYTES: u64 = 100 * 1024 * 1024;

/// Largest MLS message the server stores. Welcomes carry the ratchet tree, and
/// X-Wing keys are about 1.2 KB each, so a 100-member group's Welcome is a few
/// hundred kilobytes.
pub const MAX_PAYLOAD_BYTES: usize = 1024 * 1024;

/// Largest text body a client will send.
pub const MAX_TEXT_BYTES: usize = 64 * 1024;

/// Allowed clock difference for signed requests.
pub const MAX_CLOCK_SKEW_MS: u64 = 5 * 60 * 1000;

/// Reasons a username is rejected.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum UsernameError {
    #[error("usernames are 3 to 32 characters")]
    Length,
    #[error("usernames start with a letter")]
    Start,
    #[error("usernames use only a-z, 0-9, '_' and '.'")]
    Characters,
}

/// Lowercases and checks a username: `^[a-z][a-z0-9_.]{2,31}$`.
pub fn normalize_username(input: &str) -> Result<String, UsernameError> {
    let name = input.trim().to_ascii_lowercase();
    let len = name.chars().count();
    if !(3..=32).contains(&len) {
        return Err(UsernameError::Length);
    }
    let mut chars = name.chars();
    if !chars.next().is_some_and(|c| c.is_ascii_lowercase()) {
        return Err(UsernameError::Start);
    }
    if !chars.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.') {
        return Err(UsernameError::Characters);
    }
    Ok(name)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usernames() {
        assert_eq!(normalize_username("  Alice_01 ").unwrap(), "alice_01");
        assert_eq!(normalize_username("ab"), Err(UsernameError::Length));
        assert_eq!(normalize_username("1abc"), Err(UsernameError::Start));
        assert_eq!(normalize_username("ab-c"), Err(UsernameError::Characters));
        assert_eq!(normalize_username("émile"), Err(UsernameError::Start));
        assert!(normalize_username(&"a".repeat(33)).is_err());
    }
}
