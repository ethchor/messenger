//! Checks uploaded MLS key packages before storing them, so a key package
//! handed out for an account is valid, uses our ciphersuite and is bound to
//! that account's registered identity key.

use messenger_protocol::{v1::MemberIdentity, CIPHERSUITE_ID};
use openmls::prelude::{BasicCredential, KeyPackageIn, ProtocolVersion};
use openmls_traits::types::Ciphersuite;
use prost::Message;
use tls_codec::DeserializeBytes;

use crate::db::Account;

#[derive(Debug, thiserror::Error)]
pub enum KeyPackageError {
    #[error("not a TLS-encoded key package")]
    Encoding,
    #[error("signature or structure check failed: {0}")]
    Invalid(String),
    #[error("ciphersuite must be 0x{CIPHERSUITE_ID:04X} (X-Wing)")]
    Ciphersuite,
    #[error("leaf signature key is not the account's identity key")]
    WrongKey,
    #[error("credential does not name this account")]
    WrongIdentity,
    #[error("last-resort flag does not match the upload slot")]
    LastResortMismatch,
}

pub fn validate(
    crypto: &openmls_libcrux_crypto::CryptoProvider,
    account: &Account,
    data: &[u8],
    expect_last_resort: bool,
) -> Result<(), KeyPackageError> {
    let (key_package_in, rest) =
        KeyPackageIn::tls_deserialize_bytes(data).map_err(|_| KeyPackageError::Encoding)?;
    if !rest.is_empty() {
        return Err(KeyPackageError::Encoding);
    }
    let key_package = key_package_in
        .validate(crypto, ProtocolVersion::Mls10)
        .map_err(|e| KeyPackageError::Invalid(e.to_string()))?;
    if key_package.ciphersuite() != Ciphersuite::MLS_256_XWING_CHACHA20POLY1305_SHA256_Ed25519 {
        return Err(KeyPackageError::Ciphersuite);
    }
    let leaf = key_package.leaf_node();
    if leaf.signature_key().as_slice() != account.identity_key.as_slice() {
        return Err(KeyPackageError::WrongKey);
    }
    let credential = BasicCredential::try_from(leaf.credential().clone()).map_err(|_| KeyPackageError::WrongIdentity)?;
    let identity = MemberIdentity::decode(credential.identity()).map_err(|_| KeyPackageError::WrongIdentity)?;
    if identity.account_id != account.account_id || identity.username != account.username {
        return Err(KeyPackageError::WrongIdentity);
    }
    if key_package.last_resort() != expect_last_resort {
        return Err(KeyPackageError::LastResortMismatch);
    }
    Ok(())
}
