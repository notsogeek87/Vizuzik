package com.vizuzik.app.data.remote.deezer

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vizuzik.app.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deezerAuthDataStore by preferencesDataStore(name = "deezer_auth")

sealed interface DeezerAuthResult {
    data object Success : DeezerAuthResult
    data class Error(val message: String) : DeezerAuthResult
}

/**
 * Échange le code d'autorisation OAuth contre un jeton d'accès et le
 * conserve. Deezer ne fournit pas de refresh token (confirmé par plusieurs
 * intégrations tierces indépendantes) : en pratique le jeton n'expire pas
 * tant que l'utilisateur ne révoque pas l'accès depuis son compte Deezer —
 * une reconnexion complète sera nécessaire s'il le fait.
 */
@Singleton
class DeezerAuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val accessTokenKey = stringPreferencesKey("access_token")

    val accessToken: Flow<String?> = context.deezerAuthDataStore.data.map { it[accessTokenKey] }
    val isAuthenticated: Flow<Boolean> = accessToken.map { !it.isNullOrBlank() }

    suspend fun exchangeCode(code: String): DeezerAuthResult = withContext(ioDispatcher) {
        if (!DeezerOAuthConfig.isConfigured) {
            return@withContext DeezerAuthResult.Error(
                "Identifiants Deezer non configurés (DEEZER_APP_ID/DEEZER_APP_SECRET manquants à la compilation)."
            )
        }
        runCatching {
            val url = "https://connect.deezer.com/oauth/access_token.php" +
                "?app_id=${DeezerOAuthConfig.appId}" +
                "&secret=${DeezerOAuthConfig.appSecret}" +
                "&code=$code" +
                "&output=json"
            val body = DeezerHttp.get(url)
            val token = extractAccessToken(body)
                ?: return@withContext DeezerAuthResult.Error("Réponse Deezer sans access_token : $body")
            context.deezerAuthDataStore.edit { it[accessTokenKey] = token }
            DeezerAuthResult.Success
        }.getOrElse { error -> DeezerAuthResult.Error(error.message ?: "Échec de connexion à Deezer") }
    }

    suspend fun logout() = withContext(ioDispatcher) {
        context.deezerAuthDataStore.edit { it.clear() }
    }

    /**
     * `output=json` doit renvoyer du JSON, mais l'API Deezer est connue pour
     * parfois répondre en `access_token=xxx&expires=yyy` — on gère les deux
     * plutôt que de supposer, n'ayant pas pu tester ce point en direct.
     */
    private fun extractAccessToken(body: String): String? {
        runCatching { JSONObject(body) }.getOrNull()?.let { json ->
            return json.optString("access_token").takeIf { it.isNotBlank() }
        }
        return body.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0] == "access_token" }
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
    }
}
