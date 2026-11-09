package com.qwantarc.messenger.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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

/** The tabs at the bottom, as on iPhone: chats, the map, and you. */
enum class MainTab { Chats, Map, You }

fun UiState.currentTab(): MainTab = when {
    mapOpen -> MainTab.Map
    settingsOpen -> MainTab.You
    else -> MainTab.Chats
}

/**
 * A black pill of tabs over the screen's foot: the chosen one is a marigold
 * capsule with its name, the others just icons. Chats carries the unread
 * count. The iPhone app draws the same pill (FloatingTabBar).
 */
@Composable
fun MainTabBar(state: UiState, model: AppViewModel, modifier: Modifier = Modifier) {
    val current = state.currentTab()
    // Muted and archived chats don't count.
    val unread = state.conversations.filter { it.mutedUntilMs == null && !it.archived }.sumOf { it.unread.toInt() }
    val t = tones()
    fun go(tab: MainTab) {
        when (tab) {
            MainTab.Chats -> { model.closeMap(); model.closeSettings() }
            MainTab.Map -> { model.closeSettings(); model.openMap() }
            MainTab.You -> { model.closeMap(); model.openSettings() }
        }
    }
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
            .shadow(16.dp, RoundedCornerShape(50), ambientColor = Color.Black.copy(alpha = 0.28f), spotColor = Color.Black.copy(alpha = 0.28f))
            .clip(RoundedCornerShape(50))
            .background(t.pill)
            .border(1.dp, t.pillLine, RoundedCornerShape(50))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        TabItem("Chats", Glyphs.ChatsFilled, current == MainTab.Chats, badge = unread) { go(MainTab.Chats) }
        TabItem("Map", Glyphs.MapFilled, current == MainTab.Map) { go(MainTab.Map) }
        TabItem("You", Glyphs.Profile, current == MainTab.You) { go(MainTab.You) }
    }
}

@Composable
private fun TabItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    val t = tones()
    val source = remember { MutableInteractionSource() }
    val tint = if (selected) Palette.Ink else t.idleTab
    val background by animateColorAsState(if (selected) Palette.Marigold else Color.Transparent, tween(300), label = "tab")
    Row(
        Modifier
            .pressScale(source)
            .height(50.dp)
            .clip(RoundedCornerShape(50))
            .background(background)
            .clickable(interactionSource = source, indication = null, role = Role.Tab, onClick = onClick)
            .semantics {
                contentDescription = if (badge > 0) "$label, $badge unread" else label
                this.selected = selected
            }
            .animateContentSize(spring(dampingRatio = 0.6f, stiffness = 500f))
            .padding(start = 14.dp, end = if (selected) 18.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 12.dp, y = (-8).dp)
                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                        .background(Palette.Marigold, CircleShape)
                        .border(2.dp, t.pill, CircleShape)
                        .padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 99) "99+" else badge.toString(),
                        color = Palette.Ink,
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (selected) {
            Text(label, color = Palette.Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A toolbar button in a round pill, like the iPhone's glass buttons. */
@Composable
fun RoundBarButton(onClick: () -> Unit, contentDescription: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
        modifier = Modifier.padding(horizontal = 8.dp).size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
    }
}
