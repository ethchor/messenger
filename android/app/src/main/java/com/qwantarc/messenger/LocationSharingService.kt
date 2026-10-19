package com.qwantarc.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import com.qwantarc.messenger.core.LocationFix
import com.qwantarc.messenger.core.LocationShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Sends this phone's position while we share our location in any chat, also
 * with the app in the background. Android shows a notification meanwhile:
 * it says who can see the location and has a Stop button.
 */
class LocationSharingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var locationManager: LocationManager
    private val listener = LocationListenerCompat { location -> onLocation(location) }
    private var listening = false
    private var watchJob: Job? = null
    private var lastSent: Location? = null
    private var lastSentAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val channel = NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while you share your location"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires the notification right away, even when stopping.
        if (!startInForeground(notification(emptyList())) || !hasLocationPermission(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_ALL) {
            scope.launch { stopAll() }
            return START_NOT_STICKY
        }
        startListening()
        if (watchJob?.isActive != true) {
            watchJob = scope.launch { watchShares() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (listening) LocationManagerCompat.removeUpdates(locationManager, listener)
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification): Boolean = try {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        true
    } catch (e: Exception) {
        // Not allowed from the background, or location access was revoked.
        false
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand
    private fun startListening() {
        if (listening) return
        // No minimum distance: a phone standing still keeps getting fresh
        // positions, so the heartbeat below shows people it's still live.
        val request = LocationRequestCompat.Builder(10_000L)
            .setMinUpdateDistanceMeters(0f)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && locationManager.hasProvider(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            } else {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            }
        }
        val executor = ContextCompat.getMainExecutor(this)
        for (provider in providers) {
            LocationManagerCompat.requestLocationUpdates(locationManager, provider, request, executor, listener)
        }
        listening = providers.isNotEmpty()
        // Start with the last known position, so people see us at once.
        providers.firstNotNullOfOrNull { locationManager.getLastKnownLocation(it) }?.let { onLocation(it) }
    }

    private fun onLocation(location: Location) {
        val now = SystemClock.elapsedRealtime()
        lastSent?.let { previous ->
            val elapsed = now - lastSentAt
            val moved = location.distanceTo(previous)
            val better = location.hasAccuracy() && previous.hasAccuracy() && location.accuracy < previous.accuracy / 2
            // Moving: every few seconds. Standing still: a heartbeat every half minute.
            val due = elapsed >= 30_000 || (elapsed >= 5_000 && (moved >= 15f || better))
            if (!due) return
        }
        lastSent = location
        lastSentAt = now
        val fix = LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = if (location.hasAccuracy()) location.accuracy else 50f,
            measuredAtMs = location.time,
            speedMps = if (location.hasSpeed()) location.speed else null,
            courseDeg = if (location.hasBearing()) location.bearing.takeIf { it < 360f } else null,
            batteryPercent = batteryPercent(),
        )
        scope.launch(Dispatchers.IO) {
            runCatching { MessengerCore.openExisting(this@LocationSharingService).updateLocation(fix) }
        }
    }

    /** Keeps the notification current and stops once nothing is shared. */
    private suspend fun watchShares() {
        while (scope.isActive) {
            val shares = withContext(Dispatchers.IO) {
                runCatching { MessengerCore.openExisting(this@LocationSharingService).locationShares() }.getOrNull()
            }
            if (shares != null) {
                if (shares.isEmpty()) {
                    stopSelf()
                    return
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(shares))
            }
            delay(30_000)
        }
    }

    private suspend fun stopAll() {
        withContext(Dispatchers.IO) {
            runCatching {
                val client = MessengerCore.openExisting(this@LocationSharingService)
                client.locationShares().forEach { client.stopSharingLocation(it.conversationId) }
            }
        }
        stopSelf()
    }

    private fun notification(shares: List<LocationShare>): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LocationSharingService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val who = shares.joinToString(", ") { it.title }
        val until = shares.mapNotNull { it.untilMs }.takeIf { it.size == shares.size && it.isNotEmpty() }?.max()
        val text = when {
            shares.isEmpty() -> "Starting…"
            until != null -> "With $who until ${DateFormat.getTimeFormat(this).format(Date(until))}"
            else -> "With $who"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_location_sharing)
            .setContentTitle("Sharing your location")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun batteryPercent(): UByte? {
        val level = getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level?.takeIf { it in 0..100 }?.toUByte()
    }

    companion object {
        private const val CHANNEL = "location-sharing"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP_ALL = "com.qwantarc.messenger.STOP_SHARING_LOCATION"

        fun hasLocationPermission(context: Context): Boolean =
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        /** Starts (or refreshes) the service. Only works while the app is on screen. */
        fun start(context: Context) {
            if (!hasLocationPermission(context)) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, LocationSharingService::class.java))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationSharingService::class.java))
        }
    }
}
