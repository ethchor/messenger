package com.qwantarc.messenger.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Our own set of ten shapes (the same as on the iPhone). Each one means
 * something, pops in with a little overshoot, idles in its own way, and
 * squashes when tapped. Decoration only: hidden from TalkBack.
 */
enum class ShapeKind(
    val d: String,
    val light: Long, val mid: Long, val deep: Long, val shade: Long,
    val strokeWidth: Float? = null,
) {
    Heart("M50 90C47 90 6 66 6 35C6 18 18 8 31 8C40 8 46 13 50 21C54 13 60 8 69 8C82 8 94 18 94 35C94 66 53 90 50 90Z", 0xFFFFE0EE, 0xFFFF86BE, 0xFFEE3A8C, 0xFFB8125F),
    Sparkle("M50 4C53 33 67 47 96 50C67 53 53 67 50 96C47 67 33 53 4 50C33 47 47 33 50 4Z", 0xFFFFF6C4, 0xFFFFD84A, 0xFFFFA21F, 0xFFE86A00),
    Bolt("M60 6L22 56H47L39 94L79 40H54L62 6Z", 0xFFFFF3A6, 0xFFFFC524, 0xFFFF7A1A, 0xFFD94E00),
    Squiggle("M10 64C18 36 32 32 38 52C44 72 58 74 64 50C70 26 84 24 90 40", 0xFF86E3FF, 0xFF8F86FF, 0xFFFF7CCF, 0xFF5B3FD6, strokeWidth = 15f),
    Moon("M62 6A45 45 0 1 0 94 62A34 34 0 0 1 62 6Z", 0xFFF1ECFF, 0xFFB4A4FF, 0xFF6A57E8, 0xFF3E2DB3),
    Clover("M50 26A24 24 0 1 1 74 50A24 24 0 1 1 50 74A24 24 0 1 1 26 50A24 24 0 1 1 50 26Z", 0xFFDDFFF3, 0xFF5EE3C1, 0xFF0E9F9A, 0xFF06706C),
    Star("M50 12V88M12 50H88M23 23L77 77M77 23L23 77", 0xFFFFE0DF, 0xFFFF7A7A, 0xFFE0313F, 0xFFA3111E, strokeWidth = 12f),
    Bubble("M50 10C76 10 93 26 93 46C93 66 76 81 52 81C46 81 40 80 35 78L15 89L20 70C12 63 7 55 7 46C7 26 24 10 50 10Z", 0xFFE6F5FF, 0xFF7FC8FF, 0xFF3B7CFF, 0xFF1F4FD1),
    Seal("M50 10Q63.7 -1.1 70 15.4Q87.4 12.6 84.6 30Q101.1 36.3 90 50Q101.1 63.7 84.6 70Q87.4 87.4 70 84.6Q63.7 101.1 50 90Q36.3 101.1 30 84.6Q12.6 87.4 15.4 70Q-1.1 63.7 10 50Q-1.1 36.3 15.4 30Q12.6 12.6 30 15.4Q36.3 -1.1 50 10Z", 0xFFE2FFD3, 0xFF63E08F, 0xFF0FA968, 0xFF07734A),
    Pebble("M53 8C79 9 94 27 92 51C90 76 71 92 46 92C22 92 8 75 8 52C9 27 27 7 53 8Z", 0xFFFFE6DA, 0xFFFF9F7E, 0xFFF0582F, 0xFFB8350F);

    val path: Path by lazy { PathParser().parsePathString(d).toPath() }
}

/** Whether the system asks for less motion (animations off). */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** One shape: pops in after [delayMs], idles, and squashes when tapped. */
@Composable
fun ShapeSticker(
    kind: ShapeKind,
    modifier: Modifier = Modifier,
    size: Dp = 58.dp,
    delayMs: Int = 0,
    phase: Float = 0f,
    tappable: Boolean = false,
) {
    val still = reduceMotion()
    val pop = remember { Animatable(if (still) 1f else 0f) }
    val sx = remember { Animatable(1f) }
    val sy = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        if (still) return@LaunchedEffect
        launch {
            delay(delayMs.toLong())
            pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
        }
        while (true) withFrameMillis { time = it }
    }
    val t = time / 1000.0 + phase
    val m = if (still) ShapeMotion() else idle(kind, t)
    val dyPx = with(LocalDensity.current) { (m.dy * size.value / 58f).dp.toPx() }
    val tap = if (tappable) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        scope.launch {
            launch { sx.animateTo(1f, keyframes { durationMillis = 620; 1.28f at 136; 0.84f at 298; 1.06f at 446 }) }
            launch { sy.animateTo(1f, keyframes { durationMillis = 620; 0.72f at 136; 1.18f at 298; 0.95f at 446 }) }
        }
    } else Modifier
    Canvas(
        modifier
            .size(size)
            .clearAndSetSemantics {}
            .then(tap)
            .graphicsLayer {
                val p = pop.value
                alpha = p.coerceIn(0f, 1f)
                transformOrigin = TransformOrigin(0.5f, 1f)
                scaleX = p * sx.value
                scaleY = p * sy.value
                translationY = (1f - p) * 18.dp.toPx()
            }
            .graphicsLayer {
                transformOrigin = TransformOrigin(m.ax, m.ay)
                scaleX = m.sx; scaleY = m.sy
                rotationZ = m.rotation
                translationY = dyPx
            },
    ) {
        val k = this.size.width / 100f
        scale(k, k, pivot = Offset.Zero) {
            val body = Brush.linearGradient(
                0f to Color(kind.light), 0.5f to Color(kind.mid), 1f to Color(kind.deep),
                start = Offset(8f, 92f), end = Offset(92f, 8f),
            )
            val light = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), center = Offset(30f, 74f), radius = 46f)
            val shade = Brush.radialGradient(listOf(Color(kind.shade).copy(alpha = 0.42f), Color(kind.shade).copy(alpha = 0f)), center = Offset(84f, 16f), radius = 52f)
            val path = if (kind == ShapeKind.Squiggle && (m.trimFrom > 0f || m.trimTo < 1f)) trimmed(kind.path, m.trimFrom, m.trimTo) else kind.path
            for (brush in listOf(body, light, shade)) {
                val w = kind.strokeWidth
                if (w != null) {
                    drawPath(path, brush, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    if (kind == ShapeKind.Bolt) drawPath(path, brush, style = Stroke(width = 9f, join = StrokeJoin.Round))
                    drawPath(path, brush, style = Fill)
                }
            }
            if (m.bright > 0f) drawPath(path, Color.White.copy(alpha = m.bright), style = Fill)
        }
    }
}

