package com.vizuzik.app.domain.library

import com.vizuzik.app.testutil.testTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAggregatorTest {

    @Test
    fun `aggregateAlbums groups tracks by album and counts them`() {
        val tracks = listOf(
            testTrack(id = "1", album = "Album A", artist = "Artist A"),
            testTrack(id = "2", album = "Album A", artist = "Artist A"),
            testTrack(id = "3", album = "Album B", artist = "Artist B"),
        )

        val albums = LibraryAggregator.aggregateAlbums(tracks)

        assertEquals(2, albums.size)
        val albumA = albums.first { it.title == "Album A" }
        assertEquals(2, albumA.trackCount)
    }

    @Test
    fun `aggregateAlbums keeps the highest known year and ignores unknown years`() {
        val tracks = listOf(
            testTrack(id = "1", album = "Album A", year = 0),
            testTrack(id = "2", album = "Album A", year = 1999),
            testTrack(id = "3", album = "Album A", year = 2005),
        )

        val album = LibraryAggregator.aggregateAlbums(tracks).single()

        assertEquals(2005, album.year)
    }

    @Test
    fun `aggregateArtists counts distinct albums per artist`() {
        val tracks = listOf(
            testTrack(id = "1", artist = "Artist A", album = "Album A"),
            testTrack(id = "2", artist = "Artist A", album = "Album B"),
            testTrack(id = "3", artist = "Artist A", album = "Album A"),
        )

        val artist = LibraryAggregator.aggregateArtists(tracks).single()

        assertEquals(2, artist.albumCount)
        assertEquals(3, artist.trackCount)
    }

    @Test
    fun `aggregateAlbums and aggregateArtists handle unknown metadata without crashing`() {
        val tracks = listOf(testTrack(id = "1", artist = "Artiste inconnu", album = "Album inconnu"))

        val albums = LibraryAggregator.aggregateAlbums(tracks)
        val artists = LibraryAggregator.aggregateArtists(tracks)

        assertTrue(albums.isNotEmpty())
        assertTrue(artists.isNotEmpty())
    }

    @Test
    fun `aggregateAlbums sorts alphabetically case-insensitively`() {
        val tracks = listOf(
            testTrack(id = "1", album = "zebra"),
            testTrack(id = "2", album = "Apple"),
        )

        val titles = LibraryAggregator.aggregateAlbums(tracks).map { it.title }

        assertEquals(listOf("Apple", "zebra"), titles)
    }
}
