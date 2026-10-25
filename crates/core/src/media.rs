//! Photos, videos, voice notes and files.
//!
//! Each file is padded, encrypted with a key of its own and uploaded as a
//! blob; the message that carries it holds the blob's id, the key and the
//! digest (see `Attachment` in proto/messenger/v1/content.proto for the
//! format). Our own files, and the ones we fetch, live in `media/` next to
//! the database.

use std::{
    collections::HashSet,
    fs::File,
    io::{BufReader, BufWriter, Read, Write},
    path::{Path, PathBuf},
};

use messenger_protocol::{
    api::NewBlobRequest,
    v1::{attachment::Kind, content::Body, Attachment, Content, Text},
    BLOB_ID_LEN, CONTENT_ID_LEN, MAX_BLOB_BYTES, MAX_TEXT_BYTES,
};
use openmls_traits::{crypto::OpenMlsCrypto, types::AeadType};
use sha2::{Digest, Sha256};

use crate::{
    api::RequestSigner,
    client::{active_conversation, parse_id, queue_content, reply_target, Client},
    keys,
    store::{self, attachment_state, message_status, AttachmentRow, NewMessage},
    Error, Result,
};

/// Plaintext bytes per encrypted chunk.
const CHUNK_BYTES: u64 = 64 * 1024;
const TAG_BYTES: u64 = 16;
/// Associated data of every chunk, so a chunk can't pass for anything else.
const AAD: &[u8] = b"messenger attachment v1";
const KEY_BYTES: usize = 32;

/// Most files in one message.
pub const MAX_ATTACHMENTS: usize = 10;
/// Largest file we send: once padded and encrypted it still fits in a blob.
pub const MAX_ATTACHMENT_BYTES: u64 = 96 * 1024 * 1024;
pub const MAX_THUMBNAIL_BYTES: usize = 8 * 1024;
pub const MAX_WAVEFORM: usize = 256;
const MAX_FILE_NAME_BYTES: usize = 255;
const MAX_CONTENT_TYPE_BYTES: usize = 128;

pub(crate) const MEDIA_DIR: &str = "media";
/// Encrypted files on their way out or in; never left there for long.
pub(crate) const TRANSIT_DIR: &str = "transit";

/// What a file is, for showing it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AttachmentKind {
    Photo,
    Video,
    Voice,
    File,
}

impl AttachmentKind {
    fn code(self) -> i64 {
        match self {
            Self::Photo => Kind::Photo as i64,
            Self::Video => Kind::Video as i64,
            Self::Voice => Kind::Voice as i64,
            Self::File => Kind::File as i64,
        }
    }

    pub(crate) fn from_code(code: i64) -> Self {
        match Kind::try_from(code as i32) {
            Ok(Kind::Photo) => Self::Photo,
            Ok(Kind::Video) => Self::Video,
            Ok(Kind::Voice) => Self::Voice,
            _ => Self::File,
        }
    }
}

/// A file to send, as the app prepared it.
#[derive(Debug, Clone)]
pub struct NewAttachment {
    /// A file the app wrote for this message. The core moves it into its
    /// own folder, so it's gone from here afterwards.
    pub path: String,
    pub kind: AttachmentKind,
    pub content_type: String,
    /// What to call it; any folders are dropped.
    pub file_name: String,
    pub width: u32,
    pub height: u32,
    pub duration_ms: u32,
    /// Photos and videos: a small JPEG, at most 8 KiB; a bigger one is left out.
    pub thumbnail: Option<Vec<u8>>,
    /// Voice notes: loudness over time, 0 to 255, at most 256 steps.
    pub waveform: Vec<u8>,
}

/// In-flight transfers, so two threads never move the same file at once.
#[derive(Default)]
pub(crate) struct Transfers {
    /// (conversation, message) being uploaded.
    pub uploading: HashSet<(Vec<u8>, Vec<u8>)>,
    /// (conversation, message, position) being fetched.
    pub downloading: HashSet<(Vec<u8>, Vec<u8>, i64)>,
}

// ---- the file format ----

