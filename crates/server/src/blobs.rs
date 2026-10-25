//! Encrypted files: photos, videos, voice notes and documents sent in chats.
//!
//! A client encrypts each file with a key of its own (see `Attachment` in
//! proto/messenger/v1/content.proto), asks for an id with a signed request,
//! and uploads the ciphertext under it. The id travels to the other members
//! inside MLS, and they fetch the file without signing in. So the server
//! learns which account uploaded how many bytes when, but not what, nor who
//! fetched it or in which chat it was sent.

use std::{
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};

use axum::{
    body::{Body, Bytes},
    extract::{OriginalUri, Path as UrlPath, State},
    http::{header, HeaderMap, Method, StatusCode},
    response::Response,
    Json,
};
use futures_util::StreamExt;
use messenger_protocol::{
    api::{Empty, NewBlobRequest, NewBlobResponse},
    b64, BLOB_ID_LEN, MAX_BLOB_BYTES,
};
use sha2::{Digest, Sha256};
use tokio::io::AsyncWriteExt;

use crate::{auth, db, ApiError, AppState};

/// How long an upload may take to arrive after its id was handed out.
pub const UPLOAD_WINDOW: Duration = Duration::from_secs(3600);
const DAY_MS: i64 = 24 * 3600 * 1000;

/// Where files go and how much each account may send.
#[derive(Debug, Clone)]
pub struct BlobConfig {
    /// Folder for the files; `None` puts a "blobs" folder next to the database.
    pub dir: Option<PathBuf>,
    /// Largest file accepted, at most [`MAX_BLOB_BYTES`].
    pub max_bytes: u64,
    /// Most bytes one account may upload in 24 hours.
    pub daily_quota_bytes: u64,
}

impl Default for BlobConfig {
    fn default() -> Self {
        Self { dir: None, max_bytes: MAX_BLOB_BYTES, daily_quota_bytes: 2 * 1024 * 1024 * 1024 }
    }
}

pub(crate) struct BlobStore {
    dir: PathBuf,
    max_bytes: u64,
    daily_quota_bytes: u64,
}

impl BlobStore {
    pub(crate) fn open(config: &BlobConfig, db_path: &Path) -> anyhow::Result<Self> {
        let dir = match &config.dir {
            Some(dir) => dir.clone(),
            None => db_path.parent().unwrap_or(Path::new("")).join("blobs"),
        };
        std::fs::create_dir_all(dir.join("incoming"))
            .map_err(|e| anyhow::anyhow!("can't make the blob folder {}: {e}", dir.display()))?;
        Ok(Self { dir, max_bytes: config.max_bytes.min(MAX_BLOB_BYTES), daily_quota_bytes: config.daily_quota_bytes })
    }

    /// Where a finished file lives: spread over 256 folders by its id.
    fn path(&self, id: &[u8]) -> PathBuf {
        let name = hex(id);
        self.dir.join(&name[..2]).join(name)
    }

    /// Deletes the files of these ids; missing ones are fine.
    pub(crate) fn remove(&self, ids: &[Vec<u8>]) {
        for id in ids {
            match std::fs::remove_file(self.path(id)) {
                Ok(()) => {}
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {}
                Err(e) => tracing::warn!(error = %e, "deleting a blob"),
            }
        }
    }

