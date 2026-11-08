//! Identity, recovery key and key package helpers.

use ed25519_dalek::{Signer as _, SigningKey};
use hkdf::Hkdf;
use messenger_protocol::{v1::MemberIdentity, GROUP_META_EXTENSION_TYPE};
use openmls::prelude::{
    BasicCredential, Capabilities, Ciphersuite, Credential, CredentialType, CredentialWithKey, ExtensionType,
    KeyPackage, Lifetime,
};
use openmls_basic_credential::SignatureKeyPair;
use openmls_traits::{signatures::Signer, OpenMlsProvider};
use prost::Message;
use sha2::Sha256;
use tls_codec::Serialize as _;

use crate::{error::mls, Error, Result};

pub const CIPHERSUITE: Ciphersuite = Ciphersuite::MLS_256_XWING_CHACHA20POLY1305_SHA256_Ed25519;

/// Key packages kept on the server; topped up when fewer remain.
pub const KEY_PACKAGE_TARGET: usize = 20;
pub const KEY_PACKAGE_LOW_WATER: u32 = 8;
/// Key packages expire after four weeks; last-resort ones are refreshed too.
const KEY_PACKAGE_LIFETIME_SECS: u64 = 28 * 24 * 3600;

pub fn random_bytes<const N: usize>() -> Result<[u8; N]> {
    let mut buf = [0u8; N];
    getrandom::fill(&mut buf).map_err(|e| Error::Internal(format!("random: {e}")))?;
    Ok(buf)
}

// ---- recovery key ----

const BASE32: &[u8; 32] = b"abcdefghijklmnopqrstuvwxyz234567";
const RECOVERY_SECRET_LEN: usize = 40;

/// The account recovery key: 40 random bytes shown to the user once as 64
/// base32 characters. It never stays on the device; an Ed25519 key derived
/// from it is registered with the server and can move the account to a new
/// device.
pub struct RecoveryKey {
    secret: [u8; RECOVERY_SECRET_LEN],
}

impl RecoveryKey {
    pub fn generate() -> Result<Self> {
        Ok(Self { secret: random_bytes()? })
    }

    /// Parses the key as typed by the user; spaces, dashes and case are ignored.
    pub fn parse(text: &str) -> Result<Self> {
        let chars: Vec<u8> =
            text.bytes().filter(|b| !b.is_ascii_whitespace() && *b != b'-').map(|b| b.to_ascii_lowercase()).collect();
        if chars.len() != RECOVERY_SECRET_LEN * 8 / 5 {
            return Err(Error::BadRecoveryKey);
        }
        let mut bits: u64 = 0;
        let mut nbits = 0;
        let mut out = Vec::with_capacity(RECOVERY_SECRET_LEN);
        for c in chars {
            let v = BASE32.iter().position(|&x| x == c).ok_or(Error::BadRecoveryKey)? as u64;
            bits = (bits << 5) | v;
            nbits += 5;
            if nbits >= 8 {
                nbits -= 8;
                out.push((bits >> nbits) as u8);
                bits &= (1 << nbits) - 1;
            }
        }
        let secret = out.try_into().map_err(|_| Error::BadRecoveryKey)?;
        Ok(Self { secret })
    }

    /// 64 characters in 16 groups of 4, e.g. `abcd efgh ...`.
    pub fn display(&self) -> String {
        let mut text = String::with_capacity(80);
        let mut bits: u64 = 0;
        let mut nbits = 0;
        let mut count = 0;
        for &byte in &self.secret {
            bits = (bits << 8) | u64::from(byte);
            nbits += 8;
            while nbits >= 5 {
                nbits -= 5;
                if count > 0 && count % 4 == 0 {
                    text.push(' ');
                }
                text.push(BASE32[((bits >> nbits) & 31) as usize] as char);
                bits &= (1 << nbits) - 1;
                count += 1;
            }
        }
        text
    }

    pub fn signing_key(&self) -> SigningKey {
        let hk = Hkdf::<Sha256>::new(Some(b"messenger recovery v1"), &self.secret);
        let mut seed = [0u8; 32];
        hk.expand(b"recovery signing key", &mut seed).expect("32 bytes is a valid HKDF length");
        SigningKey::from_bytes(&seed)
    }

    pub fn public_key(&self) -> Vec<u8> {
        self.signing_key().verifying_key().to_bytes().to_vec()
    }

    pub fn sign(&self, message: &[u8]) -> Vec<u8> {
        self.signing_key().sign(message).to_bytes().to_vec()
    }
}

// ---- identity ----

pub fn new_signer() -> Result<SignatureKeyPair> {
    SignatureKeyPair::new(CIPHERSUITE.signature_algorithm()).map_err(mls)
}

pub fn sign(signer: &SignatureKeyPair, message: &[u8]) -> Result<Vec<u8>> {
    signer.sign(message).map_err(mls)
}

