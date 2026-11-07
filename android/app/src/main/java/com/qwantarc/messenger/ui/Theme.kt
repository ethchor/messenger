package com.qwantarc.messenger.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.qwantarc.messenger.core.PersonProfile

// The look of the app: inks to colour it, papers to put behind chats, and
// drawn avatars (see AvatarArt.kt). The inks are deep, like fountain pen ink,
// so white text reads well on the bubbles you send.

/**
 * The colours to choose from. Yours tints the app, and others see your
 * avatar in it. [light] and [dark] are for lines and buttons in each mode;
 * [darkBubble] is your bubbles in dark mode. Each passes 5:1 or better.
 */
enum class Ink(val id: String, val label: String, val light: Color, val dark: Color, val darkBubble: Color) {
    BlueBlack("blue-black", "Blue-black", Color(0xFF26457E), Color(0xFF9BB4E6), Color(0xFF2E4F8A)),
    Teal("teal", "Teal", Color(0xFF0B6E6E), Color(0xFF6CCFCB), Color(0xFF0F6767)),
    Forest("forest", "Forest", Color(0xFF2D6A39), Color(0xFF8ACF97), Color(0xFF2B6436)),
    Oxblood("oxblood", "Oxblood", Color(0xFF872235), Color(0xFFEE9AAA), Color(0xFF7E2434)),
    Aubergine("aubergine", "Aubergine", Color(0xFF5C3886), Color(0xFFC3A6EC), Color(0xFF583882)),
    Saffron("saffron", "Saffron", Color(0xFF8A5A00), Color(0xFFEDC15A), Color(0xFF7A5100)),
    Sepia("sepia", "Sepia", Color(0xFF6A4A2D), Color(0xFFD9B58F), Color(0xFF684A2E)),
    Graphite("graphite", "Graphite", Color(0xFF3E4550), Color(0xFFC5CBD4), Color(0xFF3D444E)),
    ;

    /** Lines, buttons and links. */
    fun color(dark: Boolean) = if (dark) this.dark else light

    /** The bubbles you send, with white text on them. */
    fun bubble(dark: Boolean) = if (dark) darkBubble else light

    /** A pale wash, behind selected things. */
    fun wash(dark: Boolean) = if (dark) this.dark.copy(alpha = 0.2f) else light.copy(alpha = 0.12f)

    /** The pale circle behind someone's avatar. The same in dark mode, like a photo would be. */
    val pastel: Color
        get() = when (this) {
            BlueBlack -> Color(0xFFDCE6FA)
            Teal -> Color(0xFFD2F0EC)
            Forest -> Color(0xFFDCF1DA)
            Oxblood -> Color(0xFFF9DDE3)
            Aubergine -> Color(0xFFEADFF8)
            Saffron -> Color(0xFFFCEFC6)
            Sepia -> Color(0xFFF3E6D5)
            Graphite -> Color(0xFFE3E6EB)
        }

    companion object {
        fun byId(id: String?): Ink? = entries.firstOrNull { it.id == id }

        /** A steady ink for someone who hasn't picked one. */
        fun forName(name: String): Ink = entries[name.sumOf { it.code } % entries.size]
    }
}

/** What goes behind a chat, drawn in the ink. */
sealed class Paper(val id: String, val label: String) {
    data object Plain : Paper("plain", "Plain")
    data object Dots : Paper("dots", "Dots")
    data object Ruled : Paper("ruled", "Ruled")
    data object Grid : Paper("grid", "Grid")
    data object Doodles : Paper("doodles", "Doodles")
    data object Wash : Paper("wash", "Ink wash")

    /** A tile of nature doodles. Stored as "wallpaper:<name>", so the older papers keep their ids. */
    data class Wall(val wallpaper: Wallpaper) : Paper("wallpaper:" + wallpaper.id, wallpaper.title)

    companion object {
        val patterns: List<Paper> by lazy { listOf(Plain, Dots, Ruled, Grid, Doodles, Wash) }
        val wallpapers: List<Paper> by lazy { Wallpaper.entries.map { Wall(it) } }
        val entries: List<Paper> by lazy { patterns + wallpapers }

        fun byId(id: String?): Paper? = entries.firstOrNull { it.id == id }
    }
}

/** Bubbles from others. Opaque, so chat paper doesn't show through text. */
fun incomingBubble(dark: Boolean) = if (dark) Color(0xFF2B2E34) else Color(0xFFEDEFF3)

/**
 * This device's choices: the ink, the paper behind chats, and papers picked
 * for single chats. The ink is also shared, through the profile.
 */