    /// Forgets old files and abandoned uploads.
    pub(crate) async fn sweep(state: &Arc<AppState>, retention: Duration) -> Result<usize, ApiError> {
        let cutoff = db::now_ms() - retention.as_millis() as i64;
        let pending_cutoff = db::now_ms() - UPLOAD_WINDOW.as_millis() as i64;
        let ids = state.with_db(move |conn, _| Ok(db::delete_blobs_before(conn, cutoff, pending_cutoff)?)).await?;
        state.blobs.remove(&ids);
        Ok(ids.len())
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn blob_id(text: &str) -> Result<Vec<u8>, ApiError> {
    let id = b64::decode(text).map_err(|_| ApiError::not_found("no such file"))?;
    if id.len() != BLOB_ID_LEN {
        return Err(ApiError::not_found("no such file"));
    }
    Ok(id)
}

/// `POST /v1/blobs`: an id to upload one file under, if the account may.
pub(crate) async fn new_blob(
    State(state): State<Arc<AppState>>,
    method: Method,
    OriginalUri(uri): OriginalUri,
    headers: HeaderMap,
    body: Bytes,
) -> Result<Json<NewBlobResponse>, ApiError> {
    state
        .with_db(move |conn, state| {
            let account = auth::authenticate(conn, &state.replay, &method, &uri, &headers, &body)?;
            let req: NewBlobRequest =
                serde_json::from_slice(&body).map_err(|e| ApiError::bad_request(format!("bad body: {e}")))?;
            if req.digest.len() != 32 {
                return Err(ApiError::bad_request("the digest is a SHA-256"));
            }
            if req.size == 0 {
                return Err(ApiError::bad_request("the file is empty"));
            }
            let max = state.blobs.max_bytes;
            if req.size > max {
                return Err(ApiError::too_large(format!("files can be at most {} MB here", max / (1024 * 1024))));
            }
            let mut id = [0u8; BLOB_ID_LEN];
            getrandom::fill(&mut id).map_err(|_| ApiError::internal("no randomness"))?;
            let since = db::now_ms() - DAY_MS;
            let quota = state.blobs.daily_quota_bytes;
            if !db::reserve_blob(conn, &id, &account.account_id, req.size, &req.digest, since, quota)? {
                return Err(ApiError::quota("you've sent a lot of files today; try again later"));
            }
            Ok(Json(NewBlobResponse { id: id.to_vec() }))
        })
        .await
}

/// `PUT /v1/blobs/{id}`: the encrypted file itself. It has to be exactly the
/// size and digest asked for; a second upload of a finished file is a no-op.
pub(crate) async fn upload_blob(
    State(state): State<Arc<AppState>>,
    UrlPath(id): UrlPath<String>,
    headers: HeaderMap,
    body: Body,
) -> Result<Json<Empty>, ApiError> {
    let id = blob_id(&id)?;
    let lookup = id.clone();
    let blob = state
        .with_db(move |conn, _| Ok(db::blob(conn, &lookup)?))
        .await?
        .ok_or_else(|| ApiError::not_found("no such upload"))?;
    if blob.completed {
        return Ok(Json(Empty {}));
    }
    if db::now_ms() - blob.created_at > UPLOAD_WINDOW.as_millis() as i64 {
        return Err(ApiError::not_found("this upload has expired; ask for a new one"));
    }
    let length = headers.get(header::CONTENT_LENGTH).and_then(|v| v.to_str().ok()).and_then(|v| v.parse::<u64>().ok());
    if length != Some(blob.size) {
        return Err(ApiError::bad_request("Content-Length has to be the size asked for"));
    }
    // Into a file of its own first, and into place once it checks out.
    let mut name = [0u8; 16];
    getrandom::fill(&mut name).map_err(|_| ApiError::internal("no randomness"))?;
    let temp = state.blobs.dir.join("incoming").join(hex(&name));
    let received = receive(body, &temp, blob.size).await;
    match received {
        Ok(digest) if digest == blob.digest => {}
        Ok(_) => {
            let _ = tokio::fs::remove_file(&temp).await;
            return Err(ApiError::bad_request("the file doesn't match its digest"));
        }
        Err(e) => {
            let _ = tokio::fs::remove_file(&temp).await;
            return Err(e);
        }
    }
    let path = state.blobs.path(&id);
    if let Some(folder) = path.parent() {
        tokio::fs::create_dir_all(folder).await?;
    }
    tokio::fs::rename(&temp, &path).await?;
    state.with_db(move |conn, _| Ok(db::complete_blob(conn, &id)?)).await?;
    Ok(Json(Empty {}))
}

/// Writes the body to `temp`, at most `size` bytes. Returns its SHA-256.
async fn receive(body: Body, temp: &Path, size: u64) -> Result<Vec<u8>, ApiError> {
    let mut file = tokio::fs::File::create(temp).await?;
    let mut hasher = Sha256::new();
    let mut received = 0u64;
    let mut stream = body.into_data_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| ApiError::bad_request(format!("the upload broke off: {e}")))?;
        received += chunk.len() as u64;
        if received > size {
            return Err(ApiError::bad_request("the file is longer than asked for"));
        }
        hasher.update(&chunk);
        file.write_all(&chunk).await?;
    }
    if received != size {
        return Err(ApiError::bad_request("the file is shorter than asked for"));
    }
    file.sync_all().await?;
    Ok(hasher.finalize().to_vec())
}

/// `GET /v1/blobs/{id}`: the encrypted file, to whoever has its id.
pub(crate) async fn download_blob(
    State(state): State<Arc<AppState>>,
    UrlPath(id): UrlPath<String>,
) -> Result<Response, ApiError> {
    let id = blob_id(&id)?;
    let lookup = id.clone();
    let blob = state
        .with_db(move |conn, _| Ok(db::blob(conn, &lookup)?))
        .await?
        .filter(|b| b.completed)
        .ok_or_else(|| ApiError::not_found("no such file; it may have expired"))?;
    let file = tokio::fs::File::open(state.blobs.path(&id))
        .await
        .map_err(|_| ApiError::not_found("no such file; it may have expired"))?;
    Response::builder()
        .status(StatusCode::OK)
        .header(header::CONTENT_TYPE, "application/octet-stream")
        .header(header::CONTENT_LENGTH, blob.size)
        .header(header::CACHE_CONTROL, "private, no-store")
        .body(Body::from_stream(tokio_util::io::ReaderStream::new(file)))
        .map_err(|e| ApiError::internal(format!("response: {e}")))
}
