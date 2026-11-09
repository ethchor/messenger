package com.qwantarc.messenger.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// Map pins, as designed (R-Club Map tab, R-More empty Map and Share live
// location): a sticker balloon, Find My style, the same as on the iPhone.
// The face (photo, animal or initial) sits in a 4 dp ring that runs down
// into a 9 dp tail; the tip is the exact position. Under the tip: a soft
// ground shadow and a dot in the person's ink (blue for you), a pulsing halo
// while they share live, and a frosted label. Pins drop in, and lift a
// little when pressed.

private val Ring = 4.dp
private val Tail = 9.dp
private val You = Color(0xFF3D6BFF)

/** The balloon: a circle that runs down into a short rounded tail. */
private fun balloonShape(tailPx: Float) = GenericShape { size, _ ->
    val d = size.width
    val r = d / 2
    val cx = d / 2
    val cy = r
    val tipY = d + tailPx
    val spread = Math.toRadians(28.0)
    val lx = cx - r * sin(spread).toFloat()
    val ly = cy + r * cos(spread).toFloat()
    val rx = cx + r * sin(spread).toFloat()
    // From the right side of the tail, round the top, to the left side.
    arcTo(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r), (90 - 28).toFloat(), -(360f - 56f), true)
    quadraticTo(lx + r * 0.12f, tipY - tailPx * 0.35f, cx, tipY)
    quadraticTo(rx - r * 0.12f, tipY - tailPx * 0.35f, rx, ly)
    close()
}

/**
 * A pin whose layout size is the balloon alone, so its bottom centre is the
 * exact position: place it with its bottom centre on the point. The ground,
 * halo and label hang below, outside its bounds.
 */
@Composable
fun BalloonPin(
    ink: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    live: Boolean = false,
    isMe: Boolean = false,
    label: String? = null,
    pressed: Boolean = false,
    dropIn: Boolean = true,
    face: @Composable () -> Unit,
) {
    val dark = isDark()
    val still = reduceMotion()
    val land = remember { Animatable(if (dropIn && !still) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (land.value < 1f) land.animateTo(1f, tween(600, easing = CubicBezierEasing(0.3f, 1.5f, 0.5f, 1f)))
    }
    val lift by animateFloatAsState(if (pressed) 1f else 0f, spring(dampingRatio = 0.6f), label = "lift")
    val outer = size + Ring * 2
    Layout(
        modifier = modifier,
        content = {
            // The ground first, so the balloon's tip sits on it.
            Box(Modifier.size(32.dp, 16.dp), contentAlignment = Alignment.Center) {
                if (live && !still) LiveHalo(ink)
                Canvas(Modifier.size(14.dp, 6.dp)) {
                    drawOval(Color.Black.copy(alpha = if (dark) 0.45f else 0.18f))
                }
                Box(
                    Modifier.size(6.dp).background(if (isMe) You else ink, CircleShape)
                        .border(1.dp, if (dark) Color.Transparent else Color.White.copy(alpha = 0.9f), CircleShape),
                )
            }
            val tailPx = with(androidx.compose.ui.platform.LocalDensity.current) { Tail.toPx() }
            val shape = remember(tailPx) { balloonShape(tailPx) }
            Box(
                Modifier
                    .size(outer, outer + Tail)
                    .graphicsLayer {
                        val p = land.value
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        val s = (0.7f + 0.3f * p) * (1f + 0.06f * lift)
                        scaleX = s; scaleY = s
                        translationY = -22.dp.toPx() * (1f - p) - 3.dp.toPx() * lift
                        alpha = p.coerceIn(0f, 1f)
                    }
                    .shadow(if (dark) 12.dp else 10.dp, shape, ambientColor = Color.Black, spotColor = if (dark) Color.Black else Color(0xFF141414))
                    .background(if (dark) Color(0xFF2C2C30) else Color.White, shape)
                    .border(1.dp, if (dark) Color.White.copy(alpha = 0.16f) else Color(0xFF141414).copy(alpha = 0.08f), shape),
            ) {
                Box(Modifier.padding(Ring).size(size).clip(CircleShape)) { face() }
            }
            if (label != null) PinLabel(label, live)
        },
    ) { measurables, _ ->
        val free = Constraints()
        val ground = measurables[0].measure(free)
        val balloon = measurables[1].measure(free)
        val text = measurables.getOrNull(2)?.measure(free)
        layout(balloon.width, balloon.height) {
            ground.place((balloon.width - ground.width) / 2, balloon.height - ground.height / 2 + 3.dp.roundToPx())
            balloon.place(0, 0)
            text?.place((balloon.width - text.width) / 2, balloon.height + 12.dp.roundToPx())
        }
    }
}

/** A ring in the person's ink that spreads out from the ground and fades, every 2.2 s. */
@Composable
private fun LiveHalo(ink: Color) {
    val t by rememberInfiniteTransition(label = "halo").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "t",
    )
    Canvas(Modifier.size(32.dp, 16.dp).graphicsLayer { val s = 0.6f + 1.8f * t; scaleX = s; scaleY = s }) {
        drawOval(ink.copy(alpha = 0.35f * (1f - t)), Offset.Zero, Size(size.width, size.height))
    }
}

/** "Mira · 2.4 km" on frosted glass, with a green dot while live. */
@Composable
fun PinLabel(text: String, live: Boolean) {
    val t = tones()
    Row(
        Modifier.clip(CircleShape).background(t.surface.copy(alpha = 0.88f)).border(0.5.dp, t.line, CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) {
            Box(Modifier.size(6.dp).background(Color(0xFF2E9E5B), CircleShape))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = t.text, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
