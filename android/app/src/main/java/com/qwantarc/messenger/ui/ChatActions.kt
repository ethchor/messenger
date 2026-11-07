package com.qwantarc.messenger.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.ConversationSummary

/** The chat actions that need a second tap to confirm. */
sealed class ChatConfirm(val conversation: ConversationSummary) {
    class Block(c: ConversationSummary) : ChatConfirm(c)
    class Clear(c: ConversationSummary) : ChatConfirm(c)
    class Delete(c: ConversationSummary) : ChatConfirm(c)
    class ExitGroup(c: ConversationSummary) : ChatConfirm(c)

    val title: String
        get() = when (this) {
            is Block -> "Block ${conversation.title}?"
            is Clear -> "Clear this chat?"
            is Delete -> if (conversation.kind == ConversationKind.GROUP) "Delete this group?" else "Delete this chat?"
            is ExitGroup -> "Exit ${conversation.title}?"
        }

    val message: String
        get() = when (this) {
            is Block -> "Their messages won't reach this phone, here or in groups, and you can't message them. They aren't told."
            is Clear -> "Its messages, photos and files are deleted from this phone. The chat stays, and the others keep their copies."
            is Delete -> when {
                !conversation.active -> "It's deleted from this phone, with its messages and files."
                conversation.kind == ConversationKind.GROUP -> "You'll leave the group, and it's deleted from this phone."
                else -> "The chat ends for both of you, and it's deleted from this phone."
            }
            is ExitGroup -> "You'll stop getting its messages. What's already here stays."
        }

    val button: String
        get() = when (this) {
            is Block -> "Block"
            is Clear -> "Clear chat"
            is Delete -> "Delete"
            is ExitGroup -> "Exit group"
        }
}

/** Asks before blocking, clearing, deleting or exiting, then does it. */
@Composable
fun ChatConfirmDialog(confirm: ChatConfirm, model: AppViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(confirm.title) },
        text = { Text(confirm.message) },
        confirmButton = {
            TextButton(onClick = {
                val c = confirm.conversation
                when (confirm) {
                    is ChatConfirm.Block -> model.setBlocked(c.title, true)
                    is ChatConfirm.Clear -> model.clearChat(c.id)
                    is ChatConfirm.Delete -> model.deleteChat(c.id)
                    is ChatConfirm.ExitGroup -> model.exitGroup(c.id)
                }
                onDismiss()
            }) { Text(confirm.button, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Every action for a chat, as the iPhone's pop-up menu. */
@Composable
fun ChatActionsMenu(
    conversation: ConversationSummary,
    expanded: Boolean,
    model: AppViewModel,
    onConfirm: (ChatConfirm) -> Unit,
    onDismiss: () -> Unit,
) {
    var muting by remember { mutableStateOf(false) }
    val id = conversation.id
    val unread = conversation.unread > 0u || conversation.markedUnread
    fun act(work: () -> Unit) {
        onDismiss()
        work()
    }
    PopMenu(expanded = expanded && !muting, onDismissRequest = onDismiss) {
        PopMenuItem(if (unread) "Mark as read" else "Mark as unread", Glyphs.Envelope, first = true) { act { model.setRead(id, unread) } }
        if (!conversation.archived) {
            PopMenuItem(if (conversation.pinned) "Unpin" else "Pin", Glyphs.Pin) { act { model.setPinned(id, !conversation.pinned) } }
        }
        PopMenuItem(if (conversation.archived) "Unarchive" else "Archive", Glyphs.Archive) {
            act { model.setArchived(id, !conversation.archived) }
        }
        if (conversation.mutedUntilMs != null) {
            PopMenuItem("Unmute", Glyphs.Bell) { act { model.unmute(id) } }
        } else {
            PopMenuItem("Mute…", Glyphs.BellSlash) { muting = true }
        }
        PopMenuItem(if (conversation.locked) "Unlock chat" else "Lock chat", Glyphs.Lock) { act { model.setLocked(id, !conversation.locked) } }
        if (conversation.kind == ConversationKind.DIRECT) {
            if (conversation.blocked) {
                PopMenuItem("Unblock ${conversation.title}", Glyphs.Hand) { act { model.setBlocked(conversation.title, false) } }
            } else {
                PopMenuItem("Block ${conversation.title}", Glyphs.Hand, destructive = true) { act { onConfirm(ChatConfirm.Block(conversation)) } }
            }
        }
        PopMenuItem("Clear chat", Glyphs.Eraser, destructive = true) { act { onConfirm(ChatConfirm.Clear(conversation)) } }
        if (conversation.kind == ConversationKind.GROUP && conversation.active && !conversation.leaving) {
            PopMenuItem("Exit group", Glyphs.Exit, destructive = true) { act { onConfirm(ChatConfirm.ExitGroup(conversation)) } }
        }
        PopMenuItem("Delete chat", Glyphs.Trash, destructive = true) { act { onConfirm(ChatConfirm.Delete(conversation)) } }
    }
    PopMenu(expanded = expanded && muting, onDismissRequest = { muting = false; onDismiss() }) {
        PopMenuItem("8 hours", first = true) { muting = false; act { model.mute(id, 8 * HOUR) } }
        PopMenuItem("1 week") { muting = false; act { model.mute(id, 7 * 24 * HOUR) } }
        PopMenuItem("Always") { muting = false; act { model.mute(id, null) } }
    }
}

const val HOUR = 3_600_000L
