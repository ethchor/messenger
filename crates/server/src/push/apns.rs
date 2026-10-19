//! Apple Push Notification service, with token-based (.p8 key) auth over
//! HTTP/2.
//!
//! The push is an alert ("Messenger: New message") with `mutable-content`,
//! so the app's notification extension can fetch, decrypt and replace it.
//! It names nothing: no sender, no conversation.

use std::{
    sync::Mutex,
    time::{Duration, Instant},
};

use p256::{ecdsa::SigningKey, pkcs8::DecodePrivateKey};
use serde_json::json;

use super::{jwt, ApnsConfig, Outcome};

/// Apple wants provider tokens refreshed at most every 20 minutes and at
/// least every hour.
const TOKEN_LIFETIME: Duration = Duration::from_secs(40 * 60);
/// How long Apple keeps trying to deliver to a device that's offline.
const EXPIRY: Duration = Duration::from_secs(24 * 3600);

pub struct Apns {
    key: SigningKey,
    key_id: String,
    team_id: String,
    topic: String,
    token: Mutex<Option<(String, Instant)>>,
}

impl Apns {
    pub fn new(config: &ApnsConfig) -> anyhow::Result<Self> {
        let key = SigningKey::from_pkcs8_pem(&config.key_pem)
            .map_err(|e| anyhow::anyhow!("the APNs key is not a .p8 (PKCS#8 P-256) key: {e}"))?;
        Ok(Self {
            key,
            key_id: config.key_id.clone(),
            team_id: config.team_id.clone(),
            topic: config.topic.clone(),
            token: Mutex::new(None),
        })
    }

    fn provider_token(&self) -> String {
        let mut cached = self.token.lock().unwrap_or_else(|e| e.into_inner());
        if let Some((token, made)) = cached.as_ref() {
            if made.elapsed() < TOKEN_LIFETIME {
                return token.clone();
            }
        }
        let token = jwt::sign_es256(
            &self.key,
            &json!({"alg": "ES256", "kid": self.key_id}),
            &json!({"iss": self.team_id, "iat": super::now_s()}),
        );
        *cached = Some((token.clone(), Instant::now()));
        token
    }

    pub async fn send(&self, client: &reqwest::Client, device_token: &[u8], sandbox: bool) -> Outcome {
        let host = if sandbox { "api.sandbox.push.apple.com" } else { "api.push.apple.com" };
        let url = format!("https://{host}/3/device/{}", hex(device_token));
        let body = json!({
            "aps": {
                "alert": {"title": "Messenger", "body": "New message"},
                "sound": "default",
                "mutable-content": 1,
            }
        });
        let request = client
            .post(&url)
            .bearer_auth(self.provider_token())
            .header("apns-topic", &self.topic)
            .header("apns-push-type", "alert")
            .header("apns-priority", "10")
            .header("apns-expiration", (super::now_s() + EXPIRY.as_secs()).to_string())
            .json(&body);
        let response = match request.send().await {
            Ok(response) => response,
            Err(e) => return Outcome::Failed(format!("APNs request failed: {e}")),
        };
        let status = response.status().as_u16();
        if status == 200 {
            return Outcome::Delivered;
        }
        let reason = response
            .json::<serde_json::Value>()
            .await
            .ok()
            .and_then(|v| v.get("reason").and_then(|r| r.as_str()).map(str::to_string))
            .unwrap_or_default();
        match (status, reason.as_str()) {
            // The app was removed, or the token belongs to the other environment.
            (410, _) | (400, "BadDeviceToken") | (400, "DeviceTokenNotForTopic") => Outcome::Gone,
            _ => Outcome::Failed(format!("APNs answered {status} {reason}")),
        }
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
    use p256::pkcs8::{EncodePrivateKey, LineEnding};

    #[test]
    fn reads_a_p8_key_and_caches_its_token() {
        let pem = SigningKey::from_slice(&[9u8; 32]).unwrap().to_pkcs8_pem(LineEnding::LF).unwrap();
        let apns = Apns::new(&ApnsConfig {
            key_pem: pem.to_string(),
            key_id: "ABC123DEFG".into(),
            team_id: "TEAM123456".into(),
            topic: "com.qwantarc.messenger".into(),
        })
        .unwrap();
        let token = apns.provider_token();
        assert_eq!(token, apns.provider_token());
        let header: serde_json::Value =
            serde_json::from_slice(&URL_SAFE_NO_PAD.decode(token.split('.').next().unwrap()).unwrap()).unwrap();
        assert_eq!(header["kid"], "ABC123DEFG");
        assert!(Apns::new(&ApnsConfig {
            key_pem: "not a key".into(),
            key_id: String::new(),
            team_id: String::new(),
            topic: String::new()
        })
        .is_err());
        assert_eq!(hex(&[0x0a, 0xff]), "0aff");
    }
}
