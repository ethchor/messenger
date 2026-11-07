package com.qwantarc.messenger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.min

/**
 * One shape of a scene in [Illustration], as scripts/artwork.py writes it:
 * path data in the drawing's own units, a fill and an outline (ARGB, or null
 * for none), and its dashes. The [backdrop] is painted in the pastel of the ink.
 */
class IllustrationShape(
    val data: String,
    val fill: Long?,
    val stroke: Long?,
    val width: Float,
    val dash: List<Float> = emptyList(),
    val backdrop: Boolean = false,
) {
    val path: Path by lazy { PathParser().parsePathString(data).toPath() }
}

/**
 * A scene from [Illustration] in its own colours, scaled to fit, on a backdrop
 * in the pastel of the ink. Like the avatars, it's the same in dark mode.
 */
@Composable
fun IllustrationArt(illustration: Illustration, modifier: Modifier = Modifier) {
    val backdrop = LocalAppearance.current.ink.pastel
    Canvas(modifier.aspectRatio(illustration.width / illustration.height)) {
        val factor = min(size.width / illustration.width, size.height / illustration.height)
        translate((size.width - illustration.width * factor) / 2f, (size.height - illustration.height * factor) / 2f) {
            scale(factor, factor, pivot = Offset.Zero) {
                for (shape in illustration.shapes) {
                    if (shape.backdrop) {
                        drawPath(shape.path, backdrop)
                    } else {
                        shape.fill?.let { drawPath(shape.path, Color(it)) }
                    }
                    if (shape.stroke != null && shape.width > 0f) {
                        drawPath(
                            shape.path,
                            Color(shape.stroke),
                            style = Stroke(
                                width = shape.width,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = if (shape.dash.isEmpty()) null else PathEffect.dashPathEffect(shape.dash.toFloatArray()),
                            ),
                        )
                    }
                }
            }
        }
    }
}
