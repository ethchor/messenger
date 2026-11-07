package com.qwantarc.messenger.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qwantarc.messenger.AppLock
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.AccountInfo
import com.qwantarc.messenger.core.ConversationKind
import com.qwantarc.messenger.core.SafetyNumber
import com.qwantarc.messenger.core.coreVersion
import com.qwantarc.messenger.push.Notifier
import com.qwantarc.messenger.push.Push
import com.qwantarc.messenger.userMessage
import kotlinx.coroutines.launch

private const val MAIN = "main"
private const val SAFETY_NUMBERS = "safety"
private const val SAFETY_NUMBER = "safety:" // + conversation id
private const val DELETE_ACCOUNT = "delete"
private const val RECOVERY_KEY = "recovery-key"
private const val PROFILE = "profile"
private const val ACCOUNT = "account"
private const val PRIVACY = "privacy"
private const val CHATS = "chats"
private const val NOTIFICATIONS = "notifications"
private const val ABOUT = "about"

/** The pages the first page's rows open, and their titles. */
private val CATEGORY_TITLES = mapOf(
    PROFILE to "Profile",
    ACCOUNT to "Account",
    PRIVACY to "Privacy",
    CHATS to "Chats",
    NOTIFICATIONS to "Notifications",
    ABOUT to "About",
)

/**
 * Account, privacy and security. One screen with a few pages; the page is
 * saved, so rotating the phone keeps it.
 */
