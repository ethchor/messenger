package com.qwantarc.messenger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.MessageView
import com.qwantarc.messenger.core.ThreadSummary

/**
 * A thread on its own: the message it hangs off, the replies under it, and a
 * box to reply in. Replies here stay out of the chat.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(state: UiState, model: AppViewModel) {
    val conversationId = state.openConversationId ?: return
    val rootId = state.openThread ?: return
    val conversation = state.openConversation
    val isGroup = conversation?.kind == ConversationKind.GROUP
    val canSend = conversation?.active == true && !conversation.leaving
    val root = state.threadMessages.firstOrNull { it.id == rootId }
    val replies = state.threadMessages.filter { it.id != rootId }
    var draft by remember(rootId) { mutableStateOf("") }
    var editing by remember { mutableStateOf<MessageView?>(null) }
    var reactingTo by remember { mutableStateOf<MessageView?>(null) }
    var viewing by remember { mutableStateOf<MediaSelection?>(null) }
    val context = LocalContext.current
    val trayKey = model.trayKey(conversationId, rootId)
    val tray = model.tray(trayKey)
    val appearance = LocalAppearance.current
    val listState = rememberLazyListState()

    BackHandler { model.closeThread() }
    LaunchedEffect(state.threadMessages.size) {
        val items = listState.layoutInfo.totalItemsCount
        if (items > 0) listState.animateScrollToItem(items - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Thread")
                        conversation?.let {
                            Text(it.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = model::closeThread) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the chat")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PaperBackground(appearance.paperFor(conversationId), appearance.ink, Modifier.matchParentSize())
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (root != null) {
                        item(key = root.id) {
                            MessageRow(
                                message = root,
                                conversationId = conversationId,
                                isGroup = isGroup,
                                run = MessageRun(first = true, last = true),
                                fetching = state.fetching,
                                onReact = { emoji -> model.react(conversationId, root.id, emoji) },
                                onMoreReactions = { reactingTo = root },
                                onEdit = { editing = root },
                                onDelete = { model.delete(conversationId, root.id) },
                                onOpenAttachment = { attachment ->
                                    openAttachment(context, attachment, root, conversationId) { viewing = it }
                                },
                                onDownload = { ref -> model.download(ref, first = true) },
                            )
                        }
                        item(key = "replies") {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    if (replies.size == 1) "1 reply" else "${replies.size} replies",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                HorizontalDivider(Modifier.weight(1f))
                            }
                        }
                    }
                    itemsIndexed(replies, key = { _, message -> message.id }) { index, reply ->
                        MessageRow(
                            message = reply,
                            conversationId = conversationId,
                            isGroup = isGroup,
                            run = runAt(replies, index),
                            fetching = state.fetching,
                            onReact = { emoji -> model.react(conversationId, reply.id, emoji) },
                            onMoreReactions = { reactingTo = reply },
                            onEdit = { editing = reply },
                            onDelete = { model.delete(conversationId, reply.id) },
                            onOpenAttachment = { attachment ->
                                openAttachment(context, attachment, reply, conversationId) { viewing = it }
                            },
                            onDownload = { ref -> model.download(ref, first = true) },
                        )
                    }
                }
            }
            HorizontalDivider()
            MessageComposer(
                draft = draft,
                onDraftChange = { draft = it },
                placeholder = if (canSend) "Reply in thread" else "You can't reply here",
                enabled = canSend,
                onSend = {
                    val text = draft.trim()
                    val files = tray.take()
                    draft = ""
                    if (files.isEmpty()) {
                        model.replyInThread(conversationId, rootId, text)
                    } else {
                        model.sendMedia(conversationId, text, files, rootId, inThread = true)
                    }
                },
                tray = tray,
                trayKey = trayKey,
                onVoiceNote = { note -> model.sendVoiceNote(conversationId, note, rootId, inThread = true) },
            )
        }
    }

    editing?.let { message ->
        var text by remember(message.id) { mutableStateOf(message.text) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit message") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    model.edit(conversationId, message.id, text.trim())
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    reactingTo?.let { message ->
        EmojiPickerSheet(onPick = { emoji -> model.react(conversationId, message.id, emoji) }, onDismiss = { reactingTo = null })
    }
    viewing?.let { selection ->
        MediaViewer(selection, state, onDownload = { model.download(it, first = true) }, onDismiss = { viewing = null })
    }
}

/** Under a message with a thread: who replied, how many, and what's new. */
@Composable
fun ThreadSummaryChip(thread: ThreadSummary, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.primary
    val replies = if (thread.replyCount == 1u) "1 reply" else "${thread.replyCount} replies"
    val new = if (thread.unreadCount > 0u) ", ${thread.unreadCount} new" else ""
    Row(
        modifier
            .clip(CircleShape)
            .background(incomingBubble(dark))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clearAndSetSemantics {
                contentDescription = "Thread: $replies$new, from ${thread.participants.joinToString(", ")}"
                role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            thread.participants.reversed().forEachIndexed { index, name ->
                Box(
                    Modifier
                        .offset(x = (index * 14).dp)
                        .size(20.dp)
                        .border(1.5.dp, incomingBubble(dark), CircleShape),
                ) {
                    Avatar(name, size = 20)
                }
            }
            // Room for the overlapping avatars.
            Box(Modifier.size(width = (20 + (thread.participants.size - 1).coerceAtLeast(0) * 14).dp, height = 20.dp))
        }
        Text(replies, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
        if (thread.unreadCount > 0u) {
            Box(Modifier.size(6.dp).background(accent, CircleShape))
            Text("${thread.unreadCount} new", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = accent)
        } else {
            Text(shortTime(thread.lastReplyAtMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
