use std::{net::SocketAddr, path::PathBuf};

use clap::Parser;
use tracing_subscriber::EnvFilter;

/// Mailbox server for the messenger. Stores only ciphertext, public keys and
/// random mailbox ids.
#[derive(Debug, Parser)]
#[command(version)]
struct Args {
    /// Address to listen on.
    #[arg(long, env = "MESSENGER_LISTEN", default_value = "127.0.0.1:8080")]
    listen: SocketAddr,
    /// SQLite database file. Created if missing.
    #[arg(long, env = "MESSENGER_DB", default_value = "messenger-server.db")]
    db: PathBuf,
    /// Days to keep undelivered messages and uploaded files.
    #[arg(long, env = "MESSENGER_RETENTION_DAYS", default_value_t = 30)]
    retention_days: u32,
    /// Folder for uploaded files (encrypted photos, videos, voice notes and
    /// documents). Default: "blobs" next to the database.
    #[arg(long, env = "MESSENGER_BLOBS")]
    blobs: Option<PathBuf>,
    /// Largest file people may send, in MB (at most 100).
    #[arg(long, env = "MESSENGER_MAX_FILE_MB", default_value_t = 100)]
    max_file_mb: u64,
    /// Most each account may upload in a day, in MB.
    #[arg(long, env = "MESSENGER_DAILY_UPLOAD_MB", default_value_t = 2048)]
    daily_upload_mb: u64,
    /// Contact for push services (Web Push / UnifiedPush): a mailto: or https: URL.
    #[arg(long, env = "MESSENGER_PUSH_CONTACT", default_value = "mailto:admin@localhost")]
    push_contact: String,
    /// Allow Web Push endpoints on private networks and plain http, for a
    /// push service (like ntfy) running on your LAN.
    #[arg(long, env = "MESSENGER_ALLOW_PRIVATE_PUSH")]
    allow_private_push: bool,
    /// APNs auth key (.p8 file) from your Apple developer account. Pushes to
    /// iPhones need it, plus --apns-key-id and --apns-team-id.
    #[arg(long, env = "MESSENGER_APNS_KEY", requires_all = ["apns_key_id", "apns_team_id"])]
    apns_key: Option<PathBuf>,
    /// The key's 10-character id.
    #[arg(long, env = "MESSENGER_APNS_KEY_ID")]
    apns_key_id: Option<String>,
    /// Your Apple developer team id.
    #[arg(long, env = "MESSENGER_APNS_TEAM_ID")]
    apns_team_id: Option<String>,
    /// The iPhone app's bundle id.
    #[arg(long, env = "MESSENGER_APNS_TOPIC", default_value = "com.qwantarc.messenger")]
    apns_topic: String,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .init();
    let args = Args::parse();
    let apns = match (args.apns_key, args.apns_key_id, args.apns_team_id) {
        (Some(path), Some(key_id), Some(team_id)) => Some(messenger_server::push::ApnsConfig {
            key_pem: std::fs::read_to_string(&path)
                .map_err(|e| anyhow::anyhow!("can't read the APNs key {}: {e}", path.display()))?,
            key_id,
            team_id,
            topic: args.apns_topic,
        }),
        _ => None,
    };
    let config = messenger_server::Config {
        db_path: args.db,
        retention: std::time::Duration::from_secs(u64::from(args.retention_days) * 24 * 3600),
        push: messenger_server::push::PushConfig {
            apns,
            contact: args.push_contact,
            allow_private_endpoints: args.allow_private_push,
            ..Default::default()
        },
        blobs: messenger_server::blobs::BlobConfig {
            dir: args.blobs,
            max_bytes: args.max_file_mb.saturating_mul(1024 * 1024),
            daily_quota_bytes: args.daily_upload_mb.saturating_mul(1024 * 1024),
        },
    };
    let listener = tokio::net::TcpListener::bind(args.listen).await?;
    tracing::info!("listening on http://{}", listener.local_addr()?);
    messenger_server::serve(listener, config, shutdown_signal()).await
}

async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
    tracing::info!("shutting down");
}
