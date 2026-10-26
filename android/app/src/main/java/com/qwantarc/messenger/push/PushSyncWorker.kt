package com.qwantarc.messenger.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.qwantarc.messenger.AppVisibility
import com.qwantarc.messenger.MessengerCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs when a push arrives: fetches, decrypts and shows notifications. A
 * job rather than work in the push callback, so Android lets it finish.
 */
class PushSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val client = runCatching { MessengerCore.openExisting(applicationContext) }.getOrNull()
            ?: return@withContext Result.success()
        if (runCatching { client.account() }.getOrNull() == null) return@withContext Result.success()
        runCatching { client.sync(0u) }
        // On screen, the app shows what's new itself.
        if (!AppVisibility.onScreen.value) {
            val notes = runCatching { client.takeNotifications(50u) }.getOrDefault(emptyList())
            Notifier.show(applicationContext, notes)
        }
        Result.success()
    }

    // Before Android 12, urgent work runs as a foreground service and needs a notification.
    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifier.CHECKING_ID, Notifier.checkingNotification(applicationContext))

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // A push during a sync gets a sync of its own afterwards.
            WorkManager.getInstance(context).enqueueUniqueWork("push-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
