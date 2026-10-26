//! Push notifications that carry no content. A push only wakes a device so
//! its app fetches from its mailboxes, decrypts and decides what to show.
//!
//! Who gets woken:
//! - The owner of an invitation inbox, when a Welcome lands in it.
//! - The accounts whose wake tokens a post names. Members share their wake
//!   token inside each group (an MLS message), and include each other's with
//!   messages worth a notification, so only fellow members can wake someone.
//!
//! What this shows the server: which accounts it wakes for which post. That
//! is enough to work out who talks to whom, as with Signal, whose server
//! knows every message's recipient. Turning notifications off in the app
//! removes the push target and hides it again.
//!
//! Pushes per device are spaced at least [`MIN_GAP`] apart (a burst of
//! messages becomes one push) and capped at [`PER_HOUR`].

mod apns;
mod endpoint;
mod jwt;
mod webpush;

use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use messenger_protocol::api::PushTarget;
use tokio::sync::{mpsc, Semaphore};

use crate::{db, ApiError, AppState};

pub(crate) use endpoint::check as check_endpoint;
pub(crate) use webpush::VapidKey;

/// Closest two pushes to one device may be, by default.
const MIN_GAP: Duration = Duration::from_secs(2);
/// Most pushes to one device per hour.
const PER_HOUR: u32 = 300;
/// Wakes arriving this close together are handled as one batch.
const GATHER: Duration = Duration::from_millis(250);
/// Wakes waiting to be handled; more are dropped.
const QUEUE: usize = 10_000;
/// Pushes being sent at once.
const IN_FLIGHT: usize = 16;

/// Push settings.
#[derive(Clone)]
pub struct PushConfig {
    /// Apple's push service; without it, iPhones aren't woken.
    pub apns: Option<ApnsConfig>,
    /// Contact for push services, sent in VAPID tokens: `mailto:` or `https:`.
    pub contact: String,
    /// Allows Web Push endpoints on private networks and plain http, for a
    /// push service on the LAN. Off by default, so clients can't point the
    /// server at its own network.
    pub allow_private_endpoints: bool,
    /// Closest two pushes to one device may be; wake-ups in between are
    /// folded into one push at the end of the gap.
    pub min_gap: Duration,
    /// Tests: record pushes instead of sending them.
    pub recorder: Option<Recorder>,
}

impl Default for PushConfig {
    fn default() -> Self {
        Self {
            apns: None,
            contact: "mailto:admin@localhost".into(),
            allow_private_endpoints: false,
            min_gap: MIN_GAP,
            recorder: None,
        }
    }
}

impl std::fmt::Debug for PushConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PushConfig")
            .field("apns", &self.apns)
            .field("contact", &self.contact)
            .field("allow_private_endpoints", &self.allow_private_endpoints)
            .field("min_gap", &self.min_gap)
            .field("recorder", &self.recorder.is_some())
            .finish()
    }
}

/// Token-based APNs authentication.
#[derive(Clone)]
pub struct ApnsConfig {
    /// Contents of the `.p8` key file.
    pub key_pem: String,
    pub key_id: String,
    pub team_id: String,
    /// The app's bundle id.
    pub topic: String,
}

impl std::fmt::Debug for ApnsConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("ApnsConfig")
            .field("key_id", &self.key_id)
            .field("team_id", &self.team_id)
            .field("topic", &self.topic)
            .finish_non_exhaustive()
    }
}

/// A push the server would have sent (see [`PushConfig::recorder`]).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Delivery {
    pub username: String,
    /// `apns` or `webpush`.
    pub kind: &'static str,
}

pub type Recorder = Arc<dyn Fn(Delivery) + Send + Sync>;

pub(crate) enum Outcome {
    Delivered,
    /// The device or subscription is gone: stop pushing to it.
    Gone,
    Failed(String),
}

pub(crate) fn now_s() -> u64 {
    (db::now_ms() / 1000).max(0) as u64
}

/// Name of a target kind, as stored.
pub(crate) fn kind_of(target: &PushTarget) -> &'static str {
    match target {
        PushTarget::Apns { .. } => "apns",
        PushTarget::Webpush { .. } => "webpush",
    }
}

pub(crate) struct Pusher {
    queue: mpsc::Sender<Vec<u8>>,
    receiver: Mutex<Option<mpsc::Receiver<Vec<u8>>>>,
    pub vapid: VapidKey,
    pub config: PushConfig,
    apns: Option<apns::Apns>,
    apns_client: reqwest::Client,
    webpush_client: reqwest::Client,
}

