//! Blocking HTTP client for the mailbox server.

use std::time::Duration;

use messenger_protocol::{
    api::{
        error_code, header, ConflictResponse, DirectoryEntry, Empty, ErrorBody, FetchRequest, FetchResponse,
        KeyPackageCount, KeyPackageResponse, NewRecoveryKeyRequest, PostMessage, PostResponse, PushInfo,
        PushRegistered, PushRegistration, RecoverRequest, RegisterRequest, RegisterResponse, UploadKeyPackagesRequest,
    },
    auth::request_payload,
    b64,
};
use serde::{de::DeserializeOwned, Serialize};

use crate::{Error, Result};

/// Largest response body accepted (a full fetch page plus base64 overhead).
const MAX_RESPONSE_BYTES: u64 = 32 * 1024 * 1024;

pub enum PostResult {
    Accepted(u64),
    /// The mailbox has a commit we haven't processed (409).
    Conflict,
}

/// Signs requests with the device key.
pub struct RequestSigner<'a> {
    pub account_id: &'a [u8],
    pub sign: &'a dyn Fn(&[u8]) -> Result<Vec<u8>>,
}

pub struct Api {
    agent: ureq::Agent,
    base: String,
}

impl Api {
    pub fn new(base_url: &str) -> Self {
        // Timeouts per step rather than one for the whole call: with a
        // timeout covering name lookup, ureq does every lookup on a thread
        // of its own, which the apps' higher-priority threads then wait on
        // (Xcode flags it as a hang risk). The system resolver has its own
        // timeouts.
        let config = ureq::Agent::config_builder()
            .http_status_as_error(false)
            .timeout_connect(Some(Duration::from_secs(15)))
            .timeout_send_request(Some(Duration::from_secs(30)))
            .timeout_send_body(Some(Duration::from_secs(30)))
            // Long enough for a long-poll (the server holds it up to 30 s)
            // plus slow networks.
            .timeout_recv_response(Some(Duration::from_secs(75)))
            .timeout_recv_body(Some(Duration::from_secs(60)))
            .proxy(None)
            .user_agent(concat!("messenger-core/", env!("CARGO_PKG_VERSION")))
            .build();
        Self { agent: config.into(), base: base_url.trim_end_matches('/').to_string() }
    }

    pub fn base_url(&self) -> &str {
        &self.base
    }

    /// Sends a request and returns the status and raw body.
    fn request(
        &self,
        method: &str,
        path: &str,
        body: Option<Vec<u8>>,
        headers: &[(&str, String)],
    ) -> Result<(u16, Vec<u8>)> {
        let url = format!("{}{}", self.base, path);
        let result = match method {
            "GET" => {
                let mut req = self.agent.get(&url);
                for (k, v) in headers {
                    req = req.header(*k, v);
                }
                req.call()
            }
            "DELETE" => {
                let mut req = self.agent.delete(&url);
                for (k, v) in headers {
                    req = req.header(*k, v);
                }
                req.call()
            }
            "PUT" => {
                let mut req = self.agent.put(&url).header("content-type", "application/json");
                for (k, v) in headers {
                    req = req.header(*k, v);
                }
                req.send(body.unwrap_or_default())
            }
            _ => {
                let mut req = self.agent.post(&url).header("content-type", "application/json");
                for (k, v) in headers {
                    req = req.header(*k, v);
                }
                req.send(body.unwrap_or_default())
            }
        };
        let mut response = result.map_err(|e| Error::Network(e.to_string()))?;
        let status = response.status().as_u16();
        let bytes = response
            .body_mut()
            .with_config()
            .limit(MAX_RESPONSE_BYTES)
            .read_to_vec()
            .map_err(|e| Error::Network(e.to_string()))?;
        Ok((status, bytes))
    }

    fn json<T: DeserializeOwned>(&self, method: &str, path: &str, body: Option<&impl Serialize>) -> Result<T> {
        let body = body.map(serde_json::to_vec).transpose()?;
        let (status, bytes) = self.request(method, path, body, &[])?;
        decode(status, &bytes)
    }

    fn signed<T: DeserializeOwned>(
        &self,
        method: &str,
        path: &str,
        body: Option<&impl Serialize>,
        signer: &RequestSigner<'_>,
    ) -> Result<T> {
        let body = body.map(serde_json::to_vec).transpose()?.unwrap_or_default();
        let timestamp_ms = crate::store::now_ms() as u64;
        let nonce = crate::keys::random_bytes::<16>()?;
        let payload = request_payload(method, path, signer.account_id, timestamp_ms, &nonce, &body);
        let signature = (signer.sign)(&payload)?;
        let headers = [
            (header::ACCOUNT, b64::encode(signer.account_id)),
            (header::TIMESTAMP, timestamp_ms.to_string()),
            (header::NONCE, b64::encode(nonce)),
            (header::SIGNATURE, b64::encode(signature)),
        ];
        let (status, bytes) = self.request(method, path, Some(body), &headers)?;
        decode(status, &bytes)
    }