/// Padmé on 64-bit lengths, never below 1 byte (see `messenger_protocol::padding`).
fn padded_len(size: u64) -> u64 {
    let len = size.max(1);
    if len < 2 {
        return len;
    }
    let e = 63 - u64::from(len.leading_zeros());
    let s = 64 - u64::from(e.leading_zeros());
    let mask = (1u64 << e.saturating_sub(s)) - 1;
    (len + mask) & !mask
}

fn chunk_count(padded: u64) -> u64 {
    padded.div_ceil(CHUNK_BYTES)
}

/// Size of the encrypted file for a file of `size` bytes.
pub fn encrypted_len(size: u64) -> u64 {
    let padded = padded_len(size);
    padded + chunk_count(padded) * TAG_BYTES
}

/// Chunk `counter`'s nonce: 11 bytes of counter, big-endian, then whether
/// it's the last chunk.
fn nonce(counter: u64, last: bool) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[3..11].copy_from_slice(&counter.to_be_bytes());
    nonce[11] = u8::from(last);
    nonce
}

fn io(context: &str, e: std::io::Error) -> Error {
    Error::Storage(format!("{context}: {e}"))
}

/// Pads and encrypts the `size`-byte file at `input` into `output`. Returns
/// the SHA-256 of what was written.
pub(crate) fn encrypt_file(
    crypto: &impl OpenMlsCrypto,
    key: &[u8],
    input: &Path,
    size: u64,
    output: &Path,
) -> Result<[u8; 32]> {
    let mut reader = BufReader::new(File::open(input).map_err(|e| io("opening the file to send", e))?);
    let mut writer = BufWriter::new(File::create(output).map_err(|e| io("writing the encrypted file", e))?);
    let padded = padded_len(size);
    let chunks = chunk_count(padded);
    let mut hasher = Sha256::new();
    let mut left = size;
    let mut buffer = vec![0u8; CHUNK_BYTES as usize];
    for counter in 0..chunks {
        let last = counter + 1 == chunks;
        let len = if last { padded - counter * CHUNK_BYTES } else { CHUNK_BYTES } as usize;
        let real = left.min(len as u64) as usize;
        reader
            .read_exact(&mut buffer[..real])
            .map_err(|_| Error::InvalidInput("the file changed while it was being sent".into()))?;
        buffer[real..len].fill(0);
        left -= real as u64;
        let sealed = crypto
            .aead_encrypt(AeadType::ChaCha20Poly1305, key, &buffer[..len], &nonce(counter, last), AAD)
            .map_err(|e| Error::Mls(format!("encrypting a file: {e:?}")))?;
        hasher.update(&sealed);
        writer.write_all(&sealed).map_err(|e| io("writing the encrypted file", e))?;
    }
    let mut probe = [0u8; 1];
    if reader.read(&mut probe).map_err(|e| io("reading the file to send", e))? != 0 {
        return Err(Error::InvalidInput("the file changed while it was being sent".into()));
    }
    let file = writer.into_inner().map_err(|e| io("writing the encrypted file", e.into_error()))?;
    file.sync_all().map_err(|e| io("writing the encrypted file", e))?;
    Ok(hasher.finalize().into())
}

