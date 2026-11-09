package com.qwantarc.messenger.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The parts of a settings page, as designed. The iPhone app has the same (SettingsKit.swift). */
object Kit {
    @Composable fun cardLine(): Color = if (isDark()) Color(0xFF2A2A2F) else Color(0xFFE4E0D4)
    val OkGreen = Color(0xFF2E9E5B)
    val NotOk = Color(0xFFE5484D)
}

/**
 * A settings page: the grid ground, a glass header with "‹ You" and a
 * centred title, then the page's cards.
 */
@Composable
fun KitPage(title: String, onBack: () -> Unit, back: String = "You", content: @Composable ColumnScope.() -> Unit) {
    val t = tones()
    Box(Modifier.fillMaxSize()) {
        HomeGround(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth().background(t.home.copy(alpha = 0.9f)).statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            ) {
                Row(
                    Modifier.align(Alignment.CenterStart).height(44.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onBack)
                        .semantics { contentDescription = "Back to $back" }
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Glyphs.Back, contentDescription = null, tint = t.text, modifier = Modifier.size(22.dp))
                    Text(back, color = t.text, fontSize = 16.sp)
                }
                Text(
                    title,
                    color = t.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 84.dp).semantics { heading() },
                )
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** A small grey label over a card, with an optional value at the end. */
@Composable
fun KitLabel(text: String, trailing: String? = null) {
    val t = tones()
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 10.dp).semantics { heading() }) {
        Text(text, color = t.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The small print under a card. */
@Composable
fun KitFoot(text: String, color: Color? = null) {
    Text(
        text,
        color = color ?: tones().secondary,
        fontSize = 13.sp,
        lineHeight = 18.5.sp,
        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 6.dp),
    )
}

/** A white card with a hairline, and lines between its rows. */
@Composable
fun KitCard(radius: Dp = 20.dp, inset: Dp = 0.dp, content: @Composable () -> Unit) {
    Group(inset = inset, outer = 0.dp, radius = radius, content = content)
}

/** A white card with a hairline, holding one block of anything. */
@Composable
fun KitPanel(radius: Dp = 24.dp, padding: Dp = 14.dp, spacing: Dp = 10.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(tones().surface).border(1.dp, Kit.cardLine(), shape).padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A row's label, and a line under it. */
@Composable
private fun KitRowLabel(label: String, hint: String?, color: Color, modifier: Modifier) {
    val t = tones()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = color, fontSize = 16.sp)
        if (hint != null) Text(hint, color = t.secondary, fontSize = 13.sp, lineHeight = 17.5.sp)
    }
}

/** The one row every list row is: label, hint, and something at the end. */
@Composable
fun KitRow(
    label: String,
    hint: String? = null,
    color: Color? = null,
    modifier: Modifier = Modifier,
    end: @Composable () -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KitRowLabel(label, hint, color ?: tones().text, Modifier.weight(1f))
        end()
    }
}

/** A value at the end of a row. */
@Composable
fun KitValueText(value: String) {
    Text(value, color = tones().secondary, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
}

/** chevron.forward, at the end of a row that opens a page. */
@Composable
fun KitChevron() {
    Icon(Glyphs.Forward, contentDescription = null, tint = tones().secondary, modifier = Modifier.size(16.dp))
}

/** A row that does something: left-aligned, or centred for the last card. */
@Composable
fun KitButton(label: String, color: Color? = null, weight: FontWeight = FontWeight.Normal, centred: Boolean = false, height: Dp = 54.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val c = color ?: tones().text
    Box(
        Modifier.fillMaxWidth().heightIn(min = height).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = if (centred) Alignment.Center else Alignment.CenterStart,
    ) {
        Text(label, color = if (enabled) c else c.copy(alpha = 0.4f), fontSize = 16.sp, fontWeight = weight)
    }
}

/** The nine inks in one row; the chosen one ringed. */
@Composable
fun InkSwatches(ink: Ink, onPick: (Ink) -> Unit) {
    val dark = isDark()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Ink.entries.forEach { choice ->
            val ring by animateColorAsState(if (choice == ink) choice.bubble(dark) else Color.Transparent, tween(200), label = "ring")
            Box(
                Modifier.weight(1f).aspectRatio(1f).border(2.dp, ring, CircleShape).padding(5.dp).clip(CircleShape)
                    .background(choice.bubble(dark))
                    .clickable(role = Role.RadioButton) { onPick(choice) }
                    .semantics {
                        contentDescription = choice.label
                        selected = choice == ink
                    },
            )
        }
    }
}

/** A paper's chip: filled when chosen, with LIVE on the ones that move. */
@Composable
fun PaperChip(paper: Paper, selected: Boolean, onClick: () -> Unit) {
    val t = tones()
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier.height(38.dp).pressScale(source).clip(CircleShape)
            .background(if (selected) t.text else Color.Transparent)
            .border(1.dp, if (selected) t.text else t.line, CircleShape)
            .clickable(interactionSource = source, indication = null, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(paper.label, color = if (selected) t.ground else t.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (paper is Paper.Live) {
            Text(
                "LIVE",
                color = Color(0xFF17171A),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Palette.Marigold, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** The chips, wrapping onto more lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperChips(selected: Paper, onPick: (Paper) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Paper.entries.forEach { paper -> PaperChip(paper, paper == selected) { onPick(paper) } }
    }
}

/** A small status pill: a coloured dot and a word. */
@Composable
fun KitPill(text: String, dot: Color = Kit.OkGreen) {
    val t = tones()
    Row(
        Modifier.height(26.dp).background(t.ground, CircleShape).border(1.dp, t.line, CircleShape).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(text, color = t.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Space between cards that belong apart. */
@Composable
fun KitGap() = Spacer(Modifier.height(8.dp))