class Appearance(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    var ink by mutableStateOf(Ink.byId(prefs.getString(KEY_INK, null)) ?: Ink.BlueBlack)
        private set
    var paper by mutableStateOf(Paper.byId(prefs.getString(KEY_PAPER, null)) ?: Paper.Plain)
        private set
    var chatPapers by mutableStateOf(loadChatPapers())
        private set

    fun changeInk(value: Ink) {
        ink = value
        prefs.edit().putString(KEY_INK, value.id).apply()
    }

    fun changePaper(value: Paper) {
        paper = value
        prefs.edit().putString(KEY_PAPER, value.id).apply()
    }

    fun paperFor(conversationId: String): Paper = chatPapers[conversationId] ?: paper

    /** A paper for one chat, or null to follow the default. */
    fun changeChatPaper(conversationId: String, value: Paper?) {
        chatPapers = if (value == null) chatPapers - conversationId else chatPapers + (conversationId to value)
        prefs.edit().putStringSet(KEY_CHAT_PAPERS, chatPapers.map { (id, p) -> "$id=${p.id}" }.toSet()).apply()
    }

    /** Back to the start, when the account leaves this device. */
    fun reset() {
        prefs.edit().clear().apply()
        ink = Ink.BlueBlack
        paper = Paper.Plain
        chatPapers = emptyMap()
    }

    private fun loadChatPapers(): Map<String, Paper> =
        prefs.getStringSet(KEY_CHAT_PAPERS, emptySet()).orEmpty().mapNotNull { entry ->
            val (id, paperId) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            Paper.byId(paperId)?.let { id to it }
        }.toMap()

    private companion object {
        const val KEY_INK = "ink"
        const val KEY_PAPER = "paper"
        const val KEY_CHAT_PAPERS = "chatPapers"
    }
}

val LocalAppearance = staticCompositionLocalOf<Appearance> { error("MessengerTheme provides the appearance") }

/** Everyone's avatar and ink we know of, by username. */
val LocalProfiles = compositionLocalOf<Map<String, PersonProfile>> { emptyMap() }

/** Someone's ink: the one they picked, or a steady one from their name. */
fun inkFor(username: String, profiles: Map<String, PersonProfile>): Ink =
    Ink.byId(profiles[username]?.ink) ?: Ink.forName(username)

@Composable
fun MessengerTheme(appearance: Appearance, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = appearance.ink
    val colors = if (dark) {
        darkColorScheme(
            primary = ink.dark,
            onPrimary = Color(0xFF111318),
            primaryContainer = ink.darkBubble,
            onPrimaryContainer = Color.White,
            secondary = lerp(Color(0xFFC6C8CE), ink.dark, 0.25f),
            surfaceVariant = Color(0xFF2B2E34),
        )
    } else {
        lightColorScheme(
            primary = ink.light,
            onPrimary = Color.White,
            primaryContainer = lerp(Color.White, ink.light, 0.14f),
            onPrimaryContainer = ink.light,
            secondary = lerp(Color(0xFF5B5E66), ink.light, 0.25f),
            surfaceVariant = Color(0xFFEDEFF3),
        )
    }
    CompositionLocalProvider(LocalAppearance provides appearance) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

/** A chat's paper, filling [modifier]. */
@Composable
fun PaperBackground(paper: Paper, ink: Ink, modifier: Modifier = Modifier, tileScale: Float = 1f) {
    val dark = isSystemInDarkTheme()
    val inkColor = ink.color(dark)
    val base = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        drawRect(base)
        val line = inkColor.copy(alpha = if (dark) 0.22f else 0.16f)
        when (paper) {
            Paper.Plain -> Unit
            Paper.Wash -> drawRect(inkColor.copy(alpha = if (dark) 0.12f else 0.07f))
            Paper.Dots -> {
                val gap = 18.dp.toPx()
                val radius = 1.1.dp.toPx()
                var x = gap / 2
                while (x < size.width) {
                    var y = gap / 2
                    while (y < size.height) {
                        drawCircle(line, radius, Offset(x, y))
                        y += gap
                    }
                    x += gap
                }
            }
            Paper.Ruled -> {
                val gap = 28.dp.toPx()
                var y = gap
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), 0.75.dp.toPx())
                    y += gap
                }
            }
            Paper.Grid -> {
                val gap = 22.dp.toPx()
                val width = 0.5.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), width)
                    x += gap
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), width)
                    y += gap
                }
            }
            Paper.Doodles -> {
                val art = Artwork.PaperDoodles
                val tile = 120.dp.toPx()
                val factor = tile / art.width
                var x = 0f
                while (x < size.width) {
                    var y = 0f
                    while (y < size.height) {
                        translate(x, y) {
                            scale(factor, factor, pivot = Offset.Zero) {
                                for (stroke in art.strokes) {
                                    drawPath(stroke.path, line, style = Stroke(stroke.width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                }
                            }
                        }
                        y += tile
                    }
                    x += tile
                }
            }
            is Paper.Wall -> {
                // Motifs cross the tile's edges and carry on into the next
                // tile, so tiling starts a tile up and to the left.
                val tile = 180.dp.toPx() * tileScale
                val factor = tile / Wallpaper.SIDE
                val pen = Stroke(1.2.dp.toPx() * maxOf(tileScale, 0.6f) / factor, cap = StrokeCap.Round, join = StrokeJoin.Round)
                var x = -tile
                while (x < size.width) {
                    var y = -tile
                    while (y < size.height) {
                        translate(x, y) {
                            scale(factor, factor, pivot = Offset.Zero) {
                                for (stroke in paper.wallpaper.strokes) {
                                    if (stroke.kind == StrokeKind.Fill) drawPath(stroke.path, line) else drawPath(stroke.path, line, style = pen)
                                }
                            }
                        }
                        y += tile
                    }
                    x += tile
                }
            }
        }
    }
}
