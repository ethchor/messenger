package com.qwantarc.messenger

import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * App lock and screen privacy. These are settings of this device, not of the
 * account, so they live in shared preferences: they protect the phone.
 *
 * The activity reports when the app goes away and comes back, and runs the
 * fingerprint, face or screen lock prompt; this decides when to lock.
 */
class AppLock(context: Context) {
    enum class Timeout(val seconds: Long, val label: String) {
        IMMEDIATELY(0, "Immediately"),
        ONE_MINUTE(60, "After 1 minute"),
        FIVE_MINUTES(300, "After 5 minutes"),
        FIFTEEN_MINUTES(900, "After 15 minutes"),
        ONE_HOUR(3600, "After 1 hour"),
    }

    /** What a prompt on screen is for. */
    enum class Purpose { UNLOCK, TURN_ON }

    data class State(
        val enabled: Boolean,
        val timeout: Timeout,
        /** Show a blank card for the app in recent apps. */
        val hideInRecents: Boolean,
        /** Block screenshots and screen recording (FLAG_SECURE). */
        val blockScreenshots: Boolean,
        val locked: Boolean,
        val authenticating: Boolean = false,
        val error: String? = null,
    )

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("security", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<State> = _state.asStateFlow()

    /** When the app went to the background (elapsed realtime, ms). */
    private var leftAt: Long? = null
    var purpose: Purpose? = null
        private set
    /** The prompt opens by itself once per lock, so dismissing it doesn't bring it straight back. */
    private var prompted = false

    private fun load(): State {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        return State(
            enabled = enabled,
            timeout = Timeout.entries.firstOrNull { it.name == prefs.getString(KEY_TIMEOUT, null) } ?: Timeout.IMMEDIATELY,
            hideInRecents = prefs.getBoolean(KEY_HIDE_IN_RECENTS, true),
            blockScreenshots = prefs.getBoolean(KEY_BLOCK_SCREENSHOTS, false),
            // A fresh start of the app counts as coming back.
            locked = enabled,
        )
    }

    // ---- settings ----

    /** Whether this device has a fingerprint, face or screen lock to check. */
    fun isAvailable(): Boolean =
        BiometricManager.from(appContext).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    /** Turning the lock on goes through [Purpose.TURN_ON] first; turning it off doesn't. */
    fun turnOff() {
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        _state.update { it.copy(enabled = false, locked = false, error = null) }
    }

    fun setTimeout(timeout: Timeout) {
        prefs.edit().putString(KEY_TIMEOUT, timeout.name).apply()
        _state.update { it.copy(timeout = timeout) }
    }

    fun setHideInRecents(on: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_IN_RECENTS, on).apply()
        _state.update { it.copy(hideInRecents = on) }
    }

    fun setBlockScreenshots(on: Boolean) {
        prefs.edit().putBoolean(KEY_BLOCK_SCREENSHOTS, on).apply()
        _state.update { it.copy(blockScreenshots = on) }
    }

    /** Back to defaults, for a device whose account was removed. */
    fun reset() {
        prefs.edit().clear().apply()
        leftAt = null
        purpose = null
        prompted = false
        _state.value = load()
    }

    // ---- locking ----

    fun appWentAway() {
        if (_state.value.authenticating || leftAt != null) return
        leftAt = SystemClock.elapsedRealtime()
    }

    fun appCameBack() {
        // The screen lock prompt can cover the app for a moment; that isn't leaving.
        if (_state.value.authenticating) return
        val left = leftAt ?: return
        leftAt = null
        val state = _state.value
        if (state.locked) {
            // Still locked from before: ask once more now that they're back.
            prompted = false
        } else if (state.enabled && SystemClock.elapsedRealtime() - left >= state.timeout.seconds * 1000) {
            prompted = false
            _state.update { it.copy(locked = true, error = null) }
        }
    }

    /** Lets the prompt open by itself again, after the system closed it (say, the app went away). */
    fun allowPromptAgain() {
        prompted = false
    }

    /** Whether to open the prompt by itself now (once each time the app comes back). */
    fun shouldPromptNow(): Boolean {
        val state = _state.value
        if (!state.locked || state.authenticating || prompted) return false
        prompted = true
        return true
    }

    /** False if a prompt is already showing. */
    fun startAuthenticating(purpose: Purpose): Boolean {
        if (_state.value.authenticating) return false
        this.purpose = purpose
        _state.update { it.copy(authenticating = true, error = null) }
        return true
    }

    fun authenticated() {
        val turningOn = purpose == Purpose.TURN_ON
        purpose = null
        leftAt = null
        if (turningOn) prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        _state.update {
            if (turningOn) it.copy(enabled = true, authenticating = false) else it.copy(locked = false, authenticating = false)
        }
    }

    /** The prompt closed without success. [message] is null when the person cancelled it. */
    fun authenticationFailed(message: String?) {
        purpose = null
        _state.update { it.copy(authenticating = false, error = message) }
    }

    /**
     * The device's screen lock was removed after app lock was turned on.
     * Removing it takes the screen lock itself, so the owner did it; the app
     * lock goes off too instead of locking them out.
     */
    fun screenLockRemoved() {
        purpose = null
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        _state.update { it.copy(enabled = false, locked = false, authenticating = false, error = null) }
    }

    /** Whether the device has no screen lock at all (as opposed to a sensor being busy). */
    fun hasNoScreenLock(): Boolean =
        BiometricManager.from(appContext).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

    companion object {
        /**
         * Fingerprint or face, falling back to the PIN, pattern or password.
         * (BIOMETRIC_STRONG with DEVICE_CREDENTIAL isn't supported on Android 9 and 10.)
         */
        const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        private const val KEY_ENABLED = "appLock.enabled"
        private const val KEY_TIMEOUT = "appLock.timeout"
        private const val KEY_HIDE_IN_RECENTS = "privacy.hideInRecents"
        private const val KEY_BLOCK_SCREENSHOTS = "privacy.blockScreenshots"
    }
}
