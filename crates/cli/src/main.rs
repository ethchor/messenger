//! `messenger`: a command-line client over messenger-core.
//!
//! Each data directory is one device. Conversations can be named by id, id
//! prefix, group title or the other person's username.
//!
//! ```text
//! messenger --data ./alice register alice
//! messenger --data ./alice chat bob
//! messenger --data ./alice send bob "hello"
//! messenger --data ./bob watch
//! ```

use std::path::PathBuf;

use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use messenger_core::{Client, ConversationKind, ConversationSummary, MessageStatus, MessageView};

#[derive(Debug, Parser)]
#[command(name = "messenger", version, about = "End-to-end encrypted messenger (development client)")]
struct Cli {
    /// Directory holding this device's keys and messages.
    #[arg(long, env = "MESSENGER_DATA", default_value = "./messenger-data")]
    data: PathBuf,
    /// Server URL (used until an account is registered).
    #[arg(long, env = "MESSENGER_SERVER", default_value = "http://127.0.0.1:8080")]
    server: String,
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Create an account. Prints the recovery key once.
    Register { username: String },
    /// Move an account onto this (empty) data directory.
    Recover { username: String, recovery_key: String },
    /// Show the account on this device.
    Whoami,
    /// Open (or create) the 1:1 chat with a user.
    Chat { username: String },
    /// Create a group.
    Group { title: String, members: Vec<String> },
    /// Add people to a group.
    Add { conversation: String, members: Vec<String> },
    /// Remove someone from a group.
    Remove { conversation: String, member: String },
    /// Send a text message.
    Send {
        conversation: String,
        text: String,
        /// Id (or prefix) of the message to reply to.
        #[arg(long)]
        reply_to: Option<String>,
    },
    /// React to a message (no emoji removes your reaction).
    React { conversation: String, message: String, emoji: Option<String> },
    /// Edit one of your messages.
    Edit { conversation: String, message: String, text: String },
    /// Delete one of your messages for everyone.
    Delete { conversation: String, message: String },
    /// Fetch and process new messages.
    Sync {
        /// Seconds to wait for new messages.
        #[arg(long, default_value_t = 0)]
        wait: u32,
    },
    /// List conversations.
    List,
    /// Show the latest messages of a conversation.
    Show {
        conversation: String,
        #[arg(long, default_value_t = 30)]
        limit: u32,
    },
    /// List the members of a conversation.
    Members { conversation: String },
    /// Show the safety number of a 1:1 chat.
    Safety { conversation: String },
    /// Keep syncing and print new messages as they arrive.
    Watch,
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    let client = Client::open(&cli.data, &cli.server).context("opening the data directory")?;
    match cli.command {
        Command::Register { username } => {
            let registration = client.register(&username)?;
            client.confirm_recovery_key_saved()?;
            println!("Registered as {} (account {})", registration.account.username, registration.account.account_id);
            println!("Recovery key (write it down; it is shown only once):");
            println!("  {}", registration.recovery_key);
        }
        Command::Recover { username, recovery_key } => {
            let account = client.recover(&username, &recovery_key)?;
            println!("Recovered {} onto this device. Contacts will see a new safety number.", account.username);
        }
        Command::Whoami => match client.account()? {
            Some(a) => println!("{} (account {}) on {}", a.username, a.account_id, a.server_url),
            None => println!("Not registered. Run: messenger register <username>"),
        },
        Command::Chat { username } => {
            let id = client.start_direct_chat(&username)?;
            println!("{id}");
        }
        Command::Group { title, members } => {
            let id = client.create_group(&title, &members)?;
            println!("{id}");
        }
        Command::Add { conversation, members } => {
            let id = resolve(&client, &conversation)?;
            client.add_members(&id, &members)?;
            println!("Adding {}", members.join(", "));
        }
        Command::Remove { conversation, member } => {
            let id = resolve(&client, &conversation)?;
            client.remove_member(&id, &member)?;
            println!("Removing {member}");
        }
        Command::Send { conversation, text, reply_to } => {
            let id = resolve(&client, &conversation)?;
            let reply = reply_to.map(|r| resolve_message(&client, &id, &r)).transpose()?;
            let message = client.send_text(&id, &text, reply.as_deref())?;
            client.sync(0).ok();
            println!("{message}");
        }
        Command::React { conversation, message, emoji } => {
            let id = resolve(&client, &conversation)?;
            let message = resolve_message(&client, &id, &message)?;
            client.react(&id, &message, emoji.as_deref())?;
            client.sync(0).ok();
        }
        Command::Edit { conversation, message, text } => {
            let id = resolve(&client, &conversation)?;
            let message = resolve_message(&client, &id, &message)?;
            client.edit_message(&id, &message, &text)?;
            client.sync(0).ok();
        }
        Command::Delete { conversation, message } => {
            let id = resolve(&client, &conversation)?;
            let message = resolve_message(&client, &id, &message)?;
            client.delete_message(&id, &message)?;
            client.sync(0).ok();
        }
        Command::Sync { wait } => {
            let report = client.sync(wait.saturating_mul(1000))?;
            println!(
                "{} new message(s), {} new conversation(s), {} waiting to send",
                report.new_messages,
                report.joined_conversations.len(),
                report.pending_outbox
            );
        }
        Command::List => {
            for c in client.conversations()? {
                print_conversation(&c);
            }
        }
        Command::Show { conversation, limit } => {
            let id = resolve(&client, &conversation)?;
            for m in client.messages(&id, limit)? {
                print_message(&m);
            }
            client.mark_read(&id)?;
        }
        Command::Members { conversation } => {
            let id = resolve(&client, &conversation)?;
            for m in client.members(&id)? {
                println!("{}{}", m.username, if m.is_me { " (you)" } else { "" });
            }
        }
        Command::Safety { conversation } => {
            let id = resolve(&client, &conversation)?;
            let number = client.safety_number(&id)?;
            println!("{}", number.digits);
        }
        Command::Watch => watch(&client)?,
    }
    Ok(())
}

