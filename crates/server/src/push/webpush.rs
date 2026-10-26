//! Web Push: message encryption (RFC 8291, with the `aes128gcm` content
//! coding of RFC 8188) and VAPID authorization (RFC 8292). UnifiedPush
//! distributors on Android speak it.
//!
//! The messages carry no content anyway; encrypting them keeps the push
//! service from adding anything to the timing it already sees.

use aes_gcm::{aead::Aead, Aes128Gcm, KeyInit, Nonce};
use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use hkdf::Hkdf;
use p256::{ecdh, ecdsa::SigningKey, elliptic_curve::sec1::ToEncodedPoint, PublicKey, SecretKey};
use reqwest::Url;
use serde_json::json;
use sha2::Sha256;

use super::jwt;

/// Record size written into the header. Our messages are far smaller.
const RECORD_SIZE: u32 = 4096;
/// How long a VAPID token is valid (RFC 8292 allows at most 24 hours).
const VAPID_LIFETIME_S: u64 = 12 * 3600;

#[derive(Debug, thiserror::Error)]
pub enum EncryptError {
    #[error("the subscription's public key is not a P-256 point")]
    BadPublicKey,
    #[error("the subscription's auth secret must be 16 bytes")]
    BadAuthSecret,
    #[error("message too long for one record")]
    TooLong,
    #[error("no randomness")]
    Random,
}

fn random<const N: usize>() -> Result<[u8; N], EncryptError> {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).map_err(|_| EncryptError::Random)?;
    Ok(bytes)
}

/// A fresh P-256 secret key.
pub(crate) fn random_secret_key() -> Option<SecretKey> {
    // A random 32-byte string is a valid scalar with overwhelming probability.
    for _ in 0..8 {
        let bytes: [u8; 32] = random().ok()?;
        if let Ok(key) = SecretKey::from_slice(&bytes) {
            return Some(key);
        }
    }
    None
}

/// Encrypts `plaintext` for a subscription's keys, with a fresh sender key
/// and salt. Returns the request body.
pub fn encrypt(ua_public: &[u8], auth_secret: &[u8], plaintext: &[u8]) -> Result<Vec<u8>, EncryptError> {
    let sender = random_secret_key().ok_or(EncryptError::Random)?;
    encrypt_with(ua_public, auth_secret, plaintext, &sender, random()?)
}

fn encrypt_with(
    ua_public: &[u8],
    auth_secret: &[u8],
    plaintext: &[u8],
    sender: &SecretKey,
    salt: [u8; 16],
) -> Result<Vec<u8>, EncryptError> {
    let ua_key = PublicKey::from_sec1_bytes(ua_public).map_err(|_| EncryptError::BadPublicKey)?;
    let ua_public = ua_key.to_encoded_point(false);
    if auth_secret.len() != 16 {
        return Err(EncryptError::BadAuthSecret);
    }
    // One record: content, the 0x02 last-record delimiter, and the GCM tag.
    if plaintext.len() + 1 + 16 > RECORD_SIZE as usize {
        return Err(EncryptError::TooLong);
    }
    let as_public = sender.public_key().to_encoded_point(false);
    let shared = ecdh::diffie_hellman(sender.to_nonzero_scalar(), ua_key.as_affine());

    // RFC 8291 section 3.4: combine the ECDH secret with the auth secret.
    let mut key_info = b"WebPush: info\0".to_vec();
    key_info.extend_from_slice(ua_public.as_bytes());
    key_info.extend_from_slice(as_public.as_bytes());
    let mut ikm = [0u8; 32];
    Hkdf::<Sha256>::new(Some(auth_secret), shared.raw_secret_bytes())
        .expand(&key_info, &mut ikm)
        .map_err(|_| EncryptError::TooLong)?;

    // RFC 8188: content encryption key and nonce from the salt.
    let prk = Hkdf::<Sha256>::new(Some(&salt), &ikm);
    let mut cek = [0u8; 16];
    let mut nonce = [0u8; 12];
    prk.expand(b"Content-Encoding: aes128gcm\0", &mut cek).map_err(|_| EncryptError::TooLong)?;
    prk.expand(b"Content-Encoding: nonce\0", &mut nonce).map_err(|_| EncryptError::TooLong)?;

    let mut padded = plaintext.to_vec();
    padded.push(0x02);
    let cipher = Aes128Gcm::new_from_slice(&cek).map_err(|_| EncryptError::TooLong)?;
    let ciphertext = cipher.encrypt(Nonce::from_slice(&nonce), padded.as_slice()).map_err(|_| EncryptError::TooLong)?;

    // Header: salt, record size, key id (the sender's public key).
    let mut body = Vec::with_capacity(16 + 4 + 1 + 65 + ciphertext.len());
    body.extend_from_slice(&salt);
    body.extend_from_slice(&RECORD_SIZE.to_be_bytes());
    body.push(as_public.as_bytes().len() as u8);
    body.extend_from_slice(as_public.as_bytes());
    body.extend_from_slice(&ciphertext);
    Ok(body)
}

