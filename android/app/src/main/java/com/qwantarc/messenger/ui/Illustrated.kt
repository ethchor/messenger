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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A short explanation with a drawing, for the moments that need one: a
 * title, a plain sentence or two, and maybe a button.
 */
@Composable
fun HelperCard(
    illustration: Illustration,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    HelperCard(
        art = { IllustrationArt(illustration, Modifier.fillMaxSize()) },
        title = title,
        text = text,
        modifier = modifier,
        onDismiss = onDismiss,
        actions = actions,
    )
}

/** A helper card with any picture beside its words, in a 72 by 54 box. */
@Composable
fun HelperCard(
    art: @Composable () -> Unit,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    val paper = incomingBubble(isSystemInDarkTheme())
    Row(
        modifier
            .fillMaxWidth()
            .background(paper, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, top = 14.dp, bottom = 14.dp, end = if (onDismiss != null) 0.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(width = 72.dp, height = 54.dp), contentAlignment = Alignment.Center) { art() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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

/** Up to three avatars, overlapping, the middle one on top and a little raised. */
@Composable
fun AvatarTrio(drawings: List<AvatarDrawing>, background: Color) {
    val ring = incomingBubble(isSystemInDarkTheme())
    Box(contentAlignment = Alignment.Center) {
        for (index in listOf(0, 2, 1).filter { it < drawings.size }) {
            Box(
                Modifier
                    .offset(x = ((index - 1) * 19).dp, y = if (index == 1) (-5).dp else 5.dp)
                    .size(38.dp)
                    .background(ring, CircleShape)
                    .padding(2.dp),
            ) {
                AvatarArt(drawings[index], background, Modifier.fillMaxSize())
            }
        }
    }
}

/** What an empty screen says: a drawing, a title, what to do, and the button that does it. */
@Composable
fun EmptyState(
    illustration: Illustration,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IllustrationArt(illustration, Modifier.size(width = 180.dp, height = 135.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
