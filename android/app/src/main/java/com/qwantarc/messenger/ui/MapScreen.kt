package com.qwantarc.messenger.ui

import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CenterAlignedTopAppBar
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

    // Full screen: the map runs up under the status bar, with the title
    // and its reassurance over it.
    Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { _ ->
        Box(Modifier.fillMaxSize()) {
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
            // As designed: with nobody sharing, the card floats on the map,
            // above the tabs, with no sheet around it.
            if (state.sharedLocations.isEmpty() && state.locationShares.isEmpty()) {
                EmptyMapCard(state, model, Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 96.dp).navigationBarsPadding())
            } else PeoplePanel(
                model = model,
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
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            MapTitle(Modifier.align(Alignment.TopStart))
        }
    }
}

/** "Map", and under it what it promises: one line each, at any text size. */
@Composable
private fun MapTitle(modifier: Modifier = Modifier) {
    val t = tones()
    Column(
        modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(
                // Light enough that the map shows through, under the status
                // bar too; just enough to keep the title readable.
                0f to t.home.copy(alpha = 0.7f), 0.6f to t.home.copy(alpha = 0.45f), 1f to t.home.copy(alpha = 0f),
            ))
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 18.dp),
    ) {
        Text("Map", style = displayStyle(30.sp), color = t.text, maxLines = 1, softWrap = false, modifier = Modifier.semantics { heading() })
        Text(
            "Only the people you share with see where you are",
            fontSize = 13.sp, color = t.secondary,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun focus(map: MapLibreMap, person: SharedLocation) {
    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(person.latitude, person.longitude), 15.0))
}

@Composable
private fun PeoplePanel(
    state: UiState,
    model: AppViewModel,
    me: Location?,
    now: Long,
    onFocus: (SharedLocation) -> Unit,
    onDirections: (SharedLocation) -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        color = tones().surface,
        shadowElevation = 6.dp,
    ) {
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(38.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(tones().line))
            }
            if (state.sharedLocations.isNotEmpty()) {
                Text("Sharing with you", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tones().secondary, modifier = Modifier.padding(horizontal = 4.dp))
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
                        Icon(Glyphs.Directions, contentDescription = "Directions to ${person.username}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            // Each of ours on a light card of its own (no rule above).
            state.locationShares.forEach { share ->
                val t = tones()
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(t.home).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Palette.Marigold), contentAlignment = Alignment.Center) {
                        Icon(Glyphs.Location, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("You're sharing with ${share.title}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 2)
                        Text(sharingUntilText(share.untilMs), fontSize = 13.sp, color = t.secondary)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Stop",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = t.danger,
                        modifier = Modifier.clip(CircleShape).border(1.5.dp, t.line, CircleShape)
                            .clickable { onStop(share.conversationId) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
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
        parts += distanceAway(metres.toDouble())
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

/** "350 m away", "2.4 km away": straight-line, the same on every platform. */
fun distanceAway(metres: Double): String =
    if (metres < 1000) "${(metres / 10).roundToInt() * 10} m away" else String.format(java.util.Locale.US, "%.1f km away", metres / 1000)

/** Where this phone last was, if location is allowed. Nothing is asked for. */
@android.annotation.SuppressLint("MissingPermission")
fun lastKnownLocation(context: android.content.Context): Location? {
    val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
    if (fine != android.content.pm.PackageManager.PERMISSION_GRANTED && coarse != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    return manager.getProviders(true)
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
}

/** "Live · 2.4 km away", or just that they're sharing when we don't know where we are. */
fun liveAwayText(person: SharedLocation?, me: Location?): String {
    if (person == null || me == null) return "Sharing live location"
    val result = FloatArray(1)
    Location.distanceBetween(me.latitude, me.longitude, person.latitude, person.longitude, result)
    return "Live · " + distanceAway(result[0].toDouble())
}
