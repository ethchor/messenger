package com.qwantarc.messenger.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import com.qwantarc.messenger.R
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// The Balanced design's colours, type and pieces. The iPhone and Mac app
// draws the same ones (Theme.swift), so both look and behave alike.

/**
 * The colours of the app beyond the inks: a warm ground, a dark sheet that
 * chats sit on in both modes, and one bright accent, marigold, for the things
 * you do (send, unread, the chosen tab, primary buttons).
 */
object Palette {
    /** The yellow of send, unread and the chosen tab. */
    val Marigold = Color(0xFFFFD23F)
    /** Dark text, on marigold and on light pills. */
    val Ink = Color(0xFF141414)
    /** The cream of chips and the composer's field, in both modes. */
    val Cream = Color(0xFFF7F5EE)
    val VanishSheet = Color(0xFF0E0B16)
    val VanishHome = Color(0xFF07060B)
    val VanishAccent = Color(0xFFC9BDFF)
    /** Toggles that are on, and voice messages. */
    val Green = Color(0xFF5DC584)
    val TileYellow = Color(0xFFFFD23F)
    val TilePeach = Color(0xFFFFB48A)
    val TileLavender = Color(0xFFC9BDFF)
    val TileMint = Color(0xFF9EDCB4)
    val TileApricot = Color(0xFFFFD9A8)
    val TileSky = Color(0xFFB9D3FF)
    val TilePink = Color(0xFFF4A6D0)
}

/** The colours that change with dark mode. */
class Tones(dark: Boolean) {
    val home = if (dark) Color(0xFF0F0F11) else Color(0xFFF7F5EE)
    val homeGrid = if (dark) Color(0xFF17171B) else Color(0xFFECE9DE)
    val ground = if (dark) Color(0xFF0F0F11) else Color(0xFFFAF9F6)
    val surface = if (dark) Color(0xFF18181B) else Color(0xFFFFFFFF)
    val text = if (dark) Color(0xFFF3F2EE) else Color(0xFF141414)
    val secondary = if (dark) Color(0xFFA3A1A9) else Color(0xFF4A4840)
    val line = if (dark) Color(0xFF2A2A2F) else Color(0xFFE8E6DF)
    val danger = if (dark) Color(0xFFFF8A7A) else Color(0xFFB42318)
    /** Unread badges: dark on light, marigold on dark. */
    val badge = if (dark) Palette.Marigold else Palette.Ink
    val onBadge = if (dark) Palette.Ink else Palette.Marigold
    /** The floating tab bar. */
    val pill = if (dark) Color(0xFF1F1F23) else Color(0xFF141414)
    val pillLine = if (dark) Color(0xFF2E2E33) else Color(0xFF141414)
    val idleTab = if (dark) Color(0xFFA3A1A9) else Color(0xFFCFCCC2)
    /** Outlines on stamps (avatars) and buttons in light mode; none in dark. */
    val stampLine = if (dark) Color.Transparent else Palette.Ink
    val typing = if (dark) Color(0xFF5DD08A) else Color(0xFF2E9E5B)
}

@Composable
fun tones(): Tones {
    val dark = isDark()
    return remember(dark) { Tones(dark) }
}

/** The sheet messages sit on: near-black in both modes. Follows the system, not a forced dark. */
@Composable
fun sheetColor(): Color = if (isSystemInDarkTheme()) Color(0xFF1A1A1D) else Color(0xFF141414)

/** Bricolage Grotesque (bundled, OFL): one variable font, two weights. */
@OptIn(ExperimentalTextApi::class)
val Bricolage = FontFamily(
    Font(R.font.bricolage_grotesque, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.bricolage_grotesque, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Screen titles and names: Bricolage, tight. 600, or 700 for the big ones. */
fun displayStyle(size: TextUnit, bold: Boolean = false) = TextStyle(
    fontFamily = Bricolage,
    fontSize = size,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold,
    letterSpacing = (-0.022).em,
    lineHeight = size * 1.06f,
)

/** The chat list's and You's ground: warm paper under a faint square grid. */
@Composable
fun HomeGround(modifier: Modifier = Modifier, vanish: Boolean = false) {
    val t = tones()
    if (vanish) {
        Box(modifier.background(Palette.VanishHome))
        return
    }
    Canvas(modifier.background(t.home)) {
        val gap = 28.dp.toPx()
        val width = 1.dp.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(t.homeGrid, Offset(x, 0f), Offset(x, size.height), width)
            x += gap
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(t.homeGrid, Offset(0f, y), Offset(size.width, y), width)
            y += gap
        }
    }
}

/** Shrinks a little while pressed. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, tween(140), label = "press")
    return this.scale(scale)
}

/** A round 46 dp button: outlined, or filled marigold with an outline. */
@Composable
fun RoundButton(icon: ImageVector, label: String, filled: Boolean = false, size: Int = 46, onClick: () -> Unit) {
    val t = tones()
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier
            .pressScale(source)
            .size(size.dp)
            .clip(CircleShape)
            .background(if (filled) Palette.Marigold else Color.Transparent)
            .border(1.5.dp, if (filled) Palette.Ink else t.text, CircleShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Palette.Ink else t.text, modifier = Modifier.size((size * 0.42).dp))
    }
}

