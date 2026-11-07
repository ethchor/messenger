package com.qwantarc.messenger.ui

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Papers that move: lines that drift, bend towards a finger, and ripple when a message goes or comes. */
enum class LivePaper(val id: String, val title: String) {
    /** Rings of arcs rising from below, as on qwantarc.com. */
    Arcs("arcs", "Arcs"),

    /** Contour lines, like a hiking map, over slowly shifting hills. */
    Contours("contours", "Contours"),
}

/** The newest message, so a live paper can ripple when one goes or comes. */
data class LivePulse(val id: String, val outgoing: Boolean)

/**
 * What a live paper knows between frames, in dp: where the finger is, where
 * the lines lean, and the ripples on their way out. Plain fields: the paper
 * redraws on its own clock, and touches shouldn't recompose the chat.
 */
class LiveTouch {
    var target: Offset? = null
    var lean: Offset? = null
    val ripples = ArrayList<Pair<Offset, Long>>()
    var width = 0f
    var height = 0f
    var appeared = 0L
    var lastFrame = 0L
    private var release = 0L
    var density = 1f

    fun press(px: Offset, ripple: Boolean, now: Long) {
        val point = px / density
        target = point
        release = 0
        if (ripple) ripple(point, now)
    }

    fun lift(now: Long) {
        release = now + 700
    }

    fun ripple(at: Offset, now: Long) {
        ripples += at to now
        if (ripples.size > 5) ripples.removeAt(0)
    }

    fun pulse(pulse: LivePulse, now: Long) {
        if (width == 0f) return
        ripple(Offset(width * if (pulse.outgoing) 0.8f else 0.2f, height - 90f), now)
    }

    internal fun settle(now: Long) {
        if (release != 0L && now >= release) {
            target = null
            release = 0
        }
        ripples.removeAll { now - it.second > 1800 }
    }
}

@Composable
fun rememberLiveTouch(): LiveTouch = remember { LiveTouch() }

/**
 * Watches touches over a chat for its live paper, without taking them: the
 * list on top still scrolls and its bubbles still answer.
 */
fun Modifier.liveTouch(touch: LiveTouch): Modifier = pointerInput(touch) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: continue
            val now = System.currentTimeMillis()
            touch.density = density
            when (event.type) {
                PointerEventType.Press -> touch.press(change.position, ripple = true, now)
                PointerEventType.Move -> if (change.pressed) touch.press(change.position, ripple = false, now)
                PointerEventType.Release, PointerEventType.Exit -> touch.lift(now)
            }
        }
    }
}

private fun stillFor(context: Context): Boolean {
    val animations = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val power = context.getSystemService(PowerManager::class.java)
    return animations == 0f || power?.isPowerSaveMode == true
}

/** Draws a live paper, at most 30 times a second. Still when animations are off, in Battery Saver, or as a swatch. */
@Composable
fun LivePaperCanvas(paper: LivePaper, color: Color, strength: Float, touch: LiveTouch, still: Boolean, pulse: LivePulse?, modifier: Modifier) {
    val context = LocalContext.current
    val paused = still || remember { stillFor(context) }
    var frame by remember { mutableLongStateOf(0L) }
    if (!paused) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameMillis { ms ->
                    if (ms - last >= 33) {
                        last = ms
                        frame = System.currentTimeMillis()
                    }
                }
            }
        }
    }
    LaunchedEffect(pulse) {
        // Not for the message already there on opening the chat.
        if (pulse != null && touch.appeared != 0L) touch.pulse(pulse, System.currentTimeMillis())
    }
    Canvas(modifier) {
        val now = if (paused) 40_000L else frame.takeIf { it != 0L } ?: System.currentTimeMillis()
        touch.density = density
        scale(density, density, pivot = Offset.Zero) {
            when (paper) {
                LivePaper.Arcs -> drawArcs(touch, color, strength, now, paused)
                LivePaper.Contours -> drawContours(touch, color, strength, now, paused)
            }
        }
    }
}

private class Ring(val at: Offset, val radius: Float, val amp: Float)

private class Frame(val t: Double, val intro: Float, val lean: Offset, val leaning: Boolean, val rings: List<Ring>)

