//! Vanish mode, as on Instagram: in a 1:1 chat either person turns it on,
//! and messages sent while it's on go once they've been seen: each side
//! deletes them when it closes the chat, and both delete all of them when
//! it's turned off. Screenshots taken in vanish mode are announced.

use messenger_protocol::v1::{content::Body, Screenshot, VanishMode};

use crate::{
    client::{parse_id, Client},
    engine::system_message,
    store::{self, conversation_kind},
    Error, Result,
};

impl Client {
    /// Turns vanish mode on or off in a 1:1 chat, for both people.
    pub fn set_vanish_mode(&self, conversation_id: &str, on: bool) -> Result<()> {
        let id = parse_id(conversation_id)?;
        {
            let state = self.lock()?;
            let conv = store::conversation(&state.conn, &id)?.ok_or(Error::ConversationNotFound)?;
            if conv.kind != conversation_kind::DIRECT {
                return Err(Error::InvalidInput("vanish mode is for 1:1 chats".into()));
            }
            if store::is_vanish(&state.conn, &id)? == on {
                return Ok(());
            }
        }
        let mut files = Vec::new();
        self.send_control(conversation_id, Body::VanishMode(VanishMode { on }), |ctx, conv| {
            store::set_vanish(ctx.conn, &conv.id, on)?;
            if !on {
                files = store::delete_vanished(ctx.conn, &conv.id, false)?;
            }
            let verb = if on { "turned on" } else { "turned off" };
            system_message(ctx, &conv.id, None, &format!("You {verb} vanish mode"), None)?;
            store::touch_conversation(ctx.conn, &conv.id, 0)
        })?;
        crate::media::remove_files(&files);
        Ok(())
    }

    /// The app closed the chat: messages sent in vanish mode that have been
    /// seen go (everything that came in, and ours once sent).
    pub fn close_conversation(&self, conversation_id: &str) -> Result<()> {
        let id = parse_id(conversation_id)?;
        let files = self.with_tx(|ctx| store::delete_vanished(ctx.conn, &id, true))?;
        crate::media::remove_files(&files);
        Ok(())
    }

    /// Tells the other person we took a screenshot, if the chat is in vanish
    /// mode. Does nothing otherwise.
    pub fn report_screenshot(&self, conversation_id: &str) -> Result<()> {
        let id = parse_id(conversation_id)?;
        if !store::is_vanish(&self.lock()?.conn, &id)? {
            return Ok(());
        }
        self.send_control(conversation_id, Body::Screenshot(Screenshot {}), |ctx, conv| {
            system_message(ctx, &conv.id, None, "You took a screenshot", None)?;
            store::touch_conversation(ctx.conn, &conv.id, 0)
        })
    }
}