impl Pusher {
    pub fn new(config: PushConfig, vapid: VapidKey) -> anyhow::Result<Self> {
        // reqwest brings rustls without a crypto provider; use ring, like the
        // rest of the server. Fails harmlessly if one is already installed.
        let _ = rustls::crypto::ring::default_provider().install_default();
        let apns = config.apns.as_ref().map(apns::Apns::new).transpose()?;
        let apns_client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .redirect(reqwest::redirect::Policy::none())
            .build()?;
        let mut webpush = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(10))
            .timeout(Duration::from_secs(20))
            .redirect(reqwest::redirect::Policy::none());
        if !config.allow_private_endpoints {
            webpush = webpush.dns_resolver(endpoint::PublicOnlyResolver);
        }
        let (queue, receiver) = mpsc::channel(QUEUE);
        Ok(Self {
            queue,
            receiver: Mutex::new(Some(receiver)),
            vapid,
            config,
            apns,
            apns_client,
            webpush_client: webpush.build()?,
        })
    }

    pub fn apns_enabled(&self) -> bool {
        self.apns.is_some()
    }

    /// Queues a push to an account's device. Never waits.
    pub fn wake(&self, account_id: Vec<u8>) {
        if self.queue.try_send(account_id).is_err() {
            tracing::warn!("push queue full; dropping a wake-up");
        }
    }

    async fn send(&self, target: &PushTarget) -> Outcome {
        match target {
            PushTarget::Apns { token, sandbox } => match &self.apns {
                Some(apns) => apns.send(&self.apns_client, token, *sandbox).await,
                None => Outcome::Failed("APNs isn't set up on this server".into()),
            },
            PushTarget::Webpush { endpoint, p256dh, auth } => self.send_webpush(endpoint, p256dh, auth).await,
        }
    }

    async fn send_webpush(&self, endpoint: &str, p256dh: &[u8], auth: &[u8]) -> Outcome {
        let url = match endpoint::check(endpoint, self.config.allow_private_endpoints) {
            Ok(url) => url,
            Err(e) => return Outcome::Failed(e),
        };
        let body = match webpush::encrypt(p256dh, auth, b"wake") {
            Ok(body) => body,
            Err(e) => return Outcome::Failed(e.to_string()),
        };
        let request = self
            .webpush_client
            .post(url.clone())
            .header("TTL", "86400")
            .header("Urgency", "high")
            // Undelivered pushes with the same topic replace each other, so a
            // phone that was offline gets one wake-up, not a pile.
            .header("Topic", "wake")
            .header("Content-Encoding", "aes128gcm")
            .header("Content-Type", "application/octet-stream")
            .header("Authorization", self.vapid.authorization(&url, &self.config.contact, now_s()))
            .body(body);
        match request.send().await {
            Ok(response) if response.status().is_success() => Outcome::Delivered,
            Ok(response) if matches!(response.status().as_u16(), 404 | 410) => Outcome::Gone,
            Ok(response) => Outcome::Failed(format!("push service answered {}", response.status())),
            Err(e) => Outcome::Failed(format!("push request failed: {e}")),
        }
    }
}

/// Sends queued pushes until the queue closes.
pub(crate) async fn run(state: Arc<AppState>) {
    let receiver = state.push.receiver.lock().unwrap_or_else(|e| e.into_inner()).take();
    let Some(mut receiver) = receiver else { return };
    let mut limiter = Limiter { min_gap: state.push.config.min_gap, ..Limiter::default() };
    let in_flight = Arc::new(Semaphore::new(IN_FLIGHT));
    while let Some(first) = receiver.recv().await {
        let mut batch = HashSet::from([first]);
        let until = tokio::time::Instant::now() + GATHER;
        while let Ok(Some(account)) = tokio::time::timeout_at(until, receiver.recv()).await {
            batch.insert(account);
        }
        let now = Instant::now();
        for account in batch {
            match limiter.check(&account, now) {
                Decision::Send => {
                    let state = state.clone();
                    let in_flight = in_flight.clone();
                    tokio::spawn(async move {
                        let _permit = in_flight.acquire().await;
                        deliver(&state, account).await;
                    });
                }
                Decision::Later(wait) => {
                    let queue = state.push.queue.clone();
                    tokio::spawn(async move {
                        tokio::time::sleep(wait).await;
                        let _ = queue.send(account).await;
                    });
                }
                Decision::Drop => {}
            }
        }
        limiter.prune(now);
    }
}

async fn deliver(state: &Arc<AppState>, account_id: Vec<u8>) {
    let lookup = account_id.clone();
    let found = state
        .with_db(move |conn, _| {
            let targets = db::push_targets(conn, &lookup)?;
            let username = db::account_by_id(conn, &lookup)?.map(|a| a.username).unwrap_or_default();
            Ok((targets, username))
        })
        .await;
    let (targets, username) = match found {
        Ok(found) => found,
        Err(e) => return tracing::warn!(error = %e, "push: can't read targets"),
    };
    for target in targets {
        let kind = kind_of(&target);
        if let Some(recorder) = &state.push.config.recorder {
            recorder(Delivery { username: username.clone(), kind });
            continue;
        }
        match state.push.send(&target).await {
            Outcome::Delivered => tracing::debug!(kind, "push sent"),
            Outcome::Gone => {
                tracing::info!(kind, "push target gone; removing it");
                let account = account_id.clone();
                let removed = state.with_db(move |conn, _| Ok(db::delete_push_target(conn, &account, kind)?)).await;
                if let Err(e) = removed {
                    tracing::warn!(error = %e, "push: can't remove a target");
                }
            }
            Outcome::Failed(e) => tracing::warn!(kind, error = %e, "push failed"),
        }
    }
}

