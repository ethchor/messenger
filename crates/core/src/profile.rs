//! Profiles: how each person appears to the others, as one of the apps' line
//! drawings and an ink colour, both by name.
//!
//! Ours goes to every chat as a `Profile` message when it changes, and again
//! when we join a chat or someone else does, since newcomers can't read what
//! came before. We keep the newest profile per account, our own included.
//! The server sees none of it.

use messenger_protocol::{
    v1::{content::Body, Content, MemberIdentity, Profile as ProfileMessage},
    CONTENT_ID_LEN,
};
use rusqlite::Connection;

use crate::{
    client::{leaving, meta_key, queue_content, Client, Ctx},
    keys,
    store::{self, latest_only, ConversationRow},
    Error, Result,
};

/// Longest avatar or ink name.
const MAX_NAME: usize = 32;

/// Someone's look: a drawing and an ink, by name. Empty means the app's default.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Profile {
    pub avatar: String,
    pub ink: String,
}

/// A person's profile, as last shared with us.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PersonProfile {
    pub username: String,
    pub avatar: String,
    pub ink: String,
}

impl Client {
    /// Our own profile, if we've set one.
    pub fn profile(&self) -> Result<Option<Profile>> {
        let state = self.lock()?;
        own_profile(&state.conn)
    }

    /// Sets how we appear to others and shares it with every chat we're in.
    /// Names are lowercase letters, digits and dashes, up to 32 characters.
    pub fn set_profile(&self, avatar: &str, ink: &str) -> Result<()> {
        for name in [avatar, ink] {
            let valid = name.len() <= MAX_NAME
                && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-');
            if !valid {
                return Err(Error::InvalidInput(format!("{name:?} isn't an avatar or ink name")));
            }
        }
        let profile = Profile { avatar: avatar.to_owned(), ink: ink.to_owned() };
        let shared = self.with_tx(|ctx| {
            if own_profile(ctx.conn)?.as_ref() == Some(&profile) {
                return Ok(false);
            }
            store::meta_set(ctx.conn, meta_key::PROFILE, format!("{}\n{}", profile.avatar, profile.ink).as_bytes())?;
            store::set_profile(ctx.conn, &ctx.account.account_id, &ctx.account.username, avatar, ink, store::now_ms())?;
            for conv in store::conversations(ctx.conn)? {
                if conv.active && !leaving(ctx.conn, &conv.id)? {
                    queue_profile(ctx, &conv, &profile)?;
                }
            }
            Ok(true)
        })?;
        if shared {
            self.flush_quietly();
        }
        Ok(())
    }

    /// Everyone's profile we know of, ours included, by username.
    pub fn profiles(&self) -> Result<Vec<PersonProfile>> {
        let state = self.lock()?;
        Ok(store::profiles(&state.conn)?
            .into_iter()
            .map(|row| PersonProfile { username: row.username, avatar: row.avatar, ink: row.ink })
            .collect())
    }
}

fn own_profile(conn: &Connection) -> Result<Option<Profile>> {
    let Some(bytes) = store::meta_get(conn, meta_key::PROFILE)? else { return Ok(None) };
    let text = String::from_utf8_lossy(&bytes);
    let (avatar, ink) = text.split_once('\n').unwrap_or((&text, ""));
    Ok(Some(Profile { avatar: avatar.to_owned(), ink: ink.to_owned() }))
}

/// Queues our profile for a conversation, replacing one that hasn't gone out.
fn queue_profile(ctx: &Ctx<'_>, conv: &ConversationRow, profile: &Profile) -> Result<()> {
    store::delete_unsent_latest_only(ctx.conn, &conv.id, latest_only::PROFILE)?;
    let content = Content {
        id: keys::random_bytes::<CONTENT_ID_LEN>()?.to_vec(),
        sent_at_ms: store::now_ms() as u64,
        body: Some(Body::Profile(ProfileMessage { avatar: profile.avatar.clone(), ink: profile.ink.clone() })),
    };
    let row = queue_content(ctx, conv, &content, false)?;
    store::set_outbox_latest_only(ctx.conn, row, latest_only::PROFILE)
}

/// Shares our profile in a conversation, if we've set one: when we join, and
/// when someone else joins.
pub(crate) fn share_profile(ctx: &Ctx<'_>, conv: &ConversationRow) -> Result<()> {
    match own_profile(ctx.conn)? {
        Some(profile) => queue_profile(ctx, conv, &profile),
        None => Ok(()),
    }
}

/// Applies a member's `Profile` message. Returns whether anything changed.
pub(crate) fn apply_profile(
    conn: &Connection,
    sender: &MemberIdentity,
    profile: &ProfileMessage,
    sent_at_ms: u64,
) -> Result<bool> {
    let clean = |name: &str| -> String {
        name.chars().filter(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == '-').take(MAX_NAME).collect()
    };
    store::set_profile(
        conn,
        &sender.account_id,
        &sender.username,
        &clean(&profile.avatar),
        &clean(&profile.ink),
        sent_at_ms.min(i64::MAX as u64) as i64,
    )
}