/** An outlined chip; filled with the text colour when selected. */
@Composable
fun FilterChip(title: String, selected: Boolean, count: Int = 0, onClick: () -> Unit) {
    val t = tones()
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(source)
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) t.text else Color.Transparent)
            .border(1.5.dp, t.text, RoundedCornerShape(50))
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, color = if (selected) t.home else t.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (count > 0 && !selected) {
            Box(
                Modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).background(Palette.Marigold, CircleShape).padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("$count", color = Palette.Ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold, lineHeight = 12.sp)
            }
        }
    }
}

/** An unread count: dark with marigold text, or marigold in dark mode. */
@Composable
fun UnreadBadge(count: Int, muted: Boolean = false) {
    val t = tones()
    Box(
        Modifier
            .defaultMinSize(minWidth = 24.dp, minHeight = 24.dp)
            .background(if (muted) t.line else t.badge, CircleShape)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = if (muted) t.secondary else t.onBadge,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 12.sp,
        )
    }
}

/** A rounded tile behind a settings icon. */
@Composable
fun IconTile(icon: ImageVector, tint: Color) {
    Box(Modifier.size(36.dp).background(tint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Palette.Ink, modifier = Modifier.size(19.dp))
    }
}

/** The primary button: a marigold capsule with dark text. */
@Composable
fun MarigoldButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val t = tones()
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(source)
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(50))
            .background(Palette.Marigold.copy(alpha = if (enabled) 1f else 0.45f))
            .border(1.5.dp, t.stampLine, RoundedCornerShape(50))
            .clickable(enabled = enabled, interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.Ink.copy(alpha = if (enabled) 1f else 0.6f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The marigold switch of the design. */
@Composable
fun marigoldSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedTrackColor = Color(0xFFFFC93C),
    checkedThumbColor = Color.White,
    checkedBorderColor = Color.Transparent,
)

/**
 * The switch, as on the iPhone: a 51 × 31 track, marigold when on, with a
 * white thumb that springs across. [onCheckedChange] null: the row handles taps.
 */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val dark = isDark()
    val x by androidx.compose.animation.core.animateDpAsState(
        if (checked) 20.dp else 0.dp,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
        label = "thumb",
    )
    val track by androidx.compose.animation.animateColorAsState(
        if (checked) Color(0xFFFFC93C) else if (dark) Color(0xFF3A3A40) else Color(0xFFE3E1DA),
        tween(220), label = "track",
    )
    Box(
        modifier
            .size(51.dp, 31.dp)
            .graphicsLayerAlpha(if (enabled) 1f else 0.5f)
            .clip(CircleShape)
            .background(track)
            .then(
                if (onCheckedChange != null) Modifier.clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
                else Modifier,
            )
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .padding(start = x)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

private fun Modifier.graphicsLayerAlpha(a: Float) = this.then(Modifier.alpha(a))

/** The segmented control, as on the iPhone: a sliding white thumb on a grey track. */
@Composable
fun IosSegmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val dark = isDark()
    val track = if (dark) Color(0xFF2A2A2F) else Color(0xFFE9E6DC)
    val thumb = if (dark) Color(0xFF4A4A52) else Color.White
    val text = if (dark) Color(0xFFF3F2EE) else Color(0xFF141414)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(track).padding(2.dp),
    ) {
        val w = maxWidth / labels.size
        val x by androidx.compose.animation.core.animateDpAsState(
            w * selected,
            androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow),
            label = "segment",
        )
        Box(Modifier.padding(start = x).width(w).fillMaxHeight().shadow(3.dp, RoundedCornerShape(8.dp)).background(thumb, RoundedCornerShape(8.dp)))
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(i) }
                        .semantics { this.selected = i == selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, fontSize = 13.5.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Medium, color = text)
                }
            }
        }
    }
}
