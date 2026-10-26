package com.qwantarc.messenger.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.qwantarc.messenger.MainActivity
import com.qwantarc.messenger.R
import com.qwantarc.messenger.core.MessageNotification

/** Message notifications: one per chat, decrypted on the phone. */
object Notifier {
    const val EXTRA_CONVERSATION = "com.qwantarc.messenger.CONVERSATION"

    /** With [EXTRA_CONVERSATION]: the thread to open, for a reply in one. */
    const val EXTRA_THREAD = "com.qwantarc.messenger.THREAD"
    const val CHECKING_ID = 8
    private const val CHANNEL_MESSAGES = "messages"
    private const val CHANNEL_CHECKING = "checking"
    private const val TAG = "message"

    /** Whether Android lets the app show notifications (Android 13 asks first). */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** Android's page for this app's notifications, where they can be allowed again. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Shows new messages, adding them to the chat's notification if it's
     * already up. Messages [skip] says are on screen are left out.
     */
    @SuppressLint("MissingPermission") // checked in allowed()
    fun show(context: Context, notes: List<MessageNotification>, skip: (MessageNotification) -> Boolean = { false }) {
        val shown = notes.filterNot(skip)
        if (shown.isEmpty() || !allowed(context)) return
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New messages in your chats"
            },
        )
        val previews = Push.showsPreviews(context)
        val active = runCatching { manager.activeNotifications }.getOrDefault(emptyList())
        for ((conversationId, items) in shown.groupBy { it.conversationId }) {
            val id = conversationId.hashCode()
            val first = items.first()
            val count = items.size
            // What a locked phone shows, if Android is set to hide sensitive content.
            // Tapping opens the latest message: its thread, if it's in one.
            val thread = items.last().threadRoot
            val onLockScreen = baseBuilder(context, conversationId, items.last().sentAtMs, thread)
                .setContentTitle("Messenger")
                .setContentText(if (count > 1) "New messages" else "New message")
                .build()
            val builder = baseBuilder(context, conversationId, items.last().sentAtMs, thread)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(onLockScreen)
            if (previews) {
                val earlier = active.firstOrNull { it.id == id && it.tag == TAG }?.notification
                val style = earlier?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
                    ?: NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
                style.isGroupConversation = first.isGroup
                style.conversationTitle = if (first.isGroup) first.title else null
                for (note in items) {
                    val sender = Person.Builder().setName(note.sender).setKey(note.sender).build()
                    style.addMessage(note.preview, note.sentAtMs, sender)
                }
                builder.setStyle(style)
                    .setContentTitle(first.title)
                    .setContentText(items.last().preview)
            } else {
                // Nothing about who or what; the chat shows it once opened.
                builder.setContentTitle("Messenger")
                    .setContentText(if (count > 1) "New messages" else "New message")
            }
            manager.notify(TAG, id, builder.build())
        }
    }

    /** What a notification says of a message: in a thread, it says so. */
    private val MessageNotification.preview: String
        get() = if (threadRoot != null) "In a thread: $text" else text

    private fun baseBuilder(context: Context, conversationId: String, sentAtMs: Long, thread: String?) =
        NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setWhen(sentAtMs)
            .setShowWhen(true)
            .setContentIntent(openIntent(context, conversationId, thread))

    /** The chat was opened: its notification has done its job. */
    fun cancel(context: Context, conversationId: String) {
        NotificationManagerCompat.from(context).cancel(TAG, conversationId.hashCode())
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Shown while a push is handled, where Android needs one (before Android 12). */
    fun checkingNotification(context: Context): Notification {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannel(CHANNEL_CHECKING, "Checking for messages", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown for a moment on older versions of Android while new messages are fetched"
                setShowBadge(false)
            },
        )
        return NotificationCompat.Builder(context, CHANNEL_CHECKING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Checking for messages")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    private fun openIntent(context: Context, conversationId: String, thread: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_CONVERSATION, conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (thread != null) intent.putExtra(EXTRA_THREAD, thread)
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
