package com.qwantarc.messenger.ui

import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
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
private const val LINKED_DEVICES = "devices"
private const val LINK_DEVICE = "link-device"
private const val RESET_IDENTITY = "reset-identity"

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
        page == LINKED_DEVICES -> LinkedDevicesPage(model, onLink = { page = LINK_DEVICE }) { page = MAIN }
        page == LINK_DEVICE -> LinkDevicePage(model) { page = LINKED_DEVICES }
        page == RESET_IDENTITY -> ResetIdentityPage(model) { page = ACCOUNT }
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
    Box(Modifier.fillMaxSize()) {
    if (category in CATEGORY_TITLES) {
        Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background))
    } else {
        HomeGround(Modifier.matchParentSize())
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            val bar = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            if (category in CATEGORY_TITLES) {
                CenterAlignedTopAppBar(
                    title = { Text(CATEGORY_TITLES.getValue(category), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = { RoundBarButton(back, "Back", Glyphs.Back) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            } else {
                // The first page is the You tab: a large title, nothing to go back to.
                TopAppBar(
                    title = { Text("Settings", style = displayStyle(30.sp)) },
                    colors = bar,
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 110.dp)) {
            when (category) {
                PROFILE -> {
                    AppearanceSection(state, model)
                    SectionHeader("Username")
                    Group { ValueRow("Username", account.username) }
                    Footer("Your username is how people start a chat with you. It can't be changed; deleting the account frees it.")
                }
                ACCOUNT -> {
                    SectionHeader("Account")
                    Group {
                        ValueRow("Username", account.username)
                        ValueRow("Account ID", account.accountId, monospace = true)
                        ValueRow("Server", account.serverUrl)
                    }

                    SectionHeader("Recovery key")
                    Group {
                        if (state.unconfirmedRecoveryKey != null) {
                            ListItem(
                                headlineContent = { Text("Not confirmed as written down") },
                                leadingContent = { Icon(Glyphs.Warning, contentDescription = null, tint = Color(0xFFE5940B)) },
                            )
                            ActionRow("Show recovery key", enabled = !working) { open(RECOVERY_KEY) }
                        } else {
                            ListItem(
                                headlineContent = { Text("Written down") },
                                leadingContent = { Icon(Glyphs.Checked, contentDescription = null, tint = Color(0xFF30A46C)) },
                            )
                        }
                        ActionRow("Create a new recovery key", enabled = !working) { confirmNewKey = true }
                    }
                    Footer("Your recovery key moves your account to a new device. Nobody else has it, not even the server. If you lost it, create a new one; the old one stops working.")

                    Spacer(Modifier.height(12.dp))
                    Group { ActionRow("Reset identity", destructive = true, enabled = !working) { open(RESET_IDENTITY) } }
                    Footer("If a device was lost or stolen: signs out every other device and gives you a new safety number. Needs your recovery key.")
                }
                PRIVACY -> {
                    SectionHeader("Privacy")
                    Group {
                        SwitchRow("Read receipts", checked = state.privacy.readReceipts) {
                            model.setPrivacy(state.privacy.copy(readReceipts = it))
                        }
                        SwitchRow("Typing indicators", checked = state.privacy.typingIndicators) {
                            model.setPrivacy(state.privacy.copy(typingIndicators = it))
                        }
                    }
                    Footer("These work both ways: if you turn one off, you won't see it from others either.")

                    SectionHeader("Security")
                    Group {
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
                    }
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
                    Group {
                        ValueRow("Encryption", "MLS (RFC 9420)")
                        ValueRow("Key exchange", "X-Wing: ML-KEM-768 + X25519")
                        ValueRow("Core version", coreVersion())
                    }
                    Footer("Messages are end-to-end encrypted, and the server stores only ciphertext. Without notifications it can't tell who talks to whom; with them, it learns which accounts each message wakes.")
                }
                else -> {
                    // You, then a row per page, then leaving.
                    Spacer(Modifier.height(8.dp))
                    MeCard(account.username, state.online, onEdit = { open(PROFILE) })
                    SectionHeader("Settings")
                    Group(inset = 64.dp) {
                        CategoryRow("Profile", "Avatar, ink, username", Glyphs.Profile, Palette.TilePink) { open(PROFILE) }
                        CategoryRow("Chats and appearance", "Chat paper", Glyphs.Palette, Palette.TileYellow) { open(CHATS) }
                        CategoryRow("Notifications", "Messages, what they show", Glyphs.Bell, Palette.TilePeach) { open(NOTIFICATIONS) }
                        CategoryRow("Privacy and security", "Receipts, typing, app lock, panic wipe", Glyphs.Lock, Palette.TileLavender) { open(PRIVACY) }
                        CategoryRow("Linked devices", "Your phone, computer and other devices", Glyphs.Devices, Palette.TileApricot) { open(LINKED_DEVICES) }
                        CategoryRow("Account", "Recovery key, account details", Glyphs.Key, Palette.TileSky) { open(ACCOUNT) }
                        CategoryRow("About", "Encryption, version", Glyphs.Info, Palette.TileMint) { open(ABOUT) }
                    }
                    Spacer(Modifier.height(20.dp))
                    Group {
                        ActionRow("Remove account from this device", destructive = true, enabled = !working) { confirmRemove = true }
                        ActionRow("Delete account", destructive = true, enabled = !working) { open(DELETE_ACCOUNT) }
                    }
                    if (working) {
                        Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                    error?.let { Footer(it, color = MaterialTheme.colorScheme.error) }
                    Footer("Removing the account from this device signs it out. Your other linked devices stay signed in; with none, your recovery key brings the account back, without the old messages. Deleting it also frees your username for good.")
                }
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
    Group {
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
                    leadingContent = { Icon(Glyphs.Warning, contentDescription = null, tint = Color(0xFFE5940B)) },
                )
                ActionRow("Allow in Android settings") { runCatching { context.startActivity(Notifier.settingsIntent(context)) } }
            }
            when (status) {
                Push.Status.On -> ListItem(
                    headlineContent = { Text("Instant") },
                    supportingContent = { Text(distributor?.let { "Pushes arrive through $it" } ?: "Connected") },
                    leadingContent = { Icon(Glyphs.Checked, contentDescription = null, tint = Color(0xFF30A46C)) },
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
                        leadingContent = { Icon(Glyphs.Warning, contentDescription = null, tint = Color(0xFFE5940B)) },
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Glyphs.Back, contentDescription = "Back") } },
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
                navigationIcon = { IconButton(onClick = onBack) { Icon(Glyphs.Back, contentDescription = "Back") } },
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
                    IconButton(onClick = onBack, enabled = !working) { Icon(Glyphs.Back, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Glyphs.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
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
internal fun SectionHeader(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
internal fun Footer(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 4.dp),
    )
}

@Composable
internal fun ValueRow(label: String, value: String, monospace: Boolean = false) {
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
internal fun ActionRow(title: String, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
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
internal fun SwitchRow(
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
        trailingContent = { IosSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    )
}

/** A row on the first page: an icon, the page it opens, and a line about it. */
@Composable
private fun CategoryRow(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(detail, fontSize = 12.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
        leadingContent = { IconTile(icon, tint) },
        trailingContent = { Chevron() },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** chevron.forward, faint, at the end of a row that opens a page. */
@Composable
private fun Chevron() {
    Icon(Glyphs.Forward, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
}

/**
 * A run of rows in one rounded card on the grey page, as in iPhone
 * settings, with hairlines between them from [inset] to the edge.
 */
@Composable
internal fun Group(inset: Dp = 16.dp, content: @Composable () -> Unit) {
    val lines = remember { mutableListOf<Int>() }
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Layout(
        content = content,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .drawWithContent {
                drawContent()
                val start = inset.toPx()
                lines.forEach { y -> drawLine(hairline, Offset(start, y.toFloat()), Offset(size.width, y.toFloat()), strokeWidth = 0.5.dp.toPx()) }
            },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }.filter { it.height > 0 }
        layout(constraints.maxWidth, placeables.sumOf { it.height }) {
            lines.clear()
            var y = 0
            placeables.forEachIndexed { i, p ->
                p.place(0, y)
                y += p.height
                if (i < placeables.lastIndex) lines += y
            }
        }
    }
}


/**
 * You, at the top of the You tab: a card in your ink with your stamp, your
 * name, and ways to edit or share your profile. The iPhone app draws the
 * same card (MeCard).
 */
@Composable
private fun MeCard(username: String, online: Boolean, onEdit: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ink = LocalAppearance.current.ink
    val card by androidx.compose.animation.animateColorAsState(
        if (ink == Ink.Marigold) Palette.Marigold else ink.pastel,
        androidx.compose.animation.core.tween(300),
        label = "card",
    )
    var boing by remember { mutableStateOf(0) }
    val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(boing) {
        if (boing == 0) return@LaunchedEffect
        bounce.animateTo(0.86f, androidx.compose.animation.core.tween(90))
        bounce.animateTo(1.1f, androidx.compose.animation.core.tween(120))
        bounce.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f))
    }
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Column(
            Modifier
                    .fillMaxWidth()
                .clip(shape)
                .background(card)
                .drawBehind {
                    // A faint dotted texture, fading in from the corner.
                    val gap = 18.dp.toPx()
                    val r = 1.6.dp.toPx()
                    var x = gap / 2
                    while (x < size.width) {
                        var y = gap / 2
                        while (y < size.height) {
                            val fade = ((x / size.width + y / size.height) / 2f).coerceIn(0f, 1f)
                            drawCircle(Palette.Ink.copy(alpha = 0.11f * fade), r, Offset(x, y))
                            y += gap
                        }
                        x += gap
                    }
                }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier
                        .graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }
                        .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { boing++ },
                ) {
                    // The stamp sits on a dark offset shadow.
                    Box(Modifier.padding(top = 6.dp).size(92.dp).background(Palette.Ink, RoundedCornerShape(30.dp)))
                    Box(
                        Modifier
                            .size(92.dp)
                            .background(Color.White, RoundedCornerShape(30.dp))
                            .border(2.dp, Palette.Ink, RoundedCornerShape(30.dp)),
                    ) {
                        Avatar(username, size = 92, stamp = true)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("@$username", color = Palette.Ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.022).em, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Row(
                        Modifier.height(26.dp).background(Color.White.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Glyphs.Lock, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(12.dp))
                        Text(if (online) "Sealed · ${ink.label} ink" else "Offline", color = Palette.Ink, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Ink)
                        .clickable(role = Role.Button, onClick = onEdit),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyphs.Pencil, contentDescription = null, tint = Palette.Cream, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit profile", color = Palette.Cream, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .border(1.5.dp, Palette.Ink, RoundedCornerShape(50))
                        .clickable(role = Role.Button) {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, "Find me on Messenger: @$username")
                            }
                            context.startActivity(android.content.Intent.createChooser(send, null))
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyphs.Share, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Share @$username", color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
        // Your shapes: a heart, a sparkle and a squiggle. Tap one to squash it.
        ShapeSticker(ShapeKind.Heart, Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp), size = 60.dp, delayMs = 80, tappable = true)
        ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 84.dp), size = 36.dp, delayMs = 200, phase = -1.2f, tappable = true)
        ShapeSticker(ShapeKind.Squiggle, Modifier.align(Alignment.TopEnd).padding(top = 92.dp, end = 16.dp), size = 40.dp, delayMs = 300, tappable = true)
    }
}
