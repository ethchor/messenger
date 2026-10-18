package com.qwantarc.messenger.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import androidx.core.content.ContextCompat
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.LocationSharingService
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.MemberView
import com.qwantarc.messenger.core.MessageStatus
import com.qwantarc.messenger.core.MessageView
import com.qwantarc.messenger.core.SafetyNumber
import com.qwantarc.messenger.parseUsernames
import com.qwantarc.messenger.userMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: UiState, model: AppViewModel) {
    val conversationId = state.openConversationId ?: return
    val conversation = state.openConversation
    val canSend = conversation?.active == true && !conversation.leaving
    val placeholder = when {
        conversation == null -> "Message"
        conversation.leaving -> "Leaving this chat…"
        !conversation.active ->
            if (conversation.kind == ConversationKind.DIRECT) "This chat has ended" else "You're no longer in this group"
        else -> "Message"
    }
    var draft by remember { mutableStateOf("") }
    var replyTo by remember { mutableStateOf<MessageView?>(null) }
    var editing by remember { mutableStateOf<MessageView?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val typingNames = state.typing[conversationId].orEmpty()

    BackHandler { model.closeConversation() }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }
    // Typing indicators expire on their own; look again after a while.
    LaunchedEffect(conversationId, typingNames) {
        if (typingNames.isNotEmpty()) {
            delay(6_500)
            model.refreshTyping(conversationId)
        }
    }
    DisposableEffect(conversationId) {
        onDispose { model.setTyping(conversationId, false) }
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
            AnimatedVisibility(visible = typingNames.isNotEmpty()) {
                TypingIndicator(
                    names = typingNames,
                    isGroup = conversation?.kind == ConversationKind.GROUP,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                TextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        model.setTyping(conversationId, it.isNotBlank())
                    },
                    enabled = canSend,
                    placeholder = { Text(placeholder) },
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
                            MessageStatus.READ -> "✓✓"
                            MessageStatus.RECEIVED -> ""
                        }
                        if (status.isNotEmpty()) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (message.status == MessageStatus.READ) FontWeight.Bold else null,
                                color = textColor.copy(alpha = if (message.status == MessageStatus.READ) 1f else 0.7f),
                                modifier = Modifier.semantics {
                                    contentDescription = when (message.status) {
                                        MessageStatus.READ -> "Read"
                                        MessageStatus.SENT -> "Sent"
                                        else -> status
                                    }
                                },
                            )
                        }
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
                if (message.outgoing && message.readBy.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text("Read by ${message.readBy.joinToString(", ")}") },
                        onClick = {},
                        enabled = false,
                    )
                }
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
    val isGroup = conversation?.kind == ConversationKind.GROUP
    val activeGroup = isGroup && conversation?.active == true && !conversation.leaving
    val canLeave = conversation?.active == true && !conversation.leaving
    var members by remember { mutableStateOf<List<MemberView>>(emptyList()) }
    var safety by remember { mutableStateOf<SafetyNumber?>(null) }
    var newMembers by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
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
                    SafetyNumberCard(number, conversation?.title ?: "them")
                }
                if (canLeave) {
                    HorizontalDivider()
                    LocationControls(conversationId, state, model) {
                        onDismiss()
                        model.openMap()
                    }
                    HorizontalDivider()
                    TextButton(
                        enabled = !leaving,
                        onClick = { confirmLeave = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(if (isGroup) "Leave group" else "End chat") }
                } else if (conversation?.leaving == true) {
                    HorizontalDivider()
                    Text(
                        "Leaving. The others see it once one of them is online.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (isGroup) "Leave this group?" else "End this chat?") },
            text = {
                Text(
                    if (isGroup) {
                        "The others see that you left. You won't get new messages from this group."
                    } else {
                        "${conversation?.title ?: "They"} will see that you left. You can start a new chat later."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmLeave = false
                        leaving = true
                        scope.launch {
                            try {
                                model.leave(conversationId)
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.userMessage
                            } finally {
                                leaving = false
                            }
                        }
                    },
                ) { Text(if (isGroup) "Leave group" else "End chat") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

private enum class SharingChoice(val label: String) {
    HOUR("For 1 hour"),
    END_OF_DAY("Until end of day"),
    UNTIL_STOPPED("Until I stop");

    fun untilMs(): Long? = when (this) {
        HOUR -> System.currentTimeMillis() + 3_600_000L
        END_OF_DAY -> Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 0)
        }.timeInMillis
        UNTIL_STOPPED -> null
    }
}

/** Location permissions still missing; notifications too (the sharing notice). */
private fun missingPermissions(context: Context): Array<String> = buildList {
    if (!LocationSharingService.hasLocationPermission(context)) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()

/** Share my location (and see who shares theirs) in a chat's info. */
@Composable
private fun LocationControls(conversationId: String, state: UiState, model: AppViewModel, onShowMap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val share = state.locationShare(conversationId)
    val theirs = state.sharedLocations.filter { it.conversationId == conversationId }
    var choosing by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SharingChoice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun start(choice: SharingChoice) {
        scope.launch {
            try {
                model.startSharingLocation(conversationId, choice.untilMs())
                error = null
            } catch (e: Exception) {
                error = e.userMessage
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val choice = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (LocationSharingService.hasLocationPermission(context)) {
            start(choice)
        } else {
            error = "Allow location access to share your location."
        }
    }

    Text("Location", style = MaterialTheme.typography.titleSmall)
    if (share != null) {
        Text("Sharing your location · ${sharingUntilText(share.untilMs)}", style = MaterialTheme.typography.bodyMedium)
        TextButton(
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            onClick = {
                scope.launch {
                    try {
                        model.stopSharingLocation(conversationId)
                    } catch (e: Exception) {
                        error = e.userMessage
                    }
                }
            },
        ) { Text("Stop sharing my location") }
    } else {
        TextButton(onClick = { choosing = true }) { Text("Share my location") }
    }
    theirs.forEach { person ->
        TextButton(onClick = onShowMap) { Text("${person.username} is sharing their location · Show on map") }
    }
    Text(
        "Your location is end-to-end encrypted. Only the people in this chat see it, only while you share, and you can stop at any time.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Share my location") },
            text = {
                Column {
                    SharingChoice.entries.forEach { choice ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                choosing = false
                                val missing = missingPermissions(context)
                                if (missing.isEmpty()) {
                                    start(choice)
                                } else {
                                    pending = choice
                                    permissions.launch(missing)
                                }
                            },
                        ) { Text(choice.label, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }
}

/** The QR code and the 60 digits to compare with the other person. */
@Composable
internal fun SafetyNumberCard(number: SafetyNumber, otherName: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            "Compare these digits with $otherName in person, or scan each other's code. If they match, nobody is listening in the middle.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** "alice is typing" with three dots that pulse in turn. */
@Composable
private fun TypingIndicator(names: List<String>, isGroup: Boolean, modifier: Modifier = Modifier) {
    val text = when (names.size) {
        0 -> ""
        1 -> if (isGroup) "${names[0]} is typing" else "typing"
        2 -> "${names[0]} and ${names[1]} are typing"
        else -> "${names.size} people are typing"
    }
    val description = if (names.size == 1) "${names[0]} is typing" else "${names.joinToString(", ")} are typing"
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "dots",
    )
    Row(
        modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                Box(
                    Modifier
                        .size(6.dp)
                        .alpha(if (phase.toInt() % 3 == index) 1f else 0.35f)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape),
                )
            }
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun qrCode(payload: String, size: Int = 360): ImageBitmap? = runCatching {
    val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
}.getOrNull()