/** Moves the lean after the finger (or a slow drift), and ages the ripples. Sizes in dp. */
private fun DrawScope.frame(touch: LiveTouch, now: Long, paused: Boolean, drift: (Double) -> Offset): Frame {
    val w = size.width / density
    val h = size.height / density
    touch.width = w
    touch.height = h
    val t = (now % 10_000_000L) / 1000.0
    if (paused) return Frame(t, 1f, drift(t), false, emptyList())
    touch.settle(now)
    if (touch.appeared == 0L) touch.appeared = now
    val dt = ((now - (if (touch.lastFrame == 0L) now else touch.lastFrame)).coerceIn(0, 100)) / 1000.0
    touch.lastFrame = now
    val goal = touch.target ?: drift(t)
    val lean = touch.lean ?: goal
    val ease = (1 - (1 - if (touch.target == null) 0.075 else 0.14).pow(dt * 60)).toFloat()
    val eased = Offset(lean.x + (goal.x - lean.x) * ease, lean.y + (goal.y - lean.y) * ease)
    touch.lean = eased
    val introAge = min((now - touch.appeared) / 1400.0, 1.0)
    val intro = (1 - (1 - introAge).pow(3)).toFloat()
    val reach = max(w, h)
    val rings = touch.ripples.map { (at, start) ->
        val age = (now - start) / 1000.0
        Ring(at, (age * reach * 0.75).toFloat(), (30 * exp(-age * 2.4)).toFloat())
    }
    return Frame(t, intro, eased, touch.target != null, rings)
}

