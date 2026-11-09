package com.qwantarc.messenger.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Launch: the Q turns the key. The ring clicks, the tail turns, our shapes
 * spill out, then the app takes over at about 1.2 s. A slow start gets a
 * calm status line; a failed one stays and explains, with Try again.
 * (As on the iPhone.)
 */
@Composable
fun SplashScreen(failure: String?, slow: Boolean, retry: () -> Unit) {
    val dark = isDark()
    val ground = if (dark) Color(0xFF111110) else Color(0xFFF7F5EE)
    val ink = if (dark) Color(0xFFEBE8E2) else Color(0xFF141414)
    val sub = if (dark) Color(0xFFA3A1A9) else Color(0xFF5F5E66)
    val still = reduceMotion()

    val ring = remember { Animatable(1f) }
    val tail = remember { Animatable(0f) }
    val grid = remember { Animatable(if (still) 1f else 0f) }
    val word = remember { Animatable(if (still) 1f else 0f) }
    val tag = remember { Animatable(if (still) 1f else 0f) }
    val foot = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        val up = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
        launch { ring.animateTo(1f, keyframes { durationMillis = 680; 1f at 60; 0.9f at 250; 1.07f at 470 }) }
        launch { tail.animateTo(0f, keyframes { durationMillis = 640; 0f at 20; -9.8f at 210; 4.2f at 410 }) }
        launch { delay(300); grid.animateTo(1f, tween(500)) }
        launch { delay(420); word.animateTo(1f, tween(500, easing = up)) }
        launch { delay(580); tag.animateTo(1f, tween(500, easing = up)) }
        launch { delay(700); foot.animateTo(1f, tween(500)) }
    }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(slow) {
        if (!slow) { status = null; return@LaunchedEffect }
        delay(100); status = "Unlocking your chats…"
        delay(2000); status = "Almost there. Big chats take a moment"
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ground)) {
        val cx = maxWidth / 2
        val cy = maxHeight * 0.415f
        val gridLine = if (dark) Color(0xFF17171B) else Color(0xFFECE9DE)
        // The grid, clear around the mark.
        Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = grid.value }) {
            val step = 28.dp.toPx()
            var x = 0f
            while (x <= size.width) { drawRect(gridLine, Offset(x, 0f), androidx.compose.ui.geometry.Size(1.dp.toPx(), size.height)); x += step }
            var y = 0f
            while (y <= size.height) { drawRect(gridLine, Offset(0f, y), androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())); y += step }
            drawRect(
                Brush.radialGradient(
                    0f to ground, 0.2f to ground, 1f to ground.copy(alpha = 0f),
                    center = Offset(size.width / 2, size.height * 0.44f), radius = size.height * 0.42f,
                ),
            )
        }
        // The six shapes that spill out of the Q.
        val burst = listOf(
            Triple(ShapeKind.Sparkle, 44, Offset(-71f, -92f)) to 300,
            Triple(ShapeKind.Heart, 54, Offset(60f, -97f)) to 370,
            Triple(ShapeKind.Bolt, 42, Offset(128f, 1f)) to 440,
            Triple(ShapeKind.Star, 30, Offset(104f, 75f)) to 510,
            Triple(ShapeKind.Clover, 40, Offset(-103f, 74f)) to 580,
            Triple(ShapeKind.Squiggle, 50, Offset(-130f, -3f)) to 650,
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
        // The mark: a ring with an orange tail, like a key in a lock.
        Box(
            Modifier
                .offset(cx - 70.dp, cy - 70.dp)
                .size(140.dp)
                .semantics { contentDescription = "Messenger" },
        ) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX = ring.value; scaleY = ring.value }) {
                val k = size.width / 100f
                drawCircle(ink, radius = 23.5f * k, center = Offset(49f * k, 49f * k), style = Stroke(width = 8f * k))
            }
            Canvas(Modifier.fillMaxSize().graphicsLayer { translationX = tail.value.dp.toPx(); translationY = tail.value.dp.toPx() }) {
                val k = size.width / 100f
                drawLine(Color(0xFFEC7B53), Offset(58.8f * k, 58.8f * k), Offset(73.6f * k, 73.6f * k), strokeWidth = 11.5f * k, cap = StrokeCap.Round)
            }
        }
        Column(
            Modifier.fillMaxWidth().offset(y = cy + 116.dp).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Messenger",
                style = displayStyle(38.sp, bold = true),
                color = ink,
                modifier = Modifier.graphicsLayer { alpha = word.value; translationY = (1 - word.value) * 14.dp.toPx() },
            )
            Text(
                "Every chat, just between you.",
                fontSize = 16.sp,
                color = sub,
                modifier = Modifier.graphicsLayer { alpha = tag.value; translationY = (1 - tag.value) * 14.dp.toPx() },
            )
            AnimatedVisibility(visible = status != null && failure == null, enter = fadeIn() + scaleIn(initialScale = 0.96f)) {
                Column(Modifier.padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BreathingDots(ink)
                    Text(status ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = sub)
                }
            }
            AnimatedVisibility(visible = failure != null, enter = fadeIn() + scaleIn(initialScale = 0.96f)) {
                Column(
                    Modifier
                        .padding(top = 50.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (dark) Color(0xFF1C1C1E) else Color.White)
                        .border(1.dp, if (dark) Color(0xFF2A2A2F) else Color(0xFFE4E1D8), RoundedCornerShape(24.dp))
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Couldn't open your chats", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = ink)
                    Text("Give it another go. If it keeps happening, restarting your phone usually sorts it out.", fontSize = 14.sp, color = sub)
                    SelectionContainer { Text(failure ?: "", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = sub) }
                    MarigoldButton("Try again", onClick = retry, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 38.dp).graphicsLayer { alpha = foot.value },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Glyphs.Lock, contentDescription = null, tint = sub, modifier = Modifier.size(12.dp))
            Text("End-to-end encrypted", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = sub)
        }
    }
}

/** Three dots that breathe in turn. */
@Composable
fun BreathingDots(color: Color) {
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { time = it } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val p = (((time / 1000.0 - i * 0.15) % 1.1 + 1.1) % 1.1) / 1.1
            val up = if (p < 0.4) p / 0.4 else if (p < 0.8) 1 - (p - 0.4) / 0.4 else 0.0
            Box(
                Modifier
                    .graphicsLayer { alpha = (0.25 + 0.75 * up).toFloat(); translationY = (-3 * up).toFloat() * density }
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
