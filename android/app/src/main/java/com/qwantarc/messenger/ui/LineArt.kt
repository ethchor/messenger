package com.qwantarc.messenger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
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

enum class StrokeKind {
    /** A pen line. */
    Stroke,

    /** Filled with the ink (eyes, dots), and stroked if the width is above 0. */
    Fill,

    /** Filled with the paper colour to hide what's behind, then stroked. */
    Knockout,
}

/**
 * One line of a drawing in [Artwork], as scripts/artwork.py writes it:
 * absolute M, L, C and Z commands in the drawing's own units.
 */
class ArtworkStroke(val kind: StrokeKind, val data: String, val width: Float, val dash: List<Float>) {
    val path: Path by lazy { PathParser().parsePathString(data).toPath() }
}

/**
 * A drawing from [Artwork] in a single pen line, in [ink], scaled to fit.
 * [paper] paints knocked-out shapes so lines behind them disappear; match the
 * colour behind the drawing.
 */
@Composable
fun LineArt(
    artwork: Artwork,
    modifier: Modifier = Modifier,
    ink: Color = LocalContentColor.current,
    paper: Color = MaterialTheme.colorScheme.surface,
) {
    Canvas(modifier.aspectRatio(artwork.width / artwork.height)) {
        val factor = min(size.width / artwork.width, size.height / artwork.height)
        translate((size.width - artwork.width * factor) / 2f, (size.height - artwork.height * factor) / 2f) {
            scale(factor, factor, pivot = Offset.Zero) {
                for (line in artwork.strokes) {
                    val style = Stroke(
                        width = line.width,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = if (line.dash.isEmpty()) null else PathEffect.dashPathEffect(line.dash.toFloatArray()),
                    )
                    when (line.kind) {
                        StrokeKind.Stroke -> drawPath(line.path, ink, style = style)
                        StrokeKind.Fill -> {
                            drawPath(line.path, ink)
                            if (line.width > 0f) drawPath(line.path, ink, style = style)
                        }
                        StrokeKind.Knockout -> {
                            drawPath(line.path, paper)
                            if (line.width > 0f) drawPath(line.path, ink, style = style)
                        }
                    }
                }
            }
        }
    }
}
