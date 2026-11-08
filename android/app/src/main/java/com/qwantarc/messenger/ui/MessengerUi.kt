package com.qwantarc.messenger.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.key
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeOut
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qwantarc.messenger.AppLock
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.CAMERA_TRAY
import com.qwantarc.messenger.Phase
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.ConversationSummary
import com.qwantarc.messenger.parseUsernames
import com.qwantarc.messenger.userMessage
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** [onTurnOnLock] asks for the fingerprint, face or screen lock before app lock goes on. */
@Composable
fun MessengerUi(model: AppViewModel, lock: AppLock.State, onTurnOnLock: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalProfiles provides state.profiles, LocalProfilePhotos provides state.profilePhotos) {
        // The splash sits over the app until the core is open and its
        // animation has played (about 1.2 s), then scales away.
        var timeUp by remember { mutableStateOf(false) }
        var gone by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) { delay(1250); timeUp = true }
        val canGo = timeUp && state.phase != Phase.Starting && state.phase !is Phase.Failed
        LaunchedEffect(canGo) { if (canGo) gone = true }
        Box(Modifier.fillMaxSize()) {
            Screens(state, model, lock, onTurnOnLock)
            AnimatedVisibility(
                visible = !gone,
                enter = EnterTransition.None,
                exit = fadeOut(tween(450, easing = CubicBezierEasing(0.5f, 0f, 0.75f, 0f))) +
                    scaleOut(tween(450, easing = CubicBezierEasing(0.5f, 0f, 0.75f, 0f)), targetScale = 1.12f),
            ) {
                SplashScreen(
                    failure = (state.phase as? Phase.Failed)?.message,
                    slow = timeUp && state.phase == Phase.Starting,
                    retry = model::retryStart,
                )
            }
        }
    }
}

@Composable
private fun Screens(state: UiState, model: AppViewModel, lock: AppLock.State, onTurnOnLock: () -> Unit) {
    when (val phase = state.phase) {
        Phase.Starting -> Box(Modifier.fillMaxSize())
        Phase.Onboarding -> OnboardingScreen(state, model)
        is Phase.RecoveryKey -> RecoveryKeyScreen(phase.key, state.busy, model = model, state = state, lock = lock, onTurnOnLock = onTurnOnLock, onConfirm = model::confirmRecoveryKeySaved)
        Phase.Ready -> when {
            state.openConversationId != null && state.openThread != null -> ThreadScreen(state, model)
            state.openConversationId != null -> ChatScreen(state, model)
            // The tab roots, with the tab bar floating over their foot.
            else -> Box(Modifier.fillMaxSize()) {
                when {
                    state.mapOpen -> MapScreen(state, model)
                    state.settingsOpen -> SettingsScreen(state, model, lock, onTurnOnLock)
                    else -> ChatListScreen(state, model, lock.panicWipe)
                }
                MainTabBar(state, model, Modifier.align(Alignment.BottomCenter))
            }
        }
        is Phase.Failed -> Box(Modifier.fillMaxSize())
    }
    state.error?.let { message ->
        AlertDialog(
            onDismissRequest = model::dismissError,
            confirmButton = { TextButton(onClick = model::dismissError) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(message) },
        )
    }
}

private val noAutocorrect = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)

