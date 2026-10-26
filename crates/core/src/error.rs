/// Errors the apps can show or act on.
#[derive(Debug, Clone, thiserror::Error)]
pub enum Error {
    #[error("this device has no account yet")]
    NotRegistered,
    #[error("this device is already registered as {0}")]
    AlreadyRegistered(String),
    #[error("that username is taken")]
    UsernameTaken,
    #[error("no user named {0}")]
    UserNotFound(String),
    #[error("{0} can't be added right now: they have no invitation keys left. Ask them to open the app.")]
    NoKeyPackages(String),
    #[error("{0}")]
    InvalidInput(String),
    #[error("conversation not found")]
    ConversationNotFound,
    #[error("you are no longer a member of this conversation")]
    NotAMember,
    #[error("recovery key is not valid")]
    BadRecoveryKey,
    #[error("can't reach the server: {0}")]
    Network(String),
    #[error("server error {status} ({code}): {message}")]
    Server { status: u16, code: String, message: String },
    #[error("storage error: {0}")]
    Storage(String),
    #[error("encryption error: {0}")]
    Mls(String),
    #[error("injected fault at {0}")]
    InjectedFault(String),
    #[error("internal error: {0}")]
    Internal(String),
}

pub type Result<T, E = Error> = std::result::Result<T, E>;

impl From<rusqlite::Error> for Error {
    fn from(e: rusqlite::Error) -> Self {
        Error::Storage(e.to_string())
    }
}

impl From<serde_json::Error> for Error {
    fn from(e: serde_json::Error) -> Self {
        Error::Internal(format!("json: {e}"))
    }
}

/// Wraps any OpenMLS error (they are generic over the storage error type).
pub(crate) fn mls<E: std::fmt::Debug>(e: E) -> Error {
    Error::Mls(format!("{e:?}"))
}
