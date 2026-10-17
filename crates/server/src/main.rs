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
    /// Days to keep undelivered messages.
    #[arg(long, env = "MESSENGER_RETENTION_DAYS", default_value_t = 30)]
    retention_days: u32,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .init();
    let args = Args::parse();
    let config = messenger_server::Config {
        db_path: args.db,
        retention: std::time::Duration::from_secs(u64::from(args.retention_days) * 24 * 3600),
    };
    let listener = tokio::net::TcpListener::bind(args.listen).await?;
    tracing::info!("listening on http://{}", listener.local_addr()?);
    messenger_server::serve(listener, config, shutdown_signal()).await
}

async fn shutdown_signal() {
    let _ = tokio::signal::ctrl_c().await;
    tracing::info!("shutting down");
}
