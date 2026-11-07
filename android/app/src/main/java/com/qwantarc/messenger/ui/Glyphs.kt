package com.qwantarc.messenger.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's icons, drawn to look like the iPhone app's: thin round-ended
 * lines, with a filled form where the iPhone uses one (a chosen tab, send,
 * play). Each is named after the iPhone symbol it stands in for.
 */
internal object Glyphs {
    /** chevron.backward */
    val Back = glyph("Back") { line("M15,4.5 L7.5,12 L15,19.5", width = 2.2f) }

    /** chevron.forward */
    val Forward = glyph("Forward") { line("M9,4.5 L16.5,12 L9,19.5", width = 2.4f) }

    /** person */
    val Person = glyph("Person") {
        line(circle(12f, 8f, 3.8f))
        line("M4.8,20.5c0.6,-3.7 3.4,-6 7.2,-6s6.6,2.3 7.2,6z")
    }

    /** person.3 */
    val Group = glyph("Group") {
        line(circle(12f, 8.3f, 2.9f))
        line("M7,19.5c0.4,-3 2.4,-4.8 5,-4.8s4.6,1.8 5,4.8z")
        line(circle(5.6f, 9.6f, 2.1f))
        line("M1.8,18.2c0.3,-2.2 1.6,-3.5 3.6,-3.5c0.5,0 1,0.1 1.4,0.3")
        line(circle(18.4f, 9.6f, 2.1f))
        line("M22.2,18.2c-0.3,-2.2 -1.6,-3.5 -3.6,-3.5c-0.5,0 -1,0.1 -1.4,0.3")
    }

    /** photo.on.rectangle */
    val Photos = glyph("Photos") {
        line("M8,4.5h11a2,2 0 0 1 2,2v8.5a2,2 0 0 1 -2,2H8a2,2 0 0 1 -2,-2V6.5a2,2 0 0 1 2,-2z")
        line("M6,15.5 10.5,11 14,14.5 16,12.5 21,16.5")
        fill(circle(16.3f, 8.4f, 1.3f))
        line("M3,8.5v9.5a2.5,2.5 0 0 0 2.5,2.5H17")
    }

    /** video */
    val Video = glyph("Video") {
        line("M4.5,6.5h9a2,2 0 0 1 2,2v7a2,2 0 0 1 -2,2h-9a2,2 0 0 1 -2,-2v-7a2,2 0 0 1 2,-2z")
        line("M15.5,10.5 21.5,7v10l-6,-3.5")
    }

    /** arrowshape.turn.up.left */
    val Reply = glyph("Reply") {
        line("M9.5,5 3.5,11l6,6v-3.6c4.4,0 7.6,1.3 10.5,4.6c-0.8,-5 -3.8,-8.6 -10.5,-9.3z")
    }

    /** bubble.left.and.text.bubble.right */
    val Thread = glyph("Thread") {
        line("M5,8h7.5a2.5,2.5 0 0 1 2.5,2.5v4a2.5,2.5 0 0 1 -2.5,2.5H8.6L5,20V17a2.5,2.5 0 0 1 -2.5,-2.5v-4A2.5,2.5 0 0 1 5,8z")
        line("M9,8V6.5A2.5,2.5 0 0 1 11.5,4H19a2.5,2.5 0 0 1 2.5,2.5v4A2.5,2.5 0 0 1 19,13v3l-3,-3")
        line("M5.6,11.6H11.9M5.6,14H9.6", width = 1.4f)
    }

    /** doc.on.doc */
    val Copy = glyph("Copy") {
        line("M9,7h8.5a2,2 0 0 1 2,2v10a2,2 0 0 1 -2,2H9a2,2 0 0 1 -2,-2V9a2,2 0 0 1 2,-2z")
        line("M16.5,4.6A2,2 0 0 0 14.6,3H6.5a2,2 0 0 0 -2,2v10.1A2,2 0 0 0 6,17")
    }

    /** pencil */
    val Pencil = glyph("Pencil") { line("M16.2,4.3a2.1,2.1 0 0 1 3,3L8.4,18.1 4,19.6 5.5,15.2z M14.4,6.1 17.4,9.1") }

    /** trash */
    val Trash = glyph("Trash") {
        line("M4,6.5H20M9.5,6.5V4.8a1,1 0 0 1 1,-1h3a1,1 0 0 1 1,1v1.7")
        line("M6,6.5 7,19.2a1.8,1.8 0 0 0 1.8,1.6h6.4a1.8,1.8 0 0 0 1.8,-1.6L18,6.5")
        line("M10,10.5v6.5M14,10.5v6.5")
    }

    /** info.circle */
    val Info = glyph("Info") {
        line(circle(12f, 12f, 9f))
        fill(circle(12f, 7.9f, 1.2f))
        line("M12,11V16.6")
    }