    pub fn register(&self, req: &RegisterRequest) -> Result<RegisterResponse> {
        self.json("POST", "/v1/accounts", Some(req)).map_err(|e| match e {
            Error::Server { code, .. } if code == error_code::USERNAME_TAKEN => Error::UsernameTaken,
            e => e,
        })
    }

    pub fn recover(&self, req: &RecoverRequest) -> Result<RegisterResponse> {
        self.json("POST", "/v1/accounts/recover", Some(req)).map_err(|e| match e {
            Error::Server { status: 401, .. } => Error::BadRecoveryKey,
            e => e,
        })
    }

    pub fn directory(&self, username: &str) -> Result<DirectoryEntry> {
        self.json::<DirectoryEntry>("GET", &format!("/v1/directory/{username}"), None::<&()>).map_err(|e| match e {
            Error::Server { status: 404, .. } => Error::UserNotFound(username.to_string()),
            e => e,
        })
    }

    pub fn claim_key_package(&self, username: &str) -> Result<Vec<u8>> {
        self.json::<KeyPackageResponse>("POST", &format!("/v1/directory/{username}/key-package"), None::<&()>)
            .map(|r| r.key_package)
            .map_err(|e| match e {
                Error::Server { code, .. } if code == error_code::NO_KEY_PACKAGES => {
                    Error::NoKeyPackages(username.to_string())
                }
                Error::Server { status: 404, .. } => Error::UserNotFound(username.to_string()),
                e => e,
            })
    }

    pub fn upload_key_packages(
        &self,
        req: &UploadKeyPackagesRequest,
        signer: &RequestSigner<'_>,
    ) -> Result<KeyPackageCount> {
        self.signed("POST", "/v1/key-packages", Some(req), signer)
    }

    pub fn key_package_count(&self, signer: &RequestSigner<'_>) -> Result<KeyPackageCount> {
        self.signed("GET", "/v1/key-packages/count", None::<&()>, signer)
    }

    pub fn delete_key_packages(&self, signer: &RequestSigner<'_>) -> Result<()> {
        self.signed::<KeyPackageCount>("DELETE", "/v1/key-packages", None::<&()>, signer).map(|_| ())
    }

    pub fn delete_account(&self, signer: &RequestSigner<'_>) -> Result<()> {
        self.signed::<Empty>("DELETE", "/v1/accounts/me", None::<&()>, signer).map(|_| ())
    }

    pub fn new_recovery_key(&self, req: &NewRecoveryKeyRequest, signer: &RequestSigner<'_>) -> Result<()> {
        self.signed::<Empty>("POST", "/v1/accounts/me/recovery-key", Some(req), signer).map(|_| ())
    }

    pub fn push_info(&self) -> Result<PushInfo> {
        self.json("GET", "/v1/push", None::<&()>)
    }

    pub fn register_push(&self, req: &PushRegistration, signer: &RequestSigner<'_>) -> Result<PushRegistered> {
        self.signed("PUT", "/v1/push", Some(req), signer)
    }

    pub fn delete_push(&self, signer: &RequestSigner<'_>) -> Result<()> {
        self.signed::<Empty>("DELETE", "/v1/push", None::<&()>, signer).map(|_| ())
    }

    pub fn post(&self, mailbox: &[u8], msg: &PostMessage) -> Result<PostResult> {
        let path = format!("/v1/mailboxes/{}/messages", b64::encode(mailbox));
        let (status, bytes) = self.request("POST", &path, Some(serde_json::to_vec(msg)?), &[])?;
        if status == 409 {
            let conflict: ConflictResponse = serde_json::from_slice(&bytes).map_err(|e| Error::Server {
                status,
                code: "bad_response".into(),
                message: e.to_string(),
            })?;
            log::debug!("post refused; latest commit is {:?}", conflict.latest_commit_seq);
            return Ok(PostResult::Conflict);
        }
        decode::<PostResponse>(status, &bytes).map(|r| PostResult::Accepted(r.seq))
    }

    pub fn fetch(&self, req: &FetchRequest) -> Result<FetchResponse> {
        self.json("POST", "/v1/fetch", Some(req))
    }
}

fn decode<T: DeserializeOwned>(status: u16, bytes: &[u8]) -> Result<T> {
    if (200..300).contains(&status) {
        return serde_json::from_slice(bytes).map_err(|e| Error::Server {
            status,
            code: "bad_response".into(),
            message: e.to_string(),
        });
    }
    let body = serde_json::from_slice::<ErrorBody>(bytes).unwrap_or_else(|_| ErrorBody {
        error: "http".into(),
        message: String::from_utf8_lossy(bytes).chars().take(200).collect(),
    });
    Err(Error::Server { status, code: body.error, message: body.message })
}
