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
import com.qwantarc.messenger.core.SafetyNumber
import com.qwantarc.messenger.core.SyncReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** The Android emulator reaches a server running on the host machine here. */
const val DEFAULT_SERVER_URL = "http://10.0.2.2:8080"

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
    val messages: List<MessageView> = emptyList(),
    val online: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val serverUrl: String = DEFAULT_SERVER_URL,
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

    private var client: MessengerClient? = null
    private var openedUrl: String? = null
    private var syncJob: Job? = null

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
        viewModelScope.launch { refreshConversations() }
        startSync()
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
        enterApp()
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

    private suspend fun apply(@Suppress("UNUSED_PARAMETER") report: SyncReport) {
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
            core { it.markRead(conversationId) }
        } catch (e: Exception) {
            _state.update { it.copy(error = e.userMessage) }
        }
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
