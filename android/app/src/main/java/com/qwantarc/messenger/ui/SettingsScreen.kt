package com.qwantarc.messenger.ui

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.statusBarsPadding
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
private const val SECURITY = "security"
private const val LINKED_DEVICES = "devices"
private const val LINK_DEVICE = "link-device"
private const val RESET_IDENTITY = "reset-identity"

/** The pages the first page's rows open. */
private val CATEGORIES = setOf(PROFILE, ACCOUNT, PRIVACY, SECURITY, CHATS, NOTIFICATIONS)

/**
 * Account, privacy and security. One screen with a few pages; the page is
 * saved, so rotating the phone keeps it.
 */
@Composable
fun SettingsScreen(state: UiState, model: AppViewModel, lock: AppLock.State, onTurnOnLock: () -> Unit) {
    val account = state.account ?: return
    var page by rememberSaveable { mutableStateOf(MAIN) }

    when {
        page == SAFETY_NUMBERS -> SafetyNumbersPage(state, onBack = { page = MAIN }) { page = SAFETY_NUMBER + it }
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

    val back: () -> Unit = { if (category in CATEGORIES) open(MAIN) else model.closeSettings() }
    BackHandler(onBack = back)
    val t = tones()
    val appearance = LocalAppearance.current
    val safetyChats = state.conversations.count { it.kind == ConversationKind.DIRECT && it.active }
    val safetyCount = if (safetyChats == 1) "1 chat" else "$safetyChats chats"
    val host = runCatching { java.net.URI(account.serverUrl).host }.getOrNull() ?: account.serverUrl
    val recoveryPill: @Composable () -> Unit = {
        if (state.unconfirmedRecoveryKey != null) KitPill("Not written down", Kit.NotOk) else KitPill("Written down")
    }
    when (category) {
        PROFILE -> KitPage("Profile", back) { ProfilePage(state, model) }
        CHATS -> KitPage("Chats and appearance", back) { LookPage(state, model) }
        NOTIFICATIONS -> KitPage("Notifications", back) { NotificationsSection(appLockOn = lock.enabled) }
        PRIVACY -> KitPage("Privacy", back) {
            KitCard {
                SwitchRow("Read receipts", checked = state.privacy.readReceipts) {
                    model.setPrivacy(state.privacy.copy(readReceipts = it))
                }
                SwitchRow("Typing indicators", checked = state.privacy.typingIndicators) {
                    model.setPrivacy(state.privacy.copy(typingIndicators = it))
                }
            }
            KitFoot("These go both ways: turn one off and you won’t see it from others either.")
        }
        SECURITY -> KitPage("Security", back) {
            KitCard {
                SwitchRow(
                    "App lock",
                    supporting = "Unlock with your fingerprint, face or screen lock",
                    checked = lock.enabled,
                    enabled = !lock.authenticating,
                ) { on -> if (on) onTurnOnLock() else model.lock.turnOff() }
                if (lock.enabled) {
                    KitRow("Lock after", modifier = Modifier.clickable(role = Role.Button) { pickTimeout = true }) {
                        KitValueText(lock.timeout.label)
                        KitChevron()
                    }
                }
                SwitchRow("Hide in recent apps", checked = lock.hideInRecents) { model.lock.setHideInRecents(it) }
                SwitchRow(
                    "Block screenshots and screen sharing",
                    supporting = "On Android and the Mac",
                    checked = lock.blockScreenshots,
                ) { model.lock.setBlockScreenshots(it) }
            }
            lock.error?.let { KitFoot(it, color = t.danger) }
            if (lock.hideInRecents && !lock.blockScreenshots && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                KitFoot("On this version of Android, hiding the app in recent apps also blocks screenshots.")
            }
            KitGap()
            KitCard {
                SwitchRow("Panic wipe", supporting = "Triple-tap “Chats” to erase this phone instantly", checked = lock.panicWipe) {
                    model.lock.setPanicWipe(it)
                }
            }
            KitFoot("No confirmation, no network, nothing sent. Your recovery key brings the account back, without the old messages.")
            KitCard {
                KitRow("Safety numbers", modifier = Modifier.clickable(role = Role.Button) { open(SAFETY_NUMBERS) }) {
                    KitValueText(safetyCount)
                    KitChevron()
                }
            }
            KitFoot("Compare a safety number with a friend in person to be sure nobody is in the middle of your chat.")
        }
        ACCOUNT -> KitPage("Account", back) {
            KitCard {
                KitRow("Username", hint = "Yours for good. Deleting the account frees it") { KitValueText("@${account.username}") }
                if (state.unconfirmedRecoveryKey != null) {
                    KitRow(
                        "Recovery key",
                        hint = "Not confirmed as written down. Tap to see it",
                        modifier = Modifier.clickable(enabled = !working, role = Role.Button) { open(RECOVERY_KEY) },
                    ) { recoveryPill() }
                } else {
                    KitRow("Recovery key") { recoveryPill() }
                }
                ValueRow("Server", host)
                ValueRow("Account ID", account.accountId, monospace = true)
            }
            KitGap()
            KitCard {
                KitButton("Create a new recovery key", color = appearance.ink.color(isDark()), weight = FontWeight.Medium, enabled = !working) { confirmNewKey = true }
            }
            KitFoot("Your current key stops working the moment you make a new one. Nobody else has it, not even the server.")
            KitCard {
                KitButton("Reset identity", color = t.danger, weight = FontWeight.SemiBold, centred = true, enabled = !working) { open(RESET_IDENTITY) }
            }
            KitFoot("Only for a stolen device. Your contacts will see a new safety number afterwards.")
        }
        else -> Box(Modifier.fillMaxSize()) {
            HomeGround(Modifier.matchParentSize())
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("You", style = displayStyle(30.sp), color = t.text, modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp).semantics { heading() })
                MeCard(account.username, state.online, onEdit = { open(PROFILE) })
                KitLabel("Settings")
                KitCard(radius = 24.dp, inset = 64.dp) {
                    CategoryRow("Chats and appearance", "Theme, ink, paper", appearance.ink.label, Glyphs.Palette, Palette.TileYellow) { open(CHATS) }
                    CategoryRow("Notifications", "Names and messages, delivery", "", Glyphs.Bell, Palette.TilePeach) { open(NOTIFICATIONS) }
                    CategoryRow("Privacy", "Read receipts, typing", "", Glyphs.Eye, Palette.TileLavender) { open(PRIVACY) }
                    CategoryRow("Security", "App lock, panic wipe", if (lock.enabled) "On" else "Off", Glyphs.Lock, Palette.TileMint) { open(SECURITY) }
                    CategoryRow("Linked devices", "Your phone, computer and other devices", "", Glyphs.Devices, Palette.TileApricot) { open(LINKED_DEVICES) }
                    CategoryRow("Account", "Username, recovery key", "", Glyphs.Key, Palette.TileSky) { open(ACCOUNT) }
                }
                KitLabel("Account")
                KitCard(radius = 24.dp) {
                    KitRow("Recovery key", modifier = Modifier.clickable(role = Role.Button) { open(ACCOUNT) }) { recoveryPill() }
                    ValueRow("Server", host)
                    KitRow("Safety numbers", modifier = Modifier.clickable(role = Role.Button) { open(SAFETY_NUMBERS) }) {
                        KitValueText(safetyCount)
                        KitChevron()
                    }
                }
                KitCard(radius = 24.dp) {
                    KitButton("Remove from this device", weight = FontWeight.Medium, centred = true, height = 56.dp, enabled = !working) { confirmRemove = true }
                    KitButton("Delete account", color = t.danger, weight = FontWeight.SemiBold, centred = true, height = 56.dp, enabled = !working) { open(DELETE_ACCOUNT) }
                }
                if (working) {
                    Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                error?.let { KitFoot(it, color = t.danger) }
                Text(
                    "Messenger ${coreVersion()} · MLS encryption with a post-quantum key exchange",
                    color = t.secondary,
                    fontSize = 12.5.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
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

    KitCard {
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
                Push.Status.On -> KitRow("Delivery", hint = distributor?.let { "Through $it" }) { KitValueText("Instant") }
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
        }
    }
    KitFoot("App lock doesn’t cover notifications. Hide names and messages to keep your chats behind the lock.")
    KitFoot(
        "A push carries nothing readable. This phone fetches and decrypts new messages itself. To wake the right phones, " +
            "the server learns who you chat with, and the push app sees only that a push came; with notifications off, neither does.",
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

// ---- rows: the design's, shared with the other settings pages ----

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        color = tones().secondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
internal fun Footer(text: String, color: Color = tones().secondary) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 18.5.sp,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 4.dp),
    )
}

@Composable
internal fun ValueRow(label: String, value: String, monospace: Boolean = false) {
    KitRow(label) {
        SelectionContainer {
            Text(
                value,
                color = tones().secondary,
                fontSize = if (monospace) 12.sp else 14.5.sp,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
        }
    }
}

@Composable
internal fun ActionRow(title: String, destructive: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    KitButton(title, color = if (destructive) tones().danger else null, weight = if (destructive) FontWeight.SemiBold else FontWeight.Normal, enabled = enabled, onClick = onClick)
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    supporting: String? = null,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    KitRow(
        title,
        hint = supporting,
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
    ) {
        IosSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A row on You: an icon tile, the page it opens, a line about it, and a value. */
@Composable
private fun CategoryRow(title: String, detail: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, onClick: () -> Unit) {
    val t = tones()
    Row(
        Modifier.fillMaxWidth().height(62.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconTile(icon, tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = t.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = t.secondary, fontSize = 12.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        if (value.isNotEmpty()) Text(value, color = t.secondary, fontSize = 14.sp)
        KitChevron()
    }
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
internal fun Group(inset: Dp = 0.dp, outer: Dp = 16.dp, radius: Dp = 20.dp, content: @Composable () -> Unit) {
    val lines = remember { mutableListOf<Int>() }
    val hairline = tones().line
    val shape = RoundedCornerShape(radius)
    Layout(
        content = content,
        modifier = Modifier
            .padding(horizontal = outer)
            .fillMaxWidth()
            .clip(shape)
            .background(tones().surface)
            .border(1.dp, Kit.cardLine(), shape)
            .drawWithContent {
                drawContent()
                val start = inset.toPx()
                lines.forEach { y -> drawLine(hairline, Offset(start, y.toFloat()), Offset(size.width, y.toFloat()), strokeWidth = 1.dp.toPx()) }
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
