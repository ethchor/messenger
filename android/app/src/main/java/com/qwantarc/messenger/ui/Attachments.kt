package com.qwantarc.messenger.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qwantarc.messenger.AttachmentRef
import com.qwantarc.messenger.core.AttachmentKind
import com.qwantarc.messenger.core.AttachmentState
import com.qwantarc.messenger.core.AttachmentView
import com.qwantarc.messenger.core.MessageView
import com.qwantarc.messenger.media.MediaFiles
import com.qwantarc.messenger.media.Pictures
import com.qwantarc.messenger.media.VoicePlayer

/** How wide a message's photos and videos are. */
internal val MediaWidth = 240.dp

/** The bubble's column for text and other files; the grid reaches 9 dp past it on either side, nearly to the bubble's edge. */
internal val AttachmentColumnWidth = MediaWidth - 18.dp

internal val AttachmentView.isVisual: Boolean
    get() = kind == AttachmentKind.PHOTO || kind == AttachmentKind.VIDEO

/** Here to show or play: ours, or theirs once fetched. */
internal val AttachmentView.localPath: String?
    get() = if (state == AttachmentState.READY || state == AttachmentState.UPLOADING) path else null

/** The text, or what was sent when there's none: "Photo", "Voice message"… (the core's words in the chat list). */
internal val MessageView.previewText: String
    get() {
        val first = attachments.firstOrNull()
        if (text.isNotEmpty() || first == null) return text
        val n = attachments.size
        if (attachments.any { it.kind != first.kind }) return "$n attachments"
        return when (first.kind) {
            AttachmentKind.PHOTO -> if (n == 1) "Photo" else "$n photos"
            AttachmentKind.VIDEO -> if (n == 1) "Video" else "$n videos"
            AttachmentKind.VOICE -> if (n == 1) "Voice message" else "$n voice messages"
            AttachmentKind.FILE -> if (n == 1) first.fileName.ifEmpty { "File" } else "$n files"
        }
    }

/** "0:42", or "1:02:03" for the long ones. */
internal fun durationText(milliseconds: Long): String {
    val total = ((milliseconds + 500) / 1000).coerceAtLeast(0)
    val (hours, minutes, seconds) = Triple(total / 3600, total / 60 % 60, total % 60)
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

internal fun sizeText(context: Context, bytes: ULong): String =
    Formatter.formatShortFileSize(context, bytes.toLong().coerceAtLeast(0))

/** Opens a file in another app, under the name it was sent with. False if none can. */
internal fun openFile(context: Context, attachment: AttachmentView, path: String): Boolean {
    val uri = MediaFiles.shareable(context, path, attachment.fileName)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, attachment.contentType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Photos and videos open in the viewer; other files in another app. */
internal fun openAttachment(
    context: Context,
    attachment: AttachmentView,
    message: MessageView,
    conversationId: String,
    view: (MediaSelection) -> Unit,
) {
    val path = attachment.localPath ?: return
    if (attachment.isVisual) {
        view(MediaSelection(conversationId, message.id, attachment.position))
    } else if (!openFile(context, attachment, path)) {
        Toast.makeText(context, "No app here can open this file.", Toast.LENGTH_SHORT).show()
    }
}

/** Shares a file with another app, under the name it was sent with. */
internal fun shareFile(context: Context, attachment: AttachmentView, path: String) {
    val uri = MediaFiles.shareable(context, path, attachment.fileName)
    val intent = Intent(Intent.ACTION_SEND)
        .setType(attachment.contentType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(intent, null))
}

// ---- photos and videos ----

/**
 * A message's photos and videos: one large; two side by side; three or more
 * in two columns, with "+N" on the fourth when there are more.
 */
@Composable
internal fun MediaGrid(
    items: List<AttachmentView>,
    conversationId: String,
    messageId: String,
    fetching: Set<AttachmentRef>,
    onOpen: (AttachmentView) -> Unit,
    onDownload: (AttachmentRef) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = 2.dp
    val half = (MediaWidth - gap) / 2

    @Composable
    fun tile(index: Int, width: Dp, height: Dp, more: Int = 0) {
        val item = items[index]
        val ref = AttachmentRef(conversationId, messageId, item.position)
        MediaTile(
            attachment = item,
            ref = ref,
            fetching = ref in fetching,
            more = more,
            onOpen = { onOpen(item) },
            onDownload = { onDownload(ref) },
            onLongPress = onLongPress,
            modifier = Modifier.size(width, height),
        )
    }

    Column(modifier.width(MediaWidth).clip(RoundedCornerShape(14.dp)), verticalArrangement = Arrangement.spacedBy(gap)) {
        when (items.size) {
            0 -> Unit
            1 -> tile(0, MediaWidth, singleHeight(items[0]))
            2 -> Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                tile(0, half, half)
                tile(1, half, half)
            }
            3 -> {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tile(0, half, half)
                    tile(1, half, half)
                }
                tile(2, MediaWidth, half)
            }
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tile(0, half, half)
                    tile(1, half, half)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tile(2, half, half)
                    tile(3, half, half, more = items.size - 4)
                }
            }
        }
    }
}

