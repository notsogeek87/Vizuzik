package com.vizuzik.app.theme

import androidx.compose.material3.ColorScheme

enum class ThemeId(val storageKey: String) {
    MODERN("modern"),
    WINAMP_CLASSIC("winamp_classic"),
    DARK("dark"),
    ;

    companion object {
        fun fromKey(key: String): ThemeId = entries.firstOrNull { it.storageKey == key } ?: MODERN
    }
}

/**
 * Un skin complet. [colorScheme] pilote Material3 ; [lcdDisplay] et
 * [compactPlayer] sont des indicateurs supplémentaires que l'écran lecteur
 * lit via [LocalPlayerTheme] pour adapter sa mise en page (ex. affichage
 * façon LCD rétro pour Winamp Classic). Ajouter un skin = ajouter une entrée
 * dans [allThemes], sans toucher au player ni à la navigation.
 */
data class PlayerTheme(
    val id: ThemeId,
    val name: String,
    val colorScheme: ColorScheme,
    val lcdDisplay: Boolean = false,
    val compactPlayer: Boolean = false,
)
