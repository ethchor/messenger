package com.qwantarc.messenger.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qwantarc.messenger.AppViewModel
import com.qwantarc.messenger.UiState

/** You → Chats and appearance: theme, ink and paper, as designed (LookPage on the iPhone). */
@Composable
fun ColumnScope.LookPage(state: UiState, model: AppViewModel) {
    val appearance = LocalAppearance.current
    val avatar = avatarDrawing(state.myProfile?.avatar)
    val t = tones()
    KitLabel("Theme")
    IosSegmented(ThemeChoice.entries.map { it.label }, ThemeChoice.entries.indexOf(appearance.theme), large = true) {
        appearance.changeTheme(ThemeChoice.entries[it])
    }
    KitLabel("Your ink · tints your bubbles, and how others see your avatar")
    Box(Modifier.padding(vertical = 8.dp, horizontal = 2.dp)) {
        InkSwatches(appearance.ink) { model.setProfile(avatar, it) }
    }
    KitLabel("Chat paper")
    ChatPreview(appearance.paper, appearance.ink)
    PaperChips(appearance.paper) { appearance.changePaper(it) }
    Text(
        "Live papers move gently and ripple when a message comes or goes. Pick a different paper for one chat from its details.",
        color = t.secondary,
        fontSize = 13.sp,
        lineHeight = 18.5.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/** You → Profile: how everyone sees you, the avatar style, and your colour. */
@Composable
fun ColumnScope.ProfilePage(state: UiState, model: AppViewModel) {
    val appearance = LocalAppearance.current
    val avatar = avatarDrawing(state.myProfile?.avatar)
    val t = tones()
    ProfileHero(state.account?.username ?: "")
    AvatarStylePicker(state, model)
    KitPanel(spacing = 12.dp) {
        KitLabelRow("Your colour", appearance.ink.label)
        InkSwatches(appearance.ink) { model.setProfile(avatar, it) }
        Text("Tints your bubbles and your profile card. Friends see your avatar in it too.", color = t.secondary, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun KitLabelRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = tones().secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, color = tones().text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The card at the top of Profile: your avatar big, your name, and four shapes. */
@Composable
private fun ProfileHero(username: String) {
    val ink = LocalAppearance.current.ink
    val card by androidx.compose.animation.animateColorAsState(
        if (ink == Ink.Marigold) Palette.Marigold else ink.pastel,
        androidx.compose.animation.core.tween(300),
        label = "card",
    )
    var boing by remember { mutableStateOf(0) }
    val bounce = remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(boing) {
        if (boing == 0) return@LaunchedEffect
        bounce.animateTo(0.86f, androidx.compose.animation.core.tween(90))
        bounce.animateTo(1.1f, androidx.compose.animation.core.tween(120))
        bounce.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f))
    }
    val shape = RoundedCornerShape(30.dp)
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(shape).background(card)
                .drawBehind {
                    // Dots that fade in away from the avatar.
                    val gap = 18.dp.toPx()
                    val r = 1.6.dp.toPx()
                    val cx = size.width / 2
                    val cy = size.height * 0.42f
                    val far = maxOf(size.width, size.height) * 0.6f
                    var x = gap / 2
                    while (x < size.width) {
                        var y = gap / 2
                        while (y < size.height) {
                            val d = kotlin.math.hypot(x - cx, y - cy) / far
                            val fade = ((d - 0.3f) / 0.45f).coerceIn(0f, 1f)
                            if (fade > 0f) drawCircle(Palette.Ink.copy(alpha = 0.11f * fade), r, androidx.compose.ui.geometry.Offset(x, y))
                            y += gap
                        }
                        x += gap
                    }
                }
                .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .graphicsLayer { scaleX = bounce.value; scaleY = bounce.value }
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { boing++ }
                    .semantics { contentDescription = "Your avatar" },
            ) {
                Box(Modifier.padding(top = 7.dp).size(120.dp).background(Palette.Ink, RoundedCornerShape(38.dp)))
                Box(Modifier.size(120.dp).background(Color.White, RoundedCornerShape(38.dp)).border(2.dp, Palette.Ink, RoundedCornerShape(38.dp))) {
                    Avatar(username, size = 120, stamp = true)
                }
            }
            Text("@$username", style = displayStyle(28.sp, bold = true), color = Palette.Ink, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
            Text("This is how everyone sees you", color = Palette.Ink, fontSize = 13.5.sp)
        }
        ShapeSticker(ShapeKind.Heart, Modifier.align(Alignment.TopEnd).padding(top = 18.dp, end = 22.dp), size = 48.dp, delayMs = 80, tappable = true)
        ShapeSticker(ShapeKind.Sparkle, Modifier.align(Alignment.TopStart).padding(top = 30.dp, start = 26.dp), size = 32.dp, delayMs = 180, tappable = true)
        ShapeSticker(ShapeKind.Clover, Modifier.align(Alignment.BottomStart).padding(bottom = 28.dp, start = 40.dp), size = 36.dp, delayMs = 280, tappable = true)
        ShapeSticker(ShapeKind.Bolt, Modifier.align(Alignment.BottomEnd).padding(bottom = 40.dp, end = 44.dp), size = 26.dp, delayMs = 360, phase = -1f, tappable = true)
    }
}

/** Two bubbles on the paper, in the ink: what a chat will look like. */
@Composable
private fun ChatPreview(paper: Paper, ink: Ink) {
    val t = tones()
    // Chats sit on the dark sheet, so the preview does too.
    androidx.compose.runtime.CompositionLocalProvider(LocalForceDark provides true) {
        val shape = RoundedCornerShape(20.dp)
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(shape).border(1.dp, t.line, shape)
                .semantics { contentDescription = "Preview of a chat on ${paper.label} paper" },
            contentAlignment = Alignment.BottomCenter,
        ) {
            PaperBackground(paper, ink, Modifier.matchParentSize())
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Bubble("Kaia at 8:30?", incomingBubble(true), Palette.Cream, outgoing = false)
                    Spacer(Modifier.weight(1f).widthIn(min = 40.dp))
                }
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f).widthIn(min = 40.dp))
                    Bubble("Booked. Window table", ink.bubble(true), ink.onBubble, outgoing = true)
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, color: Color, textColor: Color, outgoing: Boolean) {
    val shape = if (outgoing) RoundedCornerShape(16.dp, 16.dp, 5.dp, 16.dp) else RoundedCornerShape(16.dp, 16.dp, 16.dp, 5.dp)
    Text(text, color = textColor, fontSize = 14.sp, modifier = Modifier.background(color, shape).padding(horizontal = 11.dp, vertical = 7.dp))
}

