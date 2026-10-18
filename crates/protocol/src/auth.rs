//! Canonical byte strings for signatures.
//!
//! Every payload starts with `0xFF` and a purpose label. `0xFF` can never start
//! an MLS `SignContent` (a reserved varint prefix), so the device key can sign
//! both MLS structures and these strings without one being mistaken for the
//! other. Fields are length-prefixed so no two inputs share an encoding.

use sha2::{Digest, Sha256};

const REQUEST_LABEL: &[u8] = b"\xffmessenger request v1";
const REGISTER_LABEL: &[u8] = b"\xffmessenger register v1";
const RECOVER_LABEL: &[u8] = b"\xffmessenger recover v1";
const NEW_RECOVERY_KEY_LABEL: &[u8] = b"\xffmessenger new recovery key v1";

fn push_field(out: &mut Vec<u8>, field: &[u8]) {
    out.extend_from_slice(&(field.len() as u32).to_be_bytes());
    out.extend_from_slice(field);
}

/// Payload signed for an authenticated API request. The signature, account id,
/// timestamp and nonce travel in the headers of [`crate::api::header`].
pub fn request_payload(
    method: &str,
    path_and_query: &str,
    account_id: &[u8],
    timestamp_ms: u64,
    nonce: &[u8],
    body: &[u8],
) -> Vec<u8> {
    let mut out = REQUEST_LABEL.to_vec();
    push_field(&mut out, method.as_bytes());
    push_field(&mut out, path_and_query.as_bytes());
    push_field(&mut out, account_id);
    push_field(&mut out, &timestamp_ms.to_be_bytes());
    push_field(&mut out, nonce);
    push_field(&mut out, &Sha256::digest(body));
    out
}

/// Payload signed by both the identity key and the recovery key at sign-up.
pub fn register_payload(
    username: &str,
    identity_key: &[u8],
    recovery_key: &[u8],
    intro_inbox: &[u8],
    timestamp_ms: u64,
) -> Vec<u8> {
    let mut out = REGISTER_LABEL.to_vec();
    push_field(&mut out, username.as_bytes());
    push_field(&mut out, identity_key);
    push_field(&mut out, recovery_key);
    push_field(&mut out, intro_inbox);
    push_field(&mut out, &timestamp_ms.to_be_bytes());
    out
}

/// Payload signed by the recovery key and the new identity key on recovery.
pub fn recover_payload(username: &str, identity_key: &[u8], intro_inbox: &[u8], timestamp_ms: u64) -> Vec<u8> {
    let mut out = RECOVER_LABEL.to_vec();
    push_field(&mut out, username.as_bytes());
    push_field(&mut out, identity_key);
    push_field(&mut out, intro_inbox);
    push_field(&mut out, &timestamp_ms.to_be_bytes());
    out
}

/// Payload signed by a new recovery key to prove possession when it replaces
/// the old one. The request carrying it is also signed by the device key.
pub fn new_recovery_key_payload(account_id: &[u8], recovery_key: &[u8], timestamp_ms: u64) -> Vec<u8> {
    let mut out = NEW_RECOVERY_KEY_LABEL.to_vec();
    push_field(&mut out, account_id);
    push_field(&mut out, recovery_key);
    push_field(&mut out, &timestamp_ms.to_be_bytes());
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fields_are_unambiguous() {
        let a = request_payload("GET", "/v1/ab", b"c", 1, b"n", b"");
        let b = request_payload("GET", "/v1/a", b"bc", 1, b"n", b"");
        assert_ne!(a, b);
        assert_eq!(a[0], 0xff);
    }

    #[test]
    fn purposes_differ() {
        let reg = register_payload("alice", b"k", b"r", b"i", 7);
        let rec = recover_payload("alice", b"k", b"i", 7);
        let new = new_recovery_key_payload(b"a", b"r", 7);
        assert_ne!(reg[..REGISTER_LABEL.len()], rec[..RECOVER_LABEL.len()]);
        assert!(!new.starts_with(REGISTER_LABEL) && !new.starts_with(RECOVER_LABEL));
    }
}
