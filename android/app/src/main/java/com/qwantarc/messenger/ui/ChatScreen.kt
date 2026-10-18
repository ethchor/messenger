package com.qwantarc.messenger.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.MemberView
import com.qwantarc.messenger.core.MessageStatus
import com.qwantarc.messenger.core.MessageView
import com.qwantarc.messenger.core.SafetyNumber
import com.qwantarc.messenger.parseUsernames
import com.qwantarc.messenger.userMessage
import kotlinx.coroutines.launch

private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: UiState, model: AppViewModel) {
    val conversationId = state.openConversationId ?: return
    val conversation = state.openConversation
    val canSend = conversation?.active == true
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<MessageView?>(null) }
    var editing by remember { mutableStateOf<MessageView?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    BackHandler { model.closeConversation() }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(conversation?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = model::closeConversation) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showInfo = true }) { Icon(Icons.Filled.Info, contentDescription = "Info") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            ) {
                items(state.messages, key = { it.id }) { message ->
                    MessageRow(
                        message = message,
                        showSender = conversation?.kind == ConversationKind.GROUP,
                        onReply = { replyTo = message },
                        onReact = { emoji -> model.react(conversationId, message.id, emoji) },
                        onEdit = { editing = message },
                        onDelete = { model.delete(conversationId, message.id) },
                    )
                }
            }
            replyTo?.let { reply ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Replying to ${if (reply.outgoing) "yourself" else reply.sender}", style = MaterialTheme.typography.labelMedium)
                        Text(reply.text, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    IconButton(onClick = { replyTo = null }) { Icon(Icons.Filled.Close, contentDescription = "Cancel reply") }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    enabled = canSend,
                    placeholder = { Text(if (canSend) "Message" else "You can't send messages here") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = canSend && draft.isNotBlank(),
                    onClick = {
                        val text = draft.trim()
                        draft = ""
                        val reply = replyTo?.id
                        replyTo = null
                        model.send(conversationId, text, reply)
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
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

    if (showInfo) {
        InfoDialog(conversationId, state, model) { showInfo = false }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: MessageView,
    showSender: Boolean,
    onReply: () -> Unit,
    onReact: (String?) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    if (message.system) {
        Text(
            message.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val mine = message.reactions.firstOrNull { it.mine }?.emoji
    val bubbleColor = if (message.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (message.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
    ) {
        if (showSender && !message.outgoing) {
            Text(message.sender, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
        }
        Box {
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .combinedClickable(onClick = {}, onLongClick = { if (!message.deleted) menuOpen = true }),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    message.replyTo?.let { reply ->
                        Column(
                            Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).padding(6.dp),
                        ) {
                            Text(reply.sender, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = textColor)
                            Text(reply.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = textColor)
                        }
                        Spacer(Modifier.size(4.dp))
                    }
                    Text(
                        if (message.deleted) "Message deleted" else message.text,
                        color = textColor,
                        fontStyle = if (message.deleted) FontStyle.Italic else FontStyle.Normal,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (message.edited && !message.deleted) Text("edited", style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                        Text(shortTime(message.sentAtMs), style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                        val status = when (message.status) {
                            MessageStatus.SENDING -> "sending"
                            MessageStatus.FAILED -> "failed"
                            MessageStatus.SENT -> "✓"
                            MessageStatus.RECEIVED -> ""
                        }
                        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall, color = textColor.copy(alpha = 0.7f))
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    QuickReactions.forEach { emoji ->
                        TextButton(onClick = { menuOpen = false; onReact(if (mine == emoji) null else emoji) }) { Text(emoji) }
                    }
                }
                DropdownMenuItem(text = { Text("Reply") }, onClick = { menuOpen = false; onReply() })
                DropdownMenuItem(text = { Text("Copy") }, onClick = { menuOpen = false; clipboard.setText(AnnotatedString(message.text)) })
                if (message.outgoing) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete for everyone") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
        if (message.reactions.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 2.dp)) {
                message.reactions.forEach { reaction ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (reaction.mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.combinedClickable(onClick = { onReact(if (reaction.mine) null else reaction.emoji) }),
                    ) {
                        Text(
                            if (reaction.count > 1u) "${reaction.emoji} ${reaction.count}" else reaction.emoji,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoDialog(conversationId: String, state: UiState, model: AppViewModel, onDismiss: () -> Unit) {
    val conversation = state.openConversation
    val activeGroup = conversation?.kind == ConversationKind.GROUP && conversation.active
    var members by remember { mutableStateOf<List<MemberView>>(emptyList()) }
    var safety by remember { mutableStateOf<SafetyNumber?>(null) }
    var newMembers by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun reload() {
        try {
            members = model.members(conversationId)
            if (conversation?.kind == ConversationKind.DIRECT && conversation.active) {
                safety = runCatching { model.safetyNumber(conversationId) }.getOrNull()
            }
        } catch (e: Exception) {
            error = e.userMessage
        }
    }
    LaunchedEffect(conversationId) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(conversation?.title ?: "Info") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Members", style = MaterialTheme.typography.titleSmall)
                members.forEach { member ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(member.username, size = 28)
                        Spacer(Modifier.width(8.dp))
                        Text(member.username + if (member.isMe) " (you)" else "", modifier = Modifier.weight(1f))
                        if (activeGroup && !member.isMe) {
                            TextButton(onClick = {
                                scope.launch {
                                    try {
                                        model.removeMember(conversationId, member.username)
                                        reload()
                                    } catch (e: Exception) {
                                        error = e.userMessage
                                    }
                                }
                            }) { Text("Remove") }
                        }
                    }
                }
                if (activeGroup) {
                    OutlinedTextField(value = newMembers, onValueChange = { newMembers = it }, label = { Text("Add people: alice, bob") })
                    TextButton(enabled = parseUsernames(newMembers).isNotEmpty(), onClick = {
                        scope.launch {
                            try {
                                model.addMembers(conversationId, parseUsernames(newMembers))
                                newMembers = ""
                                error = null
                                reload()
                            } catch (e: Exception) {
                                error = e.userMessage
                            }
                        }
                    }) { Text("Add to group") }
                }
                safety?.let { number ->
                    HorizontalDivider()
                    Text("Safety number", style = MaterialTheme.typography.titleSmall)
                    remember(number.qrPayload) { qrCode(number.qrPayload) }?.let { image ->
                        Image(
                            bitmap = image,
                            contentDescription = "Safety number QR code",
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.size(180.dp).align(Alignment.CenterHorizontally),
                        )
                    }
                    Text(
                        number.digits.split(" ").chunked(4).joinToString("\n") { it.joinToString("  ") },
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Compare these digits with ${conversation?.title ?: "them"} in person, or scan each other's code. If they match, nobody is listening in the middle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun qrCode(payload: String, size: Int = 360): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
