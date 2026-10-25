package com.qwantarc.messenger.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.LocationSharingService
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.SharedLocation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

/**
 * OpenFreeMap: free OpenStreetMap vector tiles, no account or key. The tile
 * server sees which areas are viewed (not whose location they are);
 * self-hosted tiles can replace it.
 */
private const val MAP_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/** Older than this, a position is probably not where they are now. */
private const val STALE_MS = 10 * 60_000L

/** Everyone sharing their location with you, on one map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(state: UiState, model: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    // Bumped as the camera moves, so the pins follow the map.
    var cameraTick by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var me by remember { mutableStateOf<Location?>(null) }
    // Once the map is moved by hand, it stays where it was put.
    var movedByHand by remember { mutableStateOf(false) }
    val people = state.sharedLocations

    BackHandler(onBack = model::closeMap)
    LaunchedEffect(Unit) {
        while (true) {
            model.refreshLocations()
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    MyLocation { me = it }

    // Frame everyone (and us) when the map opens and when someone starts or
    // stops sharing; with nobody sharing, show where we are.
    val names = people.map { it.username }
    val haveMe = me != null
    LaunchedEffect(map, names, haveMe) {
        val m = map ?: return@LaunchedEffect
        if (movedByHand) return@LaunchedEffect
        val mine = me?.let { LatLng(it.latitude, it.longitude) }
        val points = people.map { LatLng(it.latitude, it.longitude) } + listOfNotNull(mine)
        when {
            points.isEmpty() -> return@LaunchedEffect
            // Bounds need two different points.
            points.distinct().size == 1 -> m.animateCamera(CameraUpdateFactory.newLatLngZoom(points[0], 14.0))
            else -> m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), 160))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Map") },
                navigationIcon = {
                    IconButton(onClick = model::closeMap) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            MapLibreView(
                modifier = Modifier.fillMaxSize(),
                onMap = { m ->
                    map = m
                    m.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) movedByHand = true
                    }
                    m.addOnCameraMoveListener { cameraTick++ }
                    m.addOnCameraIdleListener { cameraTick++ }
                },
            )
            // Pins drawn over the map at the projected positions.
            val m = map
            // Recomposed whenever the camera moves.
            if (m != null) key(cameraTick) {
                val density = LocalDensity.current
                me?.let { location ->
                    val point = m.projection.toScreenLocation(LatLng(location.latitude, location.longitude))
                    Box(
                        Modifier
                            .offset { IntOffset(point.x.roundToInt() - 9.dp.roundToPx(), point.y.roundToInt() - 9.dp.roundToPx()) }
                            .size(18.dp)
                            .background(Color.White, CircleShape)
                            .padding(3.dp)
                            .background(Color(0xFF0A84FF), CircleShape),
                    )
                }
                people.forEach { person ->
                    val point = m.projection.toScreenLocation(LatLng(person.latitude, person.longitude))
                    val metresPerPixel = m.projection.getMetersPerPixelAtLatitude(person.latitude)
                    val radiusDp = with(density) { (person.accuracyM / metresPerPixel).toFloat().coerceAtMost(2000f).toDp() }
                    if (radiusDp > 22.dp) {
                        Box(
                            Modifier
                                .offset { IntOffset(point.x.roundToInt() - radiusDp.roundToPx(), point.y.roundToInt() - radiusDp.roundToPx()) }
                                .size(radiusDp * 2)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
                        )
                    }
                    val stale = now - person.measuredAtMs > STALE_MS
                    Box(
                        Modifier
                            .offset { IntOffset(point.x.roundToInt() - 22.dp.roundToPx(), point.y.roundToInt() - 22.dp.roundToPx()) }
                            .size(44.dp)
                            .alpha(if (stale) 0.6f else 1f)
                            .background(Color.White, CircleShape)
                            .padding(3.dp)
                            .clickable { focus(m, person) },
                    ) {
                        Avatar(person.username, size = 38)
                    }
                }
            }
            PeoplePanel(
                state = state,
                me = me,
                now = now,
                onFocus = { person -> map?.let { focus(it, person) } },
                onDirections = { person ->
                    val label = Uri.encode(person.username)
                    val uri = Uri.parse("geo:${person.latitude},${person.longitude}?q=${person.latitude},${person.longitude}($label)")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                },
                onStop = { id -> scope.launch { runCatching { model.stopSharingLocation(id) } } },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
            )
        }
    }
}

private fun focus(map: MapLibreMap, person: SharedLocation) {
    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(person.latitude, person.longitude), 15.0))
}

@Composable
private fun PeoplePanel(
    state: UiState,
    me: Location?,
    now: Long,
    onFocus: (SharedLocation) -> Unit,
    onDirections: (SharedLocation) -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.sharedLocations.isEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LineArt(Artwork.IllustrationMap, Modifier.size(width = 64.dp, height = 48.dp), ink = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Nobody is sharing their location with you", style = MaterialTheme.typography.titleSmall, fontFamily = VoiceFamily)
                        Text(
                            "To share yours, open a chat's info and tap Share my location.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            state.sharedLocations.forEach { person ->
                Row(
                    Modifier.fillMaxWidth().clickable { onFocus(person) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(person.username, size = 36)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(person.username, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        val stale = now - person.measuredAtMs > STALE_MS
                        Text(
                            detail(person, me, now),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (stale) Color(0xFFE5940B) else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    person.batteryPercent?.let { battery ->
                        Text(
                            "$battery%",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (battery.toInt() <= 15) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { onDirections(person) }) {
                        Icon(Icons.Filled.Place, contentDescription = "Directions to ${person.username}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (state.locationShares.isNotEmpty()) {
                HorizontalDivider()
                state.locationShares.forEach { share ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("You're sharing with ${share.title}", style = MaterialTheme.typography.bodyMedium)
                            Text(sharingUntilText(share.untilMs), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { onStop(share.conversationId) }) { Text("Stop") }
                    }
                }
            }
        }
    }
}

private fun detail(person: SharedLocation, me: Location?, now: Long): String {
    val measured = person.measuredAtMs.coerceAtMost(now)
    val parts = mutableListOf(
        if (now - measured < 60_000L) {
            "Updated just now"
        } else {
            "Updated " + DateUtils.getRelativeTimeSpanString(measured, now, DateUtils.MINUTE_IN_MILLIS)
        },
    )
    if (me != null) {
        val result = FloatArray(1)
        Location.distanceBetween(me.latitude, me.longitude, person.latitude, person.longitude, result)
        val metres = result[0]
        parts += if (metres < 1000) "${(metres / 10).roundToInt() * 10} m away" else "%.1f km away".format(metres / 1000)
    }
    person.speedMps?.takeIf { it > 1.5f }?.let { parts += "${(it * 3.6f).roundToInt()} km/h" }
    return parts.joinToString(" · ")
}

/** "Until 17:30", "Until you stop". */
fun sharingUntilText(untilMs: Long?): String {
    if (untilMs == null) return "Until you stop"
    val format = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    return "Until " + format.format(java.util.Date(untilMs))
}

