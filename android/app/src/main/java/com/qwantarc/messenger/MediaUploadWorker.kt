package com.qwantarc.messenger

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.qwantarc.messenger.core.MessengerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Uploads files waiting to go and sends their messages once the app has left
 * the screen, so a video on its way up still goes. Tries again later while
 * some are left: offline, or still going up from the app itself.
 */
class MediaUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val client = runCatching { MessengerCore.openExisting(applicationContext) }.getOrNull()
            ?: return@withContext Result.success()
        if (runCatching { client.account() }.getOrNull() == null) return@withContext Result.success()
        try {
            client.sendPending()
        } catch (e: MessengerException.Network) {
            return@withContext Result.retry()
        } catch (e: Exception) {
            return@withContext Result.success()
        }
        val left = runCatching { client.sync(0u).unsentMedia }.getOrNull()
        if (left == null || left > 0u) Result.retry() else Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<MediaUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("send-files", ExistingWorkPolicy.KEEP, request)
        }
    }
}
