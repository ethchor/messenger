package com.qwantarc.messenger.ui

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
    CompositionLocalProvider(LocalProfiles provides state.profiles) {
        Screens(state, model, lock, onTurnOnLock)
    }
}

@Composable
private fun Screens(state: UiState, model: AppViewModel, lock: AppLock.State, onTurnOnLock: () -> Unit) {
    when (val phase = state.phase) {
        Phase.Starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Phase.Onboarding -> OnboardingScreen(state, model)
        is Phase.RecoveryKey -> RecoveryKeyScreen(phase.key, state.busy, onConfirm = model::confirmRecoveryKeySaved)
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
        is Phase.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Couldn't open your messages: ${phase.message}", style = MaterialTheme.typography.bodyLarge)
        }
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

@Composable
private fun OnboardingScreen(state: UiState, model: AppViewModel) {
    var username by remember { mutableStateOf("") }
    var recoveryKey by remember { mutableStateOf("") }
    var recovering by remember { mutableStateOf(false) }
    val canSubmit = !state.busy && username.trim().length >= 3 && (!recovering || recoveryKey.isNotBlank())

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // As on iPhone: at most 240 wide, centred, its height from its shape.
            IllustrationArt(Illustration.Welcome, Modifier.align(Alignment.CenterHorizontally).widthIn(max = 240.dp).fillMaxWidth())
            Text("A private line to your people", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Messages are end-to-end encrypted with MLS and a post-quantum key exchange. The server only stores ciphertext and can't tell who talks to whom.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = noAutocorrect,
                modifier = Modifier.fillMaxWidth(),
            )
            if (recovering) {
                OutlinedTextField(
                    value = recoveryKey,
                    onValueChange = { recoveryKey = it },
                    label = { Text("Recovery key (64 characters)") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = noAutocorrect,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = model::setServerUrl,
                label = { Text("Server") },
                singleLine = true,
                keyboardOptions = noAutocorrect.copy(keyboardType = KeyboardType.Uri),
                supportingText = { Text("The emulator reaches a server on this computer at http://10.0.2.2:8080. On a phone, use the computer's address on your network.") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { if (recovering) model.recover(username.trim(), recoveryKey) else model.register(username.trim()) },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (recovering) "Recover account" else "Create account")
            }
            TextButton(onClick = { recovering = !recovering }, modifier = Modifier.fillMaxWidth()) {
                Text(if (recovering) "Create a new account instead" else "I have a recovery key")
            }
        }
    }
}

/**
 * The recovery key, at sign-up or after making a new one in Settings
 * ([isReplacement], with [onBack] to leave without confirming).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryKeyScreen(
    recoveryKey: String,
    busy: Boolean,
    isReplacement: Boolean = false,
    onBack: (() -> Unit)? = null,
    onConfirm: () -> Unit,
) {
    var wroteItDown by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val groups = recoveryKey.split(" ").filter { it.isNotEmpty() }

    onBack?.let { BackHandler(onBack = it) }
    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Glyphs.Back, contentDescription = "Back") }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IllustrationArt(Illustration.Key, Modifier.size(width = 160.dp, height = 120.dp))
            Text(
                if (isReplacement) "Your new recovery key" else "Save your recovery key",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (isReplacement) {
                    "Your old recovery key no longer works. Write this one down and keep it somewhere safe; it's the only way to move your account to a new device."
                } else {
                    "It's the only way to move your account to a new device. Write it down and keep it somewhere safe. It won't be shown again, and nobody, including the server, can recover it for you."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            row.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 18.sp) }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(recoveryKey)) }) { Text("Copy") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { wroteItDown = !wroteItDown }) {
                Checkbox(checked = wroteItDown, onCheckedChange = { wroteItDown = it })
                Text("I wrote my recovery key down")
            }
            Button(onClick = onConfirm, enabled = wroteItDown && !busy, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
        }
    }
}

/** [panicWipe]: triple-tapping the title erases this device. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListScreen(state: UiState, model: AppViewModel, panicWipe: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    var cameraMenuOpen by remember { mutableStateOf(false) }
    val pickers = LocalPickers.current
    var dialog by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // As on iPhone: the title in the middle, and the camera and New
            // in one round pill on the right.
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(
                        "Chats",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.pointerInput(panicWipe) {
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
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 3.dp,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Row {
                            Box {
                                IconButton(onClick = { cameraMenuOpen = true }) {
                                    Icon(Glyphs.Camera, contentDescription = "Camera", tint = MaterialTheme.colorScheme.primary)
                                }
                                PopMenu(expanded = cameraMenuOpen, onDismissRequest = { cameraMenuOpen = false }) {
                                    PopMenuItem("Take a photo", Glyphs.Camera, first = true, onClick = { cameraMenuOpen = false; pickers.takePhoto(CAMERA_TRAY) })
                                    PopMenuItem("Record a video", Glyphs.Video, onClick = { cameraMenuOpen = false; pickers.recordVideo(CAMERA_TRAY) })
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Glyphs.Compose, contentDescription = "New", tint = MaterialTheme.colorScheme.primary)
                                }
                                PopMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    PopMenuItem("New chat", Glyphs.Person, first = true, onClick = { menuOpen = false; dialog = "chat" })
                                    PopMenuItem("New group", Glyphs.Group, onClick = { menuOpen = false; dialog = "group" })
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
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
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.online) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyphs.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("Offline. Retrying…", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (state.conversations.isEmpty()) {
                EmptyState(
                    Illustration.Chats,
                    title = "No chats yet",
                    text = "Start one with someone's username. They need to have opened Messenger once.",
                ) {
                    Button(onClick = { dialog = "chat" }) { Text("New chat") }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 110.dp)) {
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
                    // One rounded card of chats, as an iPhone's inset grouped list.
                    val last = state.conversations.lastIndex
                    itemsIndexed(state.conversations, key = { _, it -> it.id }) { index, conversation ->
                        val top = if (index == 0) 22.dp else 0.dp
                        val bottom = if (index == last) 22.dp else 0.dp
                        Column(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .padding(top = if (index == 0) 8.dp else 0.dp)
                                .clip(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
                                .background(MaterialTheme.colorScheme.surface),
                        ) {
                            ConversationRow(conversation) { model.openConversation(conversation.id) }
                            if (index != last) HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant)
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

    when (dialog) {
        "chat" -> NewChatDialog(model) { dialog = null }
        "group" -> NewGroupDialog(model) { dialog = null }
    }
}

@Composable
private fun ConversationRow(conversation: ConversationSummary, onClick: () -> Unit) {
    val lastMessage = conversation.lastMessage
    val preview = when {
        conversation.leaving -> "Leaving…"
        lastMessage == null -> if (conversation.active) "No messages yet" else "This chat has ended"
        conversation.kind == ConversationKind.GROUP && !conversation.lastSender.isNullOrEmpty() ->
            if (conversation.lastInThread) "${conversation.lastSender} in a thread: $lastMessage" else "${conversation.lastSender}: $lastMessage"
        conversation.lastInThread -> "In a thread: $lastMessage"
        else -> lastMessage
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(conversation.title, isGroup = conversation.kind == ConversationKind.GROUP)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(shortTime(conversation.lastActivityMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.unread > 0u) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(conversation.unread.toString()) }
                }
            }
        }
        // As on iPhone: a faint chevron says the row opens the chat.
        Spacer(Modifier.width(10.dp))
        Icon(
            Glyphs.Forward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(13.dp),
        )
    }
}

/**
 * Someone's avatar: the drawing they picked, or the first letter of their
 * name, on the pastel of their ink. Groups get a pair of figures.
 */
@Composable
fun Avatar(name: String, isGroup: Boolean = false, size: Int = 44) {
    val profiles = LocalProfiles.current
    val ink = inkFor(name, profiles)
    val drawing = if (isGroup) null else avatarDrawing(profiles[name]?.avatar)
    if (drawing != null) {
        AvatarArt(drawing, ink.pastel, Modifier.size(size.dp))
        return
    }
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(ink.pastel),
        contentAlignment = Alignment.Center,
    ) {
        if (isGroup) {
            Icon(Glyphs.People, contentDescription = null, tint = ink.light, modifier = Modifier.size((size / 2).dp))
        } else {
            Text(
                name.take(1).uppercase(),
                color = ink.light,
                fontWeight = FontWeight.Medium,
                fontSize = (size * 0.44).sp,
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