/** A MapLibre map, tied to the screen's lifecycle. */
@Composable
private fun MapLibreView(modifier: Modifier, onMap: (MapLibreMap) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        var started = false
        var resumed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (!started) { mapView.onStart(); started = true }
                Lifecycle.Event.ON_RESUME -> if (!resumed) { mapView.onResume(); resumed = true }
                Lifecycle.Event.ON_PAUSE -> if (resumed) { mapView.onPause(); resumed = false }
                Lifecycle.Event.ON_STOP -> if (started) { mapView.onStop(); started = false }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = true
            map.uiSettings.isAttributionEnabled = true
            map.setStyle(Style.Builder().fromUri(MAP_STYLE)) { onMap(map) }
        }
        onDispose {
            lifecycle.removeObserver(observer)
            if (resumed) mapView.onPause()
            if (started) mapView.onStop()
            mapView.onDestroy()
        }
    }
    AndroidView(factory = { mapView }, modifier = modifier)
}

/** This phone's position while the map is open, if location access is granted. */
@SuppressLint("MissingPermission")
@Composable
private fun MyLocation(onLocation: (Location) -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        if (!LocationSharingService.hasLocationPermission(context)) return@DisposableEffect onDispose {}
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        val listener = LocationListenerCompat { onLocation(it) }
        manager.getLastKnownLocation(provider)?.let(onLocation)
        LocationManagerCompat.requestLocationUpdates(
            manager,
            provider,
            LocationRequestCompat.Builder(5_000L).setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY).build(),
            ContextCompat.getMainExecutor(context),
            listener,
        )
        onDispose { LocationManagerCompat.removeUpdates(manager, listener) }
    }
}
