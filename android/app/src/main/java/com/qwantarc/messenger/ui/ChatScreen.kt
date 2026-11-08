package com.qwantarc.messenger.ui

import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import com.qwantarc.messenger.core.ConversationSummary
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.CompositionLocalProvider
import com.qwantarc.messenger.core.mutedForever
import androidx.compose.material3.Switch
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.compositeOver
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
import com.qwantarc.messenger.AttachmentRef
import com.qwantarc.messenger.LocationSharingService
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.AttachmentKind
import com.qwantarc.messenger.core.AttachmentView
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

internal val QuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/** A chat; dark all over while it's in vanish mode, as on Instagram. */
@Composable
fun ChatScreen(state: UiState, model: AppViewModel) {
    if (state.openConversation?.vanishMode == true) {
        CompositionLocalProvider(LocalForceDark provides true) {
            MessengerTheme(LocalAppearance.current) { ChatScreenContent(state, model) }
        }
    } else {
        ChatScreenContent(state, model)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(state: UiState, model: AppViewModel) {
    val conversationId = state.openConversationId ?: return
    val conversation = state.openConversation
    val canSend = conversation?.active == true && !conversation.leaving
    val inVanish = conversation?.vanishMode == true
    // Vanish mode is for 1:1 chats still going.
    val canVanish = canSend && conversation?.kind == ConversationKind.DIRECT && !conversation.blocked
    // How far a swipe up past the last message has gone, 0 to 1.
    var vanishPull by remember { mutableFloatStateOf(0f) }
    val pullDistance = with(LocalDensity.current) { 120.dp.toPx() }
    val vanishScroll = remember(canVanish, inVanish, pullDistance) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                // Dragging up with nothing left to scroll: the list is at its end.
                if (canVanish && source == NestedScrollSource.UserInput && available.y < 0f) {
                    vanishPull = (vanishPull - available.y / pullDistance).coerceAtMost(1f)
                    return available
                }
                if (vanishPull > 0f && available.y > 0f) vanishPull = (vanishPull - available.y / pullDistance).coerceAtLeast(0f)
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (vanishPull >= 1f) model.setVanishMode(conversationId, !inVanish)
                vanishPull = 0f
                return Velocity.Zero
            }
        }
    }
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
    var reactingTo by remember { mutableStateOf<MessageView?>(null) }
    var showInfo by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<MediaSelection?>(null) }
    val context = LocalContext.current
    var sharingSheet by remember { mutableStateOf(false) }
    val trayKey = model.trayKey(conversationId, null)
    val tray = model.tray(trayKey)
    val appearance = LocalAppearance.current
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
        onDispose {
            model.setTyping(conversationId, false)
            // Seen vanish-mode messages go when the chat closes.
            model.closeChat(conversationId)
        }
    }
    // In vanish mode a screenshot is announced (Android 14 and later can tell).
    val activity = context as? android.app.Activity
    DisposableEffect(conversationId, inVanish, activity) {
        if (!inVanish || activity == null || android.os.Build.VERSION.SDK_INT < 34) return@DisposableEffect onDispose { }
        val callback = android.app.Activity.ScreenCaptureCallback { model.reportScreenshot(conversationId) }
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        onDispose { activity.unregisterScreenCaptureCallback(callback) }
    }

    Box(Modifier.fillMaxSize()) {
        HomeGround(Modifier.matchParentSize(), vanish = inVanish)
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            ChatHeader(conversation, typing = typingNames.isNotEmpty(), onBack = model::closeConversation) { showInfo = true }
            // The messages and the composer sit on a dark sheet, in both
            // modes, with the chat's paper on it.
            val sheet = if (inVanish) Palette.VanishSheet else sheetColor()
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
                    .background(sheet),
            ) {
            CompositionLocalProvider(LocalForceDark provides true) {
            MessengerTheme(appearance) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
            if (inVanish) VanishBanner { model.setVanishMode(conversationId, false) }
            val liveTouch = rememberLiveTouch()
            Box(Modifier.weight(1f).fillMaxWidth().liveTouch(liveTouch).nestedScroll(vanishScroll)) {
                if (!inVanish) {
                    PaperBackground(
                        appearance.paperFor(conversationId),
                        appearance.ink,
                        Modifier.matchParentSize(),
                        touch = liveTouch,
                        pulse = state.messages.lastOrNull()?.let { LivePulse(it.id, it.outgoing) },
                    )
                }
                if (vanishPull > 0f) {
                    VanishPullHint(vanishPull, turningOn = !inVanish, Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp))
                }
                if (state.messages.isEmpty() && conversation?.active == true) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(Modifier.size(140.dp, 101.dp)) {
                            ShapeSticker(ShapeKind.Bubble, Modifier.align(Alignment.BottomStart).offset(x = 11.dp), size = 101.dp, delayMs = 20)
                            ShapeSticker(ShapeKind.Heart, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = 3.dp), size = 42.dp, delayMs = 220)
                            ShapeSticker(ShapeKind.Sparkle, Modifier.offset(y = 6.dp), size = 22.dp, delayMs = 340, phase = -1f)
                        }
                        Text("Nothing here yet. Go on, break the ice.", fontSize = 14.5.sp, color = Palette.Cream.copy(alpha = 0.75f), modifier = Modifier.padding(top = 14.dp))
                        Text(
                            if (conversation.kind == ConversationKind.DIRECT) "Only you and ${conversation.title} can read what's sent here."
                            else "Only the people in ${conversation.title} can read what's sent here.",
                            fontSize = 12.5.sp, color = Palette.Cream.copy(alpha = 0.5f), textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 14.dp, start = 30.dp, end = 30.dp),
                        )
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(state.messages, key = { _, message -> message.id }) { index, message ->
                        MessageRow(
                            message = message,
                            conversationId = conversationId,
                            isGroup = conversation?.kind == ConversationKind.GROUP,
                            run = runAt(state.messages, index),
                            fetching = state.fetching,
                            onReact = { emoji -> model.react(conversationId, message.id, emoji) },
                            onMoreReactions = { reactingTo = message },
                            onEdit = { editing = message },
                            onDelete = { model.delete(conversationId, message.id) },
                            onOpenAttachment = { attachment ->
                                openAttachment(context, attachment, message, conversationId) { viewing = it }
                            },
                            onDownload = { ref -> model.download(ref, first = true) },
                            onReply = { replyTo = message },
                            onReplyInThread = { model.openThread(message.id) },
                            onOpenThread = { model.openThread(message.id) },
                        )
                    }
                }
            }
            replyTo?.let { reply ->
                Row(
                    Modifier
                        .padding(start = 10.dp, end = 10.dp, top = 6.dp)
                        .fillMaxWidth()
                        .background(incomingBubble(true), RoundedCornerShape(18.dp))
                        .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyphs.Reply, contentDescription = null, tint = Palette.Marigold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Replying to ${if (reply.outgoing) "yourself" else reply.sender}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Palette.Marigold,
                        )
                        Text(reply.previewText, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { replyTo = null }) { Icon(Glyphs.Close, contentDescription = "Cancel reply") }
                }
            }
            AnimatedVisibility(visible = typingNames.isNotEmpty()) {
                TypingIndicator(
                    names = typingNames,
                    isGroup = conversation?.kind == ConversationKind.GROUP,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // Someone you blocked: no sending until you unblock them.
            if (conversation?.kind == ConversationKind.DIRECT && conversation.blocked) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("You blocked ${conversation.title}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { model.setBlocked(conversation.title, false) }) { Text("Unblock") }
                }
            } else {
            MessageComposer(
                draft = draft,
                onDraftChange = {
                    draft = it
                    model.setTyping(conversationId, it.isNotBlank())
                },
                placeholder = placeholder,
                enabled = canSend,
                onSend = {
                    val text = draft.trim()
                    val files = tray.take()
                    draft = ""
                    val reply = replyTo?.id
                    replyTo = null
                    if (files.isEmpty()) {
                        model.send(conversationId, text, reply)
                    } else {
                        model.sendMedia(conversationId, text, files, reply, inThread = false)
                    }
                },
                tray = tray,
                trayKey = trayKey,
                onVoiceNote = { note ->
                    val reply = replyTo?.id
                    replyTo = null
                    model.sendVoiceNote(conversationId, note, reply, inThread = false)
                },
                onLocation = if (conversation?.active == true && conversation.leaving == false) ({ sharingSheet = true }) else null,
            )
            if (sharingSheet) ShareLocationSheet(conversationId, conversation?.title ?: "They", model) { sharingSheet = false }
            }
            }
            }
            }
            }
        }
    }

    reactingTo?.let { message ->
        EmojiPickerSheet(onPick = { emoji -> model.react(conversationId, message.id, emoji) }, onDismiss = { reactingTo = null })
    }

    viewing?.let { selection ->
        MediaViewer(selection, state, onDownload = { model.download(it, first = true) }, onDismiss = { viewing = null })
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

/** Where a message sits among consecutive messages from the same person. */
internal data class MessageRun(val first: Boolean, val last: Boolean)

internal fun runAt(messages: List<MessageView>, index: Int): MessageRun {
    fun same(a: MessageView, b: MessageView) = !a.system && !b.system && a.outgoing == b.outgoing && a.sender == b.sender
    val message = messages[index]
    val previous = messages.getOrNull(index - 1)
    val next = messages.getOrNull(index + 1)
    return MessageRun(first = previous == null || !same(previous, message), last = next == null || !same(message, next))
}

/**
 * One message, with its reactions and, if it has one, its thread. [onReply]
 * quotes it in the chat's composer and [onOpenThread] opens its thread;
 * both are null inside a thread.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MessageRow(
    message: MessageView,
    conversationId: String,
    isGroup: Boolean,
    run: MessageRun,
    fetching: Set<AttachmentRef>,
    onReact: (String?) -> Unit,
    onMoreReactions: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenAttachment: (AttachmentView) -> Unit,
    onDownload: (AttachmentRef) -> Unit,
    onReply: (() -> Unit)? = null,
    onReplyInThread: (() -> Unit)? = null,
    onOpenThread: (() -> Unit)? = null,
) {
    if (message.system) {
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            Text(
                message.text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .drawBehind {
                        drawRoundRect(
                            line,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                            ),
                        )
                    }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        return
    }
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val mine = message.reactions.firstOrNull { it.mine }?.emoji
    val dark = isDark()
    val ink = LocalAppearance.current.ink
    val bubbleColor = when {
        message.deleted -> Color.Transparent
        message.outgoing -> ink.bubble(dark)
        else -> incomingBubble(dark)
    }
    val textColor = if (message.outgoing) ink.onBubble else Palette.Cream
    val showsSender = isGroup && !message.outgoing
    // Rounded all round; the corner by the tail stays tight, and so does
    // the top one where it joins the bubble before.
    val big = 22.dp
    val small = 8.dp
    val top = if (run.first) big else small
    val shape = if (message.outgoing) {
        RoundedCornerShape(topStart = big, topEnd = top, bottomEnd = small, bottomStart = big)
    } else {
        RoundedCornerShape(topStart = top, topEnd = big, bottomEnd = big, bottomStart = small)
    }

    // Swipe a bubble right to reply: an arrow grows, and past the line it
    // ticks and quotes the message on release.
    val swipe = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val replyAt = with(density) { 56.dp.toPx() }
    var hearts by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth()) {
    if (swipe.value > 0f) {
        val p = (swipe.value / replyAt).coerceIn(0f, 1f)
        Box(
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(30.dp)
                .graphicsLayer { alpha = p; val k = if (p >= 1f) 1.15f else 0.6f + 0.4f * p; scaleX = k; scaleY = k }
                .clip(CircleShape).background(Palette.Cream.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.Reply, contentDescription = null, tint = Palette.Cream, modifier = Modifier.size(16.dp)) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = swipe.value }
            .then(
                if (onReply == null || message.deleted) Modifier else Modifier.pointerInput(message.id) {
                    var ticked = false
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipe.value >= replyAt) onReply()
                            ticked = false
                            scope.launch { swipe.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)) }
                        },
                        onDragCancel = { scope.launch { swipe.animateTo(0f) } },
                    ) { change, amount ->
                        if (swipe.value + amount * 0.75f > 0f || swipe.value > 0f) change.consume()
                        val next = (swipe.value + amount * 0.75f).coerceIn(0f, with(density) { 84.dp.toPx() })
                        scope.launch { swipe.snapTo(next) }
                        if (!ticked && next >= replyAt) { ticked = true; haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                        if (next < replyAt) ticked = false
                    }
                },
            )
            .padding(top = if (run.first) 8.dp else 2.dp),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showsSender) {
            // Only the last of a run carries the avatar; the others keep its place.
            if (run.last) Avatar(message.sender, size = 28) else Spacer(Modifier.width(28.dp))
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
            if (showsSender && run.first) {
                val senderInk = inkFor(message.sender, LocalProfiles.current)
                Row(
                    Modifier.padding(start = 4.dp, bottom = 4.dp).semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(20.dp).background(senderInk.pastel, RoundedCornerShape(7.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(message.sender.take(1).uppercase(), color = senderInk.light, fontSize = 11.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(message.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = senderInk.color(dark))
                }
            }
            Box {
                Surface(
                    color = bubbleColor,
                    shape = shape,
                    border = if (message.deleted) androidx.compose.foundation.BorderStroke(1.dp, incomingBubble(true)) else null,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!message.deleted) menuOpen = true },
                            // Double-tap: a heart, with a little burst of them.
                            onDoubleClick = {
                                if (!message.deleted) {
                                    if (mine != "❤️") onReact("❤️")
                                    hearts++
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            },
                        ),
                ) {
                    val visual = if (message.deleted) emptyList() else message.attachments.filter { it.isVisual }
                    val others = if (message.deleted) emptyList() else message.attachments.filter { !it.isVisual }
                    // Pictures with nothing else: the time goes on them.
                    val timeOnPictures = visual.isNotEmpty() && others.isEmpty() && message.text.isEmpty() && !message.deleted
                    val openMenu: () -> Unit = { if (!message.deleted) menuOpen = true }
                    Column(
                        (if (visual.isNotEmpty()) Modifier.width(MediaWidth + 6.dp) else Modifier)
                            .padding(
                                top = if (visual.isNotEmpty() && message.replyTo == null) 3.dp else 8.dp,
                                bottom = if (timeOnPictures) 3.dp else 8.dp,
                            ),
                    ) {
                        message.replyTo?.let { reply ->
                            Column(
                                Modifier
                                    .padding(horizontal = 12.dp)
                                    .fillMaxWidth()
                                    .background(Color.Black.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                    .padding(6.dp),
                            ) {
                                Text(reply.sender, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = textColor)
                                Text(reply.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, color = textColor)
                            }
                            Spacer(Modifier.size(4.dp))
                        }
                        if (visual.isNotEmpty()) {
                            // Nearly to the bubble's edge.
                            Box(Modifier.padding(horizontal = 3.dp)) {
                                MediaGrid(
                                    items = visual,
                                    conversationId = conversationId,
                                    messageId = message.id,
                                    fetching = fetching,
                                    onOpen = onOpenAttachment,
                                    onDownload = onDownload,
                                    onLongPress = openMenu,
                                )
                                if (timeOnPictures) {
                                    MessageFooter(
                                        message,
                                        Color.White,
                                        Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp)
                                            .background(Color.Black.copy(alpha = 0.4f), CircleShape)
                                            .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (!timeOnPictures) Spacer(Modifier.size(4.dp))
                        }
                        others.forEach { attachment ->
                            val ref = AttachmentRef(conversationId, message.id, attachment.position)
                            Box(Modifier.padding(horizontal = 12.dp)) {
                                if (attachment.kind == AttachmentKind.VOICE) {
                                    VoiceNoteRow(attachment, fetching = ref in fetching, color = textColor, onDownload = { onDownload(ref) })
                                } else {
                                    FileRow(
                                        attachment,
                                        fetching = ref in fetching,
                                        outgoing = message.outgoing,
                                        color = textColor,
                                        onOpen = { onOpenAttachment(attachment) },
                                        onDownload = { onDownload(ref) },
                                        onLongPress = openMenu,
                                    )
                                }
                            }
                            Spacer(Modifier.size(4.dp))
                        }
                        if (message.deleted || message.text.isNotEmpty()) {
                            Text(
                                if (message.deleted) "Message deleted" else message.text,
                                color = textColor,
                                fontStyle = if (message.deleted) FontStyle.Italic else FontStyle.Normal,
                                modifier = Modifier.padding(start = 14.dp, end = 12.dp),
                            )
                        }
                        if (!timeOnPictures) MessageFooter(message, textColor, Modifier.padding(horizontal = 12.dp))
                    }
                }
                HeartBurst(hearts, Modifier.align(Alignment.Center))
                PopMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Row(Modifier.padding(horizontal = 8.dp)) {
                        QuickReactions.forEach { emoji ->
                            TextButton(onClick = { menuOpen = false; onReact(if (mine == emoji) null else emoji) }) { Text(emoji) }
                        }
                    }
                    PopMenuItem("More reactions…", Glyphs.Smile, onClick = { menuOpen = false; onMoreReactions() })
                    onReply?.let { reply ->
                        PopMenuItem("Reply", Glyphs.Reply, onClick = { menuOpen = false; reply() })
                    }
                    onReplyInThread?.let { replyInThread ->
                        PopMenuItem("Reply in thread", Glyphs.Thread, onClick = { menuOpen = false; replyInThread() })
                    }
                    if (message.text.isNotEmpty()) {
                        PopMenuItem("Copy", Glyphs.Copy, onClick = { menuOpen = false; clipboard.setText(AnnotatedString(message.text)) })
                    }
                    if (message.outgoing && message.readBy.isNotEmpty()) {
                        PopMenuItem("Read by ${message.readBy.joinToString(", ")}", Glyphs.Checked, enabled = false, onClick = {})
                    }
                    if (message.outgoing) {
                        PopMenuItem("Edit", Glyphs.Pencil, onClick = { menuOpen = false; onEdit() })
                        PopMenuItem("Delete for everyone", Glyphs.Trash, destructive = true, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            if (message.reactions.isNotEmpty()) {
                ReactionChips(
                    message.reactions,
                    onToggle = { emoji, isMine -> onReact(if (isMine) null else emoji) },
                    onMore = onMoreReactions,
                    modifier = Modifier.offset(y = (-12).dp).padding(horizontal = 10.dp),
                )
            }
            val thread = message.thread
            if (thread != null && onOpenThread != null) {
                ThreadSummaryChip(thread, onOpenThread, Modifier.padding(top = 4.dp))
            }
        }
    }
    }
}

/** Five hearts that float up and out from the middle of a bubble, then go. */
@Composable
private fun HeartBurst(trigger: Int, modifier: Modifier = Modifier) {
    if (trigger == 0 || reduceMotion()) return
    val progress = remember(trigger) { Animatable(0f) }
    LaunchedEffect(trigger) { progress.animateTo(1f, tween(900, easing = CubicBezierEasing(0.2f, 0.8f, 0.3f, 1f))) }
    if (progress.value >= 1f) return
    val flights = listOf(Triple(-34f, -46f, -18f), Triple(-10f, -60f, 8f), Triple(14f, -54f, 20f), Triple(34f, -40f, 30f), Triple(0f, -30f, -6f))
    Box(modifier.clearAndSetSemantics {}) {
        flights.forEachIndexed { i, (x, y, r) ->
            val p = (progress.value - i * 0.04f).coerceIn(0f, 1f)
            Text(
                "❤️",
                fontSize = 22.sp,
                modifier = Modifier.graphicsLayer {
                    translationX = x.dp.toPx() * p; translationY = y.dp.toPx() * p
                    val k = 0.3f + 0.8f * p; scaleX = k; scaleY = k
                    rotationZ = r * p
                    alpha = if (p < 0.25f) p / 0.25f else 1 - (p - 0.25f) / 0.75f
                },
            )
        }
    }
}

/** When it was sent, whether it was edited, and how far it got. */
@Composable
private fun MessageFooter(message: MessageView, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (message.edited && !message.deleted) Text("edited", style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
        Text(shortTime(message.sentAtMs), style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.7f))
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
                color = color.copy(alpha = if (message.status == MessageStatus.READ) 1f else 0.7f),
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
    var confirm by remember { mutableStateOf<ChatConfirm?>(null) }
    var muteMenu by remember { mutableStateOf(false) }
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

    // One page, as in the design: who it is, three big actions, then cards.
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val t = tones()
        Box(Modifier.fillMaxSize()) {
            HomeGround(Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(t.text).clickable(onClick = onDismiss)
                            .semantics { contentDescription = "Back to chat"; role = Role.Button },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Glyphs.Back, contentDescription = null, tint = t.home, modifier = Modifier.size(18.dp)) }
                    Text(
                        if (isGroup) "Group" else "Contact", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(40.dp))
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 44.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    conversation?.let { c ->
                        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Avatar(c.title, isGroup = isGroup, size = 108, stamp = true)
                            Text(c.title, style = displayStyle(28.sp), color = t.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                            Text(if (isGroup) "Group · ${members.size} members" else "@${c.title}", fontSize = 14.5.sp, color = t.secondary)
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                Icon(Glyphs.Lock, contentDescription = null, tint = t.secondary, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Sealed end to end", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = t.secondary)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoAction("Message", Glyphs.Chats, filled = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                            InfoAction("Map", Glyphs.Map, modifier = Modifier.weight(1f)) { onDismiss(); model.openMap() }
                            InfoAction(if (c.archived) "Unarchive" else "Archive", Glyphs.Archive, modifier = Modifier.weight(1f)) { model.setArchived(c.id, !c.archived) }
                        }
                        InfoCard {
                            Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text("Mute", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
                                    Text(
                                        when (val until = c.mutedUntilMs) {
                                            null -> "Off"
                                            mutedForever() -> "Always"
                                            else -> "Until " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(until))
                                        },
                                        fontSize = 13.5.sp, color = t.secondary,
                                    )
                                }
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip("Off", selected = c.mutedUntilMs == null) { model.unmute(c.id) }
                                    FilterChip("8 hours", selected = false) { model.mute(c.id, 8 * HOUR) }
                                    FilterChip("1 week", selected = false) { model.mute(c.id, 7 * 24 * HOUR) }
                                    FilterChip("Always", selected = c.mutedUntilMs == mutedForever()) { model.mute(c.id, null) }
                                }
                            }
                            InfoDivider()
                            InfoToggle("Lock chat", "Only your fingerprint, face or screen lock opens it. Your list shows “Locked chat”, and notifications just say “New message”.", c.locked) { model.setLocked(c.id, it) }
                            if (c.kind == ConversationKind.DIRECT && c.active && !c.blocked) {
                                InfoDivider()
                                InfoToggle("Vanish mode", "For both of you: messages disappear once they’re seen and the chat is closed, and the other person is told about screenshots. Or swipe up at the end of the chat.", c.vanishMode) { model.setVanishMode(c.id, it) }
                            }
                            if (!c.archived) {
                                InfoDivider()
                                InfoToggle("Pin to top", "A big card at the top of your chats.", c.pinned) { model.setPinned(c.id, it) }
                            }
                        }
                    }
                    if (canLeave) {
                        InfoCard(title = "Location") {
                            Box(Modifier.padding(vertical = 8.dp)) {
                                LocationControls(conversationId, state, model) {
                                    onDismiss()
                                    model.openMap()
                                }
                            }
                        }
                    }
                    if (isGroup) {
                        InfoCard(title = "Members", detail = "${members.size}") {
                            members.forEach { member ->
                                InfoDivider()
                                Row(Modifier.padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Avatar(member.username, size = 38, stamp = true)
                                    Spacer(Modifier.width(12.dp))
                                    Text(member.username, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = t.text, modifier = Modifier.weight(1f))
                                    if (member.isMe) Text("You", fontSize = 13.sp, color = t.secondary)
                                    if (activeGroup && !member.isMe) {
                                        Text(
                                            "Remove", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.danger,
                                            modifier = Modifier.clickable {
                                                scope.launch {
                                                    try { model.removeMember(conversationId, member.username); reload() } catch (e: Exception) { error = e.userMessage }
                                                }
                                            }.padding(6.dp),
                                        )
                                    }
                                }
                            }
                            if (activeGroup) {
                                InfoDivider()
                                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(13.dp)).background(Palette.Marigold), contentAlignment = Alignment.Center) {
                                        Icon(Glyphs.Plus, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Box(Modifier.weight(1f)) {
                                        if (newMembers.isEmpty()) Text("Add someone: alice, bob", fontSize = 15.5.sp, color = t.secondary)
                                        androidx.compose.foundation.text.BasicTextField(
                                            value = newMembers, onValueChange = { newMembers = it }, singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.5.sp, color = t.text),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(t.text),
                                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add someone" },
                                        )
                                    }
                                    if (parseUsernames(newMembers).isNotEmpty()) {
                                        Text(
                                            "Add", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text,
                                            modifier = Modifier.clickable {
                                                scope.launch {
                                                    try {
                                                        model.addMembers(conversationId, parseUsernames(newMembers))
                                                        newMembers = ""; error = null; reload()
                                                    } catch (e: Exception) { error = e.userMessage }
                                                }
                                            }.padding(6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    safety?.let { number ->
                        InfoCard(title = "Safety number", detail = "60 digits") {
                            Box(Modifier.padding(vertical = 8.dp)) { SafetyNumberCard(number, conversation?.title ?: "them") }
                        }
                    }
                    InfoCard {
                        Box(Modifier.padding(vertical = 12.dp)) { ChatPaperChooser(conversationId) }
                    }
                    conversation?.let { c ->
                        InfoCard {
                            if (canLeave) {
                                InfoDanger(if (isGroup) "Leave group" else "End chat", enabled = !leaving) { confirmLeave = true }
                            } else if (c.leaving) {
                                Text("Leaving. The others see it once one of them is online.", fontSize = 13.5.sp, color = t.secondary, modifier = Modifier.padding(vertical = 14.dp))
                            }
                            if (c.kind == ConversationKind.DIRECT) {
                                InfoDivider()
                                if (c.blocked) {
                                    InfoDanger("Unblock ${c.title}", color = t.text) { model.setBlocked(c.title, false) }
                                } else {
                                    InfoDanger("Block ${c.title}") { confirm = ChatConfirm.Block(c) }
                                }
                            }
                            InfoDivider()
                            InfoDanger("Clear chat") { confirm = ChatConfirm.Clear(c) }
                            InfoDivider()
                            InfoDanger("Delete chat") { confirm = ChatConfirm.Delete(c) }
                        }
                        Text(
                            if (isGroup) "You stop getting this group’s messages, and the others see that you left." else "Ends the chat for both of you. You can start a new one any time.",
                            fontSize = 12.5.sp, color = t.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                    }
                    error?.let { Text(it, color = t.danger, fontSize = 13.sp) }
                }
            }
        }
    }

    confirm?.let { c ->
        ChatConfirmDialog(c, model) {
            confirm = null
            if (c is ChatConfirm.Delete) onDismiss()
        }
    }

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

/** Over a chat in vanish mode: what it means, and a way out. */
@Composable
private fun VanishBanner(turnOff: () -> Unit) {
    Row(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 10.dp)
            .fillMaxWidth()
            .background(Palette.VanishAccent.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
            .border(1.dp, Palette.VanishAccent.copy(alpha = 0.3f), RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Glyphs.EyeSlash, contentDescription = null, tint = Palette.VanishAccent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Vanish mode", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "Seen messages disappear when you close the chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .height(34.dp)
                .clip(CircleShape)
                .background(Palette.VanishAccent)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = turnOff)
                .padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Turn off", color = Palette.Ink, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Shown while swiping up past the last message: a ring that fills, then "Release". */
@Composable
private fun VanishPullHint(progress: Float, turningOn: Boolean, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val done = progress >= 1f
    LaunchedEffect(done) { if (done) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    Row(
        modifier.background(MaterialTheme.colorScheme.surface, CircleShape).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(20.dp), strokeWidth = 3.dp, color = Palette.VanishAccent)
        Spacer(Modifier.width(8.dp))
        val what = if (turningOn) "on" else "off"
        Text(
            if (done) "Release to turn $what vanish mode" else "Swipe up to turn $what vanish mode",
            style = MaterialTheme.typography.labelLarge,
        )
    }
}


/**
 * The top of a chat: a round back button, their stamp, their name in
 * display type, and what they're doing. Tapping the name opens the chat's
 * details. The iPhone app draws the same header (ChatHeader).
 */
@Composable
private fun ChatHeader(conversation: ConversationSummary?, typing: Boolean, onBack: () -> Unit, onInfo: () -> Unit) {
    val t = tones()
    val status = when {
        conversation == null -> ""
        typing -> "typing…"
        conversation.vanishMode -> "Vanish mode"
        conversation.kind == ConversationKind.GROUP -> "Group"
        conversation.active -> "End-to-end encrypted"
        else -> "This chat has ended"
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            Modifier
                .pressScale(source)
                .size(40.dp)
                .background(t.text, CircleShape)
                .clickable(interactionSource = source, indication = null, role = androidx.compose.ui.semantics.Role.Button, onClick = onBack)
                .semantics { contentDescription = "Back" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.Back, contentDescription = null, tint = t.home, modifier = Modifier.size(20.dp))
        }
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onInfo)
                .semantics(mergeDescendants = true) { contentDescription = "${conversation?.title ?: "Chat"}, $status. Details" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (conversation != null) {
                Avatar(conversation.title, isGroup = conversation.kind == ConversationKind.GROUP, size = 40, stamp = true)
            }
            Column {
                Text(conversation?.title ?: "", style = displayStyle(19.sp), color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, fontSize = 12.5.sp, color = if (typing) t.typing else t.secondary, maxLines = 1)
            }
        }
    }
}


/** One of the three big buttons under the name in a chat's info. */
@Composable
private fun InfoAction(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val t = tones()
    val shape = RoundedCornerShape(20.dp)
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Column(
        modifier.height(64.dp).pressScale(source).clip(shape)
            .background(if (filled) Palette.Marigold else t.surface)
            .then(if (filled) Modifier else Modifier.border(1.dp, t.line, shape))
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .semantics { role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Palette.Ink else t.text, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(5.dp))
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (filled) Palette.Ink else t.text)
    }
}

/** A rounded card in a chat's info, with an optional display-face title. */
@Composable
private fun InfoCard(title: String? = null, detail: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = tones()
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(t.surface).border(1.dp, t.line, shape).padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (title != null) {
            Row(Modifier.padding(top = 10.dp, bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
                Text(title, style = displayStyle(18.sp), color = t.text, modifier = Modifier.weight(1f).semantics { heading() })
                if (detail != null) Text(detail, fontSize = 13.sp, color = t.secondary)
            }
        }
        content()
    }
}

@Composable
private fun InfoDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(tones().line))

