//! Profiles: how each person appears to the others, as one of the apps' line
//! drawings and an ink colour, both by name, or a photo of their own.
//!
//! A photo is a small JPEG, sealed with a key of its own and uploaded as a
//! blob like any attachment; the profile message carries the blob id, key
//! and digest. Blobs expire on the server (30 days by default), so ours is
//! uploaded again every two weeks (`refresh_profile_photo`) and re-shared.
//!
//! Ours goes to every chat as a `Profile` message when it changes, and again
//! when we join a chat or someone else does, since newcomers can't read what
//! came before. We keep the newest profile per account, our own included.
//! The server sees none of it.

use std::path::Path;

use messenger_protocol::{
    v1::{attachment::Kind, content::Body, Attachment, Content, MemberIdentity, Profile as ProfileMessage},
    BLOB_ID_LEN, CONTENT_ID_LEN,
};
use prost::Message;
use rusqlite::Connection;

use crate::{
    client::{leaving, meta_key, queue_content, Client, Ctx},
    keys,
    store::{self, latest_only, ConversationRow},
    Error, Result,
};

/// Longest avatar or ink name.
const MAX_NAME: usize = 32;
/// Largest profile photo we send or fetch.
pub const MAX_PROFILE_PHOTO_BYTES: u64 = 1024 * 1024;
/// Our photo goes up again after this long, well inside the server's retention.
const PHOTO_REFRESH_MS: i64 = 14 * 24 * 60 * 60 * 1000;

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
    /// They have a photo: `profile_photo` fetches it.
    pub has_photo: bool,
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
            let photo = own_photo(ctx.conn)?.map(|a| a.encode_to_vec());
            store::set_profile(
                ctx.conn,
                &ctx.account.account_id,
                &ctx.account.username,
                avatar,
                ink,
                photo.as_deref(),
                store::now_ms(),
            )?;
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
            .map(|row| PersonProfile {
                username: row.username,
                avatar: row.avatar,
                ink: row.ink,
                has_photo: row.has_photo,
            })
            .collect())
    }

    /// Sets our profile photo from a JPEG at `path` (at most 1 MB; the app
    /// crops and shrinks it), or removes it (None), and shares that with
    /// every chat. Uploads first, so it needs the network.
    pub fn set_profile_photo(&self, path: Option<&str>) -> Result<()> {
        let photo = match path {
            Some(path) => Some(self.upload_profile_photo(Path::new(path))?),
            None => None,
        };
        self.share_photo(photo)
    }

    /// Uploads our photo again if it's been up for two weeks, so it never
    /// expires on the server. Call it now and then; it's cheap otherwise.
    pub fn refresh_profile_photo(&self) -> Result<()> {
        let (photo, at) = {
            let state = self.lock()?;
            (
                own_photo(&state.conn)?,
                store::meta_get(&state.conn, meta_key::PROFILE_PHOTO_AT)?
                    .and_then(|b| String::from_utf8_lossy(&b).parse::<i64>().ok())
                    .unwrap_or(0),
            )
        };
        if photo.is_none() || store::now_ms() - at < PHOTO_REFRESH_MS {
            return Ok(());
        }
        let Some(path) = self.own_photo_path()? else { return Ok(()) };
        let fresh = self.upload_profile_photo(Path::new(&path))?;
        self.share_photo(Some(fresh))
    }

    /// Where someone's profile photo is on this device, fetching it first if
    /// needed. None: they have no photo.
    pub fn profile_photo(&self, username: &str) -> Result<Option<String>> {
        let Some((account_id, sealed, path)) = store::profile_photo(&self.lock()?.conn, username)? else {
            return Ok(None);
        };
        if let Some(path) = path.filter(|p| Path::new(p).exists()) {
            return Ok(Some(path));
        }
        let photo =
            Attachment::decode(sealed.as_slice()).map_err(|_| Error::Internal("a damaged profile photo".into()))?;
        let dest = self.media_dir()?.join(format!(
            "profile-{}-{}.jpg",
            hex::encode(&account_id[..account_id.len().min(8)]),
            hex::encode(&photo.digest[..photo.digest.len().min(6)])
        ));
        self.fetch_and_open(&photo.blob_id, &photo.key, &photo.digest, photo.size, &dest)?;
        let path = dest.to_string_lossy().into_owned();
        let kept = self.with_tx(|ctx| store::set_profile_photo_path(ctx.conn, &account_id, &sealed, &path))?;
        if !kept {
            // A newer photo came while this one was on its way.
            let _ = std::fs::remove_file(&dest);
            return self.profile_photo(username);
        }
        Ok(Some(path))
    }

    fn own_photo_path(&self) -> Result<Option<String>> {
        let state = self.lock()?;
        Ok(store::meta_get(&state.conn, meta_key::PROFILE_PHOTO_PATH)?
            .map(|b| String::from_utf8_lossy(&b).into_owned())
            .filter(|p| Path::new(p).exists()))
    }

    /// Seals and uploads a photo, keeping a plain copy of our own.
    fn upload_profile_photo(&self, source: &Path) -> Result<Attachment> {
        let size =
            std::fs::metadata(source).map_err(|e| Error::InvalidInput(format!("can't read the photo: {e}")))?.len();
        if size == 0 || size > MAX_PROFILE_PHOTO_BYTES {
            return Err(Error::InvalidInput("the photo should be a JPEG of at most 1 MB".into()));
        }
        let ours = self.media_dir()?.join("profile-me.jpg");
        if source != ours {
            std::fs::copy(source, &ours).map_err(|e| Error::InvalidInput(format!("can't keep the photo: {e}")))?;
        }
        let key = crate::media::new_file_key()?;
        let sealed = self.transit_dir()?.join("out-profile-photo");
        let uploaded = self.seal_and_upload(&key, &ours, size, &sealed);
        let _ = std::fs::remove_file(&sealed);
        let (blob_id, digest) = uploaded?;
        self.with_tx(|ctx| {
            store::meta_set(ctx.conn, meta_key::PROFILE_PHOTO_PATH, ours.to_string_lossy().as_bytes())?;
            store::meta_set(ctx.conn, meta_key::PROFILE_PHOTO_AT, store::now_ms().to_string().as_bytes())
        })?;
        Ok(Attachment {
            kind: Kind::Photo as i32,
            blob_id,
            key,
            digest: digest.to_vec(),
            size,
            content_type: "image/jpeg".into(),
            file_name: "profile.jpg".into(),
            ..Default::default()
        })
    }

    /// Keeps our photo (or none) and shares the profile with every chat.
    fn share_photo(&self, photo: Option<Attachment>) -> Result<()> {
        self.with_tx(|ctx| {
            match &photo {
                Some(p) => store::meta_set(ctx.conn, meta_key::PROFILE_PHOTO, &p.encode_to_vec())?,
                None => {
                    store::meta_delete(ctx.conn, meta_key::PROFILE_PHOTO)?;
                    store::meta_delete(ctx.conn, meta_key::PROFILE_PHOTO_PATH)?;
                }
            }
            let profile = own_profile(ctx.conn)?.unwrap_or_default();
            if own_profile(ctx.conn)?.is_none() {
                store::meta_set(ctx.conn, meta_key::PROFILE, b"\n")?;
            }
            let sealed = photo.as_ref().map(|p| p.encode_to_vec());
            store::set_profile(
                ctx.conn,
                &ctx.account.account_id,
                &ctx.account.username,
                &profile.avatar,
                &profile.ink,
                sealed.as_deref(),
                store::now_ms(),
            )?;
            if let Some(path) = store::meta_get(ctx.conn, meta_key::PROFILE_PHOTO_PATH)? {
                if let Some(sealed) = &sealed {
                    store::set_profile_photo_path(
                        ctx.conn,
                        &ctx.account.account_id,
                        sealed,
                        &String::from_utf8_lossy(&path),
                    )?;
                }
            }
            for conv in store::conversations(ctx.conn)? {
                if conv.active && !leaving(ctx.conn, &conv.id)? {
                    queue_profile(ctx, &conv, &profile)?;
                }
            }
            Ok(())
        })?;
        self.flush_quietly();
        Ok(())
    }
}

/// Our photo as we share it, if we have one.
fn own_photo(conn: &Connection) -> Result<Option<Attachment>> {
    let Some(bytes) = store::meta_get(conn, meta_key::PROFILE_PHOTO)? else { return Ok(None) };
    Ok(Attachment::decode(bytes.as_slice()).ok())
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
        body: Some(Body::Profile(ProfileMessage {
            avatar: profile.avatar.clone(),
            ink: profile.ink.clone(),
            photo: own_photo(ctx.conn)?,
        })),
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
    // Only a photo that's well formed and small enough to fetch.
    let photo = profile
        .photo
        .as_ref()
        .filter(|p| {
            p.blob_id.len() == BLOB_ID_LEN
                && p.key.len() == 32
                && p.digest.len() == 32
                && p.size > 0
                && p.size <= MAX_PROFILE_PHOTO_BYTES
        })
        .map(|p| Attachment { thumbnail: Vec::new(), waveform: Vec::new(), ..p.clone() }.encode_to_vec());
    store::set_profile(
        conn,
        &sender.account_id,
        &sender.username,
        &clean(&profile.avatar),
        &clean(&profile.ink),
        photo.as_deref(),
        sent_at_ms.min(i64::MAX as u64) as i64,
    )
}
