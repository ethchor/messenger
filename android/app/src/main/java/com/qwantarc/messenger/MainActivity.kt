package com.qwantarc.messenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.qwantarc.messenger.push.Notifier
import com.qwantarc.messenger.push.Push
import com.qwantarc.messenger.ui.LockScreen
import com.qwantarc.messenger.ui.MessengerTheme
import com.qwantarc.messenger.ui.MessengerUi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A FragmentActivity because the fingerprint and screen lock prompt needs one. */
class MainActivity : FragmentActivity() {
    private val model: AppViewModel by viewModels()
    private val lock: AppLock get() = model.lock
    private lateinit var prompt: BiometricPrompt

    /** Whatever the answer, Settings shows where notifications stand. */
    private val askToNotify = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Created here so a result that arrives after rotation reaches the new activity.
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), PromptCallback())
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                lock.state.collect { applyScreenPrivacy(it) }
            }
        }
        // Each time the app comes back with an account: check in with the
        // push distributor, as UnifiedPush asks apps to.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.map { it.phase == Phase.Ready }.distinctUntilChanged().collect { ready ->
                    if (ready) {
                        Push.start(this@MainActivity)
                        askToNotifyOnce()
                    }
                }
            }
        }
        // Not when the activity is recreated: the chat was opened the first time.
        if (savedInstanceState == null) openFromNotification(intent)
        setContent {
            MessengerTheme(model.appearance) {
                val lockState by lock.state.collectAsStateWithLifecycle()
                if (lockState.locked) {
                    LockScreen(lockState) { authenticate(AppLock.Purpose.UNLOCK) }
                } else {
                    MessengerUi(model, lockState) { authenticate(AppLock.Purpose.TURN_ON) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lock.appCameBack()
        model.setForeground(true)
    }

    override fun onResume() {
        super.onResume()
        if (lock.shouldPromptNow()) authenticate(AppLock.Purpose.UNLOCK)
        model.syncNow()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromNotification(intent)
    }

    private fun openFromNotification(intent: Intent?) {
        val id = intent?.getStringExtra(Notifier.EXTRA_CONVERSATION) ?: return
        val thread = intent.getStringExtra(Notifier.EXTRA_THREAD)
        intent.removeExtra(Notifier.EXTRA_CONVERSATION)
        intent.removeExtra(Notifier.EXTRA_THREAD)
        model.openFromNotification(id, thread)
    }

    /**
     * Android 13 asks before an app may show notifications. Asked once, after
     * sign-up; after that, Settings links to Android's page for it.
     */
    private fun askToNotifyOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !Push.isEnabled(this)) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences("messenger", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ASKED_TO_NOTIFY, false)) return
        prefs.edit().putBoolean(KEY_ASKED_TO_NOTIFY, true).apply()
        askToNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop() {
        super.onStop()
        model.setForeground(false)
        // Rotating the screen recreates the activity; that isn't leaving the app.
        if (!isChangingConfigurations) lock.appWentAway()
    }

    private fun authenticate(purpose: AppLock.Purpose) {
        if (purpose == AppLock.Purpose.UNLOCK && lock.hasNoScreenLock()) {
            lock.screenLockRemoved()
            return
        }
        if (!lock.isAvailable()) {
            lock.authenticationFailed("Set a screen lock in Android settings to use app lock.")
            return
        }
        if (!lock.startAuthenticating(purpose)) return
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (purpose == AppLock.Purpose.TURN_ON) "Turn on app lock" else "Unlock Messenger")
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(AppLock.AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    /** FLAG_SECURE keeps the app out of screenshots, screen recordings and the recent apps preview. */
    private fun applyScreenPrivacy(state: AppLock.State) {
        val hideOnly = state.hideInRecents && !state.blockScreenshots
        // Android 13 can hide the recent apps preview without blocking screenshots.
        val secure = state.blockScreenshots || (hideOnly && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        if (secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!state.hideInRecents)
        }
    }

    private companion object {
        const val KEY_ASKED_TO_NOTIFY = "askedToNotify"
    }

    private inner class PromptCallback : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            lock.authenticated()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            when (errorCode) {
                BiometricPrompt.ERROR_USER_CANCELED,
                BiometricPrompt.ERROR_NEGATIVE_BUTTON -> lock.authenticationFailed(null)
                // The system closed it, usually because the app went to the background.
                BiometricPrompt.ERROR_CANCELED -> {
                    lock.authenticationFailed(null)
                    lock.allowPromptAgain()
                }
                BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                    if (lock.purpose == AppLock.Purpose.UNLOCK) {
                        lock.screenLockRemoved()
                    } else {
                        lock.authenticationFailed("Set a screen lock in Android settings to use app lock.")
                    }
                else -> lock.authenticationFailed(errString.toString())
            }
        }
    }
}