@Composable
private fun InfoToggle(label: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val t = tones()
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Text(hint, fontSize = 13.sp, lineHeight = 18.sp, color = t.secondary)
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(checked = on, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoDanger(label: String, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    val t = tones()
    Text(
        label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = (color ?: t.danger).copy(alpha = if (enabled) 1f else 0.5f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 16.dp).semantics { role = Role.Button },
    )
}


/** From the attach sheet: share a live location, for how long. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareLocationSheet(conversationId: String, title: String, model: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = tones()
    var pending by remember { mutableStateOf<SharingChoice?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun start(choice: SharingChoice) {
        scope.launch {
            try { model.startSharingLocation(conversationId, choice.untilMs()); onDismiss() } catch (e: Exception) { error = e.userMessage }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val choice = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (LocationSharingService.hasLocationPermission(context)) start(choice) else error = "Allow location access to share your location."
    }
    ModalBottomSheet(onDismissRequest = onDismiss, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp), containerColor = t.surface) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Share live location", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            Text("$title sees you move on the map. It's encrypted like a message, so the server never sees a place.", fontSize = 14.sp, lineHeight = 20.sp, color = t.secondary)
            val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
            SharingChoice.entries.forEach { choice ->
                val shape = RoundedCornerShape(16.dp)
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clip(shape).border(1.5.dp, t.line, shape)
                        .clickable {
                            val missing = missingPermissions(context)
                            if (missing.isEmpty()) start(choice) else { pending = choice; permissions.launch(missing) }
                        }
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (choice == SharingChoice.UNTIL_STOPPED) "Until you stop" else choice.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
                    Text(choice.untilMs()?.let { "until " + time.format(java.util.Date(it)) } ?: "you decide", fontSize = 13.5.sp, color = t.secondary)
                }
            }
            error?.let { Text(it, fontSize = 13.sp, color = t.danger) }
        }
    }
}
