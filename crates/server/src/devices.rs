//! Several devices per account (docs/LINKED-DEVICES.md): listing them for
//! the people adding the account to a chat, registering and removing them,
//! and the sealed bundle an existing device leaves for a new one.
//!
//! The server never sees inside a bundle: it's sealed to a one-time key the
//! new device showed in a QR code. It keeps a bundle at most ten minutes and
//! hands it out once.

use std::sync::Arc;

use axum::{
    body::Bytes,
    extract::{OriginalUri, Path, State},
    http::{HeaderMap, Method, StatusCode},
    Json,
};
use messenger_protocol::{
    api::{
        error_code, header, DeviceInfo, DeviceKeyPackage, DeviceKeyPackages, DeviceList, Empty, KeyPackageResponse,
        LinkBundle, RegisterDeviceRequest,
    },
    auth::device_certificate_payload,
    b64, normalize_username, DEVICE_ID_LEN, LINK_ID_LEN, MAILBOX_ID_LEN, MAX_DEVICES,
};

use crate::{auth, db, ApiError, AppState};

/// Largest link bundle: account details, settings and a history archive's key
/// and address (the archive itself is a file).
const MAX_BUNDLE_BYTES: usize = 256 * 1024;

fn info(device: db::Device, private: bool) -> DeviceInfo {
    DeviceInfo {
        device_id: device.device_id,
        device_key: device.device_key,
        certificate: device.certificate,
        intro_inbox: device.intro_inbox,
        name: if private { device.name } else { String::new() },
        added_at_ms: device.added_at,
        last_seen_ms: if private { device.last_seen } else { 0 },
    }
}

fn own_list(conn: &rusqlite::Connection, account_id: &[u8]) -> Result<Json<DeviceList>, ApiError> {
    let devices = db::devices(conn, account_id)?.into_iter().map(|d| info(d, true)).collect();
    Ok(Json(DeviceList { devices }))
}

/// `GET /v1/directory/{username}/devices`: an account's devices, for checking
/// their certificates before adding them to a chat.
pub(crate) async fn directory_devices(
    State(state): State<Arc<AppState>>,
    Path(username): Path<String>,
) -> Result<Json<DeviceList>, ApiError> {
    let username = normalize_username(&username).map_err(|e| ApiError::bad_request(e.to_string()))?;
    state
        .with_db(move |conn, _| {
            let account =
                db::account_by_username(conn, &username)?.ok_or_else(|| ApiError::not_found("no such user"))?;
            let devices = db::devices(conn, &account.account_id)?.into_iter().map(|d| info(d, false)).collect();
            Ok(Json(DeviceList { devices }))
        })
        .await
}

/// `POST /v1/directory/{username}/device-key-packages`: one key package for
/// each of an account's devices (consumed, as with one), to add them all to
/// a chat. A device with none left is skipped.
pub(crate) async fn claim_device_key_packages(
    State(state): State<Arc<AppState>>,
    Path(username): Path<String>,
) -> Result<Json<DeviceKeyPackages>, ApiError> {
    let username = normalize_username(&username).map_err(|e| ApiError::bad_request(e.to_string()))?;
    state
        .with_db(move |conn, _| {
            let account =
                db::account_by_username(conn, &username)?.ok_or_else(|| ApiError::not_found("no such user"))?;
            let mut devices = Vec::new();
            for device in db::devices(conn, &account.account_id)? {
                if let Some(key_package) = db::claim_key_package(conn, &device.device_id)? {
                    devices.push(DeviceKeyPackage { device: info(device, false), key_package });
                }
            }
            if devices.is_empty() {
                return Err(ApiError::new(
                    StatusCode::NOT_FOUND,
                    error_code::NO_KEY_PACKAGES,
                    "user has no key packages left",
                ));
            }
            Ok(Json(DeviceKeyPackages { devices }))
        })
        .await
}

/// `POST /v1/directory/{username}/devices/{device_id}/key-package`: one key
/// package for one device, to add a newly linked device to the chats it's
/// missing from without using up the others' packages.
pub(crate) async fn claim_device_key_package(
    State(state): State<Arc<AppState>>,
    Path((username, device_id)): Path<(String, String)>,
) -> Result<Json<KeyPackageResponse>, ApiError> {
    let username = normalize_username(&username).map_err(|e| ApiError::bad_request(e.to_string()))?;
    let device_id = b64::decode(&device_id).map_err(|_| ApiError::not_found("no such device"))?;
    state
        .with_db(move |conn, _| {
            let account =
                db::account_by_username(conn, &username)?.ok_or_else(|| ApiError::not_found("no such user"))?;
            db::device(conn, &account.account_id, &device_id)?.ok_or_else(|| ApiError::not_found("no such device"))?;
            let key_package = db::claim_key_package(conn, &device_id)?.ok_or_else(|| {
                ApiError::new(StatusCode::NOT_FOUND, error_code::NO_KEY_PACKAGES, "device has no key packages left")
            })?;
            Ok(Json(KeyPackageResponse { key_package }))
        })
        .await
}

/// `GET /v1/devices`: our own devices, with their names and when each was
/// last active.
pub(crate) async fn list_devices(
    State(state): State<Arc<AppState>>,
    method: Method,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
) -> Result<Json<DeviceList>, ApiError> {
    state
        .with_db(move |conn, state| {
            let account = auth::authenticate(conn, &state.replay, &method, &uri, &headers, &[])?;
            own_list(conn, &account.account_id)
        })
        .await
}

