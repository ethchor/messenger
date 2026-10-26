package com.qwantarc.messenger.ui

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

/**
 * Settings → Appearance: your avatar and ink (both shared with your chats)
 * and the paper behind chats (this device only).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(state: UiState, model: AppViewModel, chats: Boolean = false) {
    val appearance = LocalAppearance.current
    val dark = isSystemInDarkTheme()
    val ink = appearance.ink
    val avatar = avatarDrawing(state.myProfile?.avatar)
    val initial = (state.account?.username ?: "?").take(1).uppercase()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (chats) "Appearance" else "Avatar and ink",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp),
        )
        if (chats) ChatPreview(appearance.paper, ink)

        if (!chats) {
        Text("Avatar", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(selected = avatar == null, label = "Your initial", ink = ink, onClick = { model.setProfile(null, ink) }) {
                Box(Modifier.fillMaxSize().clip(CircleShape).background(ink.pastel), contentAlignment = Alignment.Center) {
                    Text(initial, color = ink.light, fontWeight = FontWeight.Medium, fontSize = 22.sp)
                }
            }
            AvatarDrawing.entries.forEach { drawing ->
                Choice(selected = avatar == drawing, label = drawing.title, ink = ink, onClick = { model.setProfile(drawing, ink) }) {
                    AvatarArt(drawing, ink.pastel, Modifier.fillMaxSize())
                }
            }
        }

        Text("Ink", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Ink.entries.forEach { choice ->
                Box(
                    Modifier
                        .size(36.dp)
                        .border(BorderStroke(2.dp, if (choice == ink) choice.color(dark) else Color.Transparent), CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(choice.color(dark))
                        .clickable(role = Role.RadioButton) { model.setProfile(avatar, choice) }
                        .semantics {
                            contentDescription = choice.label
                            selected = choice == ink
                        },
                )
            }
        }
        Text(ink.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        }

        if (chats) {
        Text("Chat paper", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Paper.patterns.forEach { paper ->
                PaperTile(paper, ink, selected = appearance.paper == paper) { appearance.changePaper(paper) }
            }
        }
        Text("Wallpapers", style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Paper.wallpapers.forEach { paper ->
                PaperTile(paper, ink, selected = appearance.paper == paper) { appearance.changePaper(paper) }
            }
        }
        }
        Text(
            if (chats) "The paper is only on this device; a chat's info can give it its own."
            else "Everyone in your chats sees your avatar, on a circle of your ink. Your ink also colours your bubbles.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Two bubbles on the paper, in the ink: what a chat will look like. */
@Composable
private fun ChatPreview(paper: Paper, ink: Ink) {
    val dark = isSystemInDarkTheme()
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).semantics { contentDescription = "Preview of a chat" }) {
        PaperBackground(paper, ink, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Bubble("Same place as last time?", incomingBubble(dark), MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f).widthIn(min = 40.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f).widthIn(min = 40.dp))
                Bubble("See you there at 8", ink.bubble(dark), Color.White)
            }
        }
    }
}

@Composable
private fun Bubble(text: String, color: Color, textColor: Color) {
    Surface(color = color, shape = RoundedCornerShape(16.dp)) {
        Text(text, color = textColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

/** One avatar to pick, ringed in the ink when it's yours. */
@Composable
private fun Choice(selected: Boolean, label: String, ink: Ink, onClick: () -> Unit, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
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
fun PaperTile(paper: Paper, ink: Ink, selected: Boolean, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    Column(
        Modifier
            .width(72.dp)
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
            PaperBackground(paper, ink, Modifier.fillMaxSize(), tileScale = 0.45f)
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
