package com.qwantarc.messenger.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
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
        if (tray != null && tray.items.isNotEmpty()) TrayStrip(tray)
        hint?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            if (tray != null && trayKey != null && !recorder.recording) {
                AttachButton(enabled = enabled && tray.room > 0, trayKey = trayKey, pickers = pickers)
            }
            if (recorder.recording) {
                RecordingBar(recorder, cancelling, Modifier.weight(1f))
            } else {
                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = enabled,
                    placeholder = { Text(placeholder) },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            if (showsMicrophone) {
                val tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                val cancelDistance = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (recorder.recording) (if (cancelling) Color.Gray else Color(0xFFD93025)) else Color.Transparent)
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
                    Icon(MediaIcons.Mic, contentDescription = null, tint = if (recorder.recording) Color.White else tint)
                }
            } else {
                IconButton(enabled = canSend, onClick = onSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** The paper clip: photos and videos, the camera, or any file. */
@Composable
private fun AttachButton(enabled: Boolean, trayKey: String, pickers: Pickers) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val camera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    Box {
        IconButton(enabled = enabled, onClick = { open = true }) {
            Icon(MediaIcons.AttachFile, contentDescription = "Attach")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Photos and videos") }, onClick = {
                open = false
                pickers.pickPhotosAndVideos(trayKey)
            })
            if (camera) {
                DropdownMenuItem(text = { Text("Take a photo") }, onClick = {
                    open = false
                    pickers.takePhoto(trayKey)
                })
                DropdownMenuItem(text = { Text("Record a video") }, onClick = {
                    open = false
                    pickers.recordVideo(trayKey)
                })
            }
            DropdownMenuItem(text = { Text("File") }, onClick = {
                open = false
                pickers.pickFiles(trayKey)
            })
        }
    }
}

/** While a voice message records: how long so far, how loud, and how to cancel it. */
@Composable
private fun RecordingBar(recorder: VoiceRecorder, cancelling: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val levels = recorder.levels.takeLast(40)
    Row(
        modifier
            .heightIn(min = 56.dp)
            .background(incomingBubble(dark), RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp)
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

/** What's going with the next message, each with a button to take it out. */
@Composable
private fun TrayStrip(tray: AttachmentTray) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tray.items.forEach { item ->
            TrayTile(item, onRemove = { tray.remove(item.id) })
        }
    }
}

@Composable
private fun TrayTile(item: AttachmentTray.Item, onRemove: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val state = item.state
    val width = if (state is AttachmentTray.State.Failed) 150.dp else 64.dp
    Box(
        Modifier
            .size(width = width, height = 64.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(incomingBubble(dark)),
    ) {
        when (state) {
            AttachmentTray.State.Preparing ->
                CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.Center), strokeWidth = 2.dp)
            is AttachmentTray.State.Ready -> {
                val preview = state.file.preview
                if (preview != null) {
                    Image(
                        remember(preview) { preview.asImageBitmap() },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize(),
                    )
                } else {
                    Column(Modifier.align(Alignment.Center).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(MediaIcons.File, contentDescription = null, modifier = Modifier.size(20.dp))
                        Text(
                            state.file.attachment.fileName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            is AttachmentTray.State.Failed ->
                Text(
                    state.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(6.dp).padding(end = 18.dp),
                )
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(28.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Remove",
                tint = Color.White,
                modifier = Modifier.size(18.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(2.dp),
            )
        }
    }
}
