package com.qwantarc.messenger.ui

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser

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
