package com.vizuzik.app.theme.themes

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.vizuzik.app.theme.PlayerTheme
import com.vizuzik.app.theme.ThemeId

/** Esprit Winamp 2.x (LCD vert, VU-meters ambrés, compact) sans réutiliser d'assets propriétaires. */
val WinampClassicTheme = PlayerTheme(
    id = ThemeId.WINAMP_CLASSIC,
    name = "Winamp Classic",
    colorScheme = darkColorScheme(
        primary = Color(0xFF3DFF6E),
        onPrimary = Color.Black,
        secondary = Color(0xFFFFB300),
        onSecondary = Color.Black,
        background = Color(0xFF14161A),
        onBackground = Color(0xFF3DFF6E),
        surface = Color(0xFF1E2126),
        onSurface = Color(0xFF3DFF6E),
        surfaceVariant = Color(0xFF282C33),
        onSurfaceVariant = Color(0xFF8FE0A6),
    ),
    lcdDisplay = true,
    compactPlayer = true,
)