private fun DrawScope.drawArcs(touch: LiveTouch, color: Color, strength: Float, now: Long, paused: Boolean) {
    val w = size.width / density
    val h = size.height / density
    if (w <= 0 || h <= 0) return
    val f = frame(touch, now, paused) { t -> Offset((w / 2 + sin(t * 0.21) * w * 0.32).toFloat(), (h * 0.42 + cos(t * 0.16) * h * 0.18).toFloat()) }
    val cx = w / 2
    val cy = h * 1.16f
    val maxRadius = hypot(w, h) * 1.05f
    val radii = ArrayList<Float>()
    var r = h * 0.22f
    var gap = 9f
    while (r < maxRadius) {
        radii += r
        r += gap
        gap *= 1.045f
    }
    val sigma = min(w, h) * 0.2f
    val leanRadius = hypot(f.lean.x - cx, f.lean.y - cy)
    val span = (PI / 2 * f.intro).toFloat()
    val breathe = if (paused) 0.0 else f.t * 0.4
    for ((i, r0) in radii.withIndex()) {
        val radius = r0 + (sin(breathe + i * 0.33) * 1.6).toFloat()
        val near = exp(-(radius - leanRadius).pow(2) / (2 * (sigma * 0.35f).pow(2)))
        val next = radii.getOrNull(i + 1) ?: (r0 + 20)
        val chosen = f.leaning && leanRadius >= r0 && leanRadius < next
        val steps = max(48, (PI * radius / 12).toInt())
        val path = Path()
        for (k in 0..steps) {
            val a = PI * 1.5 - span + 2.0 * span * k / steps
            var x = cx + cos(a).toFloat() * radius
            var y = cy + sin(a).toFloat() * radius
            val dx = f.lean.x - x
            val dy = f.lean.y - y
            val pull = 0.42f * exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
            x += dx * pull
            y += dy * pull
            for (ring in f.rings) {
                val rx = x - ring.at.x
                val ry = y - ring.at.y
                val d = max(hypot(rx, ry), 1f)
                val push = ring.amp * exp(-(d - ring.radius).pow(2) / 3200f)
                x += rx / d * push
                y += ry / d * push
            }
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val depth = 1 - i.toFloat() / radii.size
        val alpha = if (chosen) strength * 3.2f else strength * (0.45f + depth * 0.55f + near * 1.6f)
        drawPath(path, color.copy(alpha = min(alpha, 1f)), style = Stroke(if (chosen) 1.6f else 1f))
    }
}

private fun DrawScope.drawContours(touch: LiveTouch, color: Color, strength: Float, now: Long, paused: Boolean) {
    val w = size.width / density
    val h = size.height / density
    if (w <= 0 || h <= 0) return
    val f = frame(touch, now, paused) { t -> Offset((w * (0.5 + 0.3 * sin(t * 0.13))).toFloat(), (h * (0.45 + 0.25 * cos(t * 0.11))).toFloat()) }
    val t = if (paused) 40.0 else f.t
    val leaning = if (f.leaning) 1f else 0.35f
    val hills = arrayOf(
        floatArrayOf((w * (0.3 + 0.18 * sin(t * 0.07))).toFloat(), (h * (0.3 + 0.12 * cos(t * 0.05))).toFloat(), 1.3f, w * 0.42f),
        floatArrayOf((w * (0.75 + 0.15 * cos(t * 0.06))).toFloat(), (h * (0.65 + 0.15 * sin(t * 0.045))).toFloat(), -1.1f, w * 0.5f),
        floatArrayOf((w * (0.45 + 0.25 * sin(t * 0.04 + 2))).toFloat(), (h * (0.85 + 0.1 * cos(t * 0.08))).toFloat(), 0.9f, w * 0.35f),
    )
    fun value(x: Float, y: Float): Float {
        val u = x / 230.0
        val v = y / 230.0
        var z = 0.45 * sin(u * 1.3 + t * 0.05) * cos(v * 1.1 - t * 0.04) + 0.35 * sin((u + v) * 0.8 + t * 0.03)
        for (hill in hills) {
            val d2 = (x - hill[0]) * (x - hill[0]) + (y - hill[1]) * (y - hill[1])
            z += hill[2] * exp(-d2 / (2.0 * hill[3] * hill[3]))
        }
        val ld2 = (x - f.lean.x) * (x - f.lean.x) + (y - f.lean.y) * (y - f.lean.y)
        z += 0.9 * leaning * exp(-ld2 / (2.0 * 75 * 75))
        for (ring in f.rings) {
            val d = hypot(x - ring.at.x, y - ring.at.y)
            z += ring.amp / 30.0 * 0.5 * exp(-(d - ring.radius).toDouble().pow(2) / (2.0 * 28 * 28))
        }
        return z.toFloat()
    }
    val step = 11f
    val columns = ceil(w / step).toInt() + 1
    val rows = ceil(h / step).toInt() + 1
    val values = FloatArray(columns * rows)
    for (row in 0 until rows) for (column in 0 until columns) values[row * columns + column] = value(column * step, row * step)
    val base = -2f
    val gap = 0.17f
    val lines = Path()
    val index = Path()
    val near = Path()
    val reach = 95f
    val segment = FloatArray(8)
    for (row in 0 until rows - 1) {
        for (column in 0 until columns - 1) {
            val a = values[row * columns + column]
            val b = values[row * columns + column + 1]
            val c = values[(row + 1) * columns + column + 1]
            val d = values[(row + 1) * columns + column]
            val low = min(min(a, b), min(c, d))
            val high = max(max(a, b), max(c, d))
            var level = ceil((low - base) / gap).toInt()
            val x = column * step
            val y = row * step
            while (base + level * gap <= high) {
                val count = marchingSquares(a, b, c, d, base + level * gap, segment)
                for (s in 0 until count) {
                    val x1 = x + segment[s * 4] * step
                    val y1 = y + segment[s * 4 + 1] * step
                    val x2 = x + segment[s * 4 + 2] * step
                    val y2 = y + segment[s * 4 + 3] * step
                    val target = when {
                        f.leaning && hypot((x1 + x2) / 2 - f.lean.x, (y1 + y2) / 2 - f.lean.y) < reach -> near
                        level % 5 == 0 -> index
                        else -> lines
                    }
                    target.moveTo(x1, y1)
                    target.lineTo(x2, y2)
                }
                level++
            }
        }
    }
    drawPath(lines, color.copy(alpha = min(strength * 0.95f, 1f)), style = Stroke(0.9f, cap = StrokeCap.Round))
    drawPath(index, color.copy(alpha = min(strength * 1.9f, 1f)), style = Stroke(1.3f, cap = StrokeCap.Round))
    drawPath(near, color.copy(alpha = min(strength * 3f, 1f)), style = Stroke(1.3f, cap = StrokeCap.Round))
}

/**
 * Where one cell crosses [level], as up to two segments (x1, y1, x2, y2) in
 * the cell's unit square, written into [out]. Corners: a top left, b top
 * right, c bottom right, d bottom left. Returns how many.
 */
private fun marchingSquares(a: Float, b: Float, c: Float, d: Float, level: Float, out: FloatArray): Int {
    var case = 0
    if (a > level) case = case or 8
    if (b > level) case = case or 4
    if (c > level) case = case or 2
    if (d > level) case = case or 1
    if (case == 0 || case == 15) return 0
    fun t(p: Float, q: Float) = (level - p) / (q - p)
    val top = floatArrayOf(t(a, b), 0f)
    val right = floatArrayOf(1f, t(b, c))
    val bottom = floatArrayOf(t(d, c), 1f)
    val left = floatArrayOf(0f, t(a, d))
    fun put(i: Int, p: FloatArray, q: FloatArray) {
        out[i * 4] = p[0]; out[i * 4 + 1] = p[1]; out[i * 4 + 2] = q[0]; out[i * 4 + 3] = q[1]
    }
    val centreHigh = (a + b + c + d) / 4 > level
    when (case) {
        1, 14 -> put(0, left, bottom)
        2, 13 -> put(0, bottom, right)
        3, 12 -> put(0, left, right)
        4, 11 -> put(0, top, right)
        6, 9 -> put(0, top, bottom)
        7, 8 -> put(0, left, top)
        5 -> if (centreHigh) { put(0, left, top); put(1, bottom, right); return 2 } else { put(0, left, bottom); put(1, top, right); return 2 }
        10 -> if (centreHigh) { put(0, left, bottom); put(1, top, right); return 2 } else { put(0, left, top); put(1, bottom, right); return 2 }
    }
    return 1
}