/// The server's VAPID key: it signs a short-lived token on every push, so
/// push services can tell our pushes apart and the distributor can check
/// they come from the server the app registered with.
pub struct VapidKey {
    signing: SigningKey,
    public: Vec<u8>,
}

impl VapidKey {
    pub fn from_secret(secret: &[u8]) -> Option<Self> {
        let secret = SecretKey::from_slice(secret).ok()?;
        let public = secret.public_key().to_encoded_point(false).as_bytes().to_vec();
        Some(Self { signing: SigningKey::from(secret), public })
    }

    /// A new key and its secret, to store.
    pub fn generate() -> Option<(Self, Vec<u8>)> {
        let secret = random_secret_key()?.to_bytes().to_vec();
        Some((Self::from_secret(&secret)?, secret))
    }

    /// Uncompressed P-256 point, as apps pass it when registering.
    pub fn public_key(&self) -> &[u8] {
        &self.public
    }

    /// The `Authorization` header for a push to `endpoint`.
    pub fn authorization(&self, endpoint: &Url, contact: &str, now_s: u64) -> String {
        let audience = endpoint.origin().ascii_serialization();
        let token = jwt::sign_es256(
            &self.signing,
            &json!({"typ": "JWT", "alg": "ES256"}),
            &json!({"aud": audience, "exp": now_s + VAPID_LIFETIME_S, "sub": contact}),
        );
        format!("vapid t={token}, k={}", URL_SAFE_NO_PAD.encode(&self.public))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::{signature::Verifier, Signature, VerifyingKey};

    fn b64(text: &str) -> Vec<u8> {
        URL_SAFE_NO_PAD.decode(text).unwrap()
    }

    /// RFC 8291 appendix A.
    #[test]
    fn matches_the_rfc_example() {
        let sender = SecretKey::from_slice(&b64("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw")).unwrap();
        let ua_public = b64("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        let auth = b64("BTBZMqHH6r4Tts7J_aSIgg");
        let salt: [u8; 16] = b64("DGv6ra1nlYgDCS1FRnbzlw").try_into().unwrap();
        let body =
            encrypt_with(&ua_public, &auth, b"When I grow up, I want to be a watermelon", &sender, salt).unwrap();
        assert_eq!(
            URL_SAFE_NO_PAD.encode(body),
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27ml\
             mlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPT\
             pK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN"
        );
    }

    #[test]
    fn refuses_bad_subscription_keys() {
        assert!(matches!(encrypt(&[4; 65], &[0; 16], b"x"), Err(EncryptError::BadPublicKey)));
        let ua = random_secret_key().unwrap().public_key().to_encoded_point(false).as_bytes().to_vec();
        assert!(matches!(encrypt(&ua, &[0; 15], b"x"), Err(EncryptError::BadAuthSecret)));
        assert!(matches!(encrypt(&ua, &[0; 16], &[0; 4096]), Err(EncryptError::TooLong)));
        assert!(encrypt(&ua, &[0; 16], b"wake").is_ok());
    }

    #[test]
    fn vapid_header_names_the_push_service_origin() {
        let (key, secret) = VapidKey::generate().unwrap();
        assert_eq!(VapidKey::from_secret(&secret).unwrap().public_key(), key.public_key());
        let endpoint = Url::parse("https://ntfy.example:8443/upAbC?up=1").unwrap();
        let header = key.authorization(&endpoint, "mailto:admin@example.org", 1_000);
        let (token, k) = header.strip_prefix("vapid t=").unwrap().split_once(", k=").unwrap();
        assert_eq!(b64(k), key.public_key());
        let parts: Vec<&str> = token.split('.').collect();
        let claims: serde_json::Value = serde_json::from_slice(&b64(parts[1])).unwrap();
        assert_eq!(claims["aud"], "https://ntfy.example:8443");
        assert_eq!(claims["exp"], 1_000 + VAPID_LIFETIME_S);
        assert_eq!(claims["sub"], "mailto:admin@example.org");
        let verifying = VerifyingKey::from_sec1_bytes(key.public_key()).unwrap();
        let signature = Signature::from_slice(&b64(parts[2])).unwrap();
        verifying.verify(format!("{}.{}", parts[0], parts[1]).as_bytes(), &signature).unwrap();
    }
}
