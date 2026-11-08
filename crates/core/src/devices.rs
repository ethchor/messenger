//! Linked devices (docs/LINKED-DEVICES.md): one account on several devices.
//!
//! Linking goes like this:
//! 1. The new device makes its own key and a one-time HPKE key, and shows
//!    both in a QR code ([`Client::begin_link`]).
//! 2. A device already signed in scans it and approves ([`Client::approve_link`]):
//!    the account key certifies the new device's key, and the account
//!    details are sealed to the one-time key and left on the server.
//! 3. The new device collects them ([`Client::finish_link`]), registers
//!    itself with the account key, and uploads key packages.
//! 4. On its next sync the approving device adds the new device to every
//!    chat ([`Client::add_linked_devices`]). People starting chats from now on
//!    bring in all of the account's devices themselves.

use std::sync::Arc;

use messenger_protocol::{
    api::{DeviceInfo, LinkBundle, RegisterDeviceRequest},
    auth::device_certificate_payload,
    b64, DEVICE_ID_LEN, LINK_ID_LEN,
};
use openmls_basic_credential::SignatureKeyPair;
use openmls_traits::{crypto::OpenMlsCrypto, types::HpkeCiphertext};
use serde::{Deserialize, Serialize};
use tls_codec::{DeserializeBytes, Serialize as _};

use crate::{
    api::RequestSigner,
    client::{meta_key, Account, Client, Op},
    error::{mls, Error, Result},
    keys::{self, RecoveryKey, CIPHERSUITE},
    provider::Provider,
    store,
    sync::HistoryRef,
};

/// What a link QR code starts with.
const LINK_PREFIX: &str = "messenger-link:";
/// HPKE info for a link bundle.
const LINK_INFO: &[u8] = b"messenger link v1";
/// How long an approved device has to register before we stop waiting for it.
const PENDING_DEVICE_TTL_MS: i64 = 24 * 3600 * 1000;
/// Longest device name.
const MAX_NAME_CHARS: usize = 64;

/// What the new device's QR code carries.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct LinkCode {
    server: String,
    #[serde(with = "b64")]
    link_id: Vec<u8>,
    /// The one-time key the bundle is sealed to.
    #[serde(with = "b64")]
    hpke_public: Vec<u8>,
    #[serde(with = "b64")]
    device_id: Vec<u8>,
    #[serde(with = "b64")]
    device_key: Vec<u8>,
    #[serde(with = "b64")]
    intro_inbox: Vec<u8>,
    name: String,
}

/// A link the new device is waiting on, kept until it's done.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct PendingLink {
    code: LinkCode,
    #[serde(with = "b64")]
    hpke_private: Vec<u8>,
    /// The opened bundle, kept as soon as it's collected: the server hands
    /// it out only once.
    bundle: Option<Bundle>,
}

/// What the approving device seals for the new one.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct Bundle {
    #[serde(with = "b64")]
    account_id: Vec<u8>,
    username: String,
    /// The account key pair, TLS-serialized.
    #[serde(with = "b64")]
    account_key: Vec<u8>,
    #[serde(with = "b64")]
    account_public: Vec<u8>,
    #[serde(with = "b64")]
    certificate: Vec<u8>,
    /// Our chats so far, unless the new device starts fresh.
    #[serde(default)]
    history: Option<HistoryRef>,
}

/// A device we approved that still has to be added to our chats.
#[derive(Debug, Clone, Serialize, Deserialize)]
struct PendingDevice {
    #[serde(with = "b64")]
    device_id: Vec<u8>,
    since_ms: i64,
}

/// One of the account's devices, for the device list in settings.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkedDevice {
    /// Hex; what [`Client::remove_device`] takes.
    pub device_id: String,
    pub name: String,
    pub added_at_ms: u64,
    pub last_seen_ms: u64,
    pub this_device: bool,
}