    /** square.and.pencil */
    val Compose = glyph("Compose") {
        line("M11,4.5H6.5A2.5,2.5 0 0 0 4,7v10.5A2.5,2.5 0 0 0 6.5,20H17a2.5,2.5 0 0 0 2.5,-2.5V13")
        line("M18.2,3.7a1.9,1.9 0 0 1 2.7,2.7L12.6,14.7 9,15.6 9.9,12z")
    }

    /** camera */
    val Camera = glyph("Camera") {
        line("M3,8.7A2.4,2.4 0 0 1 5.4,6.3h2.3l1.6,-2.1h5.4l1.6,2.1h2.3A2.4,2.4 0 0 1 21,8.7v8.9a2.4,2.4 0 0 1 -2.4,2.4H5.4A2.4,2.4 0 0 1 3,17.6z")
        line(circle(12f, 13f, 3.6f))
    }

    /** bubble.left.and.bubble.right */
    val Chats = glyph("Chats") {
        line("M5,8h7.5a2.5,2.5 0 0 1 2.5,2.5v4a2.5,2.5 0 0 1 -2.5,2.5H8.6L5,20V17a2.5,2.5 0 0 1 -2.5,-2.5v-4A2.5,2.5 0 0 1 5,8z")
        line("M9,8V6.5A2.5,2.5 0 0 1 11.5,4H19a2.5,2.5 0 0 1 2.5,2.5v4A2.5,2.5 0 0 1 19,13v3l-3,-3")
    }

    /** bubble.left.and.bubble.right.fill */
    val ChatsFilled = glyph("ChatsFilled") {
        fill("M5,8h7.5a2.5,2.5 0 0 1 2.5,2.5v4a2.5,2.5 0 0 1 -2.5,2.5H8.6L5,20V17a2.5,2.5 0 0 1 -2.5,-2.5v-4A2.5,2.5 0 0 1 5,8z")
        fill("M11.5,3.5H19a3,3 0 0 1 3,3v4a3,3 0 0 1 -2.5,2.95V16.5L16.3,13.5V10.5A3.9,3.9 0 0 0 12.4,6.6H8.6V6.4A2.9,2.9 0 0 1 11.5,3.5z")
    }

    /** map */
    val Map = glyph("Map") {
        line("M3,6.6 9,4.2 15,6.6 21,4.2V17.4L15,19.8 9,17.4 3,19.8z")
        line("M9,4.2V17.4M15,6.6V19.8")
    }

    /** map.fill */
    val MapFilled = glyph("MapFilled") {
        fill("M2.5,6.5 8.3,4.1V17.6L2.5,20z")
        fill("M9.7,4.1 14.3,6V19.9L9.7,18z")
        fill("M15.7,6 21.5,3.6V17.5L15.7,19.9z")
    }

    /** xmark */
    val Close = glyph("Close") { line("M6.5,6.5 17.5,17.5M17.5,6.5 6.5,17.5", width = 2f) }

    /** paperclip */
    val Attach = glyph("Attach") {
        line("M19.5,11.6 12.3,18.8a4.8,4.8 0 0 1 -6.8,-6.8L12.9,4.6a3.2,3.2 0 0 1 4.5,4.5L10.1,16.4a1.6,1.6 0 0 1 -2.3,-2.3L14.6,7.3")
    }

    /** mic.fill */
    val Mic = glyph("Mic") {
        fill("M12,2.8a3.2,3.2 0 0 1 3.2,3.2v5.2a3.2,3.2 0 0 1 -6.4,0V6a3.2,3.2 0 0 1 3.2,-3.2z")
        line("M5.6,11a6.4,6.4 0 0 0 12.8,0M12,17.6V21")
    }

    /** arrow.up.circle.fill */
    val Send = glyph("Send") {
        fill(circle(12f, 12f, 10f) + "M12,6.4 17.1,11.5 15.5,13.1 13.2,10.8V17.6H10.8V10.8L8.5,13.1 6.9,11.5z", evenOdd = true)
    }

    /** play.fill */
    val Play = glyph("Play") {
        fill("M8,5.3v13.4c0,0.85 0.93,1.37 1.65,0.92l10.4,-6.7c0.66,-0.42 0.66,-1.4 0,-1.83L9.65,4.38C8.93,3.93 8,4.45 8,5.3z")
    }

    /** pause.fill */
    val Pause = glyph("Pause") {
        fill("M7.2,4.5h2.3a1,1 0 0 1 1,1v13a1,1 0 0 1 -1,1H7.2a1,1 0 0 1 -1,-1v-13a1,1 0 0 1 1,-1z")
        fill("M14.5,4.5h2.3a1,1 0 0 1 1,1v13a1,1 0 0 1 -1,1h-2.3a1,1 0 0 1 -1,-1v-13a1,1 0 0 1 1,-1z")
    }

    /** arrow.down.circle */
    val Download = glyph("Download") {
        line(circle(12f, 12f, 9f))
        line("M12,7.5V16.3M8.2,12.6 12,16.4 15.8,12.6")
    }

