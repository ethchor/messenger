package com.qwantarc.messenger.push

import android.app.Activity
import android.content.Context
import android.util.Base64
import android.util.Log
import com.qwantarc.messenger.MessengerCore
import com.qwantarc.messenger.core.PushTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Push notifications over UnifiedPush: no Google services. A distributor app
 * (ntfy, for one) keeps a connection open and wakes Messenger when the
 * server pushes. The push says nothing; the app fetches, decrypts and shows
 * the notification itself (see [PushSyncWorker]).
 */
object Push {
    enum class Status {
        /** Turned off in Settings. */
        Off,

        /** No UnifiedPush app installed. */
        NoDistributor,
        Connecting,
        On,

        /** The distributor or the server said no; tried again on the next start. */
        Failed,
    }

    private const val TAG = "Push"
    private const val PREFS = "push"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PREVIEWS = "previews"
    private const val KEY_VAPID = "vapid"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP

    /** For short work that must outlive a screen (registering an endpoint). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** The account left this device; the distributor's goodbye needs no answer. */
    @Volatile
    private var forgotten = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    /** Show who wrote and what in notifications, or just "New message". */
    fun showsPreviews(context: Context) = prefs(context).getBoolean(KEY_PREVIEWS, true)

    fun setShowsPreviews(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_PREVIEWS, value).apply()

    fun hasDistributor(context: Context) = UnifiedPush.getDistributors(context).isNotEmpty()

    /** The app that delivers pushes (ntfy, say), once one is chosen. */
    fun distributorName(context: Context): String? {
        val pkg = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context) ?: return null
        val pm = context.packageManager
        return runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }

    /**
     * Registers with the distributor. Called on every start, as UnifiedPush
     * recommends, so a changed or reinstalled distributor is picked up.
     */
    fun start(activity: Activity) {
        val context = activity.applicationContext
        forgotten = false
        if (!isEnabled(context)) {
            _status.value = Status.Off
            return
        }
        if (!hasDistributor(context)) {
            _status.value = Status.NoDistributor
            return
        }
        if (_status.value != Status.On) _status.value = Status.Connecting
        scope.launch {
            // The server signs its pushes with this key, so the push service
            // can tell they come from it. Kept for starts without a network.
            val vapid = runCatching { MessengerCore.openExisting(context).pushInfo().vapidPublicKey }.getOrNull()
                ?.let { Base64.encodeToString(it, FLAGS) }
                ?.also { prefs(context).edit().putString(KEY_VAPID, it).apply() }
                ?: prefs(context).getString(KEY_VAPID, null)
            withContext(Dispatchers.Main) {
                runCatching {
                    UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
                        if (!ok) {
                            _status.value = Status.NoDistributor
                            return@tryUseCurrentOrDefaultDistributor
                        }
                        runCatching {
                            UnifiedPush.register(context, messageForDistributor = "New messages", vapid = vapid)
                        }.onFailure {
                            Log.w(TAG, "couldn't register with the distributor", it)
                            _status.value = Status.Failed
                        }
                    }
                }.onFailure {
                    Log.w(TAG, "couldn't pick a distributor", it)
                    _status.value = Status.Failed
                }
            }
        }
    }

    fun turnOn(activity: Activity) {
        prefs(activity).edit().putBoolean(KEY_ENABLED, true).apply()
        start(activity)
    }

    fun turnOff(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(KEY_ENABLED, false).apply()
        _status.value = Status.Off
        runCatching { UnifiedPush.unregister(app) }
        // The server forgets the endpoint, and the chats learn not to wake this device.
        scope.launch { runCatching { MessengerCore.openExisting(app).clearPushTarget() } }
    }

    /**
     * The account is leaving this device (removed, deleted or wiped): forget
     * the distributor and the settings here. The server has forgotten the
     * endpoint already, or will when the push service says it's gone.
     */
    fun forget(context: Context) {
        val app = context.applicationContext
        forgotten = true
        _status.value = Status.Off
        runCatching { UnifiedPush.unregister(app) }
        runCatching { UnifiedPush.removeDistributor(app) }
        prefs(app).edit().clear().apply()
    }

    internal fun onEndpoint(context: Context, url: String, pubKey: String, auth: String) {
        if (!isEnabled(context)) return
        scope.launch {
            val target = PushTarget.Webpush(
                endpoint = url,
                p256dh = Base64.decode(pubKey, FLAGS),
                auth = Base64.decode(auth, FLAGS),
            )
            runCatching { MessengerCore.openExisting(context).setPushTarget(target) }
                .onSuccess { _status.value = Status.On }
                .onFailure {
                    Log.w(TAG, "the server didn't take the push endpoint", it)
                    _status.value = Status.Failed
                }
        }
    }

    internal fun onUnencryptedEndpoint() {
        Log.w(TAG, "the distributor gave an endpoint without Web Push keys; it's too old")
        _status.value = Status.Failed
    }

    internal fun onRegistrationFailed(reason: FailedReason) {
        Log.w(TAG, "push registration failed: $reason")
        _status.value = Status.Failed
    }

    /** The distributor dropped the registration (uninstalled, or the person removed it there). */
    internal fun onUnregistered(context: Context) {
        if (forgotten || !isEnabled(context)) return
        _status.value = Status.Failed
        scope.launch { runCatching { MessengerCore.openExisting(context).clearPushTarget() } }
    }
}
