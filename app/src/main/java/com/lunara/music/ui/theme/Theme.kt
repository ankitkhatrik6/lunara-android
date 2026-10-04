package com.lunara.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AccentLime,
    onPrimary = AccentLimeDark,
    primaryContainer = SurfaceElevatedDark,
    onPrimaryContainer = TextWhite,
    secondary = AccentPurple,
    onSecondary = TextWhite,
    background = BackgroundBlack,
    onBackground = TextWhite,
    surface = BackgroundBlack,
    onSurface = TextWhite,
    surfaceVariant = SurfaceCardDark,
    onSurfaceVariant = TextMuted,
    outline = BorderSubtle,
    error = Color(0xFFEF4444),
    onError = Color.White
)

@Composable
fun LunaraTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