/** Tall pictures stay taller than wide ones, within reason. */
private fun singleHeight(item: AttachmentView): Dp {
    if (item.width == 0u || item.height == 0u) return MediaWidth * 0.75f
    val ratio = item.height.toFloat() / item.width.toFloat()
    return MediaWidth * ratio.coerceIn(0.5f, 1.35f)
}

/**
 * One photo or video in a grid, with what's going on with it: on its way up
 * or down, waiting for a tap, or gone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaTile(
    attachment: AttachmentView,
    ref: AttachmentRef,
    fetching: Boolean,
    more: Int,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val maxSide = with(LocalDensity.current) { MediaWidth.roundToPx() }
    val thumbnail = remember(ref, attachment.thumbnail) {
        attachment.thumbnail?.let { Pictures.thumbnail(it, "${ref.messageId}/${ref.position}") }
    }
    val what = if (attachment.kind == AttachmentKind.VIDEO) "Video" else "Photo"
    val description = when (attachment.state) {
        AttachmentState.REMOTE -> if (fetching) "$what, downloading" else "$what, ${sizeText(context, attachment.size)}, download"
        AttachmentState.FAILED -> "$what: ${attachment.error ?: "can't be fetched"}"
        AttachmentState.UPLOADING -> "$what, sending"
        AttachmentState.READY -> if (more > 0) "$what, and $more more" else what
    }
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.08f))
            .combinedClickable(
                onClick = {
                    when (attachment.state) {
                        AttachmentState.READY, AttachmentState.UPLOADING -> onOpen()
                        AttachmentState.REMOTE -> onDownload()
                        AttachmentState.FAILED -> Unit
                    }
                },
                onLongClick = onLongPress,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val path = attachment.localPath
        when {
            path != null -> LocalPicture(path, attachment.kind == AttachmentKind.VIDEO, maxSide, thumbnail, Modifier.matchParentSize())
            thumbnail != null -> BlurredThumbnail(thumbnail, Modifier.matchParentSize())
        }
        val chip = Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape)
        when (attachment.state) {
            AttachmentState.REMOTE ->
                if (fetching) {
                    CircularProgressIndicator(chip.padding(8.dp).size(24.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Row(chip.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(MediaIcons.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Text(sizeText(context, attachment.size), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            AttachmentState.FAILED -> Text(
                attachment.error ?: "This file can't be fetched.",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(8.dp),
            )
            AttachmentState.UPLOADING ->
                CircularProgressIndicator(chip.padding(6.dp).size(18.dp), color = Color.White, strokeWidth = 2.dp)
            AttachmentState.READY ->
                if (attachment.kind == AttachmentKind.VIDEO) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = chip.padding(10.dp).size(28.dp))
                }
        }
        if (attachment.kind == AttachmentKind.VIDEO || attachment.contentType == "image/gif") {
            val label = if (attachment.kind == AttachmentKind.VIDEO) {
                if (attachment.durationMs > 0u) durationText(attachment.durationMs.toLong()) else "Video"
            } else {
                "GIF"
            }
            Text(
                label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (more > 0) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Text("+$more", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * A photo or a video's first frame from a file on this device, filling its
 * space. Until it's decoded, [placeholder] shows, blurred.
 */
@Composable
internal fun LocalPicture(
    path: String,
    video: Boolean,
    maxSide: Int,
    placeholder: Bitmap?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    var bitmap by remember(path, maxSide) { mutableStateOf(Pictures.cached(path, maxSide)) }
    LaunchedEffect(path, maxSide) {
        if (bitmap == null) bitmap = Pictures.load(path, video, maxSide)
    }
    val shown = bitmap
    when {
        shown != null -> Image(remember(shown) { shown.asImageBitmap() }, contentDescription = null, modifier = modifier, contentScale = contentScale)
        placeholder != null -> BlurredThumbnail(placeholder, modifier)
    }
}

/** A message's tiny thumbnail, blurred so its few pixels don't show. */
@Composable
internal fun BlurredThumbnail(thumbnail: Bitmap, modifier: Modifier = Modifier) {
    Image(
        remember(thumbnail) { thumbnail.asImageBitmap() },
        contentDescription = null,
        modifier = modifier.blur(12.dp),
        contentScale = ContentScale.Crop,
    )
}