/// Checks the encrypted file at `input` against `digest`, decrypts it and
/// writes the first `size` bytes to `output`. Anything cut, reordered,
/// swapped or altered fails, and leaves no output behind.
pub(crate) fn decrypt_file(
    crypto: &impl OpenMlsCrypto,
    key: &[u8],
    input: &Path,
    digest: &[u8],
    size: u64,
    output: &Path,
) -> Result<()> {
    let damaged = || Error::Mls("the file was damaged on the way".into());
    let length = std::fs::metadata(input).map_err(|e| io("reading the fetched file", e))?.len();
    if length != encrypted_len(size) {
        return Err(damaged());
    }
    // The whole file must be the one that was sent before any of it is opened.
    let mut hasher = Sha256::new();
    let mut reader = BufReader::new(File::open(input).map_err(|e| io("reading the fetched file", e))?);
    std::io::copy(&mut reader, &mut hasher).map_err(|e| io("reading the fetched file", e))?;
    if hasher.finalize().as_slice() != digest {
        return Err(damaged());
    }
    let result = (|| {
        let mut reader = BufReader::new(File::open(input).map_err(|e| io("reading the fetched file", e))?);
        let mut writer = BufWriter::new(File::create(output).map_err(|e| io("saving the file", e))?);
        let padded = padded_len(size);
        let chunks = chunk_count(padded);
        let mut left = size;
        let mut buffer = vec![0u8; (CHUNK_BYTES + TAG_BYTES) as usize];
        for counter in 0..chunks {
            let last = counter + 1 == chunks;
            let len = if last { padded - counter * CHUNK_BYTES } else { CHUNK_BYTES } as usize;
            let sealed = &mut buffer[..len + TAG_BYTES as usize];
            reader.read_exact(sealed).map_err(|_| damaged())?;
            let plain = crypto
                .aead_decrypt(AeadType::ChaCha20Poly1305, key, sealed, &nonce(counter, last), AAD)
                .map_err(|_| damaged())?;
            let real = left.min(plain.len() as u64) as usize;
            if plain[real..].iter().any(|&b| b != 0) {
                return Err(damaged());
            }
            writer.write_all(&plain[..real]).map_err(|e| io("saving the file", e))?;
            left -= real as u64;
        }
        let file = writer.into_inner().map_err(|e| io("saving the file", e.into_error()))?;
        file.sync_all().map_err(|e| io("saving the file", e))
    })();
    if result.is_err() {
        let _ = std::fs::remove_file(output);
    }
    result
}

// ---- names ----

/// The last part of a path, without control characters, at most 255 bytes.
fn clean_file_name(name: &str) -> String {
    let base = name.rsplit(['/', '\\']).next().unwrap_or("");
    let mut out = String::new();
    for c in base.trim().chars().filter(|c| !c.is_control()) {
        if out.len() + c.len_utf8() > MAX_FILE_NAME_BYTES {
            break;
        }
        out.push(c);
    }
    out
}

fn clean_content_type(content_type: &str) -> String {
    let lower = content_type.trim().to_ascii_lowercase();
    let ok = !lower.is_empty()
        && lower.len() <= MAX_CONTENT_TYPE_BYTES
        && lower.contains('/')
        && lower.chars().all(|c| c.is_ascii_alphanumeric() || "/.+-_".contains(c));
    if ok {
        lower
    } else {
        "application/octet-stream".to_string()
    }
}

/// A file name's extension, if it's a plain one.
fn extension(file_name: &str, content_type: &str) -> String {
    let from_name = file_name
        .rsplit_once('.')
        .map(|(_, ext)| ext.to_ascii_lowercase())
        .filter(|ext| !ext.is_empty() && ext.len() <= 8 && ext.chars().all(|c| c.is_ascii_alphanumeric()));
    from_name.unwrap_or_else(|| {
        match content_type {
            "image/jpeg" => "jpg",
            "image/png" => "png",
            "image/heic" => "heic",
            "image/gif" => "gif",
            "image/webp" => "webp",
            "video/mp4" => "mp4",
            "video/quicktime" => "mov",
            "audio/mp4" | "audio/x-m4a" => "m4a",
            "audio/aac" => "aac",
            "audio/ogg" => "ogg",
            "audio/mpeg" => "mp3",
            "application/pdf" => "pdf",
            _ => "bin",
        }
        .to_string()
    })
}

/// Where a message's file lives on this device. The extension helps the
/// players and viewers that go by it.
fn local_name(content_id: &[u8], position: i64, file_name: &str, content_type: &str) -> String {
    format!("{}-{position}.{}", hex::encode(content_id), extension(file_name, content_type))
}

/// Moves a file, copying it when it's on another volume.
fn move_file(from: &Path, to: &Path) -> Result<()> {
    if std::fs::rename(from, to).is_ok() {
        return Ok(());
    }
    std::fs::copy(from, to).map_err(|e| io("taking the file to send", e))?;
    let _ = std::fs::remove_file(from);
    Ok(())
}

pub(crate) fn remove_files(paths: &[String]) {
    for path in paths {
        let _ = std::fs::remove_file(path);
    }
}

// ---- messages ----

