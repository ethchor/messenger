//! MLS group helpers: configuration, creation, delivery ids, member lists.

use messenger_protocol::{
    v1::{group_meta::Kind, GroupMeta, MemberIdentity},
    DELIVERY_ID_LABEL, GROUP_META_EXTENSION_TYPE, MAILBOX_ID_LEN,
};
use openmls::prelude::{
    CredentialType, Extension, ExtensionType, Extensions, GroupContext, GroupId, KeyPackage, KeyPackageIn, LeafNodeIndex, MlsGroup,
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

pub fn group_meta_bytes(kind: Kind, title: &str, colour: &str) -> Vec<u8> {
    GroupMeta { kind: kind as i32, title: title.to_string(), colour: colour.to_string() }.encode_to_vec()
}

/// The group context's extensions: our meta (kind, title, colour), which
/// every member must understand.
pub fn group_extensions(kind: Kind, title: &str, colour: &str) -> Result<Extensions<GroupContext>> {
    Extensions::from_vec(vec![
        Extension::Unknown(GROUP_META_EXTENSION_TYPE, UnknownExtension(group_meta_bytes(kind, title, colour))),
        Extension::RequiredCapabilities(RequiredCapabilitiesExtension::new(
            &[ExtensionType::Unknown(GROUP_META_EXTENSION_TYPE)],
            &[],
            &[CredentialType::Basic],
        )),
    ])
    .map_err(mls)
}

/// Creates a new group with only us in it.
pub fn create_group(
    provider: &Provider<'_>,
    signer: &SignatureKeyPair,
    account_id: &[u8],
    username: &str,
    device: Option<&keys::DeviceCert>,
    kind: Kind,
    title: &str,
    colour: &str,
) -> Result<MlsGroup> {
    let extensions = group_extensions(kind, title, colour)?;
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
        .build(provider, signer, keys::credential(account_id, username, signer, device))
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

/// One leaf of a group: one device of someone in it.
pub struct MemberInfo {
    pub index: LeafNodeIndex,
    pub identity: MemberIdentity,
    pub signature_key: Vec<u8>,
}

impl MemberInfo {
    /// The key this person's safety number is made from.
    pub fn account_key(&self) -> Vec<u8> {
        keys::account_key_of(&self.identity, &self.signature_key)
    }
}

/// Every leaf of a group: each device of each person in it.
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

/// One leaf per person (their first in the tree): a group's people rather
/// than their devices.
pub fn people(group: &MlsGroup) -> Vec<MemberInfo> {
    let mut seen = std::collections::HashSet::new();
    members(group).into_iter().filter(|m| seen.insert(m.identity.account_id.clone())).collect()
}

pub fn member_names(group: &MlsGroup) -> Vec<String> {
    let mut names: Vec<String> = people(group).into_iter().map(|m| m.identity.username).collect();
    names.sort();
    names
}

/// Checks every leaf's credential (see [`keys::member_is_valid`]).
pub fn check_members(group: &MlsGroup) -> Result<()> {
    for member in members(group) {
        if !keys::member_is_valid(&member.identity, &member.signature_key) {
            return Err(Error::Mls(format!("a device of {} has a bad certificate", member.identity.username)));
        }
    }
    Ok(())
}

/// Parses and verifies a key package, and checks it belongs to the directory
/// entry we looked up: same account id and username, and signed either by
/// the account key (the account's first device) or by `device_key`, a linked
/// device the account key vouched for.
pub fn check_key_package(
    crypto: &openmls_libcrux_crypto::CryptoProvider,
    bytes: &[u8],
    account_id: &[u8],
    username: &str,
    identity_key: &[u8],
    device_key: &[u8],
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
    let signature_key = leaf.signature_key().as_slice();
    if signature_key != identity_key && signature_key != device_key {
        return Err(Error::Mls(format!("key package for {username} is not signed by their registered key")));
    }
    let Some(id) = keys::identity_of(leaf.credential()) else {
        return Err(Error::Mls(format!("key package does not name {username}")));
    };
    if id.account_id != account_id || id.username != username {
        return Err(Error::Mls(format!("key package does not name {username}")));
    }
    // A linked device: the certificate must be the account key's.
    let first_device = signature_key == identity_key && id.certificate.is_empty();
    let linked =
        !id.certificate.is_empty() && id.account_key == identity_key && keys::member_is_valid(&id, signature_key);
    if !first_device && !linked {
        return Err(Error::Mls(format!("a device of {username} isn't vouched for by their account key")));
    }
    Ok(key_package)
}
