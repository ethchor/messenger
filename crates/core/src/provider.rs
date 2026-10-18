//! OpenMLS provider over one SQLite connection.
//!
//! OpenMLS writes group state through the storage provider. Building the
//! provider on the connection of an open transaction puts those writes in the
//! same transaction as the app's own rows (outbox, messages, cursors), so a
//! crash leaves either all of them or none.

use openmls_libcrux_crypto::CryptoProvider;
use openmls_sqlite_storage::{Codec, SqliteStorageProvider};
use openmls_traits::OpenMlsProvider;
use rusqlite::Connection;

#[derive(Default)]
pub struct JsonCodec;

impl Codec for JsonCodec {
    type Error = serde_json::Error;

    fn to_vec<T: serde::Serialize>(value: &T) -> Result<Vec<u8>, Self::Error> {
        serde_json::to_vec(value)
    }

    fn from_slice<T: serde::de::DeserializeOwned>(slice: &[u8]) -> Result<T, Self::Error> {
        serde_json::from_slice(slice)
    }
}

pub type Storage<'a> = SqliteStorageProvider<JsonCodec, &'a Connection>;

pub struct Provider<'a> {
    crypto: &'a CryptoProvider,
    storage: Storage<'a>,
}

impl<'a> Provider<'a> {
    pub fn new(crypto: &'a CryptoProvider, conn: &'a Connection) -> Self {
        Self { crypto, storage: SqliteStorageProvider::new(conn) }
    }

    pub fn crypto_ref(&self) -> &'a CryptoProvider {
        self.crypto
    }
}

impl<'a> OpenMlsProvider for Provider<'a> {
    type CryptoProvider = CryptoProvider;
    type RandProvider = CryptoProvider;
    type StorageProvider = Storage<'a>;

    fn storage(&self) -> &Self::StorageProvider {
        &self.storage
    }

    fn crypto(&self) -> &Self::CryptoProvider {
        self.crypto
    }

    fn rand(&self) -> &Self::RandProvider {
        self.crypto
    }
}

/// Creates or upgrades the OpenMLS tables.
pub fn migrate(conn: &mut Connection) -> crate::Result<()> {
    SqliteStorageProvider::<JsonCodec, &mut Connection>::new(conn)
        .run_migrations()
        .map_err(|e| crate::Error::Storage(format!("openmls migrations: {e}")))
}
