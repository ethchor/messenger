package com.qwantarc.messenger

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwantarc.messenger.core.AccountInfo
import com.qwantarc.messenger.core.AttachmentKind
import com.qwantarc.messenger.core.AttachmentState
import com.qwantarc.messenger.core.AttachmentView
import com.qwantarc.messenger.core.ConversationSummary
import com.qwantarc.messenger.core.LocationShare
import com.qwantarc.messenger.core.MemberView
import com.qwantarc.messenger.core.MessageView
import com.qwantarc.messenger.core.MessengerClient
import com.qwantarc.messenger.core.MessengerException
import com.qwantarc.messenger.core.NewAttachment
import com.qwantarc.messenger.core.PersonProfile
import com.qwantarc.messenger.core.PrivacySettings
import com.qwantarc.messenger.core.SafetyNumber
import com.qwantarc.messenger.core.SharedLocation
import com.qwantarc.messenger.core.SyncReport
import com.qwantarc.messenger.media.AttachmentTray
import com.qwantarc.messenger.media.MediaFiles
import com.qwantarc.messenger.media.Preparation
import com.qwantarc.messenger.media.VoiceNote
import com.qwantarc.messenger.media.VoicePlayer
import com.qwantarc.messenger.push.Notifier
import com.qwantarc.messenger.push.Push
import com.qwantarc.messenger.ui.Appearance
import com.qwantarc.messenger.ui.AvatarDrawing
import com.qwantarc.messenger.ui.EmojiCatalog
import com.qwantarc.messenger.ui.Ink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** The Android emulator reaches a server running on the host machine here. */
const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"

private val DEFAULT_PRIVACY = PrivacySettings(readReceipts = true, typingIndicators = true)

sealed interface Phase {
    data object Starting : Phase
    data object Onboarding : Phase
    data class RecoveryKey(val key: String) : Phase
    data object Ready : Phase
    data class Failed(val message: String) : Phase
}

data class UiState(
    val phase: Phase = Phase.Starting,
    val account: AccountInfo? = null,
    val conversations: List<ConversationSummary> = emptyList(),
    val openConversationId: String? = null,
    val settingsOpen: Boolean = false,
    val messages: List<MessageView> = emptyList(),
    /** The open thread in the open chat: the id of the message it hangs off. */
    val openThread: String? = null,
    /** That message, then the replies, oldest first. */
    val threadMessages: List<MessageView> = emptyList(),
    val online: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val serverUrl: String = DEFAULT_SERVER_URL,
    /** Read receipts and typing indicators (kept by the core). */
    val privacy: PrivacySettings = DEFAULT_PRIVACY,
    /** Who is typing, by conversation id. */
    val typing: Map<String, List<String>> = emptyMap(),
    /** A recovery key that hasn't been confirmed as written down yet. */
    val unconfirmedRecoveryKey: String? = null,
    /** The latest position of everyone sharing their location with us. */
    val sharedLocations: List<SharedLocation> = emptyList(),
    /** The chats we share our own location in. */
    val locationShares: List<LocationShare> = emptyList(),
    val mapOpen: Boolean = false,
    /** Everyone's avatar and ink we know of, by username (ours too). */
    val profiles: Map<String, PersonProfile> = emptyMap(),
    /** Files on their way down. */
    val fetching: Set<AttachmentRef> = emptySet(),
) {
    fun locationShare(conversationId: String): LocationShare? = locationShares.firstOrNull { it.conversationId == conversationId }

    /** A message on screen, in the open chat or thread. */
    fun message(id: String): MessageView? = messages.firstOrNull { it.id == id } ?: threadMessages.firstOrNull { it.id == id }

    val openConversation: ConversationSummary?
        get() = conversations.firstOrNull { it.id == openConversationId }

    /** Our avatar and ink, as others see them. */
    val myProfile: PersonProfile?
        get() = account?.let { profiles[it.username] }
}

