package com.qwantarc.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
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
 * A floating pill of tabs over the screen's foot, like the iPhone's: the
 * chosen tab sits on a tinted capsule. Chats carries the unread count, and
 * You is your avatar.
 */
@Composable
fun MainTabBar(state: UiState, model: AppViewModel, modifier: Modifier = Modifier) {
    val current = state.currentTab()
    val unread = state.conversations.sumOf { it.unread.toInt() }
    fun go(tab: MainTab) {
        when (tab) {
            MainTab.Chats -> { model.closeMap(); model.closeSettings() }
            MainTab.Map -> { model.closeSettings(); model.openMap() }
            MainTab.You -> { model.closeMap(); model.openSettings() }
        }
    }
    Surface(
        modifier = modifier.navigationBarsPadding().padding(bottom = 10.dp),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        shadowElevation = 10.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(32.dp))
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            TabItem("Chats", current == MainTab.Chats, onClick = { go(MainTab.Chats) }) {
                Box {
                    Icon(MediaIcons.Chats, contentDescription = null, Modifier.size(24.dp))
                    if (unread > 0) {
                        Text(
                            if (unread > 99) "99+" else unread.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(start = 14.dp)
                                .background(Color(0xFFFF3B30), CircleShape)
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            TabItem("Map", current == MainTab.Map, onClick = { go(MainTab.Map) }) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, Modifier.size(24.dp))
            }
            TabItem("You", current == MainTab.You, onClick = { go(MainTab.You) }) {
                Box(Modifier.size(26.dp).clip(CircleShape)) {
                    state.account?.username?.let { Avatar(it, size = 26) }
                }
            }
        }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected }
            .padding(horizontal = 22.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tint) {
            icon()
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = tint)
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
            Icon(icon, contentDescription = contentDescription, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        }
    }
}
