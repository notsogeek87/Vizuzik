package com.vizuzik.app.theme

import com.vizuzik.app.data.preferences.UserPreferencesRepository
import com.vizuzik.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThemeController @Inject constructor(
    private val preferences: UserPreferencesRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    val themeId: StateFlow<ThemeId> = preferences.observeThemeId(ThemeId.MODERN.storageKey)
        .map { ThemeId.fromKey(it) }
        .stateIn(scope, SharingStarted.Eagerly, ThemeId.MODERN)

    fun setTheme(id: ThemeId) {
        scope.launch { preferences.setThemeId(id.storageKey) }
    }
}
