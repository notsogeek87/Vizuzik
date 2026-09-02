package com.vizuzik.app.theme.themes

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.vizuzik.app.theme.PlayerTheme
import com.vizuzik.app.theme.ThemeId

val DarkTheme = PlayerTheme(
    id = ThemeId.DARK,
    name = "Dark",
    colorScheme = darkColorScheme(
        primary = Color(0xFFD0D0D0),
        onPrimary = Color.Black,
        secondary = Color(0xFF8A8A8A),
        onSecondary = Color.Black,
        background = Color(0xFF000000),
        onBackground = Color(0xFFE4E4E4),
        surface = Color(0xFF0A0A0A),
        onSurface = Color(0xFFE4E4E4),
        surfaceVariant = Color(0xFF181818),
        onSurfaceVariant = Color(0xFFA0A0A0),
    ),
)
