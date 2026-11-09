package com.qwantarc.messenger.ui

import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
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
fun MediaViewer(
    selection: MediaSelection,
    state: UiState,
    onDownload: (AttachmentRef) -> Unit,
    onDismiss: () -> Unit,
    model: com.qwantarc.messenger.AppViewModel? = null,
    onReply: ((com.qwantarc.messenger.core.MessageView) -> Unit)? = null,
) {
    val context = LocalContext.current
    // The message as it is now, so files fetched meanwhile show.
    val items = state.message(selection.messageId)?.attachments?.filter { it.isVisual }.orEmpty()
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val start = items.indexOfFirst { it.position == selection.position }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { items.size }

    val message = state.message(selection.messageId)
    var chrome by remember { mutableStateOf(true) }
    var forwarding by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Zooms out of the bubble: 0.4 s, the design's ease.
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(1f, androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)))
    }
    val chromeAlpha by androidx.compose.animation.core.animateFloatAsState(if (chrome) 1f else 0f, androidx.compose.animation.core.tween(250), label = "chrome")
    val loved = message?.reactions?.any { it.emoji == "❤️" && it.mine } == true

    // The bars' sizes, read from the screen behind: inside the dialog's own
    // window they often come through as nothing, which put Reply, Love and
    // Forward under the navigation bar.
    val navBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars
        .asPaddingValues().calculateBottomPadding()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        val s = 0.4f + 0.6f * appear.value
                        scaleX = s; scaleY = s
                        alpha = 0.4f + 0.6f * appear.value
                    }
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { chrome = !chrome },
                key = { items[it].position.toInt() },
            ) { page ->
                val item = items[page]
                val ref = AttachmentRef(selection.conversationId, selection.messageId, item.position)
                MediaPage(item, ref, current = pager.currentPage == page, fetching = ref in state.fetching, onDownload = { onDownload(ref) })
            }
            val current = items.getOrNull(pager.currentPage)
            val path = current?.localPath
            // Who sent it and when; close and share.
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = chromeAlpha }
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                    .statusBarsPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ViewerCircle(Glyphs.Close, "Close", enabled = chrome, onClick = onDismiss)
                Column(Modifier.weight(1f)) {
                    Text(message?.let { if (it.outgoing) "You" else it.sender } ?: "", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    val whenText = message?.let { android.text.format.DateUtils.getRelativeTimeSpanString(it.sentAtMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString() }
                    Text(
                        listOfNotNull(whenText, if (items.size > 1) "${pager.currentPage + 1} of ${items.size}" else null).joinToString(" · "),
                        color = Color.White.copy(alpha = 0.75f), fontSize = 12.5.sp,
                    )
                }
                if (current != null && path != null) {
                    ViewerCircle(Glyphs.Share, "Save or share", enabled = chrome) { shareFile(context, current, path) }
                }
            }
            // The caption, where we are in the album, and Reply, Love and Forward.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().graphicsLayer { alpha = chromeAlpha }
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                    .padding(bottom = navBottom).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                message?.text?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Color.White, fontSize = 15.sp, maxLines = 4) }
                if (items.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ViewerCircleText("‹", "Previous", enabled = chrome) { scope.launch { pager.animateScrollToPage((pager.currentPage - 1).coerceAtLeast(0)) } }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                            items.indices.forEach { i ->
                                val width by androidx.compose.animation.core.animateDpAsState(if (i == pager.currentPage) 18.dp else 6.dp, label = "dot")
                                Box(Modifier.size(width, 6.dp).clip(CircleShape).background(if (i == pager.currentPage) Palette.Marigold else Color.White.copy(alpha = 0.4f)))
                            }
                        }
                        ViewerCircleText("›", "Next", enabled = chrome) { scope.launch { pager.animateScrollToPage((pager.currentPage + 1).coerceAtMost(items.size - 1)) } }
                    }
                }
                if (message != null && model != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onReply != null) ViewerPill("Reply", Modifier.weight(1f), chrome) { onReply(message); onDismiss() }
                        ViewerPill(if (loved) "❤️ Loved" else "♡ Love", Modifier.weight(1f), chrome) {
                            model.react(selection.conversationId, message.id, if (loved) null else "❤️")
                        }
                        if (!message.vanish) ViewerPill("Forward", Modifier.weight(1f), chrome) { forwarding = true }
                    }
                }
            }
        }
    }
    if (forwarding && message != null && model != null) {
        ForwardSheet(selection.conversationId, message, state, model) { forwarding = false }
    }
}

@Composable
private fun ViewerCircle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp)) }
}

@Composable
private fun ViewerCircleText(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, color = Color.White, fontSize = 18.sp) }
}

@Composable
private fun ViewerPill(label: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold) }
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