@Composable
fun SettingsScreen(state: UiState, model: AppViewModel, lock: AppLock.State, onTurnOnLock: () -> Unit) {
    val account = state.account ?: return
    var page by rememberSaveable { mutableStateOf(MAIN) }

    when {
        page == SAFETY_NUMBERS -> SafetyNumbersPage(state, onBack = { page = PRIVACY }) { page = SAFETY_NUMBER + it }
        page.startsWith(SAFETY_NUMBER) -> {
            val id = page.removePrefix(SAFETY_NUMBER)
            val title = state.conversations.firstOrNull { it.id == id }?.title ?: ""
            SafetyNumberPage(id, title, model) { page = SAFETY_NUMBERS }
        }
        page == DELETE_ACCOUNT -> DeleteAccountPage(account.username, model) { page = MAIN }
        page == RECOVERY_KEY -> {
            val key = state.unconfirmedRecoveryKey
            // Confirming clears the key, which brings Settings back.
            LaunchedEffect(key) { if (key == null) page = ACCOUNT }
            if (key != null) {
                RecoveryKeyScreen(
                    recoveryKey = key,
                    busy = state.busy,
                    isReplacement = true,
                    onBack = { page = ACCOUNT },
                    onConfirm = model::confirmRecoveryKeySaved,
                )
            }
        }
        else -> MainPage(state, account, model, lock, onTurnOnLock, page) { page = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainPage(
    state: UiState,
    account: AccountInfo,
    model: AppViewModel,
    lock: AppLock.State,
    onTurnOnLock: () -> Unit,
    category: String,
    open: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmNewKey by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pickTimeout by remember { mutableStateOf(false) }

    fun perform(work: suspend () -> Unit) {
        working = true
        scope.launch {
            try {
                work()
                error = null
            } catch (e: Exception) {
                error = e.userMessage
            } finally {
                working = false
            }
        }
    }

    val back = { if (category in CATEGORY_TITLES) open(MAIN) else model.closeSettings() }
    BackHandler(onBack = back)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(CATEGORY_TITLES[category] ?: "Settings") },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            when (category) {
                PROFILE -> {
                    AppearanceSection(state, model)
                    SectionHeader("Username")
                    ValueRow("Username", account.username)
                    Footer("Your username is how people start a chat with you. It can't be changed; deleting the account frees it.")
                }
                ACCOUNT -> {
                    SectionHeader("Account")
                    ValueRow("Username", account.username)
                    ValueRow("Account ID", account.accountId, monospace = true)
                    ValueRow("Server", account.serverUrl)

                    SectionHeader("Recovery key")
                    if (state.unconfirmedRecoveryKey != null) {
                        ListItem(
                            headlineContent = { Text("Not confirmed as written down") },
                            leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFE5940B)) },
                        )
                        ActionRow("Show recovery key", enabled = !working) { open(RECOVERY_KEY) }
                    } else {
                        ListItem(
                            headlineContent = { Text("Written down") },
                            leadingContent = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF30A46C)) },
                        )
                    }
                    ActionRow("Create a new recovery key", enabled = !working) { confirmNewKey = true }
                    Footer("Your recovery key moves your account to a new device. Nobody else has it, not even the server. If you lost it, create a new one; the old one stops working.")
                }
                PRIVACY -> {
                    SectionHeader("Privacy")
                    SwitchRow("Read receipts", checked = state.privacy.readReceipts) {
                        model.setPrivacy(state.privacy.copy(readReceipts = it))
                    }
                    SwitchRow("Typing indicators", checked = state.privacy.typingIndicators) {
                        model.setPrivacy(state.privacy.copy(typingIndicators = it))
                    }
                    Footer("These work both ways: if you turn one off, you won't see it from others either.")

                    SectionHeader("Security")
                    SwitchRow(
                        "App lock",
                        supporting = "Unlock with your fingerprint, face or screen lock",
                        checked = lock.enabled,
                        enabled = !lock.authenticating,
                    ) { on -> if (on) onTurnOnLock() else model.lock.turnOff() }
                    if (lock.enabled) {
                        ListItem(
                            headlineContent = { Text("Lock") },
                            supportingContent = { Text(lock.timeout.label) },
                            modifier = Modifier.clickable { pickTimeout = true },
                        )
                    }
                    SwitchRow("Hide in recent apps", checked = lock.hideInRecents) { model.lock.setHideInRecents(it) }
                    SwitchRow(
                        "Block screenshots",
                        supporting = "Also keeps the app out of screen recordings and screen sharing",
                        checked = lock.blockScreenshots,
                    ) { model.lock.setBlockScreenshots(it) }
                    SwitchRow(
                        "Panic wipe",
                        supporting = "Triple-tap “Chats” to erase everything on this device at once. There's no confirmation and nothing is sent; your recovery key brings the account back, without the old messages.",
                        checked = lock.panicWipe,
                    ) { model.lock.setPanicWipe(it) }
                    ActionRow("Safety numbers") { open(SAFETY_NUMBERS) }
                    lock.error?.let { Footer(it, color = MaterialTheme.colorScheme.error) }
                    Footer(
                        if (lock.hideInRecents && !lock.blockScreenshots && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                            "On this version of Android, hiding the app in recent apps also blocks screenshots. Safety numbers let you check, in person, that nobody is in the middle of a chat."
                        } else {
                            "Safety numbers let you check, in person, that nobody is in the middle of a chat."
                        },
                    )
                }
                CHATS -> AppearanceSection(state, model, chats = true)
                NOTIFICATIONS -> NotificationsSection(appLockOn = lock.enabled)
                ABOUT -> {
                    SectionHeader("About")
                    ValueRow("Encryption", "MLS (RFC 9420)")
                    ValueRow("Key exchange", "X-Wing: ML-KEM-768 + X25519")
                    ValueRow("Core version", coreVersion())
                    Footer("Messages are end-to-end encrypted, and the server stores only ciphertext. Without notifications it can't tell who talks to whom; with them, it learns which accounts each message wakes.")
                }
                else -> {
                    // You, then a row per page, then leaving.
                    Column(
                        Modifier.fillMaxWidth().clickable { open(PROFILE) }.padding(vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Avatar(account.username, size = 88)
                        Text(account.username, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (state.online) "Connected" else "Offline",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (state.online) Color(0xFF30A46C) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CategoryRow("Profile", "Avatar, ink, username", Icons.Filled.AccountCircle) { open(PROFILE) }
                    CategoryRow("Account", "Recovery key, account details", Icons.Filled.AccountBox) { open(ACCOUNT) }
                    CategoryRow("Privacy", "Receipts, typing, app lock, panic wipe", Icons.Filled.Lock) { open(PRIVACY) }
                    CategoryRow("Chats", "Chat paper, wallpapers", Icons.Filled.Email) { open(CHATS) }
                    CategoryRow("Notifications", "Messages, what they show", Icons.Filled.Notifications) { open(NOTIFICATIONS) }
                    CategoryRow("About", "Encryption, version", Icons.Filled.Info) { open(ABOUT) }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    ActionRow("Remove account from this device", destructive = true, enabled = !working) { confirmRemove = true }
                    ActionRow("Delete account", destructive = true, enabled = !working) { open(DELETE_ACCOUNT) }
                    if (working) {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    error?.let { Footer(it, color = MaterialTheme.colorScheme.error) }
                    Footer("Removing the account from this device leaves your chats here and signs you out; your recovery key brings the account back, without the old messages. Deleting it also frees your username for good.")
                }
            }
        }
    }

    if (confirmNewKey) {
        AlertDialog(
            onDismissRequest = { confirmNewKey = false },
            title = { Text("Create a new recovery key?") },
            text = { Text("Your current recovery key will stop working.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmNewKey = false
                    perform {
                        model.newRecoveryKey()
                        open(RECOVERY_KEY)
                    }
                }) { Text("Create new key") }
            },
            dismissButton = { TextButton(onClick = { confirmNewKey = false }) { Text("Cancel") } },
        )
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove your account from this device?") },
            text = { Text("You'll leave all your chats on this device. To sign in again you need your recovery key, and old messages won't come back.") },
            confirmButton = {
                TextButton(
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmRemove = false
                        perform { model.removeFromDevice() }
                    },
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }

    if (pickTimeout) {
        AlertDialog(
            onDismissRequest = { pickTimeout = false },
            title = { Text("Lock") },
            text = {
                Column {
                    AppLock.Timeout.entries.forEach { timeout ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = timeout == lock.timeout, role = Role.RadioButton) {
                                    model.lock.setTimeout(timeout)
                                    pickTimeout = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = timeout == lock.timeout, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(timeout.label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { pickTimeout = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Notifications: a push that says only "wake up", from a UnifiedPush
 * distributor app; the app then fetches and decrypts what's new itself.
 */
@Composable
private fun NotificationsSection(appLockOn: Boolean) {
    val context = LocalContext.current
    val status by Push.status.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(Push.isEnabled(context)) }
    var previews by remember { mutableStateOf(Push.showsPreviews(context)) }
    var allowed by remember { mutableStateOf(Notifier.allowed(context)) }
    var distributor by remember { mutableStateOf(Push.distributorName(context)) }
    val askToNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        allowed = Notifier.allowed(context)
    }
    // Back from Android's settings or a distributor app: look again.
    LifecycleResumeEffect(Unit) {
        allowed = Notifier.allowed(context)
        distributor = Push.distributorName(context)
        onPauseOrDispose { }
    }
    LaunchedEffect(status) { distributor = Push.distributorName(context) }
    fun retry() {
        context.findActivity()?.let { Push.start(it) }
    }

    SectionHeader("Notifications")
    SwitchRow("Notifications", checked = enabled) { on ->
        enabled = on
        val activity = context.findActivity()
        if (on && activity != null) {
            Push.turnOn(activity)
            if (!Notifier.allowed(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            Push.turnOff(context)
            Notifier.cancelAll(context)
        }
    }
    if (enabled) {
        if (!allowed) {
            ListItem(
                headlineContent = { Text("Blocked by Android") },
                supportingContent = { Text("Messenger isn't allowed to show notifications on this phone.") },
                leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFE5940B)) },
            )
            ActionRow("Allow in Android settings") { runCatching { context.startActivity(Notifier.settingsIntent(context)) } }
        }
        when (status) {
            Push.Status.On -> ListItem(
                headlineContent = { Text("Instant") },
                supportingContent = { Text(distributor?.let { "Pushes arrive through $it" } ?: "Connected") },
                leadingContent = { Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF30A46C)) },
            )
            Push.Status.Connecting -> ListItem(
                headlineContent = { Text("Connecting…") },
                leadingContent = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
            )
            Push.Status.NoDistributor -> {
                HelperCard(
                    title = "Instant notifications without Google",
                    text = "Messenger is woken by a UnifiedPush app, which keeps one connection open for all your apps. " +
                        "Install one, such as ntfy (from F-Droid or ntfy.sh), then come back here. " +
                        "Until then, new messages show up while Messenger is open or still running in the background.",
                )
                ActionRow("Check again") { retry() }
            }
            Push.Status.Failed -> {
                ListItem(
                    headlineContent = { Text("Not connected") },
                    supportingContent = {
                        Text(
                            distributor?.let { "$it or the server didn't take the registration. Check that $it is set up, then try again." }
                                ?: "The push app or the server didn't take the registration.",
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFFE5940B)) },
                )
                ActionRow("Try again") { retry() }
            }
            Push.Status.Off -> Unit
        }
        SwitchRow(
            "Show names and messages",
            supporting = if (previews) null else "Notifications only say “New message”",
            checked = previews,
        ) {
            previews = it
            Push.setShowsPreviews(context, it)
        }
        if (appLockOn && previews) {
            Footer("App lock doesn't cover notifications: anyone holding your unlocked phone can read them. Turn off names and messages to keep your chats behind the lock.")
        }
    }
    Footer(
        "A push carries nothing readable: no names, no text. Your phone fetches and decrypts new messages itself. " +
            "To wake you, the server learns when a message arrives for you and who else the same message wakes; " +
            "the push app sees only that a push came. Turn notifications off to keep that from both.",
    )
}

/** An explanation that stands apart from the settings around it. */
@Composable
private fun HelperCard(title: String, text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Every 1:1 chat, to compare safety numbers with. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafetyNumbersPage(state: UiState, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val chats = state.conversations.filter { it.kind == ConversationKind.DIRECT && it.active }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Safety numbers") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (chats.isEmpty()) {
            EmptyState(
                Illustration.Safety,
                title = "No one-to-one chats yet",
                text = "Safety numbers are for chats with one other person. Start one, then compare numbers in person.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(chats, key = { it.id }) { chat ->
                    ListItem(
                        headlineContent = { Text(chat.title) },
                        leadingContent = { Avatar(chat.title, size = 36) },
                        modifier = Modifier.clickable { onOpen(chat.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SafetyNumberPage(conversationId: String, title: String, model: AppViewModel, onBack: () -> Unit) {
    var number by remember { mutableStateOf<SafetyNumber?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(conversationId) {
        try {
            number = model.safetyNumber(conversationId)
        } catch (e: Exception) {
            error = e.userMessage
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            when {
                number != null -> SafetyNumberCard(number!!, title)
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

/** Deleting the account asks for the username, so it can't happen by accident. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteAccountPage(username: String, model: AppViewModel, onBack: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val matches = typed.trim().lowercase() == username

    BackHandler(enabled = !working, onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Delete account") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !working) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("This can't be undone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            }
            Text(
                "You'll leave every chat and group, your messages on this device are erased, and the username $username becomes free for anyone to take. Your recovery key stops working.",
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                label = { Text("Type $username to confirm") },
                singleLine = true,
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = matches && !working,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                onClick = {
                    working = true
                    scope.launch {
                        try {
                            // Afterwards the app is back at sign-up and this page is gone.
                            model.deleteAccount()
                        } catch (e: Exception) {
                            error = e.userMessage
                        } finally {
                            working = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (working) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                } else {
                    Text("Delete account")
                }
            }
        }
    }
}

/** Shown instead of everything else while the app is locked. */
@Composable
fun LockScreen(lock: AppLock.State, onUnlock: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IllustrationArt(Illustration.Lock, Modifier.size(width = 160.dp, height = 120.dp))
            Spacer(Modifier.size(16.dp))
            Text("Messenger is locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.size(24.dp))
            Button(onClick = onUnlock, enabled = !lock.authenticating) { Text("Unlock") }
            lock.error?.let {
                Spacer(Modifier.size(12.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---- rows ----

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Footer(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun ValueRow(label: String, value: String, monospace: Boolean = false) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            SelectionContainer {
                Text(value, fontFamily = if (monospace) FontFamily.Monospace else null)
            }
        },
    )
}

@Composable
private fun ActionRow(title: String, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    ListItem(
        headlineContent = { Text(title, color = color) },
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    supporting: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (supporting != null) {
            { Text(supporting) }
        } else {
            null
        },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** A row on the first page: an icon, the page it opens, and a line about it. */
@Composable
private fun CategoryRow(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
