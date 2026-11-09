package com.qwantarc.messenger.ui

import com.qwantarc.messenger.media.MediaLimits
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.draw.scale
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qwantarc.messenger.media.AttachmentTray
import com.qwantarc.messenger.media.MediaException
import com.qwantarc.messenger.media.VoiceNote
import com.qwantarc.messenger.media.VoiceRecorder
import kotlinx.coroutines.delay

/**
 * Pickers and the camera, opened by the activity so their answers survive
 * the app lock and rotation: each goes to the tray it was opened for.
 */
interface Pickers {
    fun pickPhotosAndVideos(tray: String)
    fun pickFiles(tray: String)
    fun takePhoto(tray: String)
    fun recordVideo(tray: String)
    fun askForMicrophone()
}

val LocalPickers = staticCompositionLocalOf<Pickers> { error("MainActivity provides the pickers") }

/**
 * The box under a chat or a thread: text, the files going with it, and a
 * button to hold for a voice message. Without a [tray], it's only text.
 */
@Composable
internal fun MessageComposer(
    draft: String,
    onDraftChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    onSend: () -> Unit,
    tray: AttachmentTray? = null,
    trayKey: String? = null,
    onVoiceNote: ((VoiceNote) -> Unit)? = null,
    /** Share a live location from the attach sheet. Null in a thread. */
    onLocation: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val pickers = LocalPickers.current
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var cancelling by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val hasText = draft.isNotBlank()
    val canSend = enabled && (hasText || tray?.hasFiles == true) && tray?.isPreparing != true
    val showsMicrophone = onVoiceNote != null && !hasText && (tray?.items?.isEmpty() ?: true)
    val voiceNote by rememberUpdatedState(onVoiceNote)

    DisposableEffect(recorder) {
        onDispose { if (recorder.recording) recorder.cancel() }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(3_000)
            hint = null
        }
    }

    fun startRecording() {
        cancelling = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Once allowed, they hold the button again.
            pickers.askForMicrophone()
            return
        }
        try {
            recorder.start()
        } catch (e: MediaException) {
            hint = e.message
        }
    }

    fun stopRecording(cancel: Boolean) {
        cancelling = false
        if (!recorder.recording) return
        if (cancel) {
            recorder.cancel()
            return
        }
        val note = recorder.finish()
        if (note != null) voiceNote?.invoke(note) else hint = "Hold to record, and let go to send."
    }

    Column(Modifier.fillMaxWidth()) {
        if (tray != null && tray.items.isNotEmpty()) TrayStrip(tray) { trayKey?.let { pickers.pickPhotosAndVideos(it) } }
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (tray != null && trayKey != null && !recorder.recording) {
                AttachButton(enabled = enabled && tray.room > 0, trayKey = trayKey, pickers = pickers, onLocation = onLocation)
            }
            if (recorder.recording) {
                RecordingBar(recorder, cancelling, Modifier.weight(1f))
            } else {
                // A cream field with dark text, in both modes.
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = enabled,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = Palette.Ink),
                    cursorBrush = SolidColor(Color(0xFF7A5600)),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp)
                        .background(if (isDark()) Palette.Cream else Color.White, RoundedCornerShape(23.dp))
                        // On the light chat a hairline keeps the white field visible.
                        .border(1.dp, if (isDark()) Color.Transparent else Color(0xFFE2DED2), RoundedCornerShape(23.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (draft.isEmpty()) Text(if (tray?.items?.isNotEmpty() == true) "Add a caption" else placeholder, color = Color(0xFF6B6960), style = MaterialTheme.typography.bodyLarge)
                            field()
                        }
                    },
                )
            }
            if (showsMicrophone) {
                val cancelDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
                val grow by animateFloatAsState(if (recorder.recording) 1.35f else 1f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "mic")
                Box(
                    Modifier
                        .scale(grow)
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                !enabled -> Palette.Green.copy(alpha = 0.45f)
                                recorder.recording && cancelling -> Color(0xFFFF6B5E)
                                recorder.recording -> Palette.Marigold
                                else -> Palette.Green
                            },
                        )
                        .pointerInput(enabled) {
                            if (!enabled) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                startRecording()
                                var cancel = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    cancel = change.position.x - down.position.x < -cancelDistance
                                    cancelling = cancel
                                    change.consume()
                                }
                                stopRecording(cancel)
                            }
                        }
                        .semantics {
                            role = Role.Button
                            contentDescription = if (recorder.recording) "Stop and send the voice message" else "Record a voice message"
                            // Holding isn't possible with TalkBack: a tap starts, the next sends.
                            onClick {
                                if (recorder.recording) stopRecording(cancel = false) else startRecording()
                                true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyphs.Mic, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(20.dp))
                }
            } else {
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .pressScale(source)
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Palette.Marigold.copy(alpha = if (canSend) 1f else 0.45f))
                        .clickable(enabled = canSend, interactionSource = source, indication = null, role = Role.Button, onClick = onSend)
                        .semantics { contentDescription = "Send" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyphs.ArrowUp, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(21.dp))
                }
            }
        }
    }
}