/// What makes a linked device one of its account's (docs/LINKED-DEVICES.md):
/// the account key's certificate for the device's own key. An account's
/// first device has none: its key is the account key.
#[derive(Debug, Clone, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct DeviceCert {
    pub device_id: Vec<u8>,
    pub account_key: Vec<u8>,
    pub intro_inbox: Vec<u8>,
    pub certificate: Vec<u8>,
}

pub fn credential(
    account_id: &[u8],
    username: &str,
    signer: &SignatureKeyPair,
    device: Option<&DeviceCert>,
) -> CredentialWithKey {
    let device = device.cloned().unwrap_or_default();
    let identity = MemberIdentity {
        account_id: account_id.to_vec(),
        username: username.to_string(),
        device_id: device.device_id,
        account_key: device.account_key,
        intro_inbox: device.intro_inbox,
        certificate: device.certificate,
    };
    CredentialWithKey {
        credential: BasicCredential::new(identity.encode_to_vec()).into(),
        signature_key: signer.public().into(),
    }
}

/// Whether a member's credential holds together: a linked device's
/// certificate must be its account key's signature over its own key. A member
/// without one claims to be an account's first device, signing with the
/// account key itself (checked against the directory by whoever adds it).
pub fn member_is_valid(identity: &MemberIdentity, signature_key: &[u8]) -> bool {
    if identity.certificate.is_empty() {
        return identity.device_id.is_empty() && identity.account_key.is_empty();
    }
    let payload = messenger_protocol::auth::device_certificate_payload(
        &identity.account_id,
        &identity.device_id,
        signature_key,
        &identity.intro_inbox,
    );
    verify(&identity.account_key, &payload, &identity.certificate)
}

/// The key a member's safety number is made from: its account key.
pub fn account_key_of(identity: &MemberIdentity, signature_key: &[u8]) -> Vec<u8> {
    if identity.account_key.is_empty() {
        signature_key.to_vec()
    } else {
        identity.account_key.clone()
    }
}

/// Checks an Ed25519 signature made with a 32-byte public key.
pub fn verify(public_key: &[u8], message: &[u8], signature: &[u8]) -> bool {
    use ed25519_dalek::{Signature, VerifyingKey};
    let Ok(key) = <[u8; 32]>::try_from(public_key) else { return false };
    let Ok(key) = VerifyingKey::from_bytes(&key) else { return false };
    let Ok(signature) = Signature::from_slice(signature) else { return false };
    key.verify_strict(message, &signature).is_ok()
}

/// Reads the identity out of a member's credential.
pub fn identity_of(credential: &Credential) -> Option<MemberIdentity> {
    let basic = BasicCredential::try_from(credential.clone()).ok()?;
    MemberIdentity::decode(basic.identity()).ok()
}

/// What every leaf advertises: only our ciphersuite and credential type, plus
/// the group metadata extension and the last-resort key package extension
/// (OpenMLS adds the latter to last-resort key packages but not to the
/// capabilities, and validation requires both).
pub fn capabilities() -> Capabilities {
    Capabilities::new(
        None,
        Some(&[CIPHERSUITE]),
        Some(&[ExtensionType::Unknown(GROUP_META_EXTENSION_TYPE), ExtensionType::LastResort]),
        None,
        Some(&[CredentialType::Basic]),
    )
}

/// Serialized key packages, plus a last-resort one when asked for.
pub type KeyPackageBatch = (Vec<Vec<u8>>, Option<Vec<u8>>);

/// Makes `count` key packages (and optionally a last-resort one); their
/// private keys go into the provider's storage.
pub fn generate_key_packages<P: OpenMlsProvider>(
    provider: &P,
    signer: &SignatureKeyPair,
    credential: &CredentialWithKey,
    count: usize,
    last_resort: bool,
) -> Result<KeyPackageBatch> {
    let build = |last_resort: bool| -> Result<Vec<u8>> {
        let mut builder = KeyPackage::builder()
            .leaf_node_capabilities(capabilities())
            .key_package_lifetime(Lifetime::new(KEY_PACKAGE_LIFETIME_SECS));
        if last_resort {
            builder = builder.mark_as_last_resort();
        }
        let bundle = builder.build(CIPHERSUITE, provider, signer, credential.clone()).map_err(mls)?;
        bundle.key_package().tls_serialize_detached().map_err(mls)
    };
    let regular = (0..count).map(|_| build(false)).collect::<Result<Vec<_>>>()?;
    let last = if last_resort { Some(build(true)?) } else { None };
    Ok((regular, last))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recovery_key_round_trip() {
        let key = RecoveryKey::generate().unwrap();
        let text = key.display();
        assert_eq!(text.replace(' ', "").len(), 64);
        assert_eq!(text.split(' ').count(), 16);
        let parsed = RecoveryKey::parse(&text.to_uppercase().replace(' ', "-")).unwrap();
        assert_eq!(parsed.secret, key.secret);
        assert_eq!(parsed.public_key(), key.public_key());
    }

    #[test]
    fn recovery_key_rejects_garbage() {
        assert!(RecoveryKey::parse("abcd").is_err());
        assert!(RecoveryKey::parse(&"1".repeat(64)).is_err());
    }
}
