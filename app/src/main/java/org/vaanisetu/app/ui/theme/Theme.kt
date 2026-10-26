package org.vaanisetu.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Slate900,
    primaryContainer = Slate800,
    onPrimaryContainer = CyanAccent,
    secondary = EmeraldGreen,
    onSecondary = Slate900,
    tertiary = PurpleAccent,
    background = Slate900,
    onBackground = Slate200,
    surface = Slate800,
    onSurface = Slate200,
    error = CrimsonEmergency
)

@Composable
fun VaaniSetuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