/// `POST /v1/devices`: a newly linked device registers itself. It holds the
/// account key now, signs with it (no device header), and brings the
/// certificate the account key made for it.
pub(crate) async fn register_device(
    State(state): State<Arc<AppState>>,
    method: Method,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<DeviceList>, ApiError> {
    state
        .with_db(move |conn, state| {
            if headers.contains_key(header::DEVICE) {
                return Err(ApiError::unauthorized("a new device registers with the account key"));
            }
            let account = auth::authenticate(conn, &state.replay, &method, &uri, &headers, &body)?;
            let req: RegisterDeviceRequest =
                serde_json::from_slice(&body).map_err(|e| ApiError::bad_request(format!("bad body: {e}")))?;
            if req.device_id.len() != DEVICE_ID_LEN
                || req.device_key.len() != 32
                || req.intro_inbox.len() != MAILBOX_ID_LEN
            {
                return Err(ApiError::bad_request("malformed device"));
            }
            if req.name.chars().count() > 64 {
                return Err(ApiError::bad_request("device names are at most 64 characters"));
            }
            let payload =
                device_certificate_payload(&account.account_id, &req.device_id, &req.device_key, &req.intro_inbox);
            if !auth::verify(&account.identity_key, &payload, &req.certificate) {
                return Err(ApiError::unauthorized("the certificate isn't the account key's"));
            }
            let device = db::Device {
                device_id: req.device_id,
                account_id: account.account_id.clone(),
                device_key: req.device_key,
                certificate: req.certificate,
                intro_inbox: req.intro_inbox,
                name: req.name,
                added_at: 0,
                last_seen: 0,
            };
            let inserted = match db::insert_device(conn, &device) {
                Ok(inserted) => inserted,
                Err(rusqlite::Error::SqliteFailure(e, _)) if e.code == rusqlite::ErrorCode::ConstraintViolation => {
                    return Err(ApiError::new(
                        StatusCode::CONFLICT,
                        error_code::CONFLICT,
                        "that device or inbox is already registered",
                    ));
                }
                Err(e) => return Err(e.into()),
            };
            if !inserted {
                return Err(ApiError::new(
                    StatusCode::CONFLICT,
                    error_code::CONFLICT,
                    format!("an account can have at most {MAX_DEVICES} devices"),
                ));
            }
            tracing::info!("device linked");
            own_list(conn, &account.account_id)
        })
        .await
}

/// `DELETE /v1/devices/{device_id}`: removes one of our devices (this one
/// included), with its key packages, push target and invitation inbox.
pub(crate) async fn remove_device(
    State(state): State<Arc<AppState>>,
    Path(device_id): Path<String>,
    method: Method,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<DeviceList>, ApiError> {
    let device_id = b64::decode(&device_id).map_err(|_| ApiError::not_found("no such device"))?;
    state
        .with_db(move |conn, state| {
            let account = auth::authenticate(conn, &state.replay, &method, &uri, &headers, &body)?;
            if !db::delete_device(conn, &account.account_id, &device_id)? {
                return Err(ApiError::not_found("no such device"));
            }
            tracing::info!("device removed");
            own_list(conn, &account.account_id)
        })
        .await
}

fn link_id(text: &str) -> Result<Vec<u8>, ApiError> {
    let id = b64::decode(text).map_err(|_| ApiError::not_found("no such link"))?;
    if id.len() != LINK_ID_LEN {
        return Err(ApiError::not_found("no such link"));
    }
    Ok(id)
}

/// `PUT /v1/links/{link_id}`: an existing device leaves the sealed bundle
/// for the new device that showed this link id.
pub(crate) async fn put_link(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
    method: Method,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<Empty>, ApiError> {
    let id = link_id(&id)?;
    state
        .with_db(move |conn, state| {
            let account = auth::authenticate(conn, &state.replay, &method, &uri, &headers, &body)?;
            let bundle: LinkBundle =
                serde_json::from_slice(&body).map_err(|e| ApiError::bad_request(format!("bad body: {e}")))?;
            if bundle.sealed.is_empty() || bundle.sealed.len() > MAX_BUNDLE_BYTES {
                return Err(ApiError::new(StatusCode::PAYLOAD_TOO_LARGE, error_code::TOO_LARGE, "bundle too large"));
            }
            if !db::put_link(conn, &id, &account.account_id, &bundle.sealed)? {
                return Err(ApiError::new(StatusCode::CONFLICT, error_code::CONFLICT, "that link is already used"));
            }
            Ok(Json(Empty {}))
        })
        .await
}

/// `GET /v1/links/{link_id}`: the new device collects its bundle, once.
/// Nothing yet (or anymore) is a 404, so it asks again in a moment.
pub(crate) async fn take_link(
    State(state): State<Arc<AppState>>,
    Path(id): Path<String>,
) -> Result<Json<LinkBundle>, ApiError> {
    let id = link_id(&id)?;
    state
        .with_db(move |conn, _| {
            let sealed = db::take_link(conn, &id)?.ok_or_else(|| ApiError::not_found("nothing for this link yet"))?;
            Ok(Json(LinkBundle { sealed }))
        })
        .await
}
