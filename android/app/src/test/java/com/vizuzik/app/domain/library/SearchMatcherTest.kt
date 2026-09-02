package com.vizuzik.app.domain.library

import com.vizuzik.app.testutil.testTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {

    @Test
    fun `matches is case insensitive`() {
        assertTrue(SearchMatcher.matches("Bohemian Rhapsody", "rhapsody"))
        assertTrue(SearchMatcher.matches("Bohemian Rhapsody", "BOHEMIAN"))
    }

    @Test
    fun `matches ignores accents so accented queries and titles both find each other`() {
        assertTrue(SearchMatcher.matches("Été 90", "ete"))
        assertTrue(SearchMatcher.matches("Etoile", "étoile"))
        assertTrue(SearchMatcher.matches("Sérénade en La", "serenade"))
    }

    @Test
    fun `matches rejects unrelated text`() {
        assertFalse(SearchMatcher.matches("Bohemian Rhapsody", "xyz"))
    }

    @Test
    fun `search returns empty result for blank query`() {
        val tracks = listOf(testTrack(id = "1", title = "Été"))

        val result = SearchMatcher.search(tracks, emptyList(), emptyList(), "  ")

        assertTrue(result.isEmpty)
    }

    @Test
    fun `search finds tracks by accent-insensitive title, artist or album`() {
        val tracks = listOf(
            testTrack(id = "1", title = "Été", artist = "Zazie", album = "Made in Love"),
            testTrack(id = "2", title = "Autre chose", artist = "Étienne Daho", album = "Autre album"),
            testTrack(id = "3", title = "Rien à voir", artist = "Personne", album = "Rien"),
        )

        val byTitle = SearchMatcher.search(tracks, emptyList(), emptyList(), "ete")
        val byArtist = SearchMatcher.search(tracks, emptyList(), emptyList(), "etienne")

        assertEquals(setOf("1"), byTitle.tracks.map { it.id }.toSet())
        assertEquals(setOf("2"), byArtist.tracks.map { it.id }.toSet())
    }
}
