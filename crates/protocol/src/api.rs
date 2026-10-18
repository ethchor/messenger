//! JSON bodies of the HTTP API. All binary fields are base64url without padding.
//!
//! | Method | Path | Auth | Body → Response |
//! |---|---|---|---|
//! | POST | `/v1/accounts` | proof of key possession | [`RegisterRequest`] → [`RegisterResponse`] |
//! | POST | `/v1/accounts/recover` | recovery key | [`RecoverRequest`] → [`RegisterResponse`] |
//! | DELETE | `/v1/accounts/me` | signed | → [`Empty`] (frees the username) |
//! | POST | `/v1/accounts/me/recovery-key` | signed + new recovery key | [`NewRecoveryKeyRequest`] → [`Empty`] |
//! | GET | `/v1/directory/{username}` | none | → [`DirectoryEntry`] |
//! | POST | `/v1/directory/{username}/key-package` | none | → [`KeyPackageResponse`] (consumes one) |
//! | POST | `/v1/key-packages` | signed | [`UploadKeyPackagesRequest`] → [`KeyPackageCount`] |
//! | DELETE | `/v1/key-packages` | signed | → [`KeyPackageCount`] (removes all) |
//! | GET | `/v1/key-packages/count` | signed | → [`KeyPackageCount`] |
//! | POST | `/v1/mailboxes/{mailbox}/messages` | none | [`PostMessage`] → [`PostResponse`], or 409 [`ConflictResponse`] |
//! | POST | `/v1/fetch` | none | [`FetchRequest`] → [`FetchResponse`] |
//!
//! Mailbox ids are capabilities: whoever knows one can read and post. Group
//! mailboxes are derived from the MLS exporter, so only members know them.

use serde::{Deserialize, Serialize};

use crate::b64;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegisterRequest {
    pub username: String,
    /// Ed25519 public key; also the MLS signature key of the device.
    #[serde(with = "b64")]
    pub identity_key: Vec<u8>,
    /// Ed25519 public key derived from the recovery key.
    #[serde(with = "b64")]
    pub recovery_key: Vec<u8>,
    /// Mailbox where invitations (MLS Welcomes) for this account arrive.
    #[serde(with = "b64")]
    pub intro_inbox: Vec<u8>,
    pub timestamp_ms: u64,
    /// Signature by `identity_key` over [`crate::auth::register_payload`].
    #[serde(with = "b64")]
    pub identity_signature: Vec<u8>,
    /// Signature by `recovery_key` over the same payload.
    #[serde(with = "b64")]
    pub recovery_signature: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RegisterResponse {
    #[serde(with = "b64")]
    pub account_id: Vec<u8>,
}

/// Moves an account to a new device key. Signed by the recovery key (proves
/// ownership) and by the new identity key (proves possession).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct RecoverRequest {
    pub username: String,
    #[serde(with = "b64")]
    pub identity_key: Vec<u8>,
    #[serde(with = "b64")]
    pub intro_inbox: Vec<u8>,
    pub timestamp_ms: u64,
    #[serde(with = "b64")]
    pub identity_signature: Vec<u8>,
    #[serde(with = "b64")]
    pub recovery_signature: Vec<u8>,
}

/// Replaces the account's recovery key. The request is signed by the device
/// key; `recovery_signature` by the new recovery key over
/// [`crate::auth::new_recovery_key_payload`] proves it is held. The old
/// recovery key stops working.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NewRecoveryKeyRequest {
    #[serde(with = "b64")]
    pub recovery_key: Vec<u8>,
    pub timestamp_ms: u64,
    #[serde(with = "b64")]
    pub recovery_signature: Vec<u8>,
}