/// An attachment as it goes in a message.
fn to_proto(row: &AttachmentRow) -> Attachment {
    Attachment {
        kind: row.kind as i32,
        blob_id: row.blob_id.clone().unwrap_or_default(),
        key: row.key.clone(),
        digest: row.digest.clone().unwrap_or_default(),
        size: row.size.max(0) as u64,
        content_type: row.content_type.clone(),
        file_name: row.file_name.clone(),
        width: row.width.clamp(0, u32::MAX.into()) as u32,
        height: row.height.clamp(0, u32::MAX.into()) as u32,
        duration_ms: row.duration_ms.clamp(0, u32::MAX.into()) as u32,
        thumbnail: row.thumbnail.clone().unwrap_or_default(),
        waveform: row.waveform.clone().unwrap_or_default(),
    }
}

/// An attachment someone sent, if it's well formed: the ids and keys the
/// right length and the file not too big. Pictures and waves that are too
/// big are left out, and names and types cleaned up.
pub(crate) fn accept(
    conversation_id: &[u8],
    content_id: &[u8],
    position: usize,
    a: &Attachment,
) -> Option<AttachmentRow> {
    let well_formed = a.blob_id.len() == BLOB_ID_LEN
        && a.key.len() == KEY_BYTES
        && a.digest.len() == 32
        && a.size > 0
        && encrypted_len(a.size) <= MAX_BLOB_BYTES;
    if !well_formed {
        return None;
    }
    let kind = match Kind::try_from(a.kind) {
        Ok(Kind::Photo | Kind::Video | Kind::Voice | Kind::File) => a.kind as i64,
        _ => Kind::File as i64,
    };
    Some(AttachmentRow {
        conversation_id: conversation_id.to_vec(),
        content_id: content_id.to_vec(),
        position: position as i64,
        kind,
        blob_id: Some(a.blob_id.clone()),
        key: a.key.clone(),
        digest: Some(a.digest.clone()),
        size: a.size as i64,
        content_type: clean_content_type(&a.content_type),
        file_name: clean_file_name(&a.file_name),
        width: a.width.min(100_000).into(),
        height: a.height.min(100_000).into(),
        duration_ms: a.duration_ms.into(),
        thumbnail: (!a.thumbnail.is_empty() && a.thumbnail.len() <= MAX_THUMBNAIL_BYTES).then(|| a.thumbnail.clone()),
        waveform: (!a.waveform.is_empty() && a.waveform.len() <= MAX_WAVEFORM).then(|| a.waveform.clone()),
        state: attachment_state::REMOTE,
        path: None,
        error: None,
    })
}

/// What to call a message that is only files: "Photo", "3 photos",
/// "Voice message", the file's name…
pub(crate) fn summary(rows: &[AttachmentRow]) -> Option<String> {
    let first = rows.first()?;
    let kind = AttachmentKind::from_code(first.kind);
    let same = rows.iter().all(|r| AttachmentKind::from_code(r.kind) == kind);
    let n = rows.len();
    Some(match (same, kind) {
        (true, AttachmentKind::Photo) if n == 1 => "Photo".to_string(),
        (true, AttachmentKind::Photo) => format!("{n} photos"),
        (true, AttachmentKind::Video) if n == 1 => "Video".to_string(),
        (true, AttachmentKind::Video) => format!("{n} videos"),
        (true, AttachmentKind::Voice) if n == 1 => "Voice message".to_string(),
        (true, AttachmentKind::Voice) => format!("{n} voice messages"),
        (true, AttachmentKind::File) if n == 1 && !first.file_name.is_empty() => first.file_name.clone(),
        (true, AttachmentKind::File) if n == 1 => "File".to_string(),
        (true, AttachmentKind::File) => format!("{n} files"),
        (false, _) => format!("{n} attachments"),
    })
}

impl Client {
    pub(crate) fn media_dir(&self) -> Result<PathBuf> {
        let dir = self.data_dir.join(MEDIA_DIR);
        std::fs::create_dir_all(&dir).map_err(|e| io("making the media folder", e))?;
        Ok(dir)
    }

    fn transit_dir(&self) -> Result<PathBuf> {
        let dir = self.data_dir.join(TRANSIT_DIR);
        std::fs::create_dir_all(&dir).map_err(|e| io("making the transfer folder", e))?;
        Ok(dir)
    }