/// Validates a target a device registers.
pub(crate) fn check_target(target: &PushTarget, allow_private: bool) -> Result<(), ApiError> {
    match target {
        PushTarget::Apns { token, .. } => {
            if !(8..=128).contains(&token.len()) {
                return Err(ApiError::bad_request("APNs device token has the wrong length"));
            }
        }
        PushTarget::Webpush { endpoint, p256dh, auth } => {
            check_endpoint(endpoint, allow_private).map_err(ApiError::bad_request)?;
            if p256::PublicKey::from_sec1_bytes(p256dh).is_err() {
                return Err(ApiError::bad_request("p256dh is not a P-256 public key"));
            }
            if auth.len() != 16 {
                return Err(ApiError::bad_request("auth must be 16 bytes"));
            }
        }
    }
    Ok(())
}

enum Decision {
    Send,
    Later(Duration),
    Drop,
}

struct Pace {
    last_sent: Instant,
    /// A later push is already scheduled.
    deferred: bool,
    hour_started: Instant,
    sent_this_hour: u32,
}

struct Limiter {
    min_gap: Duration,
    accounts: HashMap<Vec<u8>, Pace>,
}

impl Default for Limiter {
    fn default() -> Self {
        Self { min_gap: MIN_GAP, accounts: HashMap::new() }
    }
}

impl Limiter {
    fn check(&mut self, account: &[u8], now: Instant) -> Decision {
        let Some(pace) = self.accounts.get_mut(account) else {
            self.accounts.insert(
                account.to_vec(),
                Pace { last_sent: now, deferred: false, hour_started: now, sent_this_hour: 1 },
            );
            return Decision::Send;
        };
        if now.duration_since(pace.hour_started) >= Duration::from_secs(3600) {
            pace.hour_started = now;
            pace.sent_this_hour = 0;
        }
        if pace.sent_this_hour >= PER_HOUR {
            return Decision::Drop;
        }
        let since = now.duration_since(pace.last_sent);
        if since < self.min_gap {
            if pace.deferred {
                return Decision::Drop;
            }
            pace.deferred = true;
            return Decision::Later(self.min_gap - since);
        }
        pace.deferred = false;
        pace.last_sent = now;
        pace.sent_this_hour += 1;
        Decision::Send
    }

    fn prune(&mut self, now: Instant) {
        if self.accounts.len() < 1000 {
            return;
        }
        self.accounts.retain(|_, pace| pace.deferred || now.duration_since(pace.last_sent) < Duration::from_secs(3600));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pushes_to_one_device_are_spaced_and_capped() {
        let mut limiter = Limiter::default();
        let start = Instant::now();
        let a = b"a".as_slice();
        assert!(matches!(limiter.check(a, start), Decision::Send));
        // Too soon: one push later, further wakes are folded into it.
        assert!(
            matches!(limiter.check(a, start + Duration::from_millis(500)), Decision::Later(d) if d == Duration::from_millis(1500))
        );
        assert!(matches!(limiter.check(a, start + Duration::from_millis(900)), Decision::Drop));
        assert!(matches!(limiter.check(a, start + MIN_GAP), Decision::Send));
        // Another device isn't held back.
        assert!(matches!(limiter.check(b"b", start + MIN_GAP), Decision::Send));
        // At most PER_HOUR pushes an hour.
        let mut t = start + MIN_GAP;
        for _ in 2..PER_HOUR {
            t += MIN_GAP;
            assert!(matches!(limiter.check(a, t), Decision::Send));
        }
        assert!(matches!(limiter.check(a, t + MIN_GAP), Decision::Drop));
        assert!(matches!(limiter.check(a, start + Duration::from_secs(3601)), Decision::Send));
    }

    #[test]
    fn registered_targets_are_checked() {
        let good_key = webpush::random_secret_key().unwrap().public_key();
        let p256dh = p256::elliptic_curve::sec1::ToEncodedPoint::to_encoded_point(&good_key, false).as_bytes().to_vec();
        let webpush = |endpoint: &str, p256dh: Vec<u8>, auth: Vec<u8>| PushTarget::Webpush {
            endpoint: endpoint.into(),
            p256dh,
            auth,
        };
        assert!(check_target(&webpush("https://ntfy.sh/up1", p256dh.clone(), vec![0; 16]), false).is_ok());
        assert!(check_target(&webpush("https://10.0.0.1/up1", p256dh.clone(), vec![0; 16]), false).is_err());
        assert!(check_target(&webpush("https://ntfy.sh/up1", vec![4; 65], vec![0; 16]), false).is_err());
        assert!(check_target(&webpush("https://ntfy.sh/up1", p256dh, vec![0; 8]), false).is_err());
        assert!(check_target(&PushTarget::Apns { token: vec![1; 32], sandbox: true }, false).is_ok());
        assert!(check_target(&PushTarget::Apns { token: vec![1; 4], sandbox: true }, false).is_err());
    }
}
