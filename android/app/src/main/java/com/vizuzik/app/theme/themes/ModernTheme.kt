package com.vizuzik.app.theme.themes

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.vizuzik.app.theme.PlayerTheme
import com.vizuzik.app.theme.ThemeId

val ModernTheme = PlayerTheme(
    id = ThemeId.MODERN,
    name = "Modern",
    colorScheme = darkColorScheme(
        primary = Color(0xFF7C4DFF),
        onPrimary = Color.White,
        secondary = Color(0xFF00E5A0),
        onSecondary = Color.Black,
        background = Color(0xFF121212),
        onBackground = Color(0xFFEDEDED),
        surface = Color(0xFF1C1C1E),
        onSurface = Color(0xFFEDEDED),
        surfaceVariant = Color(0xFF2A2A2D),
        onSurfaceVariant = Color(0xFFC7C7CC),
    ),
)