/** [panicWipe]: triple-tapping the title erases this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(state: UiState, model: AppViewModel, panicWipe: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    var cameraMenuOpen by remember { mutableStateOf(false) }
    val pickers = LocalPickers.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(ChatFilter.All) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<ChatConfirm?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    // Pinned chats get cards of their own while nothing narrows the list.
    val showsPinned = filter == ChatFilter.All && q.isEmpty()
    val pins = if (showsPinned) state.conversations.filter { it.pinned && !it.archived } else emptyList()
    val shown = state.conversations.filter { chat ->
        filter.includes(chat) &&
            (q.isEmpty() || chat.title.contains(q, ignoreCase = true)) &&
            !(showsPinned && chat.pinned && pins.isNotEmpty())
    }
    val t = tones()
    if (filter != ChatFilter.All) BackHandler { filter = ChatFilter.All }
    if (searching) BackHandler { searching = false; query = "" }
    val listState = rememberLazyListState()
    // Once the list moves, the header gets a hairline, as the iPhone's turns to glass.
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 4 } }

    if (state.cameraShot) {
        val chats = state.conversations.filter { it.active && !it.leaving }
        AlertDialog(
            onDismissRequest = model::dropCameraShot,
            title = { Text("Send to") },
            text = {
                if (chats.isEmpty()) {
                    Text("Start a chat first, then send it photos.")
                } else {
                    LazyColumn {
                        items(chats, key = { it.id }) { chat ->
                            Text(
                                chat.title,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.fillMaxWidth().clickable { model.sendCameraShot(chat.id) }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = model::dropCameraShot) { Text("Cancel") } },
        )
    }

    Box(Modifier.fillMaxSize()) {
        HomeGround(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // The big title, the round buttons, and the filter chips.
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.weight(1f).height(46.dp).clip(CircleShape).background(t.surface).border(1.5.dp, t.text, CircleShape).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Glyphs.Search, contentDescription = null, tint = t.secondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search names", fontSize = 16.sp, color = t.secondary)
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = TextStyle(fontSize = 16.sp, color = t.text),
                                cursorBrush = SolidColor(t.text),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Search chats" },
                            )
                        }
                    }
                    Text(
                        "Cancel", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = t.text,
                        modifier = Modifier.clickable { searching = false; query = "" }.padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                }
            } else Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Chats",
                    style = displayStyle(34.sp),
                    color = t.text,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() }
                        .pointerInput(panicWipe) {
                            // Panic wipe: triple-tap the title.
                            if (!panicWipe) return@pointerInput
                            var count = 0
                            var last = 0L
                            detectTapGestures(onTap = {
                                val now = SystemClock.uptimeMillis()
                                count = if (now - last < 500) count + 1 else 1
                                last = now
                                if (count == 3) {
                                    count = 0
                                    model.wipeThisDevice()
                                }
                            })
                        },
                )
                RoundButton(Glyphs.Search, "Search chats") { searching = true }
                Box {
                    RoundButton(Glyphs.Camera, "Camera") { cameraMenuOpen = true }
                    PopMenu(expanded = cameraMenuOpen, onDismissRequest = { cameraMenuOpen = false }) {
                        PopMenuItem("Take a photo", Glyphs.Camera, first = true, onClick = { cameraMenuOpen = false; pickers.takePhoto(CAMERA_TRAY) })
                        PopMenuItem("Record a video", Glyphs.Video, onClick = { cameraMenuOpen = false; pickers.recordVideo(CAMERA_TRAY) })
                    }
                }
                Box {
                    RoundButton(Glyphs.Plus, "New", filled = true) { menuOpen = true }
                    PopMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        PopMenuItem("New chat", Glyphs.Person, first = true, onClick = { menuOpen = false; dialog = "chat" })
                        PopMenuItem("New group", Glyphs.Group, onClick = { menuOpen = false; dialog = "group" })
                    }
                }
            }
            if (state.conversations.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChatFilter.entries.forEach { item ->
                        val count = when (item) {
                            ChatFilter.Unread -> state.conversations.count { ChatFilter.Unread.includes(it) }
                            ChatFilter.Archived -> state.conversations.count { it.archived }
                            else -> 0
                        }
                        FilterChip(item.title, selected = filter == item, count = count) { filter = item }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).graphicsLayer { alpha = if (scrolled) 1f else 0f }.background(t.line))
            if (!state.online) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyphs.Warning, contentDescription = null, tint = t.secondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Offline. Retrying…", color = t.secondary, fontSize = 14.sp)
                }
            }
            if (state.conversations.isEmpty()) {
                EmptyState(
                    Illustration.Chats,
                    title = "No chats yet",
                    text = "Start one with someone's username. They need to have opened Messenger once.",
                ) {
                    MarigoldButton("New chat") { dialog = "chat" }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {
                    // The recovery key is urgent, so it leads; the other tips
                    // wait under the chats instead of pushing them down.
                    val tip = currentTip(state, model.dismissedTips)
                    val tipFirst = tip == ChatListTip.RecoveryKey
                    if (tip != null && tipFirst) {
                        item(key = "tip") {
                            TipCard(tip, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), onOpenSettings = model::openSettings) {
                                model.dismissTip(tip.id)
                            }
                        }
                    }
                    if (pins.isNotEmpty()) {
                        item(key = "pinned-title") {
                            Row(
                                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text("Pinned", style = displayStyle(22.sp), color = t.text, modifier = Modifier.weight(1f).semantics { heading() })
                                Text("Hold a chat to pin it", fontSize = 13.5.sp, color = t.secondary)
                            }
                        }
                        item(key = "pinned") {
                            PinnedCards(pins, state, onOpen = { model.openChat(it) }, onLongClick = { menuFor = "pin:" + it.id }) { chat ->
                                ChatActionsMenu(
                                    chat,
                                    expanded = menuFor == "pin:" + chat.id,
                                    model = model,
                                    onConfirm = { confirm = it },
                                    onDismiss = { menuFor = null },
                                )
                            }
                        }
                    }
                    item(key = "title") {
                        Text(
                            if (q.isEmpty()) filter.listTitle else "Results",
                            style = displayStyle(22.sp),
                            color = t.text,
                            modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 6.dp).semantics { heading() },
                        )
                    }
                    items(shown, key = { it.id }) { conversation ->
                        Box(Modifier.padding(horizontal = 8.dp).animateItem()) {
                            SwipeRow(
                                readLabel = if (conversation.unread > 0u || conversation.markedUnread) "Read" else "Unread",
                                archiveLabel = if (conversation.archived) "Unarchive" else "Archive",
                                onRead = { model.setRead(conversation.id, conversation.unread > 0u || conversation.markedUnread) },
                                onArchive = { model.setArchived(conversation.id, !conversation.archived) },
                            ) {
                                ConversationRow(conversation, onLongClick = { menuFor = conversation.id }) { model.openChat(conversation) }
                            }
                            ChatActionsMenu(
                                conversation,
                                expanded = menuFor == conversation.id,
                                model = model,
                                onConfirm = { confirm = it },
                                onDismiss = { menuFor = null },
                            )
                        }
                    }
                    if (shown.isEmpty() && pins.isEmpty()) {
                        item(key = "empty") {
                            Column(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(170.dp, 122.dp)) {
                                    ShapeSticker(ShapeKind.Bubble, Modifier.align(Alignment.BottomStart).offset(x = 14.dp), size = 122.dp, delayMs = 20)
                                    ShapeSticker(ShapeKind.Heart, Modifier.align(Alignment.TopEnd).offset(x = (-7).dp, y = 3.dp), size = 51.dp, delayMs = 220)
                                    ShapeSticker(ShapeKind.Sparkle, Modifier.offset(y = 7.dp), size = 27.dp, delayMs = 340, phase = -1f)
                                }
                                Text(
                                    if (q.isEmpty()) filter.emptyText else "Nobody called “$q” yet.",
                                    color = t.secondary,
                                    fontSize = 15.sp,
                                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, start = 20.dp, end = 20.dp),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    item(key = "footer") {
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (filter == ChatFilter.Archived) {
                                Text("Archived chats stay here when new messages come.", color = t.secondary, fontSize = 13.sp)
                                Spacer(Modifier.height(8.dp))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Glyphs.Lock, contentDescription = null, tint = t.secondary, modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Only the people in a chat can read it", color = t.secondary, fontSize = 12.5.sp)
                            }
                        }
                    }
                    if (tip != null && !tipFirst) {
                        item(key = "tip") {
                            TipCard(tip, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp), onOpenSettings = model::openSettings) {
                                model.dismissTip(tip.id)
                            }
                        }
                    }
                }
            }
        }
    }

    confirm?.let { ChatConfirmDialog(it, model) { confirm = null } }
    when (dialog) {
        "chat" -> NewChatDialog(model) { dialog = null }
        "group" -> NewGroupDialog(model) { dialog = null }
    }
}

/** The chips over the chat list. */
enum class ChatFilter(val title: String, val listTitle: String, val emptyText: String) {
    All("All", "Everyone", "No chats here yet."),
    Unread("Unread", "Unread", "All caught up. Go do something fun."),
    Groups("Groups", "Groups", "No groups yet. Tap + to start one."),
    Archived("Archived", "Archived", "Nothing archived. Swipe a chat left to tuck it away."),
    ;

