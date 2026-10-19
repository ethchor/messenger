package com.qwantarc.messenger.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
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
    when (val phase = state.phase) {
        Phase.Starting -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Phase.Onboarding -> OnboardingScreen(state, model)
        is Phase.RecoveryKey -> RecoveryKeyScreen(phase.key, state.busy, onConfirm = model::confirmRecoveryKeySaved)
        Phase.Ready -> when {
            state.mapOpen -> MapScreen(state, model)
            state.openConversationId != null -> ChatScreen(state, model)
            state.settingsOpen -> SettingsScreen(state, model, lock, onTurnOnLock)
            else -> ChatListScreen(state, model, lock.panicWipe)
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
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            Text("Private by design", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
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
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                if (isReplacement) "Your new recovery key" else "Save your recovery key",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
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
    var dialog by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Chats",
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
                    IconButton(onClick = model::openMap) {
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = "Map",
                            tint = if (state.sharedLocations.isNotEmpty()) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = model::openSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { menuOpen = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New") },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("New chat") }, onClick = { menuOpen = false; dialog = "chat" })
                    DropdownMenuItem(text = { Text("New group") }, onClick = { menuOpen = false; dialog = "group" })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!state.online) {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("Offline. Retrying…", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            if (state.conversations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No chats yet. Start one with someone's username.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.conversations, key = { it.id }) { conversation ->
                        ConversationRow(conversation) { model.openConversation(conversation.id) }
                        HorizontalDivider(Modifier.padding(start = 76.dp))
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
            "${conversation.lastSender}: $lastMessage"
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
    }
}

@Composable
fun Avatar(name: String, isGroup: Boolean = false, size: Int = 44) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(avatarColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        if (isGroup) {
            Icon(Icons.Filled.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size((size / 2).dp))
        } else {
            Text(name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size * 0.42).sp)
        }
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
