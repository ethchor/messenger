package com.qwantarc.messenger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.min

/**
 * One shape of an avatar drawing in [AvatarDrawing], as scripts/artwork.py
 * writes it: path data in a 100-unit square, a fill and an outline (ARGB, or
 * null for none).
 */
class AvatarShape(val data: String, val fill: Long?, val stroke: Long?, val width: Float) {
    val path: Path by lazy { PathParser().parsePathString(data).toPath() }
}

/** The drawing someone's profile names ("fox"), if it's one we have. */
fun avatarDrawing(name: String?): AvatarDrawing? = if (name.isNullOrEmpty()) null else AvatarDrawing.byId(name)

/**
 * An avatar drawing on a circle of [background], cropped round: the drawings
 * run off the edge on purpose.
 */
@Composable
fun AvatarArt(drawing: AvatarDrawing, background: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1f)) {
        val side = min(size.width, size.height)
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        val circle = Path().apply { addOval(Rect(left, top, left + side, top + side)) }
        clipPath(circle) {
            drawPath(circle, background)
            val factor = side / 100f
            translate(left, top) {
                scale(factor, factor, pivot = Offset.Zero) {
                    for (shape in drawing.shapes) {
                        shape.fill?.let { drawPath(shape.path, Color(it)) }
                        if (shape.stroke != null && shape.width > 0f) {
                            drawPath(
                                shape.path,
                                Color(shape.stroke),
                                style = Stroke(width = shape.width, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }
                }
            }
        }
    }
}