    fun includes(chat: ConversationSummary): Boolean = when (this) {
        All -> !chat.archived
        Unread -> !chat.archived && (chat.unread > 0u || chat.markedUnread)
        Groups -> !chat.archived && chat.kind == ConversationKind.GROUP
        Archived -> chat.archived
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ConversationRow(conversation: ConversationSummary, onLongClick: () -> Unit, onClick: () -> Unit) {
    val lastMessage = conversation.lastMessage
    val muted = conversation.mutedUntilMs != null
    val preview = when {
        conversation.leaving -> "Leaving…"
        // A locked chat keeps what's in it to itself.
        conversation.locked -> "Locked chat"
        lastMessage == null -> if (conversation.active) "No messages yet" else "This chat has ended"
        conversation.kind == ConversationKind.GROUP && !conversation.lastSender.isNullOrEmpty() ->
            if (conversation.lastInThread) "${conversation.lastSender} in a thread: $lastMessage" else "${conversation.lastSender}: $lastMessage"
        conversation.lastInThread -> "In a thread: $lastMessage"
        else -> lastMessage
    }
    val t = tones()
    val unread = conversation.unread > 0u
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conversation.title, isGroup = conversation.kind == ConversationKind.GROUP, size = 54, stamp = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.title,
                    color = t.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (conversation.locked) Icon(Glyphs.Lock, null, Modifier.padding(start = 6.dp).size(13.dp), tint = t.secondary)
                if (muted) Icon(Glyphs.BellSlash, null, Modifier.padding(start = 6.dp).size(13.dp), tint = t.secondary)
                if (conversation.blocked) Icon(Glyphs.Hand, null, Modifier.padding(start = 6.dp).size(13.dp), tint = t.secondary)
                if (conversation.pinned) Icon(Glyphs.Pin, null, Modifier.padding(start = 6.dp).size(13.dp), tint = t.secondary)
                Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
                Text(
                    shortTime(conversation.lastActivityMs),
                    fontSize = 13.sp,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (unread) MaterialTheme.colorScheme.primary else t.secondary,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 24.dp)) {
                Text(
                    preview,
                    fontSize = 15.sp,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    fontStyle = if (lastMessage == null) FontStyle.Italic else FontStyle.Normal,
                    color = if (unread) t.text else t.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // Muted chats count in grey.
                if (unread) {
                    UnreadBadge(conversation.unread.toInt(), muted = muted)
                } else if (conversation.markedUnread) {
                    Box(
                        Modifier
                            .size(13.dp)
                            .background(Palette.Marigold, CircleShape)
                            .border(1.5.dp, Palette.Ink, CircleShape)
                            .semantics { contentDescription = "Marked unread" },
                    )
                }
            }
        }
    }
}