    /// Sends files, with `caption` as their text, maybe as a reply to
    /// `reply_to` or (with `in_thread`) in the thread under it. The message
    /// shows at once, as sending; `send_pending` uploads the files and sends
    /// it. Returns its id.
    pub fn send_media(
        &self,
        conversation_id: &str,
        caption: &str,
        attachments: Vec<NewAttachment>,
        reply_to: Option<&str>,
        in_thread: bool,
    ) -> Result<String> {
        let caption = caption.trim_end();
        if attachments.is_empty() {
            return Err(Error::InvalidInput("nothing to send".into()));
        }
        if attachments.len() > MAX_ATTACHMENTS {
            return Err(Error::InvalidInput(format!("send at most {MAX_ATTACHMENTS} files at a time")));
        }
        if caption.len() > MAX_TEXT_BYTES {
            return Err(Error::InvalidInput("the caption is too long".into()));
        }
        let id = parse_id(conversation_id)?;
        let reply_to = reply_to.map(parse_id).transpose()?;
        let content_id = keys::random_bytes::<CONTENT_ID_LEN>()?.to_vec();
        let media = self.media_dir()?;
        // The files become ours first; the message exists only once they are.
        let mut rows: Vec<AttachmentRow> = Vec::new();
        let mut moved: Vec<String> = Vec::new();
        let taken = (|| {
            for (position, a) in attachments.into_iter().enumerate() {
                let source = PathBuf::from(&a.path);
                let size = std::fs::metadata(&source)
                    .map_err(|e| Error::InvalidInput(format!("can't read the file to send: {e}")))?
                    .len();
                if size == 0 {
                    return Err(Error::InvalidInput("that file is empty".into()));
                }
                if size > MAX_ATTACHMENT_BYTES {
                    return Err(Error::InvalidInput(format!(
                        "files can be at most {} MB",
                        MAX_ATTACHMENT_BYTES / (1024 * 1024)
                    )));
                }
                let file_name = clean_file_name(&a.file_name);
                let content_type = clean_content_type(&a.content_type);
                let dest = media.join(local_name(&content_id, position as i64, &file_name, &content_type));
                move_file(&source, &dest)?;
                let path = dest.to_string_lossy().into_owned();
                moved.push(path.clone());
                rows.push(AttachmentRow {
                    conversation_id: id.clone(),
                    content_id: content_id.clone(),
                    position: position as i64,
                    kind: a.kind.code(),
                    blob_id: None,
                    key: keys::random_bytes::<KEY_BYTES>()?.to_vec(),
                    digest: None,
                    size: size as i64,
                    content_type,
                    file_name,
                    width: a.width.into(),
                    height: a.height.into(),
                    duration_ms: a.duration_ms.into(),
                    thumbnail: a.thumbnail.filter(|t| !t.is_empty() && t.len() <= MAX_THUMBNAIL_BYTES),
                    waveform: (!a.waveform.is_empty())
                        .then(|| a.waveform[..a.waveform.len().min(MAX_WAVEFORM)].to_vec()),
                    state: attachment_state::UPLOADING,
                    path: Some(path),
                    error: None,
                });
            }
            Ok(())
        })();
        let stored = taken.and_then(|()| {
            self.with_tx(|ctx| {
                let conv = active_conversation(ctx.conn, &id)?;
                let (reply_to, thread_root) = reply_target(ctx.conn, &conv.id, reply_to.clone(), in_thread)?;
                store::insert_message(
                    ctx.conn,
                    &NewMessage {
                        conversation_id: &conv.id,
                        content_id: &content_id,
                        sender_account: &ctx.account.account_id,
                        sender_username: &ctx.account.username,
                        outgoing: true,
                        system: false,
                        sent_at: store::now_ms(),
                        text: caption,
                        reply_to: reply_to.as_deref(),
                        thread_root: thread_root.as_deref(),
                        status: message_status::SENDING,
                        position: None,
                    },
                )?;
                for row in &rows {
                    store::insert_attachment(ctx.conn, row)?;
                }
                store::touch_conversation(ctx.conn, &conv.id, 0)
            })
        });
        if let Err(e) = stored {
            remove_files(&moved);
            return Err(e);
        }
        Ok(hex::encode(content_id))
    }

