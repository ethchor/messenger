//! Mailbox server.
//!
//! What it stores: usernames, random account ids, public keys, MLS key
//! packages, and ciphertext in mailboxes named by random 32-byte ids. It never
//! sees message content, group membership or which mailboxes belong together
//! (group mailboxes change every MLS epoch). Push notifications are the
//! exception to the last two: to wake a device, it has to know whose (see
//! [`push`]). See `docs/ARCHITECTURE.md`.
//!
//! Phase 0 runs on SQLite; the storage layer is one module (`db`) so Postgres
//! can replace it without touching the handlers.

mod auth;
pub mod blobs;
mod db;
mod error;
mod key_packages;
pub mod push;
mod routes;

use std::{future::Future, path::PathBuf, sync::Arc, time::Duration};

use axum::Router;
use tokio::sync::Notify;

pub use error::ApiError;

/// Server settings.
#[derive(Debug, Clone)]
pub struct Config {
    pub db_path: PathBuf,
    /// How long undelivered messages, and uploaded files, are kept.
    pub retention: Duration,
    pub push: push::PushConfig,
    pub blobs: blobs::BlobConfig,
}

pub(crate) struct AppState {
    pub db: std::sync::Mutex<rusqlite::Connection>,
    /// Woken on every accepted post, for long-polling fetches.
    pub posted: Notify,
    pub replay: auth::ReplayCache,
    pub crypto: openmls_libcrux_crypto::CryptoProvider,
    pub push: push::Pusher,
    pub blobs: blobs::BlobStore,
}

impl AppState {
    fn open(config: &Config) -> anyhow::Result<Arc<Self>> {
        let conn = db::open(&config.db_path)?;
        let vapid = match db::server_key(&conn, "vapid")?.and_then(|secret| push::VapidKey::from_secret(&secret)) {
            Some(key) => key,
            None => {
                let (key, secret) = push::VapidKey::generate().ok_or_else(|| anyhow::anyhow!("no randomness"))?;
                db::set_server_key(&conn, "vapid", &secret)?;
                key
            }
        };
        Ok(Arc::new(Self {
            db: std::sync::Mutex::new(conn),
            posted: Notify::new(),
            replay: auth::ReplayCache::default(),
            crypto: openmls_libcrux_crypto::CryptoProvider::new()
                .map_err(|e| anyhow::anyhow!("crypto provider: {e:?}"))?,
            push: push::Pusher::new(config.push.clone(), vapid)?,
            blobs: blobs::BlobStore::open(&config.blobs, &config.db_path)?,
        }))
    }

    /// Runs a blocking database closure off the async runtime.
    pub async fn with_db<T, F>(self: &Arc<Self>, f: F) -> Result<T, ApiError>
    where
        T: Send + 'static,
        F: FnOnce(&mut rusqlite::Connection, &AppState) -> Result<T, ApiError> + Send + 'static,
    {
        let state = self.clone();
        tokio::task::spawn_blocking(move || {
            let mut conn = state.db.lock().map_err(|_| ApiError::internal("database lock poisoned"))?;
            f(&mut conn, &state)
        })
        .await
        .map_err(|e| ApiError::internal(format!("database task failed: {e}")))?
    }
}

/// Builds the HTTP router over a fresh state. Used by `serve` and by tests.
pub fn router(config: &Config) -> anyhow::Result<Router> {
    let state = AppState::open(config)?;
    Ok(routes::router(state))
}

/// Serves until `shutdown` resolves. Also runs the retention sweeper.
pub async fn serve(
    listener: tokio::net::TcpListener,
    config: Config,
    shutdown: impl Future<Output = ()> + Send + 'static,
) -> anyhow::Result<()> {
    let state = AppState::open(&config)?;
    if state.push.apns_enabled() {
        tracing::info!("pushes to iPhones are on");
    }
    let sweeper = tokio::spawn(sweep_forever(state.clone(), config.retention));
    let pusher = tokio::spawn(push::run(state.clone()));
    let app = routes::router(state);
    let result = axum::serve(listener, app).with_graceful_shutdown(shutdown).await;
    sweeper.abort();
    pusher.abort();
    result.map_err(Into::into)
}

async fn sweep_forever(state: Arc<AppState>, retention: Duration) {
    let mut interval = tokio::time::interval(Duration::from_secs(3600));
    loop {
        interval.tick().await;
        let cutoff = db::now_ms() - retention.as_millis() as i64;
        match state.with_db(move |conn, _| Ok(db::delete_messages_before(conn, cutoff)?)).await {
            Ok(n) if n > 0 => tracing::info!(deleted = n, "retention sweep"),
            Ok(_) => {}
            Err(e) => tracing::warn!(error = %e, "retention sweep failed"),
        }
        match blobs::BlobStore::sweep(&state, retention).await {
            Ok(n) if n > 0 => tracing::info!(deleted = n, "blob sweep"),
            Ok(_) => {}
            Err(e) => tracing::warn!(error = %e, "blob sweep failed"),
        }
        state.replay.prune(db::now_ms() as u64);
    }
}
