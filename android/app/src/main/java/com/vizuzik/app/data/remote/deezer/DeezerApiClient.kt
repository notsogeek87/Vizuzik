package com.vizuzik.app.data.remote.deezer

import com.vizuzik.app.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
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
 * Client pour le catalogue public Deezer (`api.deezer.com/search/...`), qui ne
 * nécessite ni app développeur ni jeton OAuth — seule la lecture de la
 * bibliothèque personnelle de l'utilisateur (`user/me/...`) l'exige, et
 * Deezer n'a pas accepté la création d'une app pour ce projet. On cherche
 * donc dans le catalogue public plutôt que de lister « mes » albums/playlists :
 * ça suffit pour retrouver et lancer un album/une playlist qu'on possède déjà.
 */
@Singleton
class DeezerApiClient @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun searchAlbums(query: String): List<DeezerCollectionItem> = withContext(ioDispatcher) {
        search("https://api.deezer.com/search/album", query) { item ->
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

    suspend fun searchPlaylists(query: String): List<DeezerCollectionItem> = withContext(ioDispatcher) {
        search("https://api.deezer.com/search/playlist", query) { item ->
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

    private fun search(
        endpoint: String,
        query: String,
        map: (JSONObject) -> DeezerCollectionItem,
    ): List<DeezerCollectionItem> {
        val url = "$endpoint?q=${URLEncoder.encode(query, "UTF-8")}&limit=25"
        val body = DeezerHttp.get(url)
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { error ->
            throw IOException(error.optString("message").ifBlank { "Erreur API Deezer" })
        }
        val data = json.optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { i -> map(data.getJSONObject(i)) }
    }
}