    /// Uploads the files of messages waiting to go, then sends them. Network
    /// errors leave the rest for the next call; a file the server refuses
    /// (too big, too much today) fails its message. Safe to call from several
    /// threads: each message is uploaded once.
    pub fn send_pending(&self) -> Result<()> {
        let waiting = store::unsent_media(&self.lock()?.conn)?;
        let mut queued = false;
        for (conversation, content_id) in waiting {
            let key = (conversation.clone(), content_id.clone());
            if !self.transfers()?.uploading.insert(key.clone()) {
                continue;
            }
            let result = self.upload_message(&conversation, &content_id);
            self.transfers()?.uploading.remove(&key);
            match result {
                Ok(()) => queued = true,
                Err(Error::Server { status: 400 | 413 | 429, message, .. }) | Err(Error::InvalidInput(message)) => {
                    log::warn!("can't send a file: {message}");
                    self.with_tx(|ctx| {
                        store::set_message_status(ctx.conn, &conversation, &content_id, message_status::FAILED)
                    })?;
                }
                Err(e) => return Err(e),
            }
        }
        if queued {
            self.flush()?;
        }
        Ok(())
    }

    fn upload_message(&self, conversation: &[u8], content_id: &[u8]) -> Result<()> {
        let rows = store::attachments(&self.lock()?.conn, conversation, content_id)?;
        let transit = self.transit_dir()?;
        for row in rows.iter().filter(|r| r.state == attachment_state::UPLOADING) {
            let path = row.path.as_deref().ok_or_else(|| Error::InvalidInput("the file to send is gone".into()))?;
            if !Path::new(path).exists() {
                return Err(Error::InvalidInput("the file to send is gone".into()));
            }
            let sealed = transit.join(format!("out-{}-{}", hex::encode(content_id), row.position));
            let uploaded = self.upload_file(row, Path::new(path), &sealed);
            let _ = std::fs::remove_file(&sealed);
            let (blob_id, digest) = uploaded?;
            self.with_tx(|ctx| {
                store::set_attachment_uploaded(ctx.conn, conversation, content_id, row.position, &blob_id, &digest)
            })?;
        }
        // Every file is up: the message can go.
        self.with_tx(|ctx| {
            let rows = store::attachments(ctx.conn, conversation, content_id)?;
            if rows.iter().any(|r| r.state != attachment_state::SENT) {
                return Ok(());
            }
            let Some(message) = store::outgoing_message(ctx.conn, conversation, content_id)? else {
                return Ok(());
            };
            let conv = match store::conversation(ctx.conn, conversation)? {
                Some(conv) if conv.active => conv,
                _ => return store::set_message_status(ctx.conn, conversation, content_id, message_status::FAILED),
            };
            let content = Content {
                id: content_id.to_vec(),
                sent_at_ms: message.sent_at.max(0) as u64,
                body: Some(Body::Text(Text {
                    text: message.text,
                    reply_to: message.reply_to.unwrap_or_default(),
                    in_thread: message.thread_root.is_some(),
                    attachments: rows.iter().map(to_proto).collect(),
                })),
            };
            let row = queue_content(ctx, &conv, &content, false)?;
            store::set_outbox_notify(ctx.conn, row)
        })
    }

    /// Encrypts one file into `sealed` and uploads it. Returns the blob id
    /// and the digest.
    fn upload_file(&self, row: &AttachmentRow, plain: &Path, sealed: &Path) -> Result<(Vec<u8>, [u8; 32])> {
        let size = row.size.max(0) as u64;
        let digest = encrypt_file(&self.crypto, &row.key, plain, size, sealed)?;
        let length = encrypted_len(size);
        let (account, signer) = self.identity()?;
        let sign = |payload: &[u8]| keys::sign(&signer, payload);
        let request_signer = RequestSigner { account_id: &account.account_id, sign: &sign };
        let blob = self.api.new_blob(&NewBlobRequest { size: length, digest: digest.to_vec() }, &request_signer)?;
        if blob.id.len() != BLOB_ID_LEN {
            return Err(Error::Internal("the server gave a bad file id".into()));
        }
        self.api.put_blob(&blob.id, sealed, length)?;
        Ok((blob.id, digest))
    }

