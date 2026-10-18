//! MLS group helpers: configuration, creation, delivery ids, member lists.

use messenger_protocol::{
    v1::{group_meta::Kind, GroupMeta, MemberIdentity},
    DELIVERY_ID_LABEL, GROUP_META_EXTENSION_TYPE, MAILBOX_ID_LEN,
};
use openmls::prelude::{
    CredentialType, Extension, ExtensionType, Extensions, GroupId, KeyPackage, KeyPackageIn, LeafNodeIndex, MlsGroup,
    MlsGroupJoinConfig, ProtocolVersion, RequiredCapabilitiesExtension, UnknownExtension,
    PURE_CIPHERTEXT_WIRE_FORMAT_POLICY,
};
use openmls_basic_credential::SignatureKeyPair;
use openmls_traits::OpenMlsProvider;
use prost::Message;
use tls_codec::DeserializeBytes;

use crate::{
    error::mls,
    keys::{self, CIPHERSUITE},
    provider::Provider,
    Error, Result,
};

/// OpenMLS pads every encrypted message to a multiple of this many bytes, on
/// top of the Padmé padding applied to message content.
const PADDING_SIZE: usize = 64;

/// Settings for groups we join. Commits and application messages are always
/// encrypted (PrivateMessage), so the server can't see membership changes.
pub fn join_config() -> MlsGroupJoinConfig {
    MlsGroupJoinConfig::builder()
        .wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
        .use_ratchet_tree_extension(true)
        .padding_size(PADDING_SIZE)
        // No past-epoch secrets: the server's ordering rule means nothing
        // from an old epoch is ever delivered after a commit.
        .max_past_epochs(0)
        .build()
}

pub fn group_meta_bytes(kind: Kind, title: &str) -> Vec<u8> {
    GroupMeta { kind: kind as i32, title: title.to_string() }.encode_to_vec()
}

/// Creates a new group with only us in it.
pub fn create_group(
    provider: &Provider<'_>,
    signer: &SignatureKeyPair,
    account_id: &[u8],
    username: &str,
    kind: Kind,
    title: &str,
) -> Result<MlsGroup> {
    let extensions = Extensions::from_vec(vec![
        Extension::Unknown(GROUP_META_EXTENSION_TYPE, UnknownExtension(group_meta_bytes(kind, title))),
        Extension::RequiredCapabilities(RequiredCapabilitiesExtension::new(
            &[ExtensionType::Unknown(GROUP_META_EXTENSION_TYPE)],
            &[],
            &[CredentialType::Basic],
        )),
    ])
    .map_err(mls)?;
    let group_id = GroupId::from_slice(&keys::random_bytes::<16>()?);
    MlsGroup::builder()
        .with_group_id(group_id)
        .ciphersuite(CIPHERSUITE)
        .with_wire_format_policy(PURE_CIPHERTEXT_WIRE_FORMAT_POLICY)
        .use_ratchet_tree_extension(true)
        .padding_size(PADDING_SIZE)
        .max_past_epochs(0)
        .with_capabilities(keys::capabilities())
        .with_group_context_extensions(extensions)
        .build(provider, signer, keys::credential(account_id, username, signer))
        .map_err(mls)
}

pub fn load_group(provider: &Provider<'_>, id: &[u8]) -> Result<MlsGroup> {
    MlsGroup::load(provider.storage(), &GroupId::from_slice(id))
        .map_err(mls)?
        .ok_or_else(|| Error::Internal("group state missing".into()))
}

/// The mailbox for the group's current epoch. Every member derives the same
/// id from the MLS exporter; the server can't link one epoch's id to the next.
pub fn delivery_id(provider: &Provider<'_>, group: &MlsGroup) -> Result<Vec<u8>> {
    group.export_secret(provider.crypto(), DELIVERY_ID_LABEL, &[], MAILBOX_ID_LEN).map_err(mls)
}

pub fn group_meta(group: &MlsGroup) -> GroupMeta {
    group
        .extensions()
        .unknown(GROUP_META_EXTENSION_TYPE)
        .and_then(|ext| GroupMeta::decode(ext.0.as_slice()).ok())
        .unwrap_or_default()
}

pub struct MemberInfo {
    pub index: LeafNodeIndex,
    pub identity: MemberIdentity,
    pub signature_key: Vec<u8>,
}

pub fn members(group: &MlsGroup) -> Vec<MemberInfo> {
    group
        .members()
        .filter_map(|m| {
            keys::identity_of(&m.credential).map(|identity| MemberInfo {
                index: m.index,
                identity,
                signature_key: m.signature_key,
            })
        })
        .collect()
}

pub fn member_names(group: &MlsGroup) -> Vec<String> {
    let mut names: Vec<String> = members(group).into_iter().map(|m| m.identity.username).collect();
    names.sort();
    names
}

/// Parses and verifies a key package, and checks it belongs to the directory
/// entry we looked up (same identity key, account id and username).
pub fn check_key_package(
    crypto: &openmls_libcrux_crypto::CryptoProvider,
    bytes: &[u8],
    account_id: &[u8],
    username: &str,
    identity_key: &[u8],
) -> Result<KeyPackage> {
    let (key_package_in, rest) =
        KeyPackageIn::tls_deserialize_bytes(bytes).map_err(|_| Error::Mls("malformed key package".into()))?;
    if !rest.is_empty() {
        return Err(Error::Mls("trailing bytes after key package".into()));
    }
    let key_package = key_package_in.validate(crypto, ProtocolVersion::Mls10).map_err(mls)?;
    if key_package.ciphersuite() != CIPHERSUITE {
        return Err(Error::Mls("key package uses another ciphersuite".into()));
    }
    let leaf = key_package.leaf_node();
    if leaf.signature_key().as_slice() != identity_key {
        return Err(Error::Mls(format!("key package for {username} is not signed by their registered key")));
    }
    match keys::identity_of(leaf.credential()) {
        Some(id) if id.account_id == account_id && id.username == username => Ok(key_package),
        _ => Err(Error::Mls(format!("key package does not name {username}"))),
    }
}