/// Response with nothing to say.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Empty {}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DirectoryEntry {
    #[serde(with = "b64")]
    pub account_id: Vec<u8>,
    pub username: String,
    #[serde(with = "b64")]
    pub identity_key: Vec<u8>,
    #[serde(with = "b64")]
    pub intro_inbox: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyPackageResponse {
    /// TLS-serialized MLS `KeyPackage`.
    #[serde(with = "b64")]
    pub key_package: Vec<u8>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct UploadKeyPackagesRequest {
    #[serde(with = "b64::list")]
    pub key_packages: Vec<Vec<u8>>,
    /// Replaces the stored last-resort key package, which is handed out when
    /// the others run out and is never deleted on use.
    #[serde(default, with = "b64::option", skip_serializing_if = "Option::is_none")]
    pub last_resort: Option<Vec<u8>>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct KeyPackageCount {
    pub count: u32,
    pub has_last_resort: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MessageKind {
    /// MLS commit. The server lets one commit per `prev_commit_seq` through.
    Commit,
    Application,
    Proposal,
    Welcome,
}

impl MessageKind {
    pub fn as_i64(self) -> i64 {
        match self {
            MessageKind::Commit => 1,
            MessageKind::Application => 2,
            MessageKind::Proposal => 3,
            MessageKind::Welcome => 4,
        }
    }

    pub fn from_i64(value: i64) -> Option<Self> {
        Some(match value {
            1 => MessageKind::Commit,
            2 => MessageKind::Application,
            3 => MessageKind::Proposal,
            4 => MessageKind::Welcome,
            _ => return None,
        })
    }
}

/// Appends one message to a mailbox.
///
/// Ordering rule: the post is accepted only if `prev_commit_seq` equals the
/// sequence number of the latest commit already in the mailbox (`None` when
/// there is none). A client that missed a commit gets 409 with
/// [`ConflictResponse`], catches up, and tries again. Posts are idempotent per
/// `client_msg_id`: a retry returns the original sequence number.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PostMessage {
    #[serde(with = "b64")]
    pub client_msg_id: Vec<u8>,
    pub kind: MessageKind,
    pub prev_commit_seq: Option<u64>,
    #[serde(with = "b64")]
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PostResponse {
    pub seq: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ConflictResponse {
    pub latest_commit_seq: Option<u64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Cursor {
    #[serde(with = "b64")]
    pub mailbox: Vec<u8>,
    /// Return messages with a sequence number greater than this.
    pub after: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FetchRequest {
    pub cursors: Vec<Cursor>,
    /// Long-poll: if nothing is new, wait up to this long for a post.
    #[serde(default)]
    pub wait_ms: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Envelope {
    pub seq: u64,
    #[serde(with = "b64")]
    pub client_msg_id: Vec<u8>,
    pub kind: MessageKind,
    #[serde(with = "b64")]
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MailboxBatch {
    #[serde(with = "b64")]
    pub mailbox: Vec<u8>,
    pub messages: Vec<Envelope>,
    pub latest_commit_seq: Option<u64>,
    /// True when the batch was cut at the page size and more messages wait.
    pub more: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FetchResponse {
    pub batches: Vec<MailboxBatch>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ErrorBody {
    pub error: String,
    pub message: String,
}

/// Machine-readable values of [`ErrorBody::error`].
pub mod error_code {
    pub const BAD_REQUEST: &str = "bad_request";
    pub const UNAUTHORIZED: &str = "unauthorized";
    pub const NOT_FOUND: &str = "not_found";
    pub const USERNAME_TAKEN: &str = "username_taken";
    pub const NO_KEY_PACKAGES: &str = "no_key_packages";
    pub const INVALID_KEY_PACKAGE: &str = "invalid_key_package";
    pub const CONFLICT: &str = "conflict";
    pub const TOO_LARGE: &str = "too_large";
    pub const INTERNAL: &str = "internal";
}

/// Header names for signed requests (see [`crate::auth::request_payload`]).
pub mod header {
    pub const ACCOUNT: &str = "x-messenger-account";
    pub const TIMESTAMP: &str = "x-messenger-timestamp";
    pub const NONCE: &str = "x-messenger-nonce";
    pub const SIGNATURE: &str = "x-messenger-signature";
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn post_message_json_shape() {
        let post = PostMessage {
            client_msg_id: vec![1; 16],
            kind: MessageKind::Commit,
            prev_commit_seq: None,
            payload: vec![0xff, 0x00],
        };
        let json = serde_json::to_string(&post).unwrap();
        assert_eq!(
            json,
            r#"{"client_msg_id":"AQEBAQEBAQEBAQEBAQEBAQ","kind":"commit","prev_commit_seq":null,"payload":"_wA"}"#
        );
        let back: PostMessage = serde_json::from_str(&json).unwrap();
        assert_eq!(back.payload, vec![0xff, 0x00]);
    }

    #[test]
    fn kind_codes_round_trip() {
        for kind in [MessageKind::Commit, MessageKind::Application, MessageKind::Proposal, MessageKind::Welcome] {
            assert_eq!(MessageKind::from_i64(kind.as_i64()), Some(kind));
        }
        assert_eq!(MessageKind::from_i64(0), None);
    }
}
