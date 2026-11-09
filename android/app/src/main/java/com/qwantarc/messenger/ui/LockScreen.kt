package com.qwantarc.messenger.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qwantarc.messenger.AppLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The app lock, as designed (R-More › States › App lock), the same as on the
 * iPhone: the splash's paper and Q, breathing, with a marigold padlock at
 * its corner. The prompt opens on its own. While it's up, the buttons give
 * way to a scanning tile; a miss shakes the Q; a match drops the padlock,
 * turns the key, and the shapes burst out as at launch, then the chats open.
 */
@Composable
fun LockScreen(lock: AppLock.State, onUnlock: () -> Unit, onPasscode: () -> Unit, onWelcomed: () -> Unit) {
    val dark = isDark()
    val ground = if (dark) Color(0xFF111110) else Color(0xFFF7F5EE)
    val ink = if (dark) Color(0xFFEBE8E2) else Color(0xFF141414)
    val sub = if (dark) Color(0xFFA3A1A9) else Color(0xFF5F5E66)
    val still = reduceMotion()
    val welcome = lock.welcoming
    val failed = !welcome && !lock.authenticating && (lock.error != null)
    val scanning = lock.authenticating && !welcome

    // The prompt opens on its own when the screen appears.
    LaunchedEffect(Unit) { if (!lock.authenticating && !welcome) onUnlock() }

    val shake = remember { Animatable(0f) }
    LaunchedEffect(lock.misses, failed) {
        if ((lock.misses > 0 || failed) && !still) {
            shake.animateTo(0f, keyframes { durationMillis = 500; -10f at 60; 9f at 140; -6f at 220; 4f at 300 })
        }
    }
    val ring = remember { Animatable(1f) }
    val tail = remember { Animatable(0f) }
    LaunchedEffect(welcome) {
        if (!welcome) return@LaunchedEffect
        if (!still) {
            launch { ring.animateTo(1f, keyframes { durationMillis = 680; 1f at 60; 0.9f at 250; 1.07f at 470 }) }
            launch { tail.animateTo(0f, keyframes { durationMillis = 640; 0f at 20; -9.8f at 210; 4.2f at 410 }) }
        }
        delay(if (still) 300 else 1100)
        onWelcomed()
    }
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        1f, 0.97f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "s",
    )

    val title = when {
        welcome -> "Welcome back"
        failed -> "That didn't look like you"
        else -> "Locked, just for you"
    }
    val body = when {
        welcome -> "Your chats are ready"
        scanning -> "Looking for you…"
        failed -> "Try again, or use your passcode"
        else -> "Your chats open with your face or fingerprint."
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ground)) {
        val cx = maxWidth / 2
        val cy = maxHeight * 0.415f
        val gridLine = if (dark) Color(0xFF17171B) else Color(0xFFECE9DE)
        Canvas(Modifier.fillMaxSize()) {
            val step = 28.dp.toPx()
            var x = 0f
            while (x <= size.width) { drawRect(gridLine, Offset(x, 0f), Size(1.dp.toPx(), size.height)); x += step }
            var y = 0f
            while (y <= size.height) { drawRect(gridLine, Offset(0f, y), Size(size.width, 1.dp.toPx())); y += step }
            drawRect(
                Brush.radialGradient(
                    0f to ground, 0.2f to ground, 1f to ground.copy(alpha = 0f),
                    center = Offset(size.width / 2, size.height * 0.44f), radius = size.height * 0.42f,
                ),
            )
        }
        if (welcome) {
            val burst = listOf(
                Triple(ShapeKind.Sparkle, 44, Offset(-71f, -92f)) to 50,
                Triple(ShapeKind.Heart, 54, Offset(60f, -97f)) to 120,
                Triple(ShapeKind.Bolt, 42, Offset(128f, 1f)) to 190,
                Triple(ShapeKind.Star, 30, Offset(104f, 75f)) to 260,
                Triple(ShapeKind.Clover, 40, Offset(-103f, 74f)) to 330,
                Triple(ShapeKind.Squiggle, 50, Offset(-130f, -3f)) to 400,
            )
            burst.forEachIndexed { i, (shape, delayMs) ->
                val (kind, side, at) = shape
                val fly = remember { Animatable(if (still) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (still) return@LaunchedEffect
                    delay(delayMs.toLong())
                    fly.animateTo(1f, tween(800, easing = CubicBezierEasing(0.25f, 1.5f, 0.45f, 1f)))
                }
                Box(
                    Modifier
                        .offset(cx + at.x.dp - (side / 2).dp, cy + at.y.dp - (side / 2).dp)
                        .graphicsLayer {
                            val f = fly.value
                            alpha = (f / 0.35f).coerceIn(0f, 1f)
                            translationX = -at.x.dp.toPx() * (1 - f)
                            translationY = -at.y.dp.toPx() * (1 - f)
                            val s = 0.15f + 0.85f * f
                            scaleX = s; scaleY = s
                        },
                ) { ShapeSticker(kind, size = side.dp, phase = i * -0.6f, delayMs = 0) }
            }
        }
        // The Q, breathing, with the padlock at its corner.
        Box(
            Modifier
                .offset(cx - 70.dp, cy - 70.dp)
                .size(140.dp)
                .graphicsLayer {
                    translationX = shake.value.dp.toPx()
                    val s = if (welcome || still) 1f else breathe
                    scaleX = s; scaleY = s
                }
                .semantics { contentDescription = "Messenger is locked" },
        ) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = ring.value; scaleY = ring.value }) {
                val k = size.width / 100f
                drawCircle(ink, radius = 23.5f * k, center = Offset(49f * k, 49f * k), style = Stroke(width = 8f * k))
            }
            Canvas(Modifier.fillMaxSize().graphicsLayer { translationX = tail.value.dp.toPx(); translationY = tail.value.dp.toPx() }) {
                val k = size.width / 100f
                drawLine(Color(0xFFEC7B53), Offset(58.8f * k, 58.8f * k), Offset(73.6f * k, 73.6f * k), strokeWidth = 11.5f * k, cap = StrokeCap.Round)
            }
            AnimatedVisibility(
                !welcome,
                Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 14.dp),
                enter = fadeIn() + scaleIn(initialScale = 0.3f),
                exit = fadeOut() + scaleOut(targetScale = 0.3f),
            ) { Padlock() }
        }
        Column(
            Modifier.fillMaxWidth().offset(y = cy + 116.dp).padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = displayStyle(30.sp, bold = true), color = ink, textAlign = TextAlign.Center)
            Text(body, fontSize = 16.sp, color = sub, textAlign = TextAlign.Center)
            lock.error?.takeIf { failed && it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, color = sub, textAlign = TextAlign.Center)
            }
            when {
                welcome -> {}
                scanning -> ScanTile(ink, dark, Modifier.padding(top = 22.dp))
                else -> Column(
                    Modifier.padding(top = 26.dp).widthIn(max = 300.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(Palette.Marigold).clickable(onClick = onUnlock),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FaceGlyph(Palette.Ink, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (failed) "Try again" else "Unlock", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).clickable(onClick = onPasscode),
                        contentAlignment = Alignment.Center,
                    ) { Text("Use passcode", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ink) }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 38.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Glyphs.Lock, contentDescription = null, tint = sub, modifier = Modifier.size(12.dp))
            Text("End-to-end encrypted", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = sub)
        }
    }
}

