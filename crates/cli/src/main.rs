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
use messenger_core::{Client, ConversationKind, ConversationSummary, MessageStatus, MessageView, PrivacySettings};

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
    /// Replace the recovery key; prints the new one. The old one stops working.
    NewRecoveryKey,
    /// Leave every conversation, delete the account and erase this device.
    DeleteAccount {
        /// Type the username again to confirm.
        #[arg(long)]
        confirm: String,
    },
    /// Leave every conversation and erase this device; the recovery key
    /// brings the account back.
    SignOut,
    /// Erase this device at once, without the network (panic wipe). The
    /// recovery key brings the account back.
    Wipe,
    /// Show or change read receipts and typing indicators.
    Privacy {
        #[arg(long)]
        read_receipts: Option<Toggle>,
        #[arg(long)]
        typing: Option<Toggle>,
    },
    /// Open (or create) the 1:1 chat with a user.
    Chat { username: String },
    /// Create a group.
    Group { title: String, members: Vec<String> },
    /// Add people to a group.
    Add { conversation: String, members: Vec<String> },
    /// Remove someone from a group.
    Remove { conversation: String, member: String },
    /// Leave a conversation.
    Leave { conversation: String },
    /// Tell the others you're typing (or stopped).
    Typing { conversation: String, state: Toggle },
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

#[derive(Debug, Clone, Copy, clap::ValueEnum)]
enum Toggle {
    On,
    Off,
}

impl Toggle {
    fn on(self) -> bool {
        matches!(self, Toggle::On)
    }
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
        Command::NewRecoveryKey => {
            let key = client.new_recovery_key()?;
            client.confirm_recovery_key_saved()?;
            println!("New recovery key (write it down; the old one no longer works):");
            println!("  {key}");
        }
        Command::DeleteAccount { confirm } => {
            let account = client.account()?.context("no account on this device")?;
            if confirm.trim().to_lowercase() != account.username {
                bail!("--confirm must be your username ({})", account.username);
            }
            client.delete_account()?;
            println!("Deleted {}. This device is empty now.", account.username);
        }
        Command::SignOut => {
            client.remove_from_device()?;
            println!("Signed out. Use `messenger recover` with your recovery key to sign in again.");
        }
        Command::Wipe => {
            client.wipe_this_device()?;
            println!("Erased this device. Use `messenger recover` with your recovery key to sign in again.");
        }
        Command::Privacy { read_receipts, typing } => {
            let mut settings = client.privacy_settings()?;
            if let Some(t) = read_receipts {
                settings.read_receipts = t.on();
            }
            if let Some(t) = typing {
                settings.typing_indicators = t.on();
            }
            client.set_privacy_settings(settings)?;
            let PrivacySettings { read_receipts, typing_indicators } = settings;
            let word = |on: bool| if on { "on" } else { "off" };
            println!("read receipts: {}, typing indicators: {}", word(read_receipts), word(typing_indicators));
        }
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
        Command::Leave { conversation } => {
            let id = resolve(&client, &conversation)?;
            client.leave_conversation(&id)?;
            println!("Leaving; the others see it once one of them is online.");
        }
        Command::Typing { conversation, state } => {
            let id = resolve(&client, &conversation)?;
            client.set_typing(&id, state.on())?;
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
    let state = if c.leaving {
        " (leaving)"
    } else if c.active {
        ""
    } else {
        " (ended)"
    };
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
        MessageStatus::Read => " ✓✓",
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
                for id in &report.typing_changed {
                    let typing = client.typing_users(id)?;
                    if let (Ok(c), false) = (client.conversation(id), typing.is_empty()) {
                        println!("[{}] {} typing…", c.title, typing.join(", "));
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