/// Finds a conversation by id, id prefix, title or peer username.
fn resolve(client: &Client, query: &str) -> Result<String> {
    let query = query.trim().to_lowercase();
    let conversations = client.conversations()?;
    let matches: Vec<&ConversationSummary> =
        conversations.iter().filter(|c| c.id == query || c.title.to_lowercase() == query).collect();
    let matches =
        if matches.is_empty() { conversations.iter().filter(|c| c.id.starts_with(&query)).collect() } else { matches };
    match matches.as_slice() {
        [one] => Ok(one.id.clone()),
        [] => bail!("no conversation matches {query:?} (try: messenger list)"),
        _ => bail!("{query:?} matches several conversations; use the id"),
    }
}

fn resolve_message(client: &Client, conversation: &str, query: &str) -> Result<String> {
    let query = query.trim().to_lowercase();
    let messages = client.messages(conversation, 10_000)?;
    let found: Vec<_> = messages.iter().filter(|m| m.id.starts_with(&query)).collect();
    match found.as_slice() {
        [one] => Ok(one.id.clone()),
        [] => bail!("no message id starts with {query:?}"),
        _ => bail!("{query:?} matches several messages; use more characters"),
    }
}

fn print_conversation(c: &ConversationSummary) {
    let kind = match c.kind {
        ConversationKind::Direct => "1:1".to_string(),
        ConversationKind::Group => format!("group of {}", c.member_count),
    };
    let unread = if c.unread > 0 { format!(" [{} unread]", c.unread) } else { String::new() };
    let state = if c.active { "" } else { " (removed)" };
    let last = c.last_message.as_deref().unwrap_or("");
    println!("{}  {:<20} {kind}{unread}{state}  {last}", &c.id[..8], c.title);
}

fn print_message(m: &MessageView) {
    if m.system {
        println!("          · {}", m.text);
        return;
    }
    let status = match m.status {
        MessageStatus::Sending => " (sending)",
        MessageStatus::Failed => " (failed)",
        _ => "",
    };
    let edited = if m.edited { " (edited)" } else { "" };
    let text = if m.deleted { "Message deleted".to_string() } else { m.text.clone() };
    if let Some(reply) = &m.reply_to {
        println!("          ↳ {}: {}", reply.sender, reply.text);
    }
    let reactions: String = m.reactions.iter().map(|r| format!(" {}{}", r.emoji, r.count)).collect();
    println!("{}  {}: {text}{edited}{status}{reactions}", &m.id[..8], m.sender);
}

fn watch(client: &Client) -> Result<()> {
    let mut seen: std::collections::HashSet<String> = std::collections::HashSet::new();
    for c in client.conversations()? {
        for m in client.messages(&c.id, 10_000)? {
            seen.insert(m.id);
        }
    }
    eprintln!("Watching for messages (Ctrl-C to stop)…");
    loop {
        match client.sync(25_000) {
            Ok(report) => {
                for id in &report.joined_conversations {
                    if let Ok(c) = client.conversation(id) {
                        println!("+ joined {}", c.title);
                    }
                }
                for c in client.conversations()? {
                    for m in client.messages(&c.id, 200)? {
                        if seen.insert(m.id.clone()) && !m.outgoing {
                            print!("[{}] ", c.title);
                            print_message(&m);
                        }
                    }
                }
            }
            Err(e) => {
                eprintln!("sync failed: {e}; retrying in 5 s");
                std::thread::sleep(std::time::Duration::from_secs(5));
            }
        }
    }
}
