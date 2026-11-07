package com.qwantarc.messenger.ui

import androidx.compose.foundation.background
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
    val outer = RoundedCornerShape(50)
    Surface(
        modifier = modifier.navigationBarsPadding().padding(bottom = 8.dp),
        shape = outer,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), outer)
                .padding(4.dp),
        ) {
            TabItem("Chats", current == MainTab.Chats, onClick = { go(MainTab.Chats) }) { selected ->
                Box(Modifier.size(width = 28.dp, height = 26.dp), contentAlignment = Alignment.Center) {
                    Icon(if (selected) Glyphs.ChatsFilled else Glyphs.Chats, contentDescription = null, Modifier.size(25.dp))
                    if (unread > 0) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 10.dp, y = (-3).dp)
                                .defaultMinSize(minWidth = 17.dp, minHeight = 17.dp)
                                .background(Color(0xFFFF3B30), CircleShape)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (unread > 99) "99+" else unread.toString(),
                                color = Color.White,
                                fontSize = 11.sp,
                                lineHeight = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            TabItem("Map", current == MainTab.Map, onClick = { go(MainTab.Map) }) { selected ->
                Icon(if (selected) Glyphs.MapFilled else Glyphs.Map, contentDescription = null, Modifier.size(25.dp))
            }
            TabItem("You", current == MainTab.You, onClick = { go(MainTab.You) }) { selected ->
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier),
                ) {
                    state.account?.username?.let { Avatar(it, size = 26) }
                }
            }
        }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable (Boolean) -> Unit) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Column(
        Modifier
            .width(86.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected }
            .padding(top = 7.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tint) {
            Box(Modifier.height(26.dp), contentAlignment = Alignment.Center) { icon(selected) }
            Text(label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = tint)
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
