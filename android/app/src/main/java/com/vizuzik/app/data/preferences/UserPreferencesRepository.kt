package com.vizuzik.app.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "vizuzik_preferences")

/** Persiste les préférences utilisateur légères : skin sélectionné pour l'instant. */
@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val themeIdKey = stringPreferencesKey("theme_id")

    fun observeThemeId(default: String): Flow<String> =
        context.dataStore.data.map { it[themeIdKey] ?: default }

    suspend fun setThemeId(id: String) {
        context.dataStore.edit { it[themeIdKey] = id }
    }
}
