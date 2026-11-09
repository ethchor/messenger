package com.qwantarc.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.AttachmentKind
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.MessageView
import com.qwantarc.messenger.userMessage
import kotlinx.coroutines.launch

/**
 * "Forward to…", as designed: the message quoted, search, up to five chats
 * ticked, and one big button. Each chat gets its own encrypted copy, marked
 * "Forwarded". The iPhone has the same (ForwardSheet.swift).
 */
@Composable
fun ForwardSheet(conversationId: String, message: MessageView, state: UiState, model: AppViewModel, onDismiss: () -> Unit) {
    val t = tones()
    val scope = rememberCoroutineScope()
    val showToast = LocalShowToast.current
    val picked = remember { mutableStateListOf<String>() }
    var query by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val q = query.trim().lowercase()
    val chats = state.conversations.filter { it.active && !it.blocked && it.id != conversationId && !it.vanishMode }
        .filter { q.isEmpty() || it.title.lowercase().contains(q) }
    val visual = message.attachments.count { it.kind == AttachmentKind.PHOTO || it.kind == AttachmentKind.VIDEO }
    val quote = when {
        message.text.isNotEmpty() -> message.text
        visual > 1 -> "Album · $visual items"
        message.attachments.any { it.kind == AttachmentKind.VIDEO } -> "Video"
        message.attachments.any { it.kind == AttachmentKind.PHOTO } -> "Photo"
        else -> message.attachments.firstOrNull()?.fileName ?: "Message"
    }
    val label = when (picked.size) {
        0 -> "Pick a chat"
        1 -> "Send to ${state.conversations.firstOrNull { it.id == picked[0] }?.title ?: "1 chat"}"
        else -> "Send to ${picked.size} chats"
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
        ) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(t.home)
                    .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                    .navigationBarsPadding().imePadding().padding(top = 10.dp, bottom = 30.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(t.line))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Forward to…", style = displayStyle(22.sp), color = t.text, modifier = Modifier.weight(1f))
                    Text(
                        "Cancel", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.secondary,
                        modifier = Modifier.clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss).padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                Row(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(18.dp)).background(t.surface)
                        .border(1.dp, Kit.cardLine(), RoundedCornerShape(18.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(Palette.TilePink))
                    Text(quote, fontSize = 14.sp, lineHeight = 19.sp, color = t.secondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                ComposeSearchField(query, { query = it }, placeholder = "Search chats")
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                    KitCard(radius = 24.dp) {
                        chats.forEach { chat ->
                            val on = chat.id in picked
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                                    when {
                                        on -> picked.remove(chat.id)
                                        picked.size >= 5 -> showToast("Five chats at a time")
                                        else -> picked.add(chat.id)
                                    }
                                }.semantics { selected = on }.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Avatar(chat.title, isGroup = chat.kind == ConversationKind.GROUP, size = 40, stamp = true)
                                Text(chat.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, modifier = Modifier.weight(1f))
                                PickTick(on)
                            }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BigButton(label, enabled = picked.isNotEmpty(), working = working) {
                        working = true
                        scope.launch {
                            try {
                                val names = picked.mapNotNull { id -> state.conversations.firstOrNull { it.id == id }?.title }
                                model.forward(conversationId, message.id, picked.toList())
                                showToast("Forwarded to " + names.joinToString(", "))
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.userMessage
                            } finally {
                                working = false
                            }
                        }
                    }
                    Text(
                        error ?: "Up to 5 chats. Each gets its own encrypted copy, marked “Forwarded”.",
                        fontSize = 12.5.sp, color = if (error == null) t.secondary else t.danger, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