fn parse_code(text: &str) -> Result<LinkCode> {
    let bad = || Error::InvalidInput("that isn't a link code".into());
    let encoded = text.trim().strip_prefix(LINK_PREFIX).ok_or_else(bad)?;
    let code: LinkCode = serde_json::from_slice(&b64::decode(encoded).map_err(|_| bad())?).map_err(|_| bad())?;
    if code.link_id.len() != LINK_ID_LEN || code.device_id.len() != DEVICE_ID_LEN || code.device_key.len() != 32 {
        return Err(bad());
    }
    Ok(code)
}

fn same_server(a: &str, b: &str) -> bool {
    a.trim_end_matches('/').eq_ignore_ascii_case(b.trim_end_matches('/'))
}

fn key_pair_bytes(signer: &SignatureKeyPair) -> Result<Vec<u8>> {
    signer.tls_serialize_detached().map_err(mls)
}

fn key_pair_from(bytes: &[u8]) -> Result<SignatureKeyPair> {
    SignatureKeyPair::tls_deserialize_exact_bytes(bytes).map_err(mls)
}

impl Client {
    // ---- on the new device ----

    /// Starts linking this device to an account signed in elsewhere. Returns
    /// the text for the QR code the other device scans. Calling it again
    /// starts over with a new code.
    pub fn begin_link(&self, device_name: &str) -> Result<String> {
        let name: String = device_name.trim().chars().take(MAX_NAME_CHARS).collect();
        let state = self.lock()?;
        if let Some(account) = &state.account {
            return Err(Error::AlreadyRegistered(account.username.clone()));
        }
        let conn = &state.conn;
        let signer = keys::new_signer()?;
        let provider = Provider::new(&self.crypto, conn);
        signer.store(openmls_traits::OpenMlsProvider::storage(&provider)).map_err(mls)?;
        let seed = keys::random_bytes::<32>()?;
        let hpke = self.crypto.derive_hpke_keypair(CIPHERSUITE.hpke_config(), &seed).map_err(mls)?;
        let code = LinkCode {
            server: self.api.base_url().to_string(),
            link_id: keys::random_bytes::<LINK_ID_LEN>()?.to_vec(),
            hpke_public: hpke.public,
            device_id: keys::random_bytes::<DEVICE_ID_LEN>()?.to_vec(),
            device_key: signer.public().to_vec(),
            intro_inbox: keys::random_bytes::<32>()?.to_vec(),
            name,
        };
        let pending = PendingLink { code: code.clone(), hpke_private: hpke.private.to_vec(), bundle: None };
        store::meta_set(conn, meta_key::IDENTITY_KEY, signer.public())?;
        store::meta_set(conn, meta_key::INTRO_INBOX, &code.intro_inbox)?;
        store::meta_set(conn, meta_key::PENDING_LINK, &serde_json::to_vec(&pending)?)?;
        // A half-done registration on this device no longer applies.
        conn.execute(
            "DELETE FROM meta WHERE key IN (?1, ?2)",
            [meta_key::PENDING_USERNAME, meta_key::PENDING_RECOVERY],
        )?;
        Ok(format!("{LINK_PREFIX}{}", b64::encode(serde_json::to_vec(&code)?)))
    }