// ---- voice messages ----

/** A voice message: play or pause, the waveform with how much has played (tap it to jump), and its length. */
@Composable
internal fun VoiceNoteRow(attachment: AttachmentView, fetching: Boolean, color: Color, onDownload: () -> Unit) {
    val context = LocalContext.current
    val path = attachment.localPath
    val current = path != null && VoicePlayer.path == path
    Column(Modifier.width(AttachmentColumnWidth)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                path != null -> IconButton(onClick = { VoicePlayer.toggle(context, path) }, modifier = Modifier.size(40.dp)) {
                    val playing = current && VoicePlayer.playing
                    Icon(
                        if (playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause voice message" else "Play voice message",
                        tint = color,
                        modifier = Modifier.size(30.dp),
                    )
                }
                attachment.state == AttachmentState.FAILED ->
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.padding(8.dp).size(24.dp))
                fetching -> CircularProgressIndicator(Modifier.padding(8.dp).size(24.dp), color = color, strokeWidth = 2.dp)
                else -> IconButton(onClick = onDownload, modifier = Modifier.size(40.dp)) {
                    Icon(MediaIcons.Download, contentDescription = "Download voice message", tint = color)
                }
            }
            Waveform(
                attachment.waveform,
                progress = if (current) VoicePlayer.progress else 0f,
                color = color,
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .pointerInput(path) {
                        detectTapGestures { offset ->
                            if (path == null) return@detectTapGestures
                            if (VoicePlayer.path != path) VoicePlayer.toggle(context, path)
                            VoicePlayer.seek(path, offset.x / size.width)
                        }
                    },
            )
            Text(
                durationText(if (current) VoicePlayer.positionMs.toLong() else attachment.durationMs.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f),
            )
        }
        if (attachment.state == AttachmentState.FAILED) {
            Text(
                attachment.error ?: "This voice message can't be fetched.",
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.8f),
            )
        }
    }
}

/** Loudness over time as bars; the part already played is solid. */
@Composable
internal fun Waveform(levels: ByteArray, progress: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val bar = 2.5.dp.toPx()
        val gap = 2.dp.toPx()
        val count = ((size.width + gap) / (bar + gap)).toInt().coerceAtLeast(1)
        waveformBars(levels, count).forEachIndexed { index, level ->
            val height = maxOf(3.dp.toPx(), level * size.height)
            val played = (index + 0.5f) / count <= progress
            drawRoundRect(
                color = color.copy(alpha = if (played) 1f else 0.4f),
                topLeft = Offset(index * (bar + gap), (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** [count] bars from the levels, 0 to 1, each the loudest of its stretch. */
internal fun waveformBars(levels: ByteArray, count: Int): List<Float> {
    if (levels.isEmpty()) return List(count) { 0.1f }
    return List(count) { index ->
        val start = index * levels.size / count
        val end = maxOf(start + 1, (index + 1) * levels.size / count).coerceAtMost(levels.size)
        (start until end).maxOf { levels[it].toInt() and 0xFF } / 255f
    }
}

// ---- files ----

/** A document: its icon, name and size, and whether it's here yet. Tapping opens it, or fetches it first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FileRow(
    attachment: AttachmentView,
    fetching: Boolean,
    outgoing: Boolean,
    color: Color,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onLongPress: () -> Unit,
) {
    val context = LocalContext.current
    val detail = when (attachment.state) {
        AttachmentState.REMOTE -> if (fetching) "Downloading…" else "${sizeText(context, attachment.size)} · Download"
        AttachmentState.FAILED -> attachment.error ?: "This file can't be fetched."
        AttachmentState.UPLOADING -> "${sizeText(context, attachment.size)} · Sending…"
        AttachmentState.READY -> sizeText(context, attachment.size)
    }
    Row(
        Modifier
            .width(AttachmentColumnWidth)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = {
                    when (attachment.state) {
                        AttachmentState.READY, AttachmentState.UPLOADING -> onOpen()
                        AttachmentState.REMOTE -> onDownload()
                        AttachmentState.FAILED -> Unit
                    }
                },
                onLongClick = onLongPress,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .background(if (outgoing) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.06f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (fetching) {
                CircularProgressIndicator(Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
            } else {
                Icon(MediaIcons.File, contentDescription = null, tint = color)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                attachment.fileName.ifEmpty { "File" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = MaterialTheme.typography.labelSmall, color = color.copy(alpha = 0.75f), maxLines = 2)
        }
    }
}
