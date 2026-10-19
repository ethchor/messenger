package com.qwantarc.messenger.push

import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Where the UnifiedPush distributor reaches the app. */
class MessengerPushService : PushService() {
    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        // Only encrypted (Web Push) endpoints: the server encrypts every push.
        val keys = endpoint.pubKeySet ?: return Push.onUnencryptedEndpoint()
        Push.onEndpoint(applicationContext, endpoint.url, keys.pubKey, keys.auth)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        // The push only says "something's new": fetch it.
        PushSyncWorker.enqueue(applicationContext)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = Push.onRegistrationFailed(reason)

    override fun onUnregistered(instance: String) = Push.onUnregistered(applicationContext)
}
