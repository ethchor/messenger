//! The device endpoints over HTTP, with real signatures: registering a
//! linked device, signing as it, listing, the device limit, link bundles,
//! and removing a device.

use ed25519_dalek::{Signer, SigningKey};
use messenger_protocol::{
    api::{header, DeviceList, LinkBundle, RegisterDeviceRequest, RegisterRequest, RegisterResponse},
    auth::{device_certificate_payload, register_payload, request_payload},
    b64,
};
use reqwest::{Method, StatusCode};

fn key() -> SigningKey {
    let mut seed = [0u8; 32];
    getrandom::fill(&mut seed).unwrap();
    SigningKey::from_bytes(&seed)
}

fn random<const N: usize>() -> Vec<u8> {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).unwrap();
    bytes.to_vec()
}

fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64
}

struct Server {
    url: String,
    http: reqwest::Client,
    _dir: tempfile::TempDir,
}

async fn start() -> Server {
    let dir = tempfile::tempdir().unwrap();
    let config = messenger_server::Config {
        db_path: dir.path().join("server.db"),
        retention: std::time::Duration::from_secs(3600),
        push: Default::default(),
        blobs: Default::default(),
    };
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let url = format!("http://{}", listener.local_addr().unwrap());
    tokio::spawn(messenger_server::serve(listener, config, std::future::pending()));
    // reqwest brings rustls without a crypto provider; the server uses ring.
    let _ = rustls::crypto::ring::default_provider().install_default();
    Server { url, http: reqwest::Client::new(), _dir: dir }
}

impl Server {
    /// A signed request, by `key`; as `device` when given.
    async fn signed(
        &self,
        method: Method,
        path: &str,
        account: &[u8],
        key: &SigningKey,
        device: Option<&[u8]>,
        body: Option<String>,
    ) -> reqwest::Response {
        let body = body.unwrap_or_default();
        let timestamp = now_ms();
        let nonce = random::<16>();
        let payload = request_payload(method.as_str(), path, account, timestamp, &nonce, body.as_bytes());
        let mut request = self
            .http
            .request(method, format!("{}{path}", self.url))
            .header(header::ACCOUNT, b64::encode(account))
            .header(header::TIMESTAMP, timestamp.to_string())
            .header(header::NONCE, b64::encode(&nonce))
            .header(header::SIGNATURE, b64::encode(key.sign(&payload).to_bytes()))
            .header("content-type", "application/json")
            .body(body);
        if let Some(device) = device {
            request = request.header(header::DEVICE, b64::encode(device));
        }
        request.send().await.unwrap()
    }

    async fn register(&self, username: &str, account_key: &SigningKey) -> Vec<u8> {
        let recovery = key();
        let intro_inbox = random::<32>();
        let timestamp = now_ms();
        let identity = account_key.verifying_key().to_bytes().to_vec();
        let recovery_public = recovery.verifying_key().to_bytes().to_vec();
        let payload = register_payload(username, &identity, &recovery_public, &intro_inbox, timestamp);
        let request = RegisterRequest {
            username: username.into(),
            identity_key: identity,
            recovery_key: recovery_public,
            intro_inbox,
            timestamp_ms: timestamp,
            identity_signature: account_key.sign(&payload).to_bytes().to_vec(),
            recovery_signature: recovery.sign(&payload).to_bytes().to_vec(),
        };
        let response = self.http.post(format!("{}/v1/accounts", self.url)).json(&request).send().await.unwrap();
        assert_eq!(response.status(), StatusCode::OK, "{}", response.text().await.unwrap());
        response.json::<RegisterResponse>().await.unwrap().account_id
    }
}

/// A new device's registration, vouched for by the account key.
fn new_device(account: &[u8], account_key: &SigningKey, name: &str) -> (Vec<u8>, SigningKey, String) {
    let device_id = random::<16>();
    let device_key = key();
    let intro_inbox = random::<32>();
    let public = device_key.verifying_key().to_bytes().to_vec();
    let certificate = account_key.sign(&device_certificate_payload(account, &device_id, &public, &intro_inbox));
    let request = RegisterDeviceRequest {
        device_id: device_id.clone(),
        device_key: public,
        certificate: certificate.to_bytes().to_vec(),
        intro_inbox,
        name: name.into(),
    };
    (device_id, device_key, serde_json::to_string(&request).unwrap())
}

