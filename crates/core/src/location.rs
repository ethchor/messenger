//! Live location sharing.
//!
//! A position is an MLS application message like any other, so the server
//! relays it without learning where anyone is. Receivers keep only the latest
//! position from each person in each conversation, never a history, and it
//! never shows up in the chat. Sharing always starts on the device of the
//! person being located, for a set time or until they stop.

use std::collections::HashSet;

use messenger_protocol::v1::{content::Body, Content, Location};

use crate::{
    client::{active_conversation, leaving, parse_id, queue_content, Client, Ctx},
    keys,
    store::{self, conversation_kind, ConversationRow, LocationRow},
    views::ConversationKind,
    Error, Result,
};
use messenger_protocol::CONTENT_ID_LEN;

/// Updates to one conversation closer together than this are skipped, so a
/// chatty GPS can't flood a group.
const MIN_UPDATE_INTERVAL_MS: i64 = 2_000;
/// Positions measured longer ago than this aren't sent.
const MAX_FIX_AGE_MS: i64 = 10 * 60_000;

/// A position from the device's location service.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LocationFix {
    /// WGS 84 degrees.
    pub latitude: f64,
    pub longitude: f64,
    /// Horizontal accuracy (radius) in metres.
    pub accuracy_m: f32,
    /// When the position was measured, ms since the Unix epoch.
    pub measured_at_ms: i64,
    pub speed_mps: Option<f32>,
    /// Direction of travel, degrees clockwise from true north.
    pub course_deg: Option<f32>,
    pub battery_percent: Option<u8>,
}

/// A conversation we share our location in.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LocationShare {
    pub conversation_id: String,
    pub title: String,
    pub kind: ConversationKind,
    pub started_at_ms: i64,
    /// None: until we stop.
    pub until_ms: Option<i64>,
}

/// The latest position of someone sharing with us. One per person: if they
/// share in several conversations, the freshest update wins.
#[derive(Debug, Clone, PartialEq)]
pub struct SharedLocation {
    /// The conversation the freshest update came through.
    pub conversation_id: String,
    pub username: String,
    pub latitude: f64,
    pub longitude: f64,
    pub accuracy_m: f32,
    pub measured_at_ms: i64,
    pub received_at_ms: i64,
    /// None: until they stop.
    pub until_ms: Option<i64>,
    pub speed_mps: Option<f32>,
    pub course_deg: Option<f32>,
    pub battery_percent: Option<u8>,
}

impl Client {
    /// Starts sharing our location in a conversation, or changes until when.
    /// `until_ms` None shares until [`Client::stop_sharing_location`].
    /// Positions go out with [`Client::update_location`].
    pub fn start_sharing_location(&self, conversation_id: &str, until_ms: Option<i64>) -> Result<()> {
        let id = parse_id(conversation_id)?;
        if until_ms.is_some_and(|until| until <= store::now_ms()) {
            return Err(Error::InvalidInput("sharing has to end in the future".into()));
        }
        self.with_tx(|ctx| {
            let conv = active_conversation(ctx.conn, &id)?;
            store::set_location_share(ctx.conn, &conv.id, until_ms)
        })
    }

    /// Stops sharing our location in a conversation. The others forget it as
    /// soon as they receive this.
    pub fn stop_sharing_location(&self, conversation_id: &str) -> Result<()> {
        let id = parse_id(conversation_id)?;
        let queued = self.with_tx(|ctx| {
            let was_sharing = store::delete_location_share(ctx.conn, &id)?;
            store::delete_unsent_latest_only(ctx.conn, &id)?;
            let Some(conv) = store::conversation(ctx.conn, &id)? else { return Ok(false) };
            if !was_sharing || !conv.active || leaving(ctx.conn, &conv.id)? {
                return Ok(false);
            }
            queue_location(ctx, &conv, Location { sharing: false, ..Location::default() })?;
            Ok(true)
        })?;
        if queued {
            self.flush_quietly();
        }
        Ok(())
    }

    /// Sends a new position to every conversation we share our location in,
    /// replacing any update that hasn't gone out yet. Returns how many
    /// conversations it went to.
    pub fn update_location(&self, fix: LocationFix) -> Result<u32> {
        validate(&fix)?;
        let now = store::now_ms();
        if now.saturating_sub(fix.measured_at_ms) > MAX_FIX_AGE_MS {
            return Ok(0);
        }
        let sent = self.with_tx(|ctx| {
            let mut sent = 0;
            for share in store::location_shares(ctx.conn, now)? {
                if share.last_sent_at.is_some_and(|at| now.saturating_sub(at) < MIN_UPDATE_INTERVAL_MS) {
                    continue;
                }
                let Some(conv) = store::conversation(ctx.conn, &share.conversation_id)? else { continue };
                if leaving(ctx.conn, &conv.id)? {
                    continue;
                }
                store::delete_unsent_latest_only(ctx.conn, &conv.id)?;
                queue_location(
                    ctx,
                    &conv,
                    Location {
                        sharing: true,
                        until_ms: share.until_ms.map(|until| until.max(1) as u64).unwrap_or(0),
                        latitude: fix.latitude,
                        longitude: fix.longitude,
                        accuracy_m: fix.accuracy_m,
                        measured_at_ms: fix.measured_at_ms.max(0) as u64,
                        speed_mps: fix.speed_mps,
                        course_deg: fix.course_deg,
                        battery_percent: fix.battery_percent.map(u32::from),
                    },
                )?;
                store::set_location_sent(ctx.conn, &conv.id, now)?;
                sent += 1;
            }
            Ok(sent)
        })?;
        if sent > 0 {
            self.flush_quietly();
        }
        Ok(sent)
    }