    /// Finishes linking once the other device has approved. `Ok(false)`
    /// means not yet: ask again in a moment. `Ok(true)` means this device is
    /// signed in; its chats arrive with the next syncs.
    pub fn finish_link(&self) -> Result<bool> {
        let mut pending: PendingLink = {
            let state = self.lock()?;
            if state.account.is_some() {
                return Ok(true);
            }
            let json = store::meta_get(&state.conn, meta_key::PENDING_LINK)?
                .ok_or_else(|| Error::InvalidInput("no link in progress".into()))?;
            serde_json::from_slice(&json)?
        };
        let code = pending.code.clone();
        let bundle = match pending.bundle.clone() {
            Some(bundle) => bundle,
            None => {
                let Some(sealed) = self.api.take_link(&code.link_id)? else {
                    return Ok(false);
                };
                let bundle = self.open_bundle(&pending, &sealed)?;
                pending.bundle = Some(bundle.clone());
                let state = self.lock()?;
                store::meta_set(&state.conn, meta_key::PENDING_LINK, &serde_json::to_vec(&pending)?)?;
                bundle
            }
        };

        // The account must be the one the directory knows by that name.
        let entry = self.api.directory(&bundle.username)?;
        if entry.account_id != bundle.account_id || entry.identity_key != bundle.account_public {
            return Err(Error::InvalidInput("the link came from an account the server doesn't know".into()));
        }
        let payload =
            device_certificate_payload(&bundle.account_id, &code.device_id, &code.device_key, &code.intro_inbox);
        if !keys::verify(&bundle.account_public, &payload, &bundle.certificate) {
            return Err(Error::InvalidInput("the link's certificate isn't the account's".into()));
        }

        let account_key = key_pair_from(&bundle.account_key)?;
        if account_key.public() != bundle.account_public {
            return Err(Error::InvalidInput("the link's account key doesn't match".into()));
        }
        let sign = |payload: &[u8]| keys::sign(&account_key, payload);
        let signer = RequestSigner { account_id: &bundle.account_id, device_id: &[], sign: &sign };
        let request = RegisterDeviceRequest {
            device_id: code.device_id.clone(),
            device_key: code.device_key.clone(),
            certificate: bundle.certificate.clone(),
            intro_inbox: code.intro_inbox.clone(),
            name: code.name.clone(),
        };
        match self.api.register_device(&request, &signer) {
            Ok(_) => {}
            // We registered before and stopped before saving that.
            Err(Error::Server { status: 409, .. })
                if self.api.devices(&signer)?.devices.iter().any(|d| d.device_id == code.device_id) => {}
            Err(e) => return Err(e),
        }

        let device = keys::DeviceCert {
            device_id: code.device_id.clone(),
            account_key: bundle.account_public.clone(),
            intro_inbox: code.intro_inbox.clone(),
            certificate: bundle.certificate.clone(),
        };
        {
            let mut state = self.lock()?;
            let provider = Provider::new(&self.crypto, &state.conn);
            let device_signer = SignatureKeyPair::read(
                openmls_traits::OpenMlsProvider::storage(&provider),
                &code.device_key,
                CIPHERSUITE.signature_algorithm(),
            )
            .ok_or_else(|| Error::Storage("device key pair missing".into()))?;
            let tx = state.conn.transaction()?;
            store::meta_set(&tx, meta_key::ACCOUNT_ID, &bundle.account_id)?;
            store::meta_set(&tx, meta_key::USERNAME, bundle.username.as_bytes())?;
            store::meta_set(&tx, meta_key::SERVER_URL, self.api.base_url().as_bytes())?;
            store::meta_set(&tx, meta_key::DEVICE_CERT, &serde_json::to_vec(&device)?)?;
            store::meta_set(&tx, meta_key::ACCOUNT_KEY, &bundle.account_key)?;
            tx.execute("DELETE FROM meta WHERE key = ?1", [meta_key::PENDING_LINK])?;
            tx.commit()?;
            state.account = Some(Account {
                account_id: bundle.account_id,
                username: bundle.username,
                intro_inbox: code.intro_inbox,
                device: Some(device),
            });
            state.signer = Some(Arc::new(device_signer));
        }
        if let Err(e) = self.top_up_key_packages(true) {
            log::warn!("key package upload failed, will retry on sync: {e}");
        }
        // The chats so far. Without them this device simply starts fresh.
        if let Some(history) = &bundle.history {
            if let Err(e) = self.import_history(history) {
                log::warn!("couldn't bring the chat history across: {e}");
            }
        }
        Ok(true)
    }

    fn open_bundle(&self, pending: &PendingLink, sealed: &LinkBundle) -> Result<Bundle> {
        let bad = || Error::InvalidInput("the link couldn't be opened".into());
        let ciphertext = HpkeCiphertext::tls_deserialize_exact_bytes(&sealed.sealed).map_err(|_| bad())?;
        let json = self
            .crypto
            .hpke_open(CIPHERSUITE.hpke_config(), &ciphertext, &pending.hpke_private, LINK_INFO, &pending.code.link_id)
            .map_err(|_| bad())?;
        serde_json::from_slice(&json).map_err(|_| bad())
    }

