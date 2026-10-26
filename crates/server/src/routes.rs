use std::{sync::Arc, time::Duration};

use axum::{
    body::Bytes,
    extract::{DefaultBodyLimit, OriginalUri, Path, State},
    http::{HeaderMap, Method, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use messenger_protocol::{
    api::{
        error_code, ConflictResponse, DirectoryEntry, FetchRequest, FetchResponse, KeyPackageCount,
        KeyPackageResponse, PostMessage, PostResponse, RecoverRequest, RegisterRequest, RegisterResponse,
        UploadKeyPackagesRequest,
    },
    auth::{recover_payload, register_payload},
    b64, normalize_username, ACCOUNT_ID_LEN, CLIENT_MSG_ID_LEN, MAILBOX_ID_LEN, MAX_PAYLOAD_BYTES,
};
use tower_http::trace::TraceLayer;

use crate::{auth, db, key_packages, ApiError, AppState};

/// Longest a fetch may wait for new messages.
const MAX_WAIT: Duration = Duration::from_secs(30);
/// Most mailboxes one fetch may ask about.
const MAX_CURSORS: usize = 1000;
/// Most key packages per upload.
const MAX_UPLOAD: usize = 100;

pub fn router(state: Arc<AppState>) -> Router {
    Router::new()
        .route("/v1/health", get(|| async { "ok" }))
        .route("/v1/accounts", post(register))
        .route("/v1/accounts/recover", post(recover))
        .route("/v1/directory/{username}", get(directory))
        .route("/v1/directory/{username}/key-package", post(claim_key_package))
        .route("/v1/key-packages", post(upload_key_packages))
        .route("/v1/key-packages/count", get(key_package_count))
        .route("/v1/mailboxes/{mailbox}/messages", post(post_message))
        .route("/v1/fetch", post(fetch))
        // Base64 inflates payloads by a third; leave room for JSON framing.
        .layer(DefaultBodyLimit::max(MAX_PAYLOAD_BYTES * 2))
        .layer(TraceLayer::new_for_http())
        .with_state(state)
}

fn check_len(name: &str, bytes: &[u8], len: usize) -> Result<(), ApiError> {
    if bytes.len() != len {
        return Err(ApiError::bad_request(format!("{name} must be {len} bytes")));
    }
    Ok(())
}

fn random_bytes<const N: usize>() -> Result<[u8; N], ApiError> {
    let mut buf = [0u8; N];
    getrandom::fill(&mut buf).map_err(|_| ApiError::internal("no randomness"))?;
    Ok(buf)
}

async fn register(State(state): State<Arc<AppState>>, Json(req): Json<RegisterRequest>) -> Result<Json<RegisterResponse>, ApiError> {
    let username = normalize_username(&req.username).map_err(|e| ApiError::bad_request(e.to_string()))?;
    if username != req.username {
        return Err(ApiError::bad_request("send the username in normalized (lowercase) form"));
    }
    check_len("identity_key", &req.identity_key, 32)?;
    check_len("recovery_key", &req.recovery_key, 32)?;
    check_len("intro_inbox", &req.intro_inbox, MAILBOX_ID_LEN)?;
    auth::check_timestamp(req.timestamp_ms)?;
    let payload = register_payload(&username, &req.identity_key, &req.recovery_key, &req.intro_inbox, req.timestamp_ms);
    if !auth::verify(&req.identity_key, &payload, &req.identity_signature) {
        return Err(ApiError::unauthorized("identity signature does not verify"));
    }
    if !auth::verify(&req.recovery_key, &payload, &req.recovery_signature) {
        return Err(ApiError::unauthorized("recovery signature does not verify"));
    }
    let account = db::Account {
        account_id: random_bytes::<ACCOUNT_ID_LEN>()?.to_vec(),
        username,
        identity_key: req.identity_key,
        recovery_key: req.recovery_key,
        intro_inbox: req.intro_inbox,
    };
    let account_id = account.account_id.clone();
    let inserted = state.with_db(move |conn, _| Ok(db::insert_account(conn, &account)?)).await?;
    if !inserted {
        return Err(ApiError::new(StatusCode::CONFLICT, error_code::USERNAME_TAKEN, "username is taken"));
    }
    tracing::info!("account registered");
    Ok(Json(RegisterResponse { account_id }))
}

async fn recover(State(state): State<Arc<AppState>>, Json(req): Json<RecoverRequest>) -> Result<Json<RegisterResponse>, ApiError> {
    let username = normalize_username(&req.username).map_err(|e| ApiError::bad_request(e.to_string()))?;
    check_len("identity_key", &req.identity_key, 32)?;
    check_len("intro_inbox", &req.intro_inbox, MAILBOX_ID_LEN)?;
    auth::check_timestamp(req.timestamp_ms)?;
    let payload = recover_payload(&username, &req.identity_key, &req.intro_inbox, req.timestamp_ms);
    if !auth::verify(&req.identity_key, &payload, &req.identity_signature) {
        return Err(ApiError::unauthorized("identity signature does not verify"));
    }
    state
        .with_db(move |conn, _| {
            let account = db::account_by_username(conn, &username)?
                .ok_or_else(|| ApiError::unauthorized("recovery failed"))?;
            if !auth::verify(&account.recovery_key, &payload, &req.recovery_signature) {
                // Same answer as an unknown username, so the endpoint can't be
                // used to probe which usernames exist.
                return Err(ApiError::unauthorized("recovery failed"));
            }
            db::replace_device(conn, &account.account_id, &req.identity_key, &req.intro_inbox)?;
            tracing::info!("account recovered onto a new device key");
            Ok(Json(RegisterResponse { account_id: account.account_id }))
        })
        .await
}

async fn directory(State(state): State<Arc<AppState>>, Path(username): Path<String>) -> Result<Json<DirectoryEntry>, ApiError> {
    let username = normalize_username(&username).map_err(|e| ApiError::bad_request(e.to_string()))?;
    state
        .with_db(move |conn, _| {
            let account = db::account_by_username(conn, &username)?.ok_or_else(|| ApiError::not_found("no such user"))?;
            Ok(Json(DirectoryEntry {
                account_id: account.account_id,
                username: account.username,
                identity_key: account.identity_key,
                intro_inbox: account.intro_inbox,
            }))
        })
        .await
}

async fn claim_key_package(State(state): State<Arc<AppState>>, Path(username): Path<String>) -> Result<Json<KeyPackageResponse>, ApiError> {
    let username = normalize_username(&username).map_err(|e| ApiError::bad_request(e.to_string()))?;
    state
        .with_db(move |conn, _| {
            let account = db::account_by_username(conn, &username)?.ok_or_else(|| ApiError::not_found("no such user"))?;
            let key_package = db::claim_key_package(conn, &account.account_id)?.ok_or_else(|| {
                ApiError::new(StatusCode::NOT_FOUND, error_code::NO_KEY_PACKAGES, "user has no key packages left")
            })?;
            Ok(Json(KeyPackageResponse { key_package }))
        })
        .await
}

async fn upload_key_packages(
    State(state): State<Arc<AppState>>,
    method: Method,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<KeyPackageCount>, ApiError> {
    state
        .with_db(move |conn, state| {
            let account = auth::authenticate(conn, &state.replay, &method, &uri, &headers, &body)?;
            let req: UploadKeyPackagesRequest =
                serde_json::from_slice(&body).map_err(|e| ApiError::bad_request(format!("bad body: {e}")))?;
            if req.key_packages.len() > MAX_UPLOAD {
                return Err(ApiError::bad_request(format!("at most {MAX_UPLOAD} key packages per upload")));
            }
            let invalid = |e: key_packages::KeyPackageError| {
                ApiError::new(StatusCode::BAD_REQUEST, error_code::INVALID_KEY_PACKAGE, e.to_string())
            };
            for data in &req.key_packages {
                key_packages::validate(&state.crypto, &account, data, false).map_err(invalid)?;
            }
            if let Some(data) = &req.last_resort {
                key_packages::validate(&state.crypto, &account, data, true).map_err(invalid)?;
            }
            db::store_key_packages(conn, &account.account_id, &req.key_packages, req.last_resort.as_deref())?
                .map_err(ApiError::bad_request)?;
            let (count, has_last_resort) = db::key_package_count(conn, &account.account_id)?;
            Ok(Json(KeyPackageCount { count, has_last_resort }))
        })
        .await
}

async fn key_package_count(
    State(state): State<Arc<AppState>>,
    method: Method,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
) -> Result<Json<KeyPackageCount>, ApiError> {
    state
        .with_db(move |conn, state| {
            let account = auth::authenticate(conn, &state.replay, &method, &uri, &headers, &[])?;
            let (count, has_last_resort) = db::key_package_count(conn, &account.account_id)?;
            Ok(Json(KeyPackageCount { count, has_last_resort }))
        })
        .await
}

async fn post_message(
    State(state): State<Arc<AppState>>,
    Path(mailbox): Path<String>,
    Json(req): Json<PostMessage>,
) -> Result<Response, ApiError> {
    let mailbox = b64::decode(&mailbox).map_err(|_| ApiError::bad_request("mailbox id is not base64url"))?;
    check_len("mailbox", &mailbox, MAILBOX_ID_LEN)?;
    check_len("client_msg_id", &req.client_msg_id, CLIENT_MSG_ID_LEN)?;
    if req.payload.is_empty() {
        return Err(ApiError::bad_request("empty payload"));
    }
    if req.payload.len() > MAX_PAYLOAD_BYTES {
        return Err(ApiError::new(StatusCode::PAYLOAD_TOO_LARGE, error_code::TOO_LARGE, "payload too large"));
    }
    let outcome = state
        .with_db(move |conn, _| {
            Ok(db::post_message(conn, &mailbox, &req.client_msg_id, req.kind, req.prev_commit_seq, &req.payload)?)
        })
        .await?;
    match outcome {
        db::PostOutcome::Accepted { seq } => {
            state.posted.notify_waiters();
            Ok(Json(PostResponse { seq }).into_response())
        }
        db::PostOutcome::Conflict { latest_commit_seq } => {
            Ok((StatusCode::CONFLICT, Json(ConflictResponse { latest_commit_seq })).into_response())
        }
    }
}

async fn fetch(State(state): State<Arc<AppState>>, Json(req): Json<FetchRequest>) -> Result<Json<FetchResponse>, ApiError> {
    if req.cursors.len() > MAX_CURSORS {
        return Err(ApiError::bad_request(format!("at most {MAX_CURSORS} mailboxes per fetch")));
    }
    let mut cursors = Vec::with_capacity(req.cursors.len());
    for cursor in req.cursors {
        check_len("mailbox", &cursor.mailbox, MAILBOX_ID_LEN)?;
        cursors.push((cursor.mailbox, cursor.after));
    }
    let cursors = Arc::new(cursors);
    let deadline = tokio::time::Instant::now() + Duration::from_millis(u64::from(req.wait_ms)).min(MAX_WAIT);
    loop {
        // Register for wake-ups before reading, so a post that lands between
        // the read and the wait is not missed.
        let notified = state.posted.notified();
        tokio::pin!(notified);
        notified.as_mut().enable();

        let query = cursors.clone();
        let batches = state.with_db(move |conn, _| Ok(db::fetch(conn, &query)?)).await?;
        if !batches.is_empty() || tokio::time::Instant::now() >= deadline {
            return Ok(Json(FetchResponse { batches }));
        }
        if tokio::time::timeout_at(deadline, notified).await.is_err() {
            return Ok(Json(FetchResponse { batches: Vec::new() }));
        }
    }
}