/** A file in a message, for fetching it and showing that it's on its way. */
data class AttachmentRef(val conversationId: String, val messageId: String, val position: UInt)

/** The message the Rust core wrote for an error, fit to show to people. */
val Throwable.userMessage: String
    get() = message ?: toString()

/**
 * App state for the screens. The Rust core owns the real state (keys, groups,
 * messages); this mirrors what's on screen and runs the sync loop.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("messenger", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(serverUrl = prefs.getString(KEY_SERVER, null) ?: DEFAULT_SERVER_URL))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** App lock and screen privacy; kept here so they outlive screen rotation. */
    val lock = AppLock(app)

    /** Ink and paper, on this device. */
    val appearance = Appearance(app)

    private var client: MessengerClient? = null
    private var syncJob: Job? = null

    /** The activity is on screen. With the lock, decides whether messages count as read. */
    private var foreground = false

    /** A chat, and maybe a thread in it, to open once the app is ready (its notification was tapped while starting). */
    private var pendingOpen: Pair<String, String?>? = null

    /** Typing updates go out one at a time, in order. */
    private val typingUpdates = MutableSharedFlow<Pair<String, Boolean>>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Long-polls run here, so they never hold a thread other calls need. */
    private val pollDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** Files picked for the next message, by chat, or by "chat/thread" for a thread. */
    private val trays = mutableMapOf<String, AttachmentTray>()

    /** The tray a picker or the camera was opened for, and where the camera saves. */
    private var pickingFor: String? = null
    private var capture: Capture? = null

    private class Capture(val tray: String, val file: File, val video: Boolean)

    /** Files waiting for one of the few fetches that run at once. */
    private val fetchQueue = ArrayDeque<AttachmentRef>()
    private var fetchesRunning = 0

    /** Uploads go one round at a time; asking during one asks for another. */
    private var uploading = false
    private var uploadAgain = false

    /** From the latest sync: our messages whose files haven't gone up yet. */
    private var unsentMedia = 0u

    init {
        // Nothing is on its way to the core yet, and links for other apps can go.
        MediaFiles.removeTemporaryFiles(app)
        viewModelScope.launch {
            try {
                open(_state.value.serverUrl)
                loadAccount()
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.Failed(e.userMessage)) }
            }
        }
        viewModelScope.launch {
            typingUpdates.collect { (conversationId, typing) ->
                runCatching { core { it.setTyping(conversationId, typing) } }
            }
        }
        // Unlocking shows the open chat again, which reads it.
        viewModelScope.launch {
            lock.state.map { it.locked }.distinctUntilChanged().collect { locked ->
                if (!locked) markOpenConversationRead()
            }
        }
    }

    override fun onCleared() {
        // The client stays open: the location sharing service may still use it.
        syncJob?.cancel()
        pollDispatcher.close()
        trays.values.forEach { it.clear() }
    }

    // ---- account ----

    private suspend fun open(serverUrl: String) {
        client = MessengerCore.open(getApplication<Application>(), serverUrl)
    }

    private fun requireClient(): MessengerClient =
        client ?: throw MessengerException.Internal("The app is still starting.")

    private suspend fun <T> core(block: (MessengerClient) -> T): T {
        val c = requireClient()
        return withContext(Dispatchers.IO) { block(c) }
    }

    /** Runs [work] to the end even if the screen that asked for it goes away. */
    private suspend fun <T> detached(work: suspend () -> T): T = viewModelScope.async { work() }.await()

    private suspend fun loadAccount() {
        val account = core { it.account() }
        val pendingKey = core { it.pendingRecoveryKey() }
        _state.update { it.copy(account = account) }
        when {
            account == null -> _state.update { it.copy(phase = Phase.Onboarding) }
            pendingKey != null -> _state.update { it.copy(phase = Phase.RecoveryKey(pendingKey), serverUrl = account.serverUrl) }
            else -> {
                _state.update { it.copy(serverUrl = account.serverUrl) }
                enterApp()
            }
        }
    }

    private fun enterApp() {
        _state.update { it.copy(phase = Phase.Ready) }
        viewModelScope.launch {
            refreshConversations()
            pendingOpen?.let { (conversation, thread) ->
                pendingOpen = null
                showConversation(conversation, thread)
            }
            loadSettings()
            refreshLocations()
            refreshProfiles()
        }
        startSync()
        // Files that didn't finish going up last time.
        sendPendingMedia()
    }

    private suspend fun loadSettings() {
        runCatching { core { it.privacySettings() } }.onSuccess { privacy -> _state.update { it.copy(privacy = privacy) } }
        runCatching { core { it.pendingRecoveryKey() } }.onSuccess { key -> _state.update { it.copy(unconfirmedRecoveryKey = key) } }
    }

    fun setServerUrl(url: String) = _state.update { it.copy(serverUrl = url) }

    private fun normalizedServerUrl(): String {
        val url = _state.value.serverUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw MessengerException.InvalidInput("The server address should look like https://chat.example.com")
        }
        prefs.edit().putString(KEY_SERVER, url).apply()
        _state.update { it.copy(serverUrl = url) }
        return url
    }

    fun register(username: String) = whileBusy {
        open(normalizedServerUrl())
        val registration = core { it.register(username) }
        _state.update { it.copy(account = registration.account, phase = Phase.RecoveryKey(registration.recoveryKey)) }
    }

    fun recover(username: String, recoveryKey: String) = whileBusy {
        open(normalizedServerUrl())
        val account = core { it.recover(username, recoveryKey) }
        _state.update { it.copy(account = account) }
        enterApp()
    }

    fun confirmRecoveryKeySaved() = whileBusy {
        core { it.confirmRecoveryKeySaved() }
        _state.update { it.copy(unconfirmedRecoveryKey = null) }
        if (_state.value.phase != Phase.Ready) enterApp()
    }

    // ---- account settings (these throw so the settings screen can show errors inline) ----

    /** Replaces the recovery key. The old one stops working at once. */
    suspend fun newRecoveryKey(): String = detached {
        val key = core { it.newRecoveryKey() }
        _state.update { it.copy(unconfirmedRecoveryKey = key) }
        key
    }

    /** Leaves every chat, deletes the account on the server and erases this device. */
    suspend fun deleteAccount() = removeAccount { it.deleteAccount() }

    /** Leaves every chat and erases this device; the recovery key brings the account back. */
    suspend fun removeFromDevice() = removeAccount { it.removeFromDevice() }

    private suspend fun removeAccount(action: (MessengerClient) -> Unit) = detached {
        stopSync()
        try {
            core(action)
        } catch (e: Exception) {
            startSync()
            throw e
        }
        LocationSharingService.stop(getApplication<Application>())
        forgetLocalState()
        lock.reset()
        _state.update { UiState(phase = Phase.Onboarding, serverUrl = it.serverUrl) }
    }

    /** The account is leaving this device: no more pushes or notifications, and the look and tips back to the start. */
    private fun forgetLocalState() {
        Push.forget(getApplication<Application>())
        Notifier.cancelAll(getApplication<Application>())
        VoicePlayer.stop()
        trays.values.forEach { it.clear() }
        trays.clear()
        fetchQueue.clear()
        MediaFiles.removeTemporaryFiles(getApplication<Application>())
        EmojiCatalog.reset(getApplication<Application>())
        appearance.reset()
        tips.edit().clear().apply()
        dismissedTips = emptySet()
    }

    /**
     * Panic wipe: erases this device at once, without the network. The
     * recovery key brings the account back, without the old messages.
     */
    fun wipeThisDevice() {
        if (_state.value.phase != Phase.Ready) return
        stopSync()
        LocationSharingService.stop(getApplication<Application>())
        forgetLocalState()
        // Off the screen first; the files go a moment later.
        val serverUrl = _state.value.serverUrl
        _state.value = UiState(phase = Phase.Onboarding, serverUrl = serverUrl)
        lock.reset()
        viewModelScope.launch {
            try {
                detached { core { it.wipeThisDevice() } }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage) }
            }
        }
    }

    fun setPrivacy(settings: PrivacySettings) {
        val previous = _state.value.privacy
        _state.update { it.copy(privacy = settings, typing = if (settings.typingIndicators) it.typing else emptyMap()) }
        viewModelScope.launch {
            try {
                core { it.setPrivacySettings(settings) }
            } catch (e: Exception) {
                _state.update { it.copy(privacy = previous, error = e.userMessage) }
            }
        }
    }

    // ---- sync ----

    private fun startSync() {
        if (syncJob != null) return
        val c = client ?: return
        syncJob = viewModelScope.launch {
            var backoffMs = 1_000L
            while (isActive) {
                try {
                    val report = withContext(pollDispatcher) { c.sync(25_000u) }
                    _state.update { it.copy(online = true) }
                    backoffMs = 1_000L
                    apply(report)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is MessengerException.Network) {
                        _state.update { it.copy(online = false) }
                    } else {
                        _state.update { it.copy(error = e.userMessage) }
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun stopSync() {
        syncJob?.cancel()
        syncJob = null
    }

    /** One round now, without waiting (pull to refresh, back from background). */
    fun syncNow() {
        if (_state.value.phase != Phase.Ready) return
        viewModelScope.launch {
            try {
                apply(core { it.sync(0u) })
                _state.update { it.copy(online = true) }
            } catch (e: Exception) {
                if (e is MessengerException.Network) _state.update { it.copy(online = false) }
            }
        }
    }

    private suspend fun apply(report: SyncReport) {
        if (_state.value.phase != Phase.Ready) return
        report.typingChanged.forEach { refreshTyping(it) }
        // Cheap, and it also notices shares that just ran out.
        refreshLocations()
        if (report.profilesChanged) refreshProfiles()
        refreshConversations()
        _state.value.openConversationId?.let { loadMessages(it) }
        if (_state.value.openThread != null) loadThread()
        showNotifications()
        unsentMedia = report.unsentMedia
        if (report.unsentMedia > 0u) sendPendingMedia()
        if (report.newMessages > 0u) fetchNewMedia(report.updatedConversations.filter { it != _state.value.openConversationId })
    }

    /**
     * New messages as notifications, unless they're on screen already: the
     * open chat shows its own, and the chat list shows them all.
     */
    private suspend fun showNotifications() {
        val notes = runCatching { core { it.takeNotifications(50u) } }.getOrNull()
        if (notes.isNullOrEmpty()) return
        val app = getApplication<Application>()
        val state = _state.value
        when {
            !foreground || lock.state.value.locked || state.mapOpen -> Notifier.show(app, notes)
            // A reply in a thread that isn't open still gets one.
            state.openConversationId != null -> Notifier.show(app, notes) { note ->
                note.conversationId == state.openConversationId && (note.threadRoot == null || note.threadRoot == state.openThread)
            }
            state.settingsOpen -> Notifier.show(app, notes)
            else -> Unit
        }
    }

    private suspend fun refreshConversations() {
        runCatching { core { it.conversations() } }.onSuccess { list -> _state.update { it.copy(conversations = list) } }
    }

    private suspend fun loadMessages(conversationId: String) {
        try {
            val list = core { it.messages(conversationId, 500u) }
            if (_state.value.openConversationId == conversationId) {
                _state.update { it.copy(messages = list) }
            }
            fetchAutomatically(conversationId, list)
            if (canMarkRead) core { it.markRead(conversationId) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.userMessage) }
        }
    }

    private suspend fun loadThread() {
        val conversationId = _state.value.openConversationId ?: return
        val root = _state.value.openThread ?: return
        try {
            val list = core { it.threadMessages(conversationId, root, 500u) }
            if (_state.value.openThread == root) {
                _state.update { it.copy(threadMessages = list) }
            }
            fetchAutomatically(conversationId, list)
            if (canMarkRead) core { it.markThreadRead(conversationId, root) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.userMessage) }
        }
    }

    /** Messages only count as read (and send read receipts) while someone can see them. */
    private val canMarkRead: Boolean
        get() = foreground && !lock.state.value.locked

    fun setForeground(value: Boolean) {
        foreground = value
        AppVisibility.onScreen.value = value
        if (value) {
            markOpenConversationRead()
            if (_state.value.locationShares.isNotEmpty()) LocationSharingService.start(getApplication<Application>())
        } else if (uploading || unsentMedia > 0u) {
            // Android may stop the app before its files are up.
            MediaUploadWorker.enqueue(getApplication<Application>())
        }
    }

    private fun markOpenConversationRead() {
        val id = _state.value.openConversationId ?: return
        if (!canMarkRead || _state.value.phase != Phase.Ready) return
        Notifier.cancel(getApplication<Application>(), id)
        val thread = _state.value.openThread
        viewModelScope.launch {
            runCatching { core { it.markRead(id) } }
            if (thread != null) runCatching { core { it.markThreadRead(id, thread) } }
            refreshConversations()
        }
    }

    // ---- location sharing ----

    suspend fun refreshLocations() {
        val shared = runCatching { core { it.sharedLocations() } }.getOrNull()
        val shares = runCatching { core { it.locationShares() } }.getOrNull()
        val hadShares = _state.value.locationShares.isNotEmpty()
        _state.update {
            it.copy(sharedLocations = shared ?: it.sharedLocations, locationShares = shares ?: it.locationShares)
        }
        if (shares != null) {
            // The service also stops by itself once nothing is shared.
            if (shares.isEmpty() && hadShares) LocationSharingService.stop(getApplication<Application>())
            if (shares.isNotEmpty() && !hadShares && foreground) LocationSharingService.start(getApplication<Application>())
        }
    }

    /** Starts sharing our location in a chat until [untilMs] (null: until we stop). */
    suspend fun startSharingLocation(conversationId: String, untilMs: Long?) = detached {
        core { it.startSharingLocation(conversationId, untilMs) }
        refreshLocations()
        LocationSharingService.start(getApplication<Application>())
    }

    suspend fun stopSharingLocation(conversationId: String) = detached {
        core { it.stopSharingLocation(conversationId) }
        refreshLocations()
    }

    fun openMap() {
        _state.update { it.copy(mapOpen = true) }
        viewModelScope.launch { refreshLocations() }
    }

    fun closeMap() = _state.update { it.copy(mapOpen = false) }

    // ---- profile ----

    private suspend fun refreshProfiles() {
        runCatching { core { it.profiles() } }.onSuccess { list ->
            _state.update { it.copy(profiles = list.associateBy { p -> p.username }) }
        }
    }

    /** Sets our avatar (null: our initial) and ink, and shares them with every chat. */
    fun setProfile(avatar: AvatarDrawing?, ink: Ink) {
        appearance.changeInk(ink)
        viewModelScope.launch {
            try {
                core { it.setProfile(avatar?.id ?: "", ink.id) }
                refreshProfiles()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage) }
            }
        }
    }

    // ---- tips ----

    private val tips = app.getSharedPreferences(TIPS, Context.MODE_PRIVATE)

    /** Suggestions on the chat list that were done or dismissed. */
    var dismissedTips by mutableStateOf(tips.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet())
        private set

    fun dismissTip(id: String) {
        dismissedTips = dismissedTips + id
        tips.edit().putStringSet(KEY_DISMISSED, dismissedTips).apply()
    }

    // ---- typing ----

    suspend fun refreshTyping(conversationId: String) {
        val names = runCatching { core { it.typingUsers(conversationId) } }.getOrDefault(emptyList())
        _state.update { state ->
            state.copy(typing = if (names.isEmpty()) state.typing - conversationId else state.typing + (conversationId to names))
        }
    }

    /** Tells the others we're typing (or stopped). The core throttles repeats. */
    fun setTyping(conversationId: String, typing: Boolean) {
        if (!_state.value.privacy.typingIndicators) return
        typingUpdates.tryEmit(conversationId to typing)
    }

    // ---- navigation ----

    fun openConversation(id: String) {
        trays.keys.filter { it != id && !it.startsWith("$id/") }.forEach(::dropTray)
        _state.update { it.copy(openConversationId = id, messages = emptyList(), openThread = null, threadMessages = emptyList()) }
        Notifier.cancel(getApplication<Application>(), id)
        viewModelScope.launch {
            loadMessages(id)
            refreshConversations()
        }
    }

    /** A message notification was tapped: its chat, and its thread if it's a reply in one. */
    fun openFromNotification(conversationId: String, thread: String? = null) {
        if (_state.value.phase != Phase.Ready) {
            pendingOpen = conversationId to thread
            return
        }
        viewModelScope.launch {
            // The push may have brought a chat this screen hasn't seen yet.
            refreshConversations()
            showConversation(conversationId, thread)
        }
    }

    private fun showConversation(id: String, thread: String? = null) {
        if (_state.value.conversations.none { it.id == id }) return
        _state.update { it.copy(mapOpen = false, settingsOpen = false) }
        openConversation(id)
        thread?.let { openThread(it) }
    }

    fun closeConversation() {
        trays.keys.toList().forEach(::dropTray)
        _state.update { it.copy(openConversationId = null, messages = emptyList(), openThread = null, threadMessages = emptyList()) }
        viewModelScope.launch { refreshConversations() }
    }

    /** Opens the thread under a message in the open chat. */
    fun openThread(rootId: String) {
        _state.update { it.copy(openThread = rootId, threadMessages = emptyList()) }
        viewModelScope.launch {
            loadThread()
            // Its "new" count under the message in the chat goes.
            _state.value.openConversationId?.let { loadMessages(it) }
            refreshConversations()
        }
    }

    fun closeThread() {
        val state = _state.value
        if (state.openConversationId != null && state.openThread != null) dropTray(trayKey(state.openConversationId, state.openThread))
        _state.update { it.copy(openThread = null, threadMessages = emptyList()) }
        _state.value.openConversationId?.let { id -> viewModelScope.launch { loadMessages(id) } }
    }

    fun openSettings() {
        _state.update { it.copy(settingsOpen = true) }
        viewModelScope.launch { loadSettings() }
    }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }

    fun dismissError() = _state.update { it.copy(error = null) }

    // ---- messages ----

    fun send(conversationId: String, text: String, replyTo: String?) = perform(conversationId) {
        it.sendText(conversationId, text, replyTo)
    }

    /** Replies in the thread under [rootId]; the reply shows there, not in the chat. */
    fun replyInThread(conversationId: String, rootId: String, text: String) = perform(conversationId) {
        it.replyInThread(conversationId, rootId, text)
    }

    fun react(conversationId: String, messageId: String, emoji: String?) = perform(conversationId) {
        it.react(conversationId, messageId, emoji)
    }

    fun edit(conversationId: String, messageId: String, text: String) = perform(conversationId) {
        it.editMessage(conversationId, messageId, text)
    }

    fun delete(conversationId: String, messageId: String) = perform(conversationId) {
        it.deleteMessage(conversationId, messageId)
    }

    // ---- files ----

    fun trayKey(conversationId: String, thread: String?): String = if (thread == null) conversationId else "$conversationId/$thread"

    fun tray(key: String): AttachmentTray = trays.getOrPut(key) { AttachmentTray(getApplication<Application>(), viewModelScope) }

    private fun dropTray(key: String) {
        trays.remove(key)?.clear()
    }

    /** A picker is opening for [tray]; what it picks goes there. */
    fun pickingFor(tray: String) {
        pickingFor = tray
    }

    fun picked(uris: List<Uri>) {
        val key = pickingFor ?: return
        if (uris.isNotEmpty()) tray(key).addMedia(uris)
    }

    fun pickedFiles(uris: List<Uri>) {
        val key = pickingFor ?: return
        if (uris.isNotEmpty()) tray(key).addFiles(uris)
    }

    /** Where the camera saves a photo or video for [tray]. */
    fun captureInto(tray: String, video: Boolean): Uri {
        capture?.file?.delete()
        val file = MediaFiles.newOutgoingFile(getApplication<Application>(), if (video) "mp4" else "jpg")
        capture = Capture(tray, file, video)
        return MediaFiles.uriFor(getApplication<Application>(), file)
    }

    fun captured(saved: Boolean) {
        val capture = capture ?: return
        this.capture = null
        if (saved && capture.file.length() > 0) {
            tray(capture.tray).addCapture(capture.file, capture.video)
        } else {
            capture.file.delete()
        }
    }

    /**
     * Sends prepared files with [caption] as their text, maybe as a reply or
     * (with [inThread]) in the thread under [replyTo]. The message shows at
     * once; the files go up in the background. The core moves the files, and
     * any it didn't take are deleted.
     */
    fun sendMedia(conversationId: String, caption: String, files: List<NewAttachment>, replyTo: String?, inThread: Boolean) {
        viewModelScope.launch {
            try {
                core { it.sendMedia(conversationId, caption, files, replyTo, inThread) }
                if (unsentMedia == 0u) unsentMedia = 1u
                sendPendingMedia()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage) }
            }
            MediaFiles.remove(files)
            loadMessages(conversationId)
            if (_state.value.openThread != null) loadThread()
            refreshConversations()
        }
    }

    fun sendVoiceNote(conversationId: String, note: VoiceNote, replyTo: String?, inThread: Boolean) =
        sendMedia(conversationId, "", listOf(Preparation.voiceNote(note)), replyTo, inThread)

    /**
     * Uploads files waiting to go and sends their messages. Network errors
     * leave them for the next sync; the server refusing a file fails its
     * message, which then shows as not sent.
     */
    private fun sendPendingMedia() {
        if (client == null) return
        if (uploading) {
            uploadAgain = true
            return
        }
        uploading = true
        viewModelScope.launch {
            do {
                uploadAgain = false
                try {
                    core { it.sendPending() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e !is MessengerException.Network) _state.update { it.copy(error = e.userMessage) }
                }
                _state.value.openConversationId?.let { loadMessages(it) }
                if (_state.value.openThread != null) loadThread()
                refreshConversations()
            } while (uploadAgain)
            uploading = false
        }
    }

    /** Fetches a file someone sent. [first] puts it ahead of those waiting: someone tapped it. */
    fun download(ref: AttachmentRef, first: Boolean = false) {
        if (ref in _state.value.fetching) {
            if (first && fetchQueue.remove(ref)) fetchQueue.addFirst(ref)
            return
        }
        _state.update { it.copy(fetching = it.fetching + ref) }
        if (first) fetchQueue.addFirst(ref) else fetchQueue.addLast(ref)
        runFetches()
    }

    /** A few at a time: each holds a thread while it waits on the network. */
    private fun runFetches() {
        while (fetchesRunning < 3 && fetchQueue.isNotEmpty() && client != null) {
            val ref = fetchQueue.removeFirst()
            fetchesRunning++
            viewModelScope.launch {
                try {
                    val path = core { it.downloadAttachment(ref.conversationId, ref.messageId, ref.position) }
                    updateAttachment(ref) { it.copy(state = AttachmentState.READY, path = path) }
                } catch (e: MessengerException.InvalidInput) {
                    // Gone from the server, damaged, or the message went.
                    updateAttachment(ref) { it.copy(state = AttachmentState.FAILED, error = e.userMessage) }
                } catch (e: MessengerException.Network) {
                    _state.update { it.copy(online = false) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Tried again the next time the message is shown.
                } finally {
                    fetchesRunning--
                    _state.update { it.copy(fetching = it.fetching - ref) }
                    runFetches()
                }
            }
        }
    }

    /** Photos and voice messages come down as soon as they're seen; videos and other files wait for a tap. */
    private fun fetchAutomatically(conversationId: String, list: List<MessageView>) {
        for (message in list) {
            if (message.deleted) continue
            for (attachment in message.attachments) {
                val wanted = attachment.kind == AttachmentKind.PHOTO || attachment.kind == AttachmentKind.VOICE
                if (wanted && attachment.state == AttachmentState.REMOTE) {
                    download(AttachmentRef(conversationId, message.id, attachment.position))
                }
            }
        }
    }

    /** New photos and voice messages in chats that aren't open, so they're there when the chat is. */
    private suspend fun fetchNewMedia(conversationIds: List<String>) {
        for (id in conversationIds) {
            val recent = runCatching { core { it.messages(id, 30u) } }.getOrNull() ?: continue
            fetchAutomatically(id, recent)
        }
    }

    /** Shows a file's new state in the open chat and thread, without reading them again. */
    private fun updateAttachment(ref: AttachmentRef, change: (AttachmentView) -> AttachmentView) {
        fun patch(list: List<MessageView>) = list.map { message ->
            if (message.id != ref.messageId) {
                message
            } else {
                message.copy(attachments = message.attachments.map { if (it.position == ref.position) change(it) else it })
            }
        }
        _state.update { state ->
            if (state.openConversationId != ref.conversationId) {
                state
            } else {
                state.copy(messages = patch(state.messages), threadMessages = patch(state.threadMessages))
            }
        }
    }

    private fun perform(conversationId: String, action: (MessengerClient) -> Unit) {
        viewModelScope.launch {
            try {
                core(action)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage) }
            }
            loadMessages(conversationId)
            if (_state.value.openThread != null) loadThread()
            refreshConversations()
        }
    }

    // ---- conversations (these throw so dialogs can show the error inline) ----

    suspend fun startDirectChat(username: String) {
        val id = core { it.startDirectChat(username) }
        refreshConversations()
        openConversation(id)
    }

    suspend fun createGroup(title: String, usernames: List<String>) {
        val id = core { it.createGroup(title, usernames) }
        refreshConversations()
        openConversation(id)
    }

    suspend fun addMembers(conversationId: String, usernames: List<String>) {
        core { it.addMembers(conversationId, usernames) }
        core { it.sync(0u) }
        refreshConversations()
        loadMessages(conversationId)
    }

    suspend fun removeMember(conversationId: String, username: String) {
        core { it.removeMember(conversationId, username) }
        core { it.sync(0u) }
        refreshConversations()
        loadMessages(conversationId)
    }

    /** Leaves a chat. The others see it once one of them is online; until then it shows as leaving. */
    suspend fun leave(conversationId: String) = detached {
        core { it.leaveConversation(conversationId) }
        runCatching { core { it.sync(0u) } }
        refreshConversations()
        loadMessages(conversationId)
    }

    suspend fun members(conversationId: String): List<MemberView> = core { it.members(conversationId) }

    suspend fun safetyNumber(conversationId: String): SafetyNumber = core { it.safetyNumber(conversationId) }

    private fun whileBusy(work: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                work()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private companion object {
        const val KEY_SERVER = "serverUrl"
        const val TIPS = "tips"
        const val KEY_DISMISSED = "dismissed"
    }
}

/** Splits "alice, bob carol" into usernames. */
fun parseUsernames(text: String): List<String> =
    text.split(',', ' ', '\n', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