    /// Where we share our location now.
    pub fn location_shares(&self) -> Result<Vec<LocationShare>> {
        let state = self.lock()?;
        let mut out = Vec::new();
        for share in store::location_shares(&state.conn, store::now_ms())? {
            let Some(conv) = store::conversation(&state.conn, &share.conversation_id)? else { continue };
            out.push(LocationShare {
                conversation_id: hex::encode(&conv.id),
                title: display_title(&conv),
                kind: kind(conv.kind),
                started_at_ms: share.started_at,
                until_ms: share.until_ms,
            });
        }
        Ok(out)
    }

    /// The latest position of everyone sharing their location with us.
    pub fn shared_locations(&self) -> Result<Vec<SharedLocation>> {
        let state = self.lock()?;
        let mut seen = HashSet::new();
        let mut out = Vec::new();
        // Sorted by person, freshest first: keep the first row of each.
        for (row, _, _) in store::shared_locations(&state.conn, store::now_ms())? {
            if !seen.insert(row.sender_account.clone()) {
                continue;
            }
            out.push(SharedLocation {
                conversation_id: hex::encode(&row.conversation_id),
                username: row.sender_username,
                latitude: row.latitude,
                longitude: row.longitude,
                accuracy_m: row.accuracy_m as f32,
                measured_at_ms: row.measured_at,
                received_at_ms: row.received_at,
                until_ms: row.until_ms,
                speed_mps: row.speed_mps.map(|v| v as f32),
                course_deg: row.course_deg.map(|v| v as f32),
                battery_percent: row.battery_percent,
            });
        }
        Ok(out)
    }
}

/// Applies a location update from `sender`. Returns whether what we show changed.
pub(crate) fn apply_location(
    conn: &rusqlite::Connection,
    conv: &ConversationRow,
    sender_account: &[u8],
    sender_username: &str,
    location: &Location,
) -> Result<bool> {
    let now = store::now_ms();
    let until = (location.until_ms != 0).then(|| location.until_ms.min(i64::MAX as u64) as i64);
    if !location.sharing || until.is_some_and(|until| until <= now) {
        return store::delete_location(conn, &conv.id, sender_account);
    }
    let fix = LocationFix {
        latitude: location.latitude,
        longitude: location.longitude,
        accuracy_m: location.accuracy_m,
        // A clock running ahead mustn't make a position look newer than now.
        measured_at_ms: (location.measured_at_ms.min(i64::MAX as u64) as i64).min(now),
        speed_mps: location.speed_mps.filter(|v| v.is_finite() && *v >= 0.0),
        course_deg: location.course_deg.filter(|v| v.is_finite() && (0.0..360.0).contains(v)),
        battery_percent: location.battery_percent.filter(|v| *v <= 100).map(|v| v as u8),
    };
    if validate(&fix).is_err() {
        return Ok(false);
    }
    store::upsert_location(
        conn,
        &LocationRow {
            conversation_id: conv.id.clone(),
            sender_account: sender_account.to_vec(),
            sender_username: sender_username.to_string(),
            latitude: fix.latitude,
            longitude: fix.longitude,
            accuracy_m: f64::from(fix.accuracy_m),
            speed_mps: fix.speed_mps.map(f64::from),
            course_deg: fix.course_deg.map(f64::from),
            battery_percent: fix.battery_percent,
            measured_at: fix.measured_at_ms,
            received_at: now,
            until_ms: until,
        },
    )
}

fn validate(fix: &LocationFix) -> Result<()> {
    let ok = fix.latitude.is_finite()
        && fix.longitude.is_finite()
        && (-90.0..=90.0).contains(&fix.latitude)
        && (-180.0..=180.0).contains(&fix.longitude)
        && fix.accuracy_m.is_finite()
        && fix.accuracy_m >= 0.0
        && fix.speed_mps.is_none_or(|v| v.is_finite() && v >= 0.0)
        && fix.course_deg.is_none_or(|v| v.is_finite() && (0.0..360.0).contains(&v))
        && fix.battery_percent.is_none_or(|v| v <= 100);
    if ok {
        Ok(())
    } else {
        Err(Error::InvalidInput("not a valid position".into()))
    }
}

/// Queues a location message, marked so a newer one replaces it while unsent.
fn queue_location(ctx: &Ctx<'_>, conv: &ConversationRow, location: Location) -> Result<()> {
    let content = Content {
        id: keys::random_bytes::<CONTENT_ID_LEN>()?.to_vec(),
        sent_at_ms: store::now_ms() as u64,
        body: Some(Body::Location(location)),
    };
    let row = queue_content(ctx, conv, &content, false)?;
    store::set_outbox_latest_only(ctx.conn, row)
}

fn display_title(conv: &ConversationRow) -> String {
    if conv.kind == conversation_kind::DIRECT {
        conv.peer_username.clone().unwrap_or_else(|| conv.title.clone())
    } else {
        conv.title.clone()
    }
}

fn kind(code: i64) -> ConversationKind {
    if code == conversation_kind::DIRECT {
        ConversationKind::Direct
    } else {
        ConversationKind::Group
    }
}