/** The round +: opens the attach sheet (photos, camera, file, location). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachButton(enabled: Boolean, trayKey: String, pickers: Pickers, onLocation: (() -> Unit)?) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var cameraChoice by remember { mutableStateOf(false) }
    val camera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .border(1.5.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), CircleShape)
            .clickable(enabled = enabled, role = Role.Button) { open = true }
            .semantics { contentDescription = "Attach" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Glyphs.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.size(20.dp))
    }
    if (open) {
        val t = tones()
        ModalBottomSheet(
            onDismissRequest = { open = false; cameraChoice = false },
            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
            containerColor = t.surface,
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 34.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cameraChoice) {
                        AttachTile("Photo", Glyphs.Camera, 0xFFFFE58A, Modifier.weight(1f)) { open = false; cameraChoice = false; pickers.takePhoto(trayKey) }
                        AttachTile("Video", Glyphs.Video, 0xFFFFD9E6, Modifier.weight(1f)) { open = false; cameraChoice = false; pickers.recordVideo(trayKey) }
                    } else {
                        AttachTile("Photos", Glyphs.Photos, 0xFFFFD9E6, Modifier.weight(1f)) { open = false; pickers.pickPhotosAndVideos(trayKey) }
                        if (camera) AttachTile("Camera", Glyphs.Camera, 0xFFFFE58A, Modifier.weight(1f)) { cameraChoice = true }
                        AttachTile("File", Glyphs.Doc, 0xFFD9D2FF, Modifier.weight(1f)) { open = false; pickers.pickFiles(trayKey) }
                        if (onLocation != null) AttachTile("Location", Glyphs.Location, 0xFFBDEBCB, Modifier.weight(1f)) { open = false; onLocation() }
                    }
                }
                Text(
                    "Up to 10 at once. Photos and videos lose where, when and on what they were taken.",
                    fontSize = 12.5.sp, color = t.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AttachTile(label: String, icon: ImageVector, color: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(source).clickable(interactionSource = source, indication = null, onClick = onClick).semantics { role = Role.Button },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(20.dp)).background(Color(color)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(24.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = tones().text)
    }
}

/** While a voice message records: how long so far, how loud, and how to cancel it. */
@Composable
private fun RecordingBar(recorder: VoiceRecorder, cancelling: Boolean, modifier: Modifier = Modifier) {
    val dark = isDark()
    val levels = recorder.levels.takeLast(40)
    Row(
        modifier
            .heightIn(min = 46.dp)
            .background(incomingBubble(dark), RoundedCornerShape(23.dp))
            .padding(horizontal = 14.dp)
            .semantics { contentDescription = "Recording, ${durationText(recorder.elapsedMs)}" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(Color(0xFFD93025).copy(alpha = if ((recorder.elapsedMs / 500) % 2 == 0L) 1f else 0.3f), CircleShape),
        )
        Text(durationText(recorder.elapsedMs), style = MaterialTheme.typography.bodyMedium)
        Waveform(
            ByteArray(levels.size) { (levels[it] * 255).toInt().toByte() },
            progress = 1f,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).height(20.dp),
        )
        Text(
            if (cancelling) "Let go to cancel" else "‹ Slide to cancel",
            style = MaterialTheme.typography.labelMedium,
            color = if (cancelling) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * What's going with the next message, as designed: a card over the composer
 * with a count, Clear all, a tile for each file with ×, a dashed + for more,
 * and a line on what's stripped. It rises 30 dp from 0.96.
 */
@Composable
private fun TrayStrip(tray: AttachmentTray, onAdd: () -> Unit) {
    val sub = Color(0xFFA3A1A9)
    val rise = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        rise.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.CubicBezierEasing(0.25f, 1.25f, 0.4f, 1f)))
    }
    Column(
        Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp).fillMaxWidth()
            .graphicsLayer {
                alpha = rise.value.coerceIn(0f, 1f)
                val s = 0.96f + 0.04f * rise.value
                scaleX = s; scaleY = s
                translationY = (1f - rise.value) * 30.dp.toPx()
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
            }
            .clip(RoundedCornerShape(24.dp)).background(Color(0xFF2A2A2E)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${tray.items.size} of ${MediaLimits.FILES}", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF3F2EE), modifier = Modifier.weight(1f))
            Text(
                "Clear all", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = sub,
                modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { tray.clear() }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tray.items.forEach { item -> TrayTile(item, onRemove = { tray.remove(item.id) }) }
            if (tray.room > 0) {
                Box(
                    Modifier.size(76.dp, 92.dp).clip(RoundedCornerShape(16.dp))
                        .drawBehind {
                            drawRoundRect(
                                sub, cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                            )
                        }
                        .clickable(role = Role.Button, onClick = onAdd).semantics { contentDescription = "Add more" },
                    contentAlignment = Alignment.Center,
                ) { Text("+", fontSize = 28.sp, color = sub) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Glyphs.Lock, contentDescription = null, tint = sub, modifier = Modifier.size(12.dp))
            Text("Location, date and camera details are stripped before sending", fontSize = 12.sp, color = sub)
        }
    }
}

@Composable
private fun TrayTile(item: AttachmentTray.Item, onRemove: () -> Unit) {
    val state = item.state
    Box(Modifier.size(76.dp, 92.dp).clip(RoundedCornerShape(16.dp)).background(incomingBubble(true))) {
        when (state) {
            AttachmentTray.State.Preparing ->
                CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.Center), color = Color.White, strokeWidth = 2.dp)
            is AttachmentTray.State.Ready -> {
                val preview = state.file.preview
                if (preview != null) {
                    Image(remember(preview) { preview.asImageBitmap() }, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    if (state.file.attachment.kind == com.qwantarc.messenger.core.AttachmentKind.VIDEO) {
                        Text(
                            "▶ " + durationText(state.file.attachment.durationMs.toLong()), fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                } else {
                    val ext = state.file.attachment.fileName.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }
                    Column(Modifier.matchParentSize().background(Palette.Cream), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Glyphs.Doc, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(22.dp))
                        Text(ext, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    }
                }
            }
            is AttachmentTray.State.Failed ->
                Text(state.message, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF8A7A), modifier = Modifier.padding(6.dp))
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(Color(0xFF141414).copy(alpha = 0.75f))
                .clickable(role = Role.Button, onClick = onRemove).semantics { contentDescription = "Remove" },
            contentAlignment = Alignment.Center,
        ) { Text("×", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White) }
    }
}