    /// Fetches and decrypts a file someone sent, if it isn't here yet.
    /// Returns where it is on this device.
    pub fn download_attachment(&self, conversation_id: &str, message_id: &str, position: u32) -> Result<String> {
        let conversation = parse_id(conversation_id)?;
        let content_id = parse_id(message_id)?;
        let position = i64::from(position);
        let key = (conversation.clone(), content_id.clone(), position);
        // One fetch per file; a second caller waits for the first.
        let row = loop {
            let row = store::attachment(&self.lock()?.conn, &conversation, &content_id, position)?
                .ok_or_else(|| Error::InvalidInput("no such file".into()))?;
            if let Some(path) = row.path.as_ref().filter(|p| Path::new(p).exists()) {
                return Ok(path.clone());
            }
            if row.state == attachment_state::FAILED {
                return Err(Error::InvalidInput(row.error.unwrap_or_else(|| "this file can't be fetched".into())));
            }
            if row.blob_id.is_none() {
                return Err(Error::InvalidInput("this file isn't on the server yet".into()));
            }
            if self.transfers()?.downloading.insert(key.clone()) {
                break row;
            }
            std::thread::sleep(std::time::Duration::from_millis(200));
        };
        let result = self.fetch(&row);
        self.transfers()?.downloading.remove(&key);
        result
    }

    fn fetch(&self, row: &AttachmentRow) -> Result<String> {
        let (Some(blob_id), Some(digest)) = (&row.blob_id, &row.digest) else {
            return Err(Error::InvalidInput("this file isn't on the server yet".into()));
        };
        let size = row.size.max(0) as u64;
        let sealed = self.transit_dir()?.join(format!("in-{}-{}", hex::encode(&row.content_id), row.position));
        let fail = |message: &str| -> Result<String> {
            self.with_tx(|ctx| {
                store::set_attachment_state(
                    ctx.conn,
                    &row.conversation_id,
                    &row.content_id,
                    row.position,
                    attachment_state::FAILED,
                    None,
                    Some(message),
                )
            })?;
            Err(Error::InvalidInput(message.to_string()))
        };
        match self.api.get_blob(blob_id, &sealed, encrypted_len(size)) {
            Ok(()) => {}
            Err(Error::Server { status: 404, .. }) => {
                let _ = std::fs::remove_file(&sealed);
                return fail("This file is no longer on the server.");
            }
            Err(e) => {
                let _ = std::fs::remove_file(&sealed);
                return Err(e);
            }
        }
        let dest = self.media_dir()?.join(local_name(&row.content_id, row.position, &row.file_name, &row.content_type));
        let opened = decrypt_file(&self.crypto, &row.key, &sealed, digest, size, &dest);
        let _ = std::fs::remove_file(&sealed);
        if opened.is_err() {
            return fail("This file was damaged on the way, or isn't the one that was sent.");
        }
        let path = dest.to_string_lossy().into_owned();
        self.with_tx(|ctx| {
            store::set_attachment_state(
                ctx.conn,
                &row.conversation_id,
                &row.content_id,
                row.position,
                attachment_state::DOWNLOADED,
                Some(&path),
                None,
            )
        })?;
        Ok(path)
    }

