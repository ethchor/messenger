//! Signed-request verification. Accounts have no passwords: the device key
//! signs every authenticated request (see `messenger_protocol::auth`).

use std::{collections::HashMap, sync::Mutex};

use axum::http::{HeaderMap, Method, Uri};
use ed25519_dalek::{Signature, VerifyingKey};
use messenger_protocol::{api::header, auth::request_payload, b64, ACCOUNT_ID_LEN, MAX_CLOCK_SKEW_MS};
use rusqlite::Connection;

use crate::{db, ApiError};

/// Remembers recent (account, nonce) pairs so a captured request can't be
/// replayed inside the clock-skew window.
#[derive(Default)]
pub struct ReplayCache {
    seen: Mutex<HashMap<(Vec<u8>, Vec<u8>), u64>>,
}

impl ReplayCache {
    /// Records the pair; returns `false` if it was already used.
    pub fn check_and_insert(&self, account_id: &[u8], nonce: &[u8], expires_at_ms: u64) -> bool {
        let mut seen = self.seen.lock().unwrap_or_else(|e| e.into_inner());
        if seen.len() > 100_000 {
            let now = db::now_ms() as u64;
            seen.retain(|_, expiry| *expiry > now);
        }
        seen.insert((account_id.to_vec(), nonce.to_vec()), expires_at_ms).is_none()
    }

    pub fn prune(&self, now_ms: u64) {
        let mut seen = self.seen.lock().unwrap_or_else(|e| e.into_inner());
        seen.retain(|_, expiry| *expiry > now_ms);
    }
}

/// Verifies an Ed25519 signature made with a 32-byte public key.
pub fn verify(public_key: &[u8], message: &[u8], signature: &[u8]) -> bool {
    let Ok(key_bytes) = <[u8; 32]>::try_from(public_key) else { return false };
    let Ok(key) = VerifyingKey::from_bytes(&key_bytes) else { return false };
    let Ok(signature) = Signature::from_slice(signature) else { return false };
    key.verify_strict(message, &signature).is_ok()
}

pub fn check_timestamp(timestamp_ms: u64) -> Result<(), ApiError> {
    let now = db::now_ms() as u64;
    if now.abs_diff(timestamp_ms) > MAX_CLOCK_SKEW_MS {
        return Err(ApiError::unauthorized("timestamp outside the allowed window; check the device clock"));
    }
    Ok(())
}

fn header_bytes(headers: &HeaderMap, name: &str) -> Result<Vec<u8>, ApiError> {
    let value = headers
        .get(name)
        .and_then(|v| v.to_str().ok())
        .ok_or_else(|| ApiError::unauthorized(format!("missing header {name}")))?;
    b64::decode(value).map_err(|_| ApiError::unauthorized(format!("malformed header {name}")))
}

/// Checks a signed request and returns the account that signed it.
pub fn authenticate(
    conn: &Connection,
    replay: &ReplayCache,
    method: &Method,
    uri: &Uri,
    headers: &HeaderMap,
    body: &[u8],
) -> Result<db::Account, ApiError> {
    let account_id = header_bytes(headers, header::ACCOUNT)?;
    if account_id.len() != ACCOUNT_ID_LEN {
        return Err(ApiError::unauthorized("malformed account id"));
    }
    let timestamp_ms: u64 = headers
        .get(header::TIMESTAMP)
        .and_then(|v| v.to_str().ok())
        .and_then(|v| v.parse().ok())
        .ok_or_else(|| ApiError::unauthorized("missing or malformed timestamp"))?;
    check_timestamp(timestamp_ms)?;
    let nonce = header_bytes(headers, header::NONCE)?;
    if nonce.len() < 16 || nonce.len() > 64 {
        return Err(ApiError::unauthorized("malformed nonce"));
    }
    let signature = header_bytes(headers, header::SIGNATURE)?;
    let account = db::account_by_id(conn, &account_id)?.ok_or_else(|| ApiError::unauthorized("unknown account"))?;
    let path = uri.path_and_query().map(|p| p.as_str()).unwrap_or("/");
    let payload = request_payload(method.as_str(), path, &account_id, timestamp_ms, &nonce, body);
    if !verify(&account.identity_key, &payload, &signature) {
        return Err(ApiError::unauthorized("bad signature"));
    }
    if !replay.check_and_insert(&account_id, &nonce, timestamp_ms + 2 * MAX_CLOCK_SKEW_MS) {
        return Err(ApiError::unauthorized("request replayed"));
    }
    Ok(account)
}
