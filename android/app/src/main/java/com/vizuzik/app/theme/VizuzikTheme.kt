package com.vizuzik.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import com.vizuzik.app.theme.themes.DarkTheme
import com.vizuzik.app.theme.themes.ModernTheme
import com.vizuzik.app.theme.themes.WinampClassicTheme

val allThemes: List<PlayerTheme> = listOf(ModernTheme, WinampClassicTheme, DarkTheme)

val LocalPlayerTheme = staticCompositionLocalOf { ModernTheme }

@Composable
fun VizuzikTheme(themeId: ThemeId, content: @Composable () -> Unit) {
    val playerTheme = allThemes.firstOrNull { it.id == themeId } ?: ModernTheme
    CompositionLocalProvider(LocalPlayerTheme provides playerTheme) {
        MaterialTheme(
            colorScheme = playerTheme.colorScheme,
            content = content,
        )
    }
}