/** The marigold padlock on the Q's corner. */
@Composable
private fun Padlock() {
    Box(Modifier.size(38.dp)) {
        Box(Modifier.offset(y = 3.dp).size(38.dp).background(Palette.Ink, CircleShape))
        Box(
            Modifier.size(38.dp).background(Palette.Marigold, CircleShape).border(2.dp, Palette.Ink, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(Glyphs.Lock, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(16.dp)) }
    }
}

/** While the prompt looks: a rounded tile with the face glyph and a green line sweeping. */
@Composable
private fun ScanTile(ink: Color, dark: Boolean, modifier: Modifier = Modifier) {
    val sweep by rememberInfiniteTransition(label = "scan").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "y",
    )
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier.size(92.dp).clip(shape).background(if (dark) Color(0xFF1C1C1F) else Color.White)
            .border(1.dp, ink.copy(alpha = 0.12f), shape)
            .semantics { contentDescription = "Checking it's you" },
        contentAlignment = Alignment.Center,
    ) {
        FaceGlyph(ink, Modifier.size(44.dp))
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = (14 + 62 * sweep).dp).size(60.dp, 3.dp)
                .background(Color(0xFF2EBD6B), CircleShape),
        )
    }
}

/** A face-scan glyph: four corner brackets, two eyes, a nose and a smile. */
@Composable
fun FaceGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val s = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
        val c = w * 0.28f
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(x, y + dy * c), Offset(x, y), s.width, StrokeCap.Round)
            drawLine(color, Offset(x, y), Offset(x + dx * c, y), s.width, StrokeCap.Round)
        }
        val m = w * 0.06f
        corner(m, m, 1f, 1f); corner(w - m, m, -1f, 1f); corner(m, w - m, 1f, -1f); corner(w - m, w - m, -1f, -1f)
        drawLine(color, Offset(w * 0.36f, w * 0.38f), Offset(w * 0.36f, w * 0.45f), s.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.64f, w * 0.38f), Offset(w * 0.64f, w * 0.45f), s.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, w * 0.40f), Offset(w * 0.47f, w * 0.56f), s.width, StrokeCap.Round)
        drawArc(color, 30f, 120f, false, Offset(w * 0.33f, w * 0.48f), Size(w * 0.34f, w * 0.2f), style = s)
    }
}
