package com.vizuzik.app.data.remote.deezer

import com.vizuzik.app.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class DeezerCollectionType { ALBUM, PLAYLIST }

/**
 * [searchQuery] est ce qu'on enverra à `playFromSearch` sur la session Deezer :
 * l'utilisateur n'a besoin de lancer que l'album/playlist en entier (cf. tests
 * en direct), pas un morceau précis — inutile donc de garder le détail des
 * pistes ici.
 */
data class DeezerCollectionItem(
    val id: Long,
    val type: DeezerCollectionType,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val searchQuery: String,
)

/**
 * Client minimal pour l'API publique Deezer (`api.deezer.com`), authentifié
 * par le jeton OAuth obtenu via [DeezerAuthRepository]. Pas de SDK officiel
 * utilisé : de simples appels GET suffisent pour lister les albums/playlists
 * de l'utilisateur.
 */
@Singleton
class DeezerApiClient @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun fetchAlbums(accessToken: String): List<DeezerCollectionItem> = withContext(ioDispatcher) {
        fetchAll("https://api.deezer.com/user/me/albums", accessToken) { item ->
            val artist = item.optJSONObject("artist")?.optString("name").orEmpty()
            val title = item.optString("title")
            DeezerCollectionItem(
                id = item.optLong("id"),
                type = DeezerCollectionType.ALBUM,
                title = title,
                subtitle = artist,
                artworkUrl = item.optString("cover_medium").takeIf { it.isNotBlank() },
                searchQuery = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "),
            )
        }
    }

    suspend fun fetchPlaylists(accessToken: String): List<DeezerCollectionItem> = withContext(ioDispatcher) {
        fetchAll("https://api.deezer.com/user/me/playlists", accessToken) { item ->
            val trackCount = item.optInt("nb_tracks")
            val title = item.optString("title")
            DeezerCollectionItem(
                id = item.optLong("id"),
                type = DeezerCollectionType.PLAYLIST,
                title = title,
                subtitle = if (trackCount > 0) "$trackCount titres" else "",
                artworkUrl = item.optString("picture_medium").takeIf { it.isNotBlank() },
                searchQuery = title,
            )
        }
    }

    /**
     * Suit `next` pour parcourir toutes les pages. Le champ `next` renvoyé par
     * Deezer ne porte pas toujours `access_token` (non vérifiable en direct
     * depuis cet environnement) : on le rajoute par précaution s'il manque.
     */
    private fun fetchAll(
        baseUrl: String,
        accessToken: String,
        map: (JSONObject) -> DeezerCollectionItem,
    ): List<DeezerCollectionItem> {
        val items = mutableListOf<DeezerCollectionItem>()
        var url: String? = withAccessToken("$baseUrl?limit=100", accessToken)
        while (url != null) {
            val body = DeezerHttp.get(url)
            val json = JSONObject(body)
            json.optJSONObject("error")?.let { error ->
                throw IOException(error.optString("message").ifBlank { "Erreur API Deezer" })
            }
            val data = json.optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                items += map(data.getJSONObject(i))
            }
            url = json.optString("next").takeIf { it.isNotBlank() }?.let { withAccessToken(it, accessToken) }
        }
        return items
    }

    private fun withAccessToken(url: String, accessToken: String): String =
        if (url.contains("access_token=")) url else url + (if (url.contains("?")) "&" else "?") + "access_token=$accessToken"
}