/**
 * Someone's avatar: the drawing they picked, or the first letter of their
 * name, on the pastel of their ink. Groups get a pair of figures. A [stamp]
 * is a rounded square with an outline, as in the chat list and headers.
 */
@Composable
fun Avatar(name: String, isGroup: Boolean = false, size: Int = 44, stamp: Boolean = false) {
    val profiles = LocalProfiles.current
    val ink = inkFor(name, profiles)
    val drawing = if (isGroup) null else avatarDrawing(profiles[name]?.avatar)
    val photo = if (isGroup) null else LocalProfilePhotos.current[name]
    AvatarFace(drawing, ink, name.take(1).uppercase(), isGroup, size, stamp, photo)
}

/** A drawing, or a letter or a pair of figures, on an ink's pastel; round, or a stamp. */
@Composable
fun AvatarFace(drawing: AvatarDrawing?, ink: Ink, letter: String, isGroup: Boolean, size: Int, stamp: Boolean, photo: String? = null) {
    val shape = if (stamp) RoundedCornerShape((size / 3f).dp) else CircleShape
    val outline = if (stamp) Modifier.border(1.5.dp, tones().stampLine, shape) else Modifier
    val bitmap = photo?.let { ProfilePhotoCache.bitmap(it) }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap, contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(shape).then(outline),
        )
        return
    }
    if (drawing != null) {
        Box(Modifier.size(size.dp).then(outline)) {
            AvatarArt(drawing, ink.pastel, Modifier.size(size.dp), stamp = stamp)
        }
        return
    }
    Box(
        Modifier.size(size.dp).clip(shape).background(ink.pastel).then(outline),
        contentAlignment = Alignment.Center,
    ) {
        if (isGroup) {
            Icon(Glyphs.People, contentDescription = null, tint = ink.light, modifier = Modifier.size((size / 2).dp))
        } else {
            Text(
                letter,
                color = ink.light,
                fontWeight = if (stamp) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = (size * if (stamp) 0.36 else 0.44).sp,
            )
        }
    }
}