/** One avatar to pick, ringed in the ink when it's yours. */
@Composable
private fun Choice(selected: Boolean, label: String, ink: Ink, onClick: () -> Unit, content: @Composable () -> Unit) {
    val dark = isDark()
    Box(
        Modifier
            .size(56.dp)
            .border(BorderStroke(2.5.dp, if (selected) ink.color(dark) else Color.Transparent), CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A small sample of a paper, to pick it. */
@Composable
fun PaperTile(paper: Paper, ink: Ink, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val dark = isDark()
    Column(
        modifier
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(
                    BorderStroke(if (selected) 2.dp else 1.dp, if (selected) ink.color(dark) else MaterialTheme.colorScheme.outlineVariant),
                    RoundedCornerShape(10.dp),
                ),
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalForceDark provides true) {
                PaperBackground(paper, ink, Modifier.fillMaxSize(), still = true)
            }
        }
        Text(
            paper.label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A chat's info → Chat paper: its own paper, or the one all chats use. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatPaperChooser(conversationId: String) {
    val appearance = LocalAppearance.current
    val own = appearance.chatPapers[conversationId]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Chat paper", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = own == null,
                onClick = { appearance.changeChatPaper(conversationId, null) },
                label = { Text("Same as other chats") },
            )
            Paper.entries.forEach { paper ->
                FilterChip(
                    selected = own == paper,
                    onClick = { appearance.changeChatPaper(conversationId, paper) },
                    label = { Text(paper.label) },
                )
            }
        }
        Text("Only on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** Sticker, Photo or Initials: how you appear, with the choices for each (as on the iPhone). */
@Composable
private fun AvatarStylePicker(state: UiState, model: AppViewModel) {
    val appearance = LocalAppearance.current
    val ink = appearance.ink
    val me = state.account?.username ?: ""
    val hasPhoto = state.profilePhotos[me] != null
    val avatar = avatarDrawing(state.myProfile?.avatar)
    val current = if (hasPhoto) 1 else if (avatar != null) 0 else 2
    var chosen by remember { mutableStateOf<Int?>(null) }
    var working by remember { mutableStateOf(false) }
    val shown = chosen ?: current
    val t = tones()
    val dark = isDark()
    IosSegmented(listOf("Sticker", "Photo", "Initials"), shown, Modifier.padding(top = 6.dp)) { chosen = it }
    KitPanel {
        when (shown) {
            0 -> {
                Text("Pick a sticker · ${AvatarDrawing.entries.size} in the app", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.secondary)
                // Four to a row, as on the design; the chosen one lifts. All of
                // them, in a box that scrolls.
                Column(Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AvatarDrawing.entries.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { drawing ->
                            val index = AvatarDrawing.entries.indexOf(drawing)
                            val on = !hasPhoto && avatar == drawing
                            val shape = RoundedCornerShape(20.dp)
                            val lift by androidx.compose.animation.core.animateDpAsState(
                                if (on) (-3).dp else 0.dp,
                                androidx.compose.animation.core.spring(dampingRatio = 0.55f),
                                label = "lift",
                            )
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).offset(y = lift).clip(shape)
                                    .background(if (on) STICKER_TILES[index % 4] else if (dark) Color(0xFF1F1F23) else Color(0xFFF7F5EE))
                                    .border(if (on) 2.dp else 1.5.dp, if (on) t.text else t.line, shape)
                                    .clickable(role = Role.RadioButton) {
                                        if (hasPhoto) model.setProfilePhoto(null)
                                        model.setProfile(drawing, ink)
                                    }
                                    .semantics { contentDescription = drawing.title; selected = on }
                                    .padding(10.dp),
                            ) { AvatarArt(drawing, Color.Transparent, Modifier.fillMaxSize()) }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                }
            }
            1 -> {
                ProfilePhotoButtons(working) { jpeg -> working = true; model.setProfilePhoto(jpeg) { working = false } }
                if (hasPhoto) {
                    Text(
                        "Remove photo", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.danger,
                        modifier = Modifier.clickable { working = true; model.setProfilePhoto(null) { working = false; chosen = 0 } }.padding(vertical = 6.dp),
                    )
                }
                Text("Your photo is encrypted like a message. Where and when it was taken is stripped before it leaves your phone.", fontSize = 13.sp, lineHeight = 18.sp, color = t.secondary)
            }
            else -> {
                Text("Your initial on a stamp in your colour. Clean, and always readable.", fontSize = 13.5.sp, lineHeight = 18.sp, color = t.secondary)
                if (current != 2) MarigoldButton("Use my initial") {
                    if (hasPhoto) model.setProfilePhoto(null)
                    model.setProfile(null, ink)
                }
            }
        }
    }
}

/** The tile behind a chosen sticker, as on the design: four colours in turn. */
private val STICKER_TILES = listOf(Color(0xFFFFD23F), Color(0xFFF4A6D0), Color(0xFFC3B5FF), Color(0xFF5DC584))
