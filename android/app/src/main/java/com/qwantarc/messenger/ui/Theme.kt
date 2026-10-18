package com.qwantarc.messenger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Indigo = Color(0xFF5B5BD6)
private val IndigoLight = Color(0xFF8B8BF0)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3E2FF),
    onPrimaryContainer = Color(0xFF15134F),
    secondary = Color(0xFF5D5C72),
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF15134F),
    primaryContainer = Color(0xFF3B3AA8),
    onPrimaryContainer = Color(0xFFE3E2FF),
    secondary = Color(0xFFC6C4DD),
)

@Composable
fun MessengerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

private val AvatarPalette = listOf(
    Color(0xFF5B5BD6), Color(0xFF12A594), Color(0xFFF76B15), Color(0xFFD6409F),
    Color(0xFF8E4EC6), Color(0xFF0090FF), Color(0xFF30A46C), Color(0xFF00A2C7),
)

/** Stable per name, so a person keeps their colour across launches. */
fun avatarColor(name: String): Color = AvatarPalette[name.sumOf { it.code } % AvatarPalette.size]
