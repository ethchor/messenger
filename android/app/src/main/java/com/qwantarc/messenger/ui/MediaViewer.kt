package com.qwantarc.messenger.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Build
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.qwantarc.messenger.AttachmentRef
import com.qwantarc.messenger.UiState
import com.qwantarc.messenger.core.AttachmentKind
import com.qwantarc.messenger.core.AttachmentState
import com.qwantarc.messenger.core.AttachmentView
import com.qwantarc.messenger.media.Pictures
import com.qwantarc.messenger.media.VoicePlayer
import java.io.File

/** A message's photos and videos, opened at one of them. */
data class MediaSelection(val conversationId: String, val messageId: String, val position: UInt)

/**
 * Photos and videos full screen: swipe between the ones in a message, pinch
 * or double-tap to zoom photos, play videos, and share or open them in
 * another app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaViewer(selection: MediaSelection, state: UiState, onDownload: (AttachmentRef) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // The message as it is now, so files fetched meanwhile show.
    val items = state.message(selection.messageId)?.attachments?.filter { it.isVisual }.orEmpty()
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val start = items.indexOfFirst { it.position == selection.position }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { items.size }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), key = { items[it].position.toInt() }) { page ->
                val item = items[page]
                val ref = AttachmentRef(selection.conversationId, selection.messageId, item.position)
                MediaPage(item, ref, current = pager.currentPage == page, fetching = ref in state.fetching, onDownload = { onDownload(ref) })
            }
            val current = items.getOrNull(pager.currentPage)
            val path = current?.localPath
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                if (items.size > 1) {
                    Text("${pager.currentPage + 1} of ${items.size}", color = Color.White, style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.weight(1f))
                if (current != null && path != null) {
                    TextButton(onClick = { openFile(context, current, path) }) { Text("Open", color = Color.White) }
                    IconButton(onClick = { shareFile(context, current, path) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", tint = Color.White)
                    }
                }
            }
        }
    }
}

/** One photo or video. A file that isn't here yet is fetched as soon as it's on screen. */
@Composable
private fun MediaPage(item: AttachmentView, ref: AttachmentRef, current: Boolean, fetching: Boolean, onDownload: () -> Unit) {
    LaunchedEffect(item.state, fetching) {
        if (item.state == AttachmentState.REMOTE && !fetching) onDownload()
    }
    val path = item.localPath
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            path != null && item.kind == AttachmentKind.VIDEO -> VideoPage(path, current)
            path != null && item.contentType == "image/gif" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> AnimatedGif(path)
            path != null -> ZoomablePhoto(path)
            else -> {
                val thumbnail = remember(ref, item.thumbnail) {
                    item.thumbnail?.let { Pictures.thumbnail(it, "${ref.messageId}/${ref.position}") }
                }
                thumbnail?.let { BlurredThumbnail(it, Modifier.fillMaxSize()) }
                if (item.state == AttachmentState.FAILED) {
                    Text(item.error ?: "This file can't be fetched.", color = Color.White, modifier = Modifier.padding(24.dp))
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        }
    }
}

/** A photo to pinch or double-tap into, and to drag around once zoomed. */
@Composable
private fun ZoomablePhoto(path: String) {
    var bitmap by remember(path) { mutableStateOf(Pictures.cached(path, 2048)) }
    LaunchedEffect(path) {
        if (bitmap == null) bitmap = Pictures.load(path, video = false, maxSide = 2048)
    }
    var zoom by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    val shown = bitmap
    if (shown == null) {
        CircularProgressIndicator(color = Color.White)
        return
    }
    Image(
        remember(shown) { shown.asImageBitmap() },
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(path) {
                detectTapGestures(onDoubleTap = {
                    zoom = if (zoom > 1f) 1f else 2.5f
                    offset = Offset.Zero
                })
            }
            .pointerInput(path) {
                // Two fingers zoom; one drags only once zoomed, so a swipe still
                // moves to the next photo.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || zoom > 1f) {
                            zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                            offset = if (zoom > 1f) offset + event.calculatePan() else Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                translationX = offset.x
                translationY = offset.y
            },
    )
}

/** A GIF, moving. Frames are decoded as they're shown, not all at once. */
@Composable
private fun AnimatedGif(path: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val drawable = runCatching { ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) }.getOrNull()
                    setImageDrawable(drawable)
                    (drawable as? AnimatedImageDrawable)?.start()
                }
            }
        },
    )
}

@Composable
private fun VideoPage(path: String, current: Boolean) {
    val context = LocalContext.current
    val exo = remember(path) {
        ExoPlayer.Builder(context).build().apply {
            val attributes = AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build()
            setAudioAttributes(attributes, true)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
        }
    }
    DisposableEffect(exo) {
        onDispose { exo.release() }
    }
    LaunchedEffect(current) {
        if (current) VoicePlayer.stop()
        exo.playWhenReady = current
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { PlayerView(it).apply { player = exo } },
    )
}