/** The suggestion worth showing now, if any. */
private fun currentTip(state: UiState, dismissed: Set<String>): ChatListTip? =
    ChatListTip.entries.filter { it.id !in dismissed }.firstOrNull { tip ->
        when (tip) {
            ChatListTip.RecoveryKey -> state.unconfirmedRecoveryKey != null
            ChatListTip.Avatar -> state.myProfile == null
            ChatListTip.SafetyNumbers -> state.conversations.any { it.kind == ConversationKind.DIRECT && it.active }
        }
    }

private class TipText(val illustration: Illustration?, val title: String, val text: String, val button: String)

@Composable
private fun TipCard(tip: ChatListTip, modifier: Modifier, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val words = when (tip) {
        ChatListTip.RecoveryKey -> TipText(
            Illustration.Key,
            "Write down your recovery key",
            "Your only way back in on a new device.",
            "Show it",
        )
        ChatListTip.Avatar -> TipText(
            null,
            "Pick an avatar",
            "A drawing and an ink your chats will see.",
            "Choose",
        )
        ChatListTip.SafetyNumbers -> TipText(
            Illustration.Safety,
            "Check your safety numbers",
            "Compare in person: if they match, nobody's in the middle.",
            "Show me",
        )
    }
    val ink = LocalAppearance.current.ink
    val illustration = words.illustration
    HelperCard(
        art = {
            if (illustration == null) {
                AvatarTrio(listOf(AvatarDrawing.Fox, AvatarDrawing.Panda, AvatarDrawing.Cat), ink.pastel)
            } else {
                IllustrationArt(illustration, Modifier.fillMaxSize())
            }
        },
        title = words.title,
        text = words.text,
        modifier = modifier,
        onDismiss = onDismiss,
    ) {
        FilledTonalButton(
            onClick = onOpenSettings,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.padding(top = 4.dp).height(30.dp),
        ) { Text(words.button, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun NewChatDialog(model: AppViewModel, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New chat") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = noAutocorrect,
                )
                Text(
                    error ?: "Ask them for their username. They need to have opened the app once.",
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && username.trim().length >= 3,
                onClick = {
                    working = true
                    scope.launch {
                        try {
                            model.startDirectChat(username.trim())
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            working = false
                        }
                    }
                },
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewGroupDialog(model: AppViewModel, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var members by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Group name") }, singleLine = true)
                OutlinedTextField(
                    value = members,
                    onValueChange = { members = it },
                    label = { Text("Members: alice, bob") },
                    keyboardOptions = noAutocorrect,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && title.isNotBlank() && parseUsernames(members).isNotEmpty(),
                onClick = {
                    working = true
                    scope.launch {
                        try {
                            model.createGroup(title.trim(), parseUsernames(members))
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            working = false
                        }
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "14:05" today, "12 Oct" otherwise. */
fun shortTime(milliseconds: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(milliseconds).atZone(zone)
    return if (time.toLocalDate() == LocalDate.now(zone)) {
        time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    } else {
        time.format(DateTimeFormatter.ofPattern("d MMM"))
    }
}


/**
 * A chat row you can swipe: right for read or unread (lavender), left to
 * archive (marigold). Past the line it acts on release, with a tick.
 */
@Composable
private fun SwipeRow(readLabel: String, archiveLabel: String, onRead: () -> Unit, onArchive: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val density = LocalDensity.current
    val trigger = with(density) { 84.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    Box(Modifier.clip(RoundedCornerShape(20.dp))) {
        val v = dx.value
        if (v > 0f) Row(
            Modifier.matchParentSize().background(Palette.VanishAccent.copy(alpha = (v / with(density) { 40.dp.toPx() }).coerceIn(0f, 1f))).padding(start = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Glyphs.Envelope, null, tint = Palette.Ink, modifier = Modifier.size(18.dp).graphicsLayer { val s = if (v > trigger) 1.25f else 1f; scaleX = s; scaleY = s })
            Spacer(Modifier.width(8.dp))
            Text(readLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
        if (v < 0f) Row(
            Modifier.matchParentSize().background(Palette.Marigold.copy(alpha = (-v / with(density) { 40.dp.toPx() }).coerceIn(0f, 1f))).padding(end = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Icon(Glyphs.Archive, null, tint = Palette.Ink, modifier = Modifier.size(18.dp).graphicsLayer { val s = if (-v > trigger) 1.25f else 1f; scaleX = s; scaleY = s })
            Spacer(Modifier.width(8.dp))
            Text(archiveLabel, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
        }
        Box(
            Modifier
                .graphicsLayer { translationX = dx.value }
                .background(tones().home)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val end = dx.value
                            if (end > trigger) onRead()
                            if (end < -trigger) onArchive()
                            armed = false
                            scope.launch { dx.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
                        },
                        onDragCancel = { scope.launch { dx.animateTo(0f, spring(dampingRatio = 0.55f)) } },
                    ) { change, amount ->
                        change.consume()
                        val maxR = with(density) { 110.dp.toPx() }
                        val maxL = with(density) { 130.dp.toPx() }
                        scope.launch { dx.snapTo((dx.value + amount).coerceIn(-maxL, maxR)) }
                        val now = kotlin.math.abs(dx.value) > trigger
                        if (now != armed) { armed = now; if (now) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
                    }
                },
        ) { content() }
    }
}

/** Pinned chats as big coloured cards, each with the shape that says how it's doing. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PinnedCards(
    chats: List<ConversationSummary>,
    state: UiState,
    onOpen: (ConversationSummary) -> Unit,
    onLongClick: (ConversationSummary) -> Unit,
    menu: @Composable (ConversationSummary) -> Unit,
) {
    val colors = listOf(0xFFF4A6D0, 0xFF5DC584, 0xFFFFD23F, 0xFFC9BDFF, 0xFFFFB48A)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        chats.take(5).forEachIndexed { i, chat ->
            val bg = Color(colors[i % colors.size])
            val live = state.sharedLocations.any { it.conversationId == chat.id }
            val muted = chat.mutedUntilMs != null
            val unread = chat.unread > 0u
            val shape = when {
                live -> ShapeKind.Squiggle
                muted -> ShapeKind.Moon
                unread -> ShapeKind.Bolt
                chat.kind == ConversationKind.GROUP -> ShapeKind.Clover
                else -> ShapeKind.Heart
            }
            val last = chat.lastMessage
            val preview = when {
                chat.locked -> "Locked chat"
                last == null -> if (chat.active) "No messages yet" else "This chat has ended"
                chat.kind == ConversationKind.GROUP && !chat.lastSender.isNullOrEmpty() -> "${chat.lastSender}: $last"
                else -> last
            }
            val enter = remember { Animatable(0f) }
            LaunchedEffect(Unit) { delay(i * 60L); enter.animateTo(1f, tween(340, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) }
            Box {
                Box(
                    Modifier
                        .graphicsLayer { alpha = enter.value; val s = 0.94f + 0.06f * enter.value; scaleX = s; scaleY = s; translationY = (1 - enter.value) * 6.dp.toPx() }
                        .size(172.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(bg)
                        .combinedClickable(onClick = { onOpen(chat) }, onLongClick = { onLongClick(chat) })
                        .semantics(mergeDescendants = true) { contentDescription = chat.title + (if (unread) ", ${chat.unread} unread" else "") + ". " + preview },
                ) {
                    key(shape) {
                        ShapeSticker(shape, Modifier.align(Alignment.TopEnd).padding(10.dp), size = 58.dp, delayMs = 150 + i * 80, phase = i * -0.7f)
                    }
                    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Box {
                            Box(Modifier.border(1.5.dp, Palette.Ink, RoundedCornerShape(17.dp))) {
                                Avatar(chat.title, isGroup = chat.kind == ConversationKind.GROUP, size = 52, stamp = true)
                            }
                            if (unread) {
                                Box(
                                    Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-9).dp)
                                        .border(2.5.dp, bg, CircleShape).padding(2.5.dp)
                                        .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp).clip(CircleShape).background(Palette.Ink).padding(horizontal = 7.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text("${chat.unread}", fontSize = 12.5.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                            }
                            if (live) Box(Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp).graphicsLayer { scaleX = 2.2f; scaleY = 2.2f }) { PingDot() }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(chat.title, style = displayStyle(20.sp), color = Palette.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (live) Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFF1F7A45)))
                                Spacer(Modifier.width(5.dp))
                                Text("Sharing live location", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                            }
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(shortTime(chat.lastActivityMs)) }
                                    append(" · $preview")
                                },
                                fontSize = 13.5.sp, lineHeight = 17.sp, color = Palette.Ink.copy(alpha = 0.82f),
                                maxLines = if (live) 1 else 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                menu(chat)
            }
        }
    }
}
