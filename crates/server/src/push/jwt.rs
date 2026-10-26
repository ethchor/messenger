//! ES256 JSON Web Tokens, the kind APNs (provider tokens) and Web Push
//! (VAPID) expect.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use p256::ecdsa::{signature::Signer, Signature, SigningKey};

/// `header.claims.signature`, each part base64url without padding. The
/// signature is the raw 64-byte `r || s` form JWS uses.
pub fn sign_es256(key: &SigningKey, header: &serde_json::Value, claims: &serde_json::Value) -> String {
    let header = URL_SAFE_NO_PAD.encode(header.to_string());
    let claims = URL_SAFE_NO_PAD.encode(claims.to_string());
    let signing_input = format!("{header}.{claims}");
    let signature: Signature = key.sign(signing_input.as_bytes());
    format!("{signing_input}.{}", URL_SAFE_NO_PAD.encode(signature.to_bytes()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use p256::ecdsa::{signature::Verifier, VerifyingKey};
    use serde_json::json;

    #[test]
    fn token_verifies_with_the_public_key() {
        let key = SigningKey::from_slice(&[7u8; 32]).unwrap();
        let token = sign_es256(&key, &json!({"alg": "ES256"}), &json!({"iss": "team"}));
        let parts: Vec<&str> = token.split('.').collect();
        assert_eq!(parts.len(), 3);
        assert_eq!(URL_SAFE_NO_PAD.decode(parts[0]).unwrap(), br#"{"alg":"ES256"}"#);
        let signature = Signature::from_slice(&URL_SAFE_NO_PAD.decode(parts[2]).unwrap()).unwrap();
        let input = format!("{}.{}", parts[0], parts[1]);
        VerifyingKey::from(&key).verify(input.as_bytes(), &signature).unwrap();
    }
}