    fn transfers(&self) -> Result<std::sync::MutexGuard<'_, Transfers>> {
        self.transfers.lock().map_err(|_| Error::Internal("transfer lock".into()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use openmls_libcrux_crypto::CryptoProvider;

    fn round_trip(size: usize) {
        let crypto = CryptoProvider::new().unwrap();
        let dir = tempfile::tempdir().unwrap();
        let plain: Vec<u8> = (0..size).map(|i| (i * 7 + 3) as u8).collect();
        let (input, sealed, output) = (dir.path().join("in"), dir.path().join("sealed"), dir.path().join("out"));
        std::fs::write(&input, &plain).unwrap();
        let key = [9u8; 32];
        let digest = encrypt_file(&crypto, &key, &input, size as u64, &sealed).unwrap();
        assert_eq!(std::fs::metadata(&sealed).unwrap().len(), encrypted_len(size as u64));
        decrypt_file(&crypto, &key, &sealed, &digest, size as u64, &output).unwrap();
        assert_eq!(std::fs::read(&output).unwrap(), plain, "size {size}");
    }

    #[test]
    fn files_of_every_size_come_back() {
        let chunk = CHUNK_BYTES as usize;
        for size in [1, 2, 100, chunk - 1, chunk, chunk + 1, 3 * chunk + 17, 1_000_000] {
            round_trip(size);
        }
    }

    #[test]
    fn the_largest_file_fits_in_a_blob() {
        assert!(encrypted_len(MAX_ATTACHMENT_BYTES) <= MAX_BLOB_BYTES);
        // Padding hides the exact size: these all look the same.
        assert_eq!(encrypted_len(1_000_000), encrypted_len(1_000_100));
    }

    #[test]
    fn tampering_is_caught() {
        let crypto = CryptoProvider::new().unwrap();
        let dir = tempfile::tempdir().unwrap();
        let size = 3 * CHUNK_BYTES as usize + 5;
        let plain = vec![42u8; size];
        let (input, sealed, output) = (dir.path().join("in"), dir.path().join("sealed"), dir.path().join("out"));
        std::fs::write(&input, &plain).unwrap();
        let key = [1u8; 32];
        let digest = encrypt_file(&crypto, &key, &input, size as u64, &sealed).unwrap();
        let good = std::fs::read(&sealed).unwrap();
        let chunk = (CHUNK_BYTES + TAG_BYTES) as usize;

        let attempts: Vec<(&str, Vec<u8>, [u8; 32])> = {
            let mut flipped = good.clone();
            flipped[10] ^= 1;
            let mut swapped = good.clone();
            swapped[..chunk].copy_from_slice(&good[chunk..2 * chunk]);
            swapped[chunk..2 * chunk].copy_from_slice(&good[..chunk]);
            let cut = good[..good.len() - 1].to_vec();
            // With matching digests, so only the encryption can catch them.
            let digest_of = |bytes: &[u8]| -> [u8; 32] { Sha256::digest(bytes).into() };
            vec![
                ("a flipped bit", flipped.clone(), digest_of(&flipped)),
                ("swapped chunks", swapped.clone(), digest_of(&swapped)),
                ("a cut file", cut.clone(), digest_of(&cut)),
                ("the wrong digest", good.clone(), [0u8; 32]),
            ]
        };
        for (what, bytes, digest) in attempts {
            std::fs::write(&sealed, &bytes).unwrap();
            assert!(decrypt_file(&crypto, &key, &sealed, &digest, size as u64, &output).is_err(), "{what}");
            assert!(!output.exists(), "{what} left a file behind");
        }
        // The wrong key.
        std::fs::write(&sealed, &good).unwrap();
        assert!(decrypt_file(&crypto, &[2u8; 32], &sealed, &digest, size as u64, &output).is_err());
        // A last chunk dropped whole, with the size made to match, fails on
        // the last-chunk flag.
        let short = 2 * CHUNK_BYTES as usize;
        let mut dropped = good[..2 * chunk].to_vec();
        dropped.truncate(encrypted_len(short as u64) as usize);
        let dropped_digest: [u8; 32] = Sha256::digest(&dropped).into();
        std::fs::write(&sealed, &dropped).unwrap();
        assert!(decrypt_file(&crypto, &key, &sealed, &dropped_digest, short as u64, &output).is_err());
    }

    #[test]
    fn names_are_cleaned() {
        assert_eq!(clean_file_name("../../etc/passwd"), "passwd");
        assert_eq!(clean_file_name("C:\\Users\\me\\photo.JPG"), "photo.JPG");
        assert_eq!(clean_file_name("a\u{0}b\nc.txt"), "abc.txt");
        assert!(clean_file_name(&"é".repeat(300)).len() <= MAX_FILE_NAME_BYTES);
        assert_eq!(clean_content_type("Image/JPEG"), "image/jpeg");
        assert_eq!(clean_content_type("text/html; charset=<script>"), "application/octet-stream");
        assert_eq!(extension("photo.JPG", "image/jpeg"), "jpg");
        assert_eq!(extension("noext", "video/mp4"), "mp4");
        assert_eq!(extension("weird.<>", "nope/nope"), "bin");
    }
}