private fun trimmed(path: Path, from: Float, to: Float): Path {
    val measure = PathMeasure().apply { setPath(path, false) }
    val out = Path()
    measure.getSegment(measure.length * from, measure.length * to, out, true)
    return out
}

private data class ShapeMotion(
    val sx: Float = 1f, val sy: Float = 1f, val rotation: Float = 0f, val dy: Float = 0f,
    val ax: Float = 0.5f, val ay: Float = 0.5f, val bright: Float = 0f,
    val trimFrom: Float = 0f, val trimTo: Float = 1f,
)

/** The idle loops from the design, as functions of time (as on the iPhone). */
private fun idle(kind: ShapeKind, t: Double): ShapeMotion {
    fun phase(period: Double) = (((t % period) + period) % period / period)
    fun ease(x: Double) = x * x * (3 - 2 * x)
    fun seg(p: Double, vararg keys: Pair<Double, Double>): Float {
        for (i in 1 until keys.size) if (p <= keys[i].first) {
            val (p0, v0) = keys[i - 1]; val (p1, v1) = keys[i]
            return (v0 + (v1 - v0) * ease((p - p0) / maxOf(p1 - p0, 0.0001))).toFloat()
        }
        return keys.last().second.toFloat()
    }
    return when (kind) {
        ShapeKind.Heart -> {
            val s = seg(phase(2.6), 0.0 to 1.0, 0.1 to 1.12, 0.2 to 0.97, 0.3 to 1.07, 0.45 to 1.0, 1.0 to 1.0)
            ShapeMotion(sx = s, sy = s, ay = 0.55f)
        }
        ShapeKind.Sparkle -> {
            val p = phase(3.8)
            val s = seg(p, 0.0 to 1.0, 0.58 to 1.0, 0.78 to 1.16, 1.0 to 1.0)
            ShapeMotion(sx = s, sy = s, rotation = seg(p, 0.0 to 0.0, 0.58 to 0.0, 0.78 to 90.0, 1.0 to 90.0))
        }
        ShapeKind.Bolt -> {
            val p = phase(3.4)
            val s = seg(p, 0.0 to 1.0, 0.76 to 1.0, 0.8 to 1.14, 0.85 to 0.94, 0.9 to 1.05, 1.0 to 1.0)
            ShapeMotion(sx = s, sy = s, dy = seg(p, 0.0 to 0.0, 0.76 to 0.0, 0.8 to -4.0, 0.85 to 0.0, 1.0 to 0.0),
                bright = seg(p, 0.0 to 0.0, 0.76 to 0.0, 0.8 to 0.15, 0.9 to 0.0, 1.0 to 0.0))
        }
        ShapeKind.Squiggle -> {
            val p = phase(4.2)
            when {
                p < 0.38 -> ShapeMotion(trimTo = ease(p / 0.38).toFloat())
                p > 0.72 -> ShapeMotion(trimFrom = ease((p - 0.72) / 0.28).toFloat())
                else -> ShapeMotion()
            }
        }
        ShapeKind.Moon -> ShapeMotion(dy = (-6 * sin(PI * phase(6.0))).toFloat())
        ShapeKind.Clover -> {
            val p = phase(3.6)
            ShapeMotion(
                sx = seg(p, 0.0 to 1.0, 0.3 to 1.05, 0.6 to 0.97, 1.0 to 1.0),
                sy = seg(p, 0.0 to 1.0, 0.3 to 0.95, 0.6 to 1.04, 1.0 to 1.0),
                dy = seg(p, 0.0 to 0.0, 0.3 to 0.0, 0.6 to -4.0, 1.0 to 0.0), ay = 0.9f,
            )
        }
        ShapeKind.Star, ShapeKind.Seal -> ShapeMotion(rotation = (360 * phase(22.0)).toFloat())
        ShapeKind.Bubble, ShapeKind.Pebble -> {
            val p = sin(PI * phase(4.6)).toFloat()
            ShapeMotion(sx = 1 + 0.04f * p, sy = 1 - 0.03f * p, ay = 0.9f)
        }
    }
}