#[tokio::test]
async fn linking_signing_as_and_removing_a_device() {
    let server = start().await;
    let account_key = key();
    let account = server.register("alice", &account_key).await;

    // One device to start with: the one whose key is the account key.
    let list: DeviceList =
        server.signed(Method::GET, "/v1/devices", &account, &account_key, None, None).await.json().await.unwrap();
    assert_eq!(list.devices.len(), 1);
    assert_eq!(list.devices[0].device_key, account_key.verifying_key().to_bytes().to_vec());

    // A linked device registers with the account key and its certificate.
    let (mac_id, mac_key, mac) = new_device(&account, &account_key, "Vimu's MacBook");
    let response = server.signed(Method::POST, "/v1/devices", &account, &account_key, None, Some(mac)).await;
    assert_eq!(response.status(), StatusCode::OK);
    let list: DeviceList = response.json().await.unwrap();
    assert_eq!(list.devices.len(), 2);
    assert!(list.devices.iter().any(|d| d.name == "Vimu's MacBook"));

    // A certificate the account key didn't make is refused.
    let (_, _, forged) = new_device(&account, &key(), "Not mine");
    let response = server.signed(Method::POST, "/v1/devices", &account, &account_key, None, Some(forged)).await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    // The new device signs as itself; another key can't sign as it.
    let response = server.signed(Method::GET, "/v1/devices", &account, &mac_key, Some(&mac_id), None).await;
    assert_eq!(response.status(), StatusCode::OK);
    let response = server.signed(Method::GET, "/v1/devices", &account, &key(), Some(&mac_id), None).await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);

    // Others see the devices without names.
    let public: DeviceList = server
        .http
        .get(format!("{}/v1/directory/alice/devices", server.url))
        .send()
        .await
        .unwrap()
        .json()
        .await
        .unwrap();
    assert_eq!(public.devices.len(), 2);
    assert!(public.devices.iter().all(|d| d.name.is_empty() && d.last_seen_ms == 0));

    // Five devices at most.
    for n in 0..3 {
        let (_, _, body) = new_device(&account, &account_key, &format!("tablet {n}"));
        let response = server.signed(Method::POST, "/v1/devices", &account, &account_key, None, Some(body)).await;
        assert_eq!(response.status(), StatusCode::OK);
    }
    let (_, _, sixth) = new_device(&account, &account_key, "one too many");
    let response = server.signed(Method::POST, "/v1/devices", &account, &account_key, None, Some(sixth)).await;
    assert_eq!(response.status(), StatusCode::CONFLICT);

    // The phone removes the Mac; the Mac can't sign any more.
    let path = format!("/v1/devices/{}", b64::encode(&mac_id));
    let response = server.signed(Method::DELETE, &path, &account, &account_key, None, None).await;
    assert_eq!(response.json::<DeviceList>().await.unwrap().devices.len(), 4);
    let response = server.signed(Method::GET, "/v1/devices", &account, &mac_key, Some(&mac_id), None).await;
    assert_eq!(response.status(), StatusCode::UNAUTHORIZED);
}

#[tokio::test]
async fn a_link_bundle_is_left_once_and_fetched_once() {
    let server = start().await;
    let account_key = key();
    let account = server.register("alice", &account_key).await;
    let link = b64::encode(random::<16>());
    let get = || server.http.get(format!("{}/v1/links/{link}", server.url)).send();

    // The new device asks before the bundle is there.
    assert_eq!(get().await.unwrap().status(), StatusCode::NOT_FOUND);
    let bundle = serde_json::to_string(&LinkBundle { sealed: b"sealed for the new device".to_vec() }).unwrap();
    let path = format!("/v1/links/{link}");
    let response = server.signed(Method::PUT, &path, &account, &account_key, None, Some(bundle.clone())).await;
    assert_eq!(response.status(), StatusCode::OK);
    // Not twice under one id.
    let response = server.signed(Method::PUT, &path, &account, &account_key, None, Some(bundle)).await;
    assert_eq!(response.status(), StatusCode::CONFLICT);

    let got: LinkBundle = get().await.unwrap().json().await.unwrap();
    assert_eq!(got.sealed, b"sealed for the new device");
    assert_eq!(get().await.unwrap().status(), StatusCode::NOT_FOUND);
}
