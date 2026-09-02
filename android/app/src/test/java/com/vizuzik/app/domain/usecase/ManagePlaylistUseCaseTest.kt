package com.vizuzik.app.domain.usecase

import com.vizuzik.app.testutil.FakePlaylistRepository
import com.vizuzik.app.testutil.testTrack
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ManagePlaylistUseCaseTest {

    @Test
    fun `createWithTracks creates the playlist and adds every track in order`() = runTest {
        val repository = FakePlaylistRepository()
        val useCase = ManagePlaylistUseCase(repository)
        val tracks = listOf(testTrack("1"), testTrack("2"), testTrack("3"))

        val id = useCase.createWithTracks("Favoris", tracks)
        val playlist = repository.observePlaylist(id).first()

        assertEquals("Favoris", playlist?.name)
        assertEquals(listOf("1", "2", "3"), playlist?.tracks?.map { it.id })
    }

    @Test
    fun `addTracks does not duplicate a track already in the playlist`() = runTest {
        val repository = FakePlaylistRepository()
        val useCase = ManagePlaylistUseCase(repository)
        val id = repository.createPlaylist("Queue du soir")
        val track = testTrack("1")

        useCase.addTracks(id, listOf(track))
        useCase.addTracks(id, listOf(track))

        val playlist = repository.observePlaylist(id).first()
        assertEquals(1, playlist?.tracks?.size)
    }
}
