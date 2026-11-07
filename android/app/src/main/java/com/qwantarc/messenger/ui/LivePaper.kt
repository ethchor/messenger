package com.qwantarc.messenger.ui

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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

    /** The terrain of ethchor.qwantarc.com/vimu: contour lines over a fixed landscape, the ground rising under a finger. */
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
    /** Whether the contours are still moving; their clock sleeps once the ground is still. */
    var awake by mutableStateOf(false)
    private var hill = floatArrayOf(0f, 0f, 0f)
    private val hillGoal = floatArrayOf(0f, 0f, 0f)
    private var hillFrame = 0L
    private var swellUntil = 0L
    private var cachedGround: Ground? = null

    /** The contours' fixed ground for this size, in dp, made once. */
    fun ground(width: Float, height: Float): Ground =
        cachedGround?.takeIf { it.width == width && it.height == height } ?: Ground(width, height).also { cachedGround = it }

    /**
     * The contours' hill now (x, y in dp, and how far it's risen): easing
     * after the finger as on the site (the place at a rate of 9 a second,
     * the height at 3.5), and jumping to the finger while it's flat.
     */
    fun hill(now: Long): FloatArray {
        if (swellUntil != 0L && now >= swellUntil) {
            swellUntil = 0
            hillGoal[2] = 0f
        }
        val dt = ((now - if (hillFrame == 0L) now else hillFrame).coerceIn(0, 100)) / 1000f
        hillFrame = now
        val follow = 1 - exp(-dt * 9)
        val rise = 1 - exp(-dt * 3.5f)
        if (hill[2] < 0.02f) {
            hill[0] = hillGoal[0]
            hill[1] = hillGoal[1]
        }
        val dx = hillGoal[0] - hill[0]
        val dy = hillGoal[1] - hill[1]
        val dz = hillGoal[2] - hill[2]
        hill[0] += dx * follow
        hill[1] += dy * follow
        hill[2] += dz * rise
        if (kotlin.math.abs(dx) < 0.3f && kotlin.math.abs(dy) < 0.3f && kotlin.math.abs(dz) < 0.002f && swellUntil == 0L) {
            hill[2] = hillGoal[2]
            hillFrame = 0
            awake = false
        }
        return hill
    }

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
        hillGoal[0] = point.x
        hillGoal[1] = point.y
        hillGoal[2] = 1f
        swellUntil = 0
        awake = true
    }

    fun lift(now: Long) {
        release = now + 700
        hillGoal[2] = 0f
        awake = true
    }

    fun ripple(at: Offset, now: Long) {
        ripples += at to now
        if (ripples.size > 5) ripples.removeAt(0)
    }

    fun pulse(pulse: LivePulse, now: Long) {
        if (width == 0f) return
        val at = Offset(width * if (pulse.outgoing) 0.8f else 0.2f, height - 90f)
        ripple(at, now)
        // The ground swells where the message is, unless a finger is down.
        if (target == null) {
            hillGoal[0] = at.x
            hillGoal[1] = at.y
            hillGoal[2] = 1f
            swellUntil = now + 700
            awake = true
        }
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
    // Arcs drift all the time, at 30 frames a second; contours redraw only
    // while their hill moves, at the display's rate.
    val running = !paused && (paper == LivePaper.Arcs || touch.awake)
    if (running) {
        LaunchedEffect(paper) {
            var last = 0L
            val gap = if (paper == LivePaper.Arcs) 33 else 0
            while (true) {
                withFrameMillis { ms ->
                    if (ms - last >= gap) {
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

/** The site's terrain, traced: the fixed ground plus the hill, a line every 0.075 of height from -0.9 up, every fifth one stronger. */
private fun DrawScope.drawContours(touch: LiveTouch, color: Color, strength: Float, now: Long, paused: Boolean) {
    val w = size.width / density
    val h = size.height / density
    if (w <= 0 || h <= 0) return
    touch.width = w
    touch.height = h
    val ground = touch.ground(w, h)
    val hill = if (paused) floatArrayOf(0f, 0f, 0f) else touch.hill(now)
    val step = ground.step
    val columns = ground.columns
    val rows = ground.rows
    val values = ground.values.copyOf()
    val lift = hill[2] * 0.5f
    if (lift > 0.002f) {
        for (row in 0 until rows) {
            val dy = row * step - hill[1]
            for (column in 0 until columns) {
                val dx = column * step - hill[0]
                values[row * columns + column] += lift * exp(-(dx * dx + dy * dy) / 33_800f)
            }
        }
    }
    val base = -0.9f
    val gap = 1.8f / 24
    val lines = Path()
    val index = Path()
    val segment = FloatArray(8)
    for (row in 0 until rows - 1) {
        val y = row * step
        for (column in 0 until columns - 1) {
            val a = values[row * columns + column]
            val b = values[row * columns + column + 1]
            val c = values[(row + 1) * columns + column + 1]
            val d = values[(row + 1) * columns + column]
            val first = max(1, ceil((min(min(a, b), min(c, d)) - base) / gap).toInt())
            val last = min(31, kotlin.math.floor((max(max(a, b), max(c, d)) - base) / gap).toInt())
            if (first > last) continue
            val x = column * step
            for (level in first..last) {
                val count = marchingSquares(a, b, c, d, base + level * gap, segment)
                val target = if (level % 5 == 0) index else lines
                for (s in 0 until count) {
                    target.moveTo(x + segment[s * 4] * step, y + segment[s * 4 + 1] * step)
                    target.lineTo(x + segment[s * 4 + 2] * step, y + segment[s * 4 + 3] * step)
                }
            }
        }
    }
    drawPath(lines, color.copy(alpha = min(strength * 3.6f * 0.6f, 1f)), style = Stroke(0.9f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawPath(index, color.copy(alpha = min(strength * 3.6f * 0.85f, 1f)), style = Stroke(1.4f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** The site's ground: three octaves of simplex noise over a grid, in dp. */
class Ground(val width: Float, val height: Float) {
    val step = if (width < 700) 7f else 8f
    val columns = ceil(width / step).toInt() + 1
    val rows = ceil(height / step).toInt() + 1
    val values = FloatArray(columns * rows).also { values ->
        for (row in 0 until rows) for (column in 0 until columns) {
            val x = column * step
            val y = row * step
            values[row * columns + column] =
                simplex(x / 560, y / 560) * 0.62f + simplex(x / 240 + 17, y / 240) * 0.27f + simplex(x / 110, y / 110 + 31) * 0.11f
        }
    }
}

/** Ashima Arts' 2D simplex noise, as the site's shader has it. */
private fun simplex(vx: Float, vy: Float): Float {
    fun mod289(x: Float) = x - 289 * kotlin.math.floor(x / 289)
    fun permute(x: Float) = mod289((x * 34 + 1) * x)
    val cx = 0.211324865405187f
    val cy = 0.366025403784439f
    val cz = -0.577350269189626f
    val cw = 0.024390243902439f
    val s = (vx + vy) * cy
    var ix = kotlin.math.floor(vx + s)
    var iy = kotlin.math.floor(vy + s)
    val t = (ix + iy) * cx
    val x0 = vx - ix + t
    val y0 = vy - iy + t
    val i1x = if (x0 > y0) 1f else 0f
    val i1y = 1f - i1x
    val xs = floatArrayOf(x0, x0 + cx - i1x, x0 + cz)
    val ys = floatArrayOf(y0, y0 + cx - i1y, y0 + cz)
    ix = mod289(ix)
    iy = mod289(iy)
    val ps = floatArrayOf(
        permute(permute(iy) + ix),
        permute(permute(iy + i1y) + ix + i1x),
        permute(permute(iy + 1) + ix + 1),
    )
    var total = 0f
    for (k in 0..2) {
        val x = xs[k]
        val y = ys[k]
        var m = max(0.5f - (x * x + y * y), 0f)
        m *= m
        m *= m
        val p = ps[k] * cw
        val gx = 2 * (p - kotlin.math.floor(p)) - 1
        val hh = kotlin.math.abs(gx) - 0.5f
        val a0 = gx - kotlin.math.floor(gx + 0.5f)
        m *= 1.79284291400159f - 0.85373472095314f * (a0 * a0 + hh * hh)
        total += m * (a0 * x + hh * y)
    }
    return 130 * total
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
    fun t(p: Float, q: Float) = (level - p) / (if (q - p == 0f) 1e-9f else q - p)
    val tx = t(a, b)
    val ry = t(b, c)
    val bx = t(d, c)
    val ly = t(a, d)
    fun put(i: Int, x1: Float, y1: Float, x2: Float, y2: Float) {
        out[i * 4] = x1; out[i * 4 + 1] = y1; out[i * 4 + 2] = x2; out[i * 4 + 3] = y2
    }
    val centreHigh = (a + b + c + d) / 4 > level
    when (case) {
        1, 14 -> put(0, 0f, ly, bx, 1f)
        2, 13 -> put(0, bx, 1f, 1f, ry)
        3, 12 -> put(0, 0f, ly, 1f, ry)
        4, 11 -> put(0, tx, 0f, 1f, ry)
        6, 9 -> put(0, tx, 0f, bx, 1f)
        7, 8 -> put(0, 0f, ly, tx, 0f)
        5 -> {
            if (centreHigh) { put(0, 0f, ly, tx, 0f); put(1, bx, 1f, 1f, ry) } else { put(0, 0f, ly, bx, 1f); put(1, tx, 0f, 1f, ry) }
            return 2
        }
        10 -> {
            if (centreHigh) { put(0, 0f, ly, bx, 1f); put(1, tx, 0f, 1f, ry) } else { put(0, 0f, ly, tx, 0f); put(1, bx, 1f, 1f, ry) }
            return 2
        }
    }
    return 1
}