    /** doc */
    val Doc = glyph("Doc") {
        line("M7,3h6.6L19,8.4V19a2,2 0 0 1 -2,2H7a2,2 0 0 1 -2,-2V5a2,2 0 0 1 2,-2z")
        line("M13.4,3.2V8.6H18.8")
    }

    /** magnifyingglass */
    val Search = glyph("Search") {
        line(circle(10.5f, 10.5f, 6.5f))
        line("M15.3,15.3 20.5,20.5", width = 2f)
    }

    /** square.and.arrow.up */
    val Share = glyph("Share") {
        line("M8.5,9.5H7.2a2,2 0 0 0 -2,2v7.3a2,2 0 0 0 2,2h9.6a2,2 0 0 0 2,-2v-7.3a2,2 0 0 0 -2,-2H15.5")
        line("M12,2.8V14.5M8.5,6.3 12,2.8 15.5,6.3")
    }

    /** exclamationmark.triangle */
    val Warning = glyph("Warning") {
        line("M10.3,4.3a2,2 0 0 1 3.4,0l7.5,13.1a2,2 0 0 1 -1.7,3H4.5a2,2 0 0 1 -1.7,-3z")
        line("M12,9.4V13.8")
        fill(circle(12f, 16.9f, 1.15f))
    }

    /** checkmark.circle.fill */
    val Checked = glyph("Checked") {
        fill(circle(12f, 12f, 10f) + "M6.9,12.5 8.4,11 10.7,13.3 15.6,8.4 17.1,9.9 10.7,16.3z", evenOdd = true)
    }

    /** person.crop.circle */
    val Profile = glyph("Profile") {
        line(circle(12f, 12f, 9.5f))
        line(circle(12f, 10f, 3.3f))
        line("M6.1,18.9c1.3,-2.3 3.4,-3.6 5.9,-3.6s4.6,1.3 5.9,3.6")
    }

    /** person.2.fill */
    val People = glyph("People") {
        fill(circle(9f, 8.2f, 3.4f))
        fill("M2.5,19.5c0,-3.5 2.9,-5.7 6.5,-5.7s6.5,2.2 6.5,5.7z")
        fill(circle(16.6f, 8.8f, 2.7f))
        fill("M16.9,19.5c0,-1.9 -0.6,-3.4 -1.7,-4.6c0.5,-0.15 1,-0.2 1.6,-0.2c2.9,0 4.9,1.8 4.9,4.8z")
    }

    /** key */
    val Key = glyph("Key") {
        line(circle(7.8f, 12f, 4.3f))
        line("M12.1,12H21V15.2M17.6,12V14.6")
    }

    /** lock */
    val Lock = glyph("Lock") {
        line("M6.6,10.6h10.8a1.6,1.6 0 0 1 1.6,1.6v7.2a1.6,1.6 0 0 1 -1.6,1.6H6.6A1.6,1.6 0 0 1 5,19.4V12.2a1.6,1.6 0 0 1 1.6,-1.6z")
        line("M8,10.6V7.5a4,4 0 0 1 8,0v3.1")
    }

    /** bell */
    val Bell = glyph("Bell") {
        line("M12,3.4a5.6,5.6 0 0 0 -5.6,5.6v3.6L4.4,16.6H19.6L17.6,12.6V9A5.6,5.6 0 0 0 12,3.4z")
        line("M9.8,19.4a2.2,2.2 0 0 0 4.4,0")
    }

    /** location.fill */
    val Location = glyph("Location") { fill("M20.6,3.4 3.8,10.9a0.6,0.6 0 0 0 0.1,1.1l7,1.1 1.1,7a0.6,0.6 0 0 0 1.1,0.1z") }

    /** arrow.triangle.turn.up.right.circle.fill */
    val Directions = glyph("Directions") {
        fill(
            circle(12f, 12f, 10f) + "M7.6,16.8V12.4a2,2 0 0 1 2,-2h4.2V7.8l3.8,3.6 -3.8,3.6v-2.5H9.9v4.3z",
            evenOdd = true,
        )
    }

    /** face.smiling */
    val Smile = glyph("Smile") {
        line(circle(12f, 12f, 9f))
        fill(circle(9f, 10f, 1.15f))
        fill(circle(15f, 10f, 1.15f))
        line("M8.3,14.2a4.4,4.4 0 0 0 7.4,0")
    }

    private class Builder(val b: ImageVector.Builder) {
        fun line(path: String, width: Float = 1.7f) {
            b.addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }

        fun fill(path: String, evenOdd: Boolean = false) {
            b.addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = SolidColor(Color.Black),
                pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            )
        }
    }

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r},${cy}a$r,$r 0 1 0 ${2 * r},0a$r,$r 0 1 0 ${-2 * r},0z"

    private fun glyph(name: String, draw: Builder.() -> Unit): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        Builder(b).draw()
        return b.build()
    }
}
