package com.qwantarc.messenger.ui

import com.qwantarc.messenger.core.mutedForever
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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

/**
 * Holding a chat, as designed (and as on the iPhone, ChatActionsSheet): a
 * sheet with the chat's stamp, name and status, then three cards. Mute opens
 * its choices in place, and the red ones ask first, in the sheet.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatActionsMenu(
    conversation: ConversationSummary,
    expanded: Boolean,
    model: AppViewModel,
    @Suppress("UNUSED_PARAMETER") onConfirm: (ChatConfirm) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!expanded) return
    val t = tones()
    var muteOpen by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<ChatConfirm?>(null) }
    val id = conversation.id
    val unread = conversation.unread > 0u || conversation.markedUnread
    fun act(work: () -> Unit) {
        onDismiss()
        work()
    }
    // Material's sheet: it keeps clear of the navigation bar and scrolls
    // when the text is large.
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        containerColor = t.home,
        dragHandle = null,
    ) {
            Column(
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.foundation.layout.Box(Modifier.align(Alignment.CenterHorizontally).size(38.dp, 5.dp).clip(CircleShape).background(t.line))
                Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(conversation.title, isGroup = conversation.kind == ConversationKind.GROUP, size = 52, stamp = true)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(conversation.title, style = displayStyle(21.sp), color = t.text, maxLines = 1)
                        Text(
                            if (conversation.kind == ConversationKind.GROUP) "Group · ${conversation.memberCount} members" else "@${conversation.title}",
                            fontSize = 13.5.sp, color = t.secondary, maxLines = 1,
                        )
                    }
                }
                val c = confirm
                if (c != null) {
                    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(c.title, style = displayStyle(24.sp), color = t.text)
                        Text(c.message, fontSize = 15.sp, lineHeight = 22.sp, color = t.secondary)
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.weight(1f).height(54.dp).clip(CircleShape).border(1.5.dp, t.text, CircleShape).clickable { confirm = null },
                                contentAlignment = Alignment.Center,
                            ) { Text("Cancel", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text) }
                            androidx.compose.foundation.layout.Box(
                                Modifier.weight(1f).height(54.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color(0xFFD92D20)).clickable {
                                    act {
                                        when (c) {
                                            is ChatConfirm.Block -> model.setBlocked(c.conversation.title, true)
                                            is ChatConfirm.Clear -> model.clearChat(c.conversation.id)
                                            is ChatConfirm.Delete -> model.deleteChat(c.conversation.id)
                                            is ChatConfirm.ExitGroup -> model.exitGroup(c.conversation.id)
                                        }
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) { Text(c.button, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White) }
                        }
                    }
                } else {
                    KitCard(radius = 22.dp) {
                        ActionRow(if (unread) "Mark as read" else "Mark as unread", Glyphs.Envelope) { act { model.setRead(id, unread) } }
                        if (!conversation.archived) {
                            ActionRow(if (conversation.pinned) "Unpin" else "Pin", Glyphs.Pin) { act { model.setPinned(id, !conversation.pinned) } }
                        }
                        ActionRow(if (conversation.archived) "Unarchive" else "Archive", Glyphs.Archive) { act { model.setArchived(id, !conversation.archived) } }
                    }
                    KitCard(radius = 22.dp) {
                        Column {
                            ActionRow(
                                if (conversation.mutedUntilMs == null) "Mute" else "Muted", Glyphs.BellSlash,
                                value = when (conversation.mutedUntilMs) { null -> "Off"; mutedForever() -> "Always"; else -> "On" },
                            ) { muteOpen = !muteOpen }
                            androidx.compose.animation.AnimatedVisibility(muteOpen) {
                                androidx.compose.foundation.layout.FlowRow(
                                    Modifier.padding(start = 54.dp, end = 14.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    MuteChip("Off", conversation.mutedUntilMs == null) { act { model.unmute(id) } }
                                    MuteChip("8 hours", false) { act { model.mute(id, 8 * HOUR) } }
                                    MuteChip("1 week", false) { act { model.mute(id, 7 * 24 * HOUR) } }
                                    MuteChip("Always", conversation.mutedUntilMs == mutedForever()) { act { model.mute(id, null) } }
                                }
                            }
                        }
                        ActionRow(if (conversation.locked) "Unlock chat" else "Lock chat", Glyphs.Lock) { act { model.setLocked(id, !conversation.locked) } }
                    }
                    KitCard(radius = 22.dp) {
                        if (conversation.kind == ConversationKind.GROUP) {
                            if (conversation.active && !conversation.leaving) {
                                ActionRow("Exit group", Glyphs.Exit, danger = true) { confirm = ChatConfirm.ExitGroup(conversation) }
                            }
                        } else if (conversation.blocked) {
                            ActionRow("Unblock ${conversation.title}", Glyphs.Hand) { act { model.setBlocked(conversation.title, false) } }
                        } else {
                            ActionRow("Block ${conversation.title}", Glyphs.Hand, danger = true) { confirm = ChatConfirm.Block(conversation) }
                        }
                        ActionRow("Clear chat", Glyphs.Eraser, danger = true) { confirm = ChatConfirm.Clear(conversation) }
                        ActionRow("Delete chat", Glyphs.Trash, danger = true) { confirm = ChatConfirm.Delete(conversation) }
                    }
                }
            }
    }
}

@Composable
private fun ActionRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String = "", danger: Boolean = false, onClick: () -> Unit) {
    val t = tones()
    val color = if (danger) t.danger else t.text
    Row(
        Modifier.fillMaxWidth().height(54.dp).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) Text(value, fontSize = 14.sp, color = t.secondary)
    }
}

@Composable
private fun MuteChip(label: String, on: Boolean, onClick: () -> Unit) {
    val t = tones()
    Text(
        label, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) t.home else t.text,
        modifier = Modifier.height(34.dp).clip(CircleShape).background(if (on) t.text else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.5.dp, if (on) t.text else t.line, CircleShape).clickable(onClick = onClick).padding(horizontal = 13.dp, vertical = 7.dp),
    )
}

const val HOUR = 3_600_000L