    // ---- on a device already signed in ----

    /// The name the new device gave itself, to show before approving.
    pub fn link_request_name(&self, code: &str) -> Result<String> {
        Ok(parse_code(code)?.name)
    }

    /// Approves the device that showed `code`: certifies it and leaves it
    /// what it needs to sign in, with our chat history unless `with_history`
    /// is false. It's added to our chats once it has signed in.
    pub fn approve_link(&self, code: &str, with_history: bool) -> Result<()> {
        let code = parse_code(code)?;
        if !same_server(&code.server, self.api.base_url()) {
            return Err(Error::InvalidInput("that device uses another server".into()));
        }
        let (account, signer) = self.identity()?;
        let account_key = self.account_signer()?;
        let payload =
            device_certificate_payload(&account.account_id, &code.device_id, &code.device_key, &code.intro_inbox);
        let bundle = Bundle {
            account_id: account.account_id.clone(),
            username: account.username.clone(),
            account_key: key_pair_bytes(&account_key)?,
            account_public: account_key.public().to_vec(),
            certificate: keys::sign(&account_key, &payload)?,
            history: if with_history { Some(self.upload_history()?) } else { None },
        };
        let ciphertext = self
            .crypto
            .hpke_seal(
                CIPHERSUITE.hpke_config(),
                &code.hpke_public,
                LINK_INFO,
                &code.link_id,
                &serde_json::to_vec(&bundle)?,
            )
            .map_err(mls)?;
        let sealed = ciphertext.tls_serialize_detached().map_err(mls)?;
        let sign = |payload: &[u8]| keys::sign(&signer, payload);
        let request_signer =
            RequestSigner { account_id: &account.account_id, device_id: account.device_id(), sign: &sign };
        self.api.put_link(&code.link_id, &LinkBundle { sealed }, &request_signer)?;
        self.with_tx(|ctx| {
            let mut pending = pending_devices(ctx.conn)?;
            if !pending.iter().any(|d| d.device_id == code.device_id) {
                pending.push(PendingDevice { device_id: code.device_id.clone(), since_ms: store::now_ms() });
            }
            set_pending_devices(ctx.conn, &pending)
        })
    }

    /// The account key: our own key on the first device, the copy we were
    /// given on a linked one.
    fn account_signer(&self) -> Result<SignatureKeyPair> {
        let state = self.lock()?;
        if let Some(bytes) = store::meta_get(&state.conn, meta_key::ACCOUNT_KEY)? {
            return key_pair_from(&bytes);
        }
        let signer = state.signer.as_deref().ok_or(Error::NotRegistered)?;
        key_pair_from(&key_pair_bytes(signer)?)
    }

    /// Adds devices we approved to every chat, once they've registered and
    /// have key packages. Run on each sync.
    pub(crate) fn add_linked_devices(&self) -> Result<()> {
        let pending = pending_devices(&self.lock()?.conn)?;
        if pending.is_empty() {
            return Ok(());
        }
        let (account, _) = self.identity()?;
        // Our devices' own group, for keeping them in step; the new device
        // is added to it with the chats.
        self.ensure_device_group()?;
        let devices = self.api.directory_devices(&account.username)?.devices;
        let now = store::now_ms();
        let mut still = Vec::new();
        for waiting in pending {
            let Some(device) = devices.iter().find(|d| d.device_id == waiting.device_id) else {
                if now - waiting.since_ms < PENDING_DEVICE_TTL_MS {
                    still.push(waiting);
                }
                continue;
            };
            if !self.add_device_to_chats(&account, device)? {
                still.push(waiting);
            }
        }
        self.with_tx(|ctx| set_pending_devices(ctx.conn, &still))
    }

