package com.qwantarc.messenger.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.ByteArrayOutputStream

// Profile photos, the same as on the iPhone: crop before setting (pinch and
// drag inside the circle, as people will see it), and view one full size.

/** The picture, upright, at most about 2048 px, for cropping. */
fun uprightBitmap(bytes: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val rotation = runCatching {
        when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (rotation == 0f) decoded
    else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
}.getOrNull()

/** A square part of [bitmap] as a profile photo: 512 px, JPEG, no metadata. */
private fun cropJpeg(bitmap: Bitmap, x: Int, y: Int, side: Int): ByteArray? = runCatching {
    val s = side.coerceAtMost(minOf(bitmap.width - x, bitmap.height - y)).coerceAtLeast(1)
    val square = Bitmap.createBitmap(bitmap, x.coerceAtLeast(0), y.coerceAtLeast(0), s, s)
    val scaled = Bitmap.createScaledBitmap(square, 512, 512, true)
    ByteArrayOutputStream().use { out ->
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        out.toByteArray()
    }
}.getOrNull()

/**
 * The picked picture under a circle: pinch to zoom, drag to place it, then
 * "Use photo". What's in the circle is what others see.
 */
@Composable
fun PhotoCropScreen(bitmap: Bitmap, onDone: (ByteArray) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        BlackDialogWindow()
        val image = remember(bitmap) { bitmap.asImageBitmap() }
        var zoom by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val sidePx = with(density) { minOf(maxWidth - 32.dp, 380.dp).toPx() }
            val base = sidePx / minOf(bitmap.width, bitmap.height)
            val scale = base * zoom
            fun clamp(o: Offset, s: Float): Offset {
                val mx = maxOf(0f, (bitmap.width * s - sidePx) / 2)
                val my = maxOf(0f, (bitmap.height * s - sidePx) / 2)
                return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
            }
            Box(
                Modifier.fillMaxSize().pointerInput(bitmap) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                        offset = clamp(offset + pan, base * zoom)
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    image, contentDescription = "Your photo",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier
                        .size(with(density) { (bitmap.width * scale).toDp() }, with(density) { (bitmap.height * scale).toDp() })
                        .graphicsLayer { translationX = offset.x; translationY = offset.y },
                )
                // Dims all but the circle.
                Canvas(Modifier.fillMaxSize()) {
                    val r = sidePx / 2
                    val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, r)) }
                    clipPath(circle, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.6f)) }
                    drawCircle(Color.White.copy(alpha = 0.85f), r, center, style = Stroke(1.5.dp.toPx()))
                }
            }
            Text(
                "Move and pinch", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
            )
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.weight(1f).height(52.dp).clip(CircleShape).border(1.5.dp, Color.White.copy(alpha = 0.7f), CircleShape).clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) { Text("Cancel", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Box(
                    Modifier.weight(1f).height(52.dp).clip(CircleShape).background(Palette.Marigold).clickable {
                        val side = (sidePx / scale).toInt()
                        val x = ((bitmap.width * scale / 2 - offset.x - sidePx / 2) / scale).toInt()
                        val y = ((bitmap.height * scale / 2 - offset.y - sidePx / 2) / scale).toInt()
                        cropJpeg(bitmap, x, y, side)?.let(onDone)
                    },
                    contentAlignment = Alignment.Center,
                ) { Text("Use photo", color = Palette.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/**
 * Someone's picture full size, on black: their photo, or their sticker or
 * initials drawn large. Tap, or swipe down, to close.
 */
@Composable
fun AvatarViewer(name: String, photo: String?, onDismiss: () -> Unit, face: @Composable (androidx.compose.ui.unit.Dp) -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        BlackDialogWindow()
        var drag by remember { mutableFloatStateOf(0f) }
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = { if (kotlin.math.abs(drag) > 300f) onDismiss() else drag = 0f },
                    ) { _, amount -> drag += amount }
                },
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth - 48.dp, 340.dp)
            Box(Modifier.graphicsLayer { translationY = drag }) {
                val image = photo?.let { ProfilePhotoCache.bitmap(it) }
                if (image != null) {
                    Image(
                        image, contentDescription = "$name's picture",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(24.dp)),
                    )
                } else {
                    face(side)
                }
            }
            Row(
                Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { Icon(Glyphs.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}
