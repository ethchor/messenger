//! What this device does with each chat: pin, archive, mute, lock, mark
//! unread, clear and delete, and who it blocks. None of it is sent to the
//! server or to the people in the chat.

use crate::{
    client::{parse_id, Client},
    store::{self, conversation_kind},
    Error, Result,
};

/// At most this many chats are pinned to the top.
pub const MAX_PINNED: u32 = 5;

/// "Always", for [`Client::mute_conversation`].
pub const MUTED_FOREVER: i64 = i64::MAX;

impl Client {
    fn update_prefs(&self, conversation_id: &str, change: impl FnOnce(&mut store::ConversationPrefs)) -> Result<()> {
        let id = parse_id(conversation_id)?;
        let state = self.lock()?;
        store::conversation(&state.conn, &id)?.ok_or(Error::ConversationNotFound)?;
        let mut prefs = store::conversation_prefs(&state.conn, &id)?;
        change(&mut prefs);
        drop(state);
        // Our other devices do the same.
        let synced = self.with_tx(|ctx| {
            store::set_conversation_prefs(ctx.conn, &id, &prefs)?;
            crate::sync::queue_prefs_sync(ctx, &id)
        })?;
        if synced {
            self.flush_quietly();
        }
        Ok(())
    }

    /// Pins a chat to the top of the list, or unpins it. At most
    /// [`MAX_PINNED`] chats are pinned.
    pub fn set_pinned(&self, conversation_id: &str, pinned: bool) -> Result<()> {
        let id = parse_id(conversation_id)?;
        if pinned {
            let state = self.lock()?;
            let already = store::conversation_prefs(&state.conn, &id)?.pinned_at.is_some();
            if !already && store::pinned_count(&state.conn)? >= MAX_PINNED {
                return Err(Error::InvalidInput(format!("you can pin up to {MAX_PINNED} chats")));
            }
        }
        self.update_prefs(conversation_id, |p| {
            p.pinned_at = if pinned { p.pinned_at.or(Some(store::now_ms())) } else { None };
        })
    }

    /// Moves a chat into Archived, or back. An archived chat stays there when
    /// new messages come.
    pub fn set_archived(&self, conversation_id: &str, archived: bool) -> Result<()> {
        self.update_prefs(conversation_id, |p| {
            p.archived = archived;
            // Archived chats aren't pinned.
            if archived {
                p.pinned_at = None;
            }
        })
    }

    /// Mutes a chat until `until_ms` (ms since the epoch; [`MUTED_FOREVER`]
    /// for always), or unmutes it with `None`. A muted chat makes no
    /// notifications.
    pub fn mute_conversation(&self, conversation_id: &str, until_ms: Option<i64>) -> Result<()> {
        self.update_prefs(conversation_id, |p| p.muted_until = until_ms)
    }

    /// Locks a chat behind the device's own unlock (the app asks for it), or
    /// unlocks it. Notifications from a locked chat don't say what's in it.
    pub fn set_locked(&self, conversation_id: &str, locked: bool) -> Result<()> {
        self.update_prefs(conversation_id, |p| p.locked = locked)
    }

    /// Shows the chat as unread until it's opened again. Opening it
    /// ([`Client::mark_read`]) clears this.
    pub fn mark_unread(&self, conversation_id: &str) -> Result<()> {
        self.update_prefs(conversation_id, |p| p.marked_unread = true)
    }

    /// Blocks or unblocks someone. Their messages are dropped on this device,
    /// in 1:1 chats and groups alike, and we can't send them any; they aren't
    /// told.
    pub fn set_blocked(&self, username: &str, blocked: bool) -> Result<()> {
        let username =
            messenger_protocol::normalize_username(username).map_err(|e| Error::InvalidInput(e.to_string()))?;
        let synced = self.with_tx(|ctx| {
            store::set_blocked(ctx.conn, &username, blocked)?;
            let mut sync = messenger_protocol::v1::DeviceSync::default();
            if blocked {
                sync.blocked.push(username.clone())
            } else {
                sync.unblocked.push(username.clone())
            }
            crate::sync::queue_sync(ctx, sync)
        })?;
        if synced {
            self.flush_quietly();
        }
        Ok(())
    }

    pub fn blocked_users(&self) -> Result<Vec<String>> {
        store::blocked_users(&self.lock()?.conn)
    }

    /// Deletes a chat's messages and files on this device. The chat stays.
    pub fn clear_conversation(&self, conversation_id: &str) -> Result<()> {
        let id = parse_id(conversation_id)?;
        let paths = self.with_tx(|ctx| {
            store::conversation(ctx.conn, &id)?.ok_or(Error::ConversationNotFound)?;
            store::clear_conversation(ctx.conn, &id)
        })?;
        remove_files(&paths);
        Ok(())
    }

    /// Deletes a chat from this device. A chat still going is left first
    /// (a 1:1 chat ends; we leave a group), and disappears from the list at
    /// once; it's gone for good when the leaving is done.
    pub fn delete_conversation(&self, conversation_id: &str) -> Result<()> {
        let id = parse_id(conversation_id)?;
        let active = {
            let state = self.lock()?;
            store::conversation(&state.conn, &id)?.ok_or(Error::ConversationNotFound)?.active
        };
        if active {
            self.update_prefs(conversation_id, |p| p.hidden = true)?;
            self.leave_conversation(conversation_id)?;
            self.purge_deleted()
        } else {
            let paths = self.with_tx(|ctx| store::purge_conversation(ctx.conn, &id))?;
            remove_files(&paths);
            Ok(())
        }
    }

    /// Finishes deleting chats that were still leaving when deleted.
    pub(crate) fn purge_deleted(&self) -> Result<()> {
        let paths = self.with_tx(|ctx| {
            let mut paths = Vec::new();
            for id in store::hidden_ended(ctx.conn)? {
                paths.extend(store::purge_conversation(ctx.conn, &id)?);
            }
            Ok(paths)
        })?;
        remove_files(&paths);
        Ok(())
    }
}

/// Refuses to send into a 1:1 chat with someone we blocked.
pub(crate) fn check_can_send(conn: &rusqlite::Connection, conv: &store::ConversationRow) -> Result<()> {
    if conv.kind == conversation_kind::DIRECT {
        if let Some(peer) = conv.peer_username.as_deref() {
            if store::is_blocked(conn, peer)? {
                return Err(Error::InvalidInput(format!("you blocked {peer}; unblock them to send")));
            }
        }
    }
    Ok(())
}

fn remove_files(paths: &[String]) {
    for path in paths {
        if let Err(e) = std::fs::remove_file(path) {
            if e.kind() != std::io::ErrorKind::NotFound {
                log::warn!("deleting a file: {e}");
            }
        }
    }
}
