package com.qwantarc.messenger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The app's own voice, for titles next to drawings: a serif. */
val VoiceFamily = FontFamily.Serif

/**
 * A short explanation with a drawing, for the moments that need one: a
 * serif title, a plain sentence or two, and maybe a button.
 */
@Composable
fun HelperCard(
    artwork: Artwork,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val dark = isSystemInDarkTheme()
    val paper = incomingBubble(dark)
    Row(
        modifier
            .fillMaxWidth()
            .background(paper, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = if (onDismiss != null) 0.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LineArt(artwork, Modifier.size(width = 72.dp, height = 54.dp), ink = MaterialTheme.colorScheme.primary, paper = paper)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontFamily = VoiceFamily, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actions()
        }
        if (onDismiss != null) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** What an empty screen says: a drawing, a title, what to do, and the button that does it. */
@Composable
fun EmptyState(
    artwork: Artwork,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LineArt(artwork, Modifier.size(width = 180.dp, height = 135.dp), ink = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, fontFamily = VoiceFamily, fontWeight = FontWeight.SemiBold)
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            action()
        }
    }
}

/** Suggestions above the chat list, one at a time, until done or dismissed. */
enum class ChatListTip(val id: String) {
    RecoveryKey("recoveryKey"),
    Avatar("avatar"),
    SafetyNumbers("safetyNumbers"),
}
