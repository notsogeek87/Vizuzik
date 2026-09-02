package com.vizuzik.app.domain.library

import com.vizuzik.app.domain.model.Album
import com.vizuzik.app.domain.model.Artist
import com.vizuzik.app.domain.model.SearchResult
import com.vizuzik.app.domain.model.Track
import java.text.Normalizer

/**
 * Recherche insensible à la casse et aux accents ("ete" retrouve "Été"),
 * ce qui évite les résultats vides sur une bibliothèque aux titres/artistes
 * accentués.
 */
object SearchMatcher {

    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{Mn}+"), "").lowercase()
    }

    fun matches(haystack: String, query: String): Boolean =
        normalize(haystack).contains(normalize(query))

    fun search(tracks: List<Track>, albums: List<Album>, artists: List<Artist>, query: String): SearchResult {
        if (query.isBlank()) return SearchResult()
        return SearchResult(
            tracks = tracks.filter { matches(it.title, query) || matches(it.artist, query) || matches(it.album, query) },
            albums = albums.filter { matches(it.title, query) || matches(it.artist, query) },
            artists = artists.filter { matches(it.name, query) },
        )
    }
}