    /// Queues adding `device` to each of our chats. False if it has no key
    /// packages yet (it's tried again on the next sync).
    fn add_device_to_chats(&self, account: &Account, device: &DeviceInfo) -> Result<bool> {
        let conversations = store::conversations(&self.lock()?.conn)?;
        let mut invitees = Vec::new();
        let mut complete = true;
        for conv in conversations.into_iter().filter(|c| c.active) {
            match self.device_invitee(&account.username, device) {
                Ok(invitee) => invitees.push((conv.id, invitee)),
                // The rest next time; chats it's already in are skipped then.
                Err(Error::NoKeyPackages(_)) => {
                    complete = false;
                    break;
                }
                Err(e) => return Err(e),
            }
        }
        self.with_tx(|ctx| {
            for (conversation_id, invitee) in invitees {
                let op = Op::Add { members: vec![invitee] };
                store::insert_op(ctx.conn, &conversation_id, &serde_json::to_string(&op)?)?;
            }
            Ok(complete)
        })
    }

    /// The account's devices.
    pub fn devices(&self) -> Result<Vec<LinkedDevice>> {
        let (account, signer) = self.identity()?;
        let sign = |payload: &[u8]| keys::sign(&signer, payload);
        let request_signer =
            RequestSigner { account_id: &account.account_id, device_id: account.device_id(), sign: &sign };
        let list = self.api.devices(&request_signer)?;
        Ok(list
            .devices
            .into_iter()
            .map(|d| LinkedDevice {
                this_device: d.device_key == signer.public(),
                device_id: hex::encode(&d.device_id),
                name: d.name,
                added_at_ms: d.added_at_ms.max(0) as u64,
                last_seen_ms: d.last_seen_ms.max(0) as u64,
            })
            .collect())
    }

    /// Removes another of our devices: it can't sign in any more and is
    /// taken out of every chat. (To remove this device, sign out of it.)
    pub fn remove_device(&self, device_id: &str) -> Result<()> {
        let device_id = hex::decode(device_id).map_err(|_| Error::InvalidInput("bad device id".into()))?;
        let (account, signer) = self.identity()?;
        let sign = |payload: &[u8]| keys::sign(&signer, payload);
        let request_signer =
            RequestSigner { account_id: &account.account_id, device_id: account.device_id(), sign: &sign };
        let device = self
            .api
            .devices(&request_signer)?
            .devices
            .into_iter()
            .find(|d| d.device_id == device_id)
            .ok_or_else(|| Error::InvalidInput("no such device".into()))?;
        if device.device_key == signer.public() {
            return Err(Error::InvalidInput("sign out to remove this device".into()));
        }
        self.api.remove_device(&device_id, &request_signer)?;
        self.with_tx(|ctx| {
            let pending: Vec<_> = pending_devices(ctx.conn)?.into_iter().filter(|d| d.device_id != device_id).collect();
            set_pending_devices(ctx.conn, &pending)?;
            let op = serde_json::to_string(&Op::RemoveDevices { device_keys: vec![device.device_key.clone()] })?;
            for conv in store::conversations(ctx.conn)?.into_iter().filter(|c| c.active) {
                store::insert_op(ctx.conn, &conv.id, &op)?;
            }
            Ok(())
        })?;
        self.settle();
        Ok(())
    }
}

