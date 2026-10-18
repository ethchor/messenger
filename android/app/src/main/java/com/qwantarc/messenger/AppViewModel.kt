package com.qwantarc.messenger

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qwantarc.messenger.core.AccountInfo
import com.qwantarc.messenger.core.ConversationSummary
import com.qwantarc.messenger.core.MemberView
import com.qwantarc.messenger.core.MessageView
import com.qwantarc.messenger.core.MessengerClient
import com.qwantarc.messenger.core.MessengerException
import com.qwantarc.messenger.core.PrivacySettings
import com.qwantarc.messenger.core.SafetyNumber
import com.qwantarc.messenger.core.SyncReport
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
) {
    val openConversation: ConversationSummary?
        get() = conversations.firstOrNull { it.id == openConversationId }
}

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

    private var client: MessengerClient? = null
    private var openedUrl: String? = null
    private var syncJob: Job? = null

    /** The activity is on screen. With the lock, decides whether messages count as read. */
    private var foreground = false

    /** Typing updates go out one at a time, in order. */
    private val typingUpdates = MutableSharedFlow<Pair<String, Boolean>>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Long-polls run here, so they never hold a thread other calls need. */
    private val pollDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** Not backed up, and excluded from device transfer (see the manifest). */
    private val dataDir = File(app.noBackupFilesDir, "messenger").path

    init {
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
        syncJob?.cancel()
        client?.close()
        pollDispatcher.close()
    }

    // ---- account ----

    private suspend fun open(serverUrl: String) {
        if (client != null && openedUrl == serverUrl) return
        client?.close()
        client = null
        client = withContext(Dispatchers.IO) { MessengerClient(dataDir, serverUrl) }
        openedUrl = serverUrl
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
            loadSettings()
        }
        startSync()
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
        lock.reset()
        _state.update { UiState(phase = Phase.Onboarding, serverUrl = it.serverUrl) }
    }

    /**
     * Panic wipe: erases this device at once, without the network. The
     * recovery key brings the account back, without the old messages.
     */
    fun wipeThisDevice() {
        if (_state.value.phase != Phase.Ready) return
        stopSync()
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
        refreshConversations()
        _state.value.openConversationId?.let { loadMessages(it) }
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
            if (canMarkRead) core { it.markRead(conversationId) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.userMessage) }
        }
    }

    /** Messages only count as read (and send read receipts) while someone can see them. */
    private val canMarkRead: Boolean
        get() = foreground && !lock.state.value.locked

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) markOpenConversationRead()
    }

    private fun markOpenConversationRead() {
        val id = _state.value.openConversationId ?: return
        if (!canMarkRead || _state.value.phase != Phase.Ready) return
        viewModelScope.launch {
            runCatching { core { it.markRead(id) } }
            refreshConversations()
        }
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
        _state.update { it.copy(openConversationId = id, messages = emptyList()) }
        viewModelScope.launch {
            loadMessages(id)
            refreshConversations()
        }
    }

    fun closeConversation() {
        _state.update { it.copy(openConversationId = null, messages = emptyList()) }
        viewModelScope.launch { refreshConversations() }
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

    fun react(conversationId: String, messageId: String, emoji: String?) = perform(conversationId) {
        it.react(conversationId, messageId, emoji)
    }

    fun edit(conversationId: String, messageId: String, text: String) = perform(conversationId) {
        it.editMessage(conversationId, messageId, text)
    }

    fun delete(conversationId: String, messageId: String) = perform(conversationId) {
        it.deleteMessage(conversationId, messageId)
    }

    private fun perform(conversationId: String, action: (MessengerClient) -> Unit) {
        viewModelScope.launch {
            try {
                core(action)
            } catch (e: Exception) {
                _state.update { it.copy(error = e.userMessage) }
            }
            loadMessages(conversationId)
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
    }
}

/** Splits "alice, bob carol" into usernames. */
fun parseUsernames(text: String): List<String> =
    text.split(',', ' ', '\n', '\t').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