impl Client {
    /// For a lost or stolen device: gives the account a new account key with
    /// the recovery key, on this device. Every other device is signed out
    /// and taken out of every chat; this device leaves its chats too, which
    /// are kept here as ended chats. Starting a chat with someone again
    /// carries on from the old one. Contacts see a new safety number, as
    /// after recovery.
    pub fn reset_identity(&self, recovery_key: &str) -> Result<crate::AccountInfo> {
        let recovery = RecoveryKey::parse(recovery_key)?;
        let (account, _) = self.identity()?;
        let signer = keys::new_signer()?;
        let intro_inbox = keys::random_bytes::<32>()?.to_vec();
        // The server goes first: with the wrong recovery key nothing changes.
        // From here on only this device can sign as the account.
        let account_id = self.recover_request(&account.username, &recovery, &signer, &intro_inbox)?;

        // Still with the old keys: leave every chat, which takes every one of
        // the account's devices out (a stolen one with them). Posting needs
        // no signature, so this works although the server no longer knows
        // the old keys.
        self.with_tx(|ctx| {
            for conv in store::conversations(ctx.conn)?.into_iter().filter(|c| c.active) {
                crate::client::queue_leave(ctx, &conv)?;
            }
            Ok(())
        })?;
        for _ in 0..2 {
            if let Err(e) = self.sync(0) {
                log::info!("couldn't post leaving every chat: {e}");
                break;
            }
        }

        let mut state = self.lock()?;
        let provider = Provider::new(&self.crypto, &state.conn);
        signer.store(openmls_traits::OpenMlsProvider::storage(&provider)).map_err(mls)?;
        let tx = state.conn.transaction()?;
        for conv in store::conversations(&tx)?.into_iter().filter(|c| c.active) {
            store::set_conversation_active(&tx, &conv.id, false)?;
            store::delete_conversation_wake_tokens(&tx, &conv.id)?;
            store::delete_conversation_locations(&tx, &conv.id)?;
        }
        // Whatever was still to go out belongs to the old identity.
        tx.execute("DELETE FROM outbox", [])?;
        tx.execute("DELETE FROM ops", [])?;
        store::meta_set(&tx, meta_key::IDENTITY_KEY, signer.public())?;
        store::meta_set(&tx, meta_key::INTRO_INBOX, &intro_inbox)?;
        store::meta_set(&tx, meta_key::ACCOUNT_ID, &account_id)?;
        tx.execute(
            "DELETE FROM meta WHERE key IN (?1, ?2, ?3, ?4, ?5, ?6)",
            [
                // A new invitation inbox, read from its start.
                meta_key::INTRO_CURSOR,
                meta_key::DEVICE_CERT,
                meta_key::ACCOUNT_KEY,
                meta_key::DEVICES_TO_ADD,
                meta_key::WAKE_TOKEN,
                meta_key::PENDING_LINK,
            ],
        )?;
        tx.commit()?;
        state.account = Some(Account { account_id, username: account.username.clone(), intro_inbox, device: None });
        state.signer = Some(Arc::new(signer));
        drop(state);
        if let Err(e) = self.top_up_key_packages(true) {
            log::warn!("key package upload failed, will retry on sync: {e}");
        }
        Ok(self.account()?.expect("just reset"))
    }
}

fn pending_devices(conn: &rusqlite::Connection) -> Result<Vec<PendingDevice>> {
    match store::meta_get(conn, meta_key::DEVICES_TO_ADD)? {
        Some(json) => Ok(serde_json::from_slice(&json)?),
        None => Ok(Vec::new()),
    }
}

fn set_pending_devices(conn: &rusqlite::Connection, pending: &[PendingDevice]) -> Result<()> {
    if pending.is_empty() {
        conn.execute("DELETE FROM meta WHERE key = ?1", [meta_key::DEVICES_TO_ADD])?;
        return Ok(());
    }
    store::meta_set(conn, meta_key::DEVICES_TO_ADD, &serde_json::to_vec(pending)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn link_codes_round_trip_and_reject_junk() {
        let code = LinkCode {
            server: "https://example.org".into(),
            link_id: vec![1; LINK_ID_LEN],
            hpke_public: vec![2; 32],
            device_id: vec![3; DEVICE_ID_LEN],
            device_key: vec![4; 32],
            intro_inbox: vec![5; 32],
            name: "Mac".into(),
        };
        let text = format!("{LINK_PREFIX}{}", b64::encode(serde_json::to_vec(&code).unwrap()));
        assert_eq!(parse_code(&text).unwrap().name, "Mac");
        assert!(parse_code("hello").is_err());
        assert!(parse_code(&format!("{LINK_PREFIX}bm9wZQ")).is_err());
        assert!(same_server("https://Example.org/", "https://example.org"));
    }
}
