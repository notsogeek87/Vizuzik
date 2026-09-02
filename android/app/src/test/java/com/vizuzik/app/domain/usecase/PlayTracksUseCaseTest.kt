package com.vizuzik.app.domain.usecase

import com.vizuzik.app.testutil.FakeMusicPlayer
import com.vizuzik.app.testutil.FakeMusicRepository
import com.vizuzik.app.testutil.testTrack
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayTracksUseCaseTest {

    @Test
    fun `sets the player queue at the requested index and records the started track`() = runTest {
        val player = FakeMusicPlayer()
        val repository = FakeMusicRepository()
        val useCase = PlayTracksUseCase(player, repository)
        val tracks = listOf(testTrack("1"), testTrack("2"), testTrack("3"))

        useCase(tracks, startIndex = 1)

        assertEquals(tracks, player.lastSetQueueTracks)
        assertEquals(1, player.lastSetQueueStartIndex)
        assertEquals(listOf(tracks[1]), repository.recordedTracks)
        assertEquals(tracks[1], player.state.value.currentTrack)
    }

    @Test
    fun `does nothing for an empty track list`() = runTest {
        val player = FakeMusicPlayer()
        val repository = FakeMusicRepository()
        val useCase = PlayTracksUseCase(player, repository)

        useCase(emptyList())

        assertEquals(null, player.lastSetQueueTracks)
        assertEquals(emptyList<Any>(), repository.recordedTracks)
    }

    @Test
    fun `clamps an out-of-range start index instead of crashing`() = runTest {
        val player = FakeMusicPlayer()
        val repository = FakeMusicRepository()
        val useCase = PlayTracksUseCase(player, repository)
        val tracks = listOf(testTrack("1"), testTrack("2"))

        useCase(tracks, startIndex = 99)

        assertEquals(tracks[1], repository.recordedTracks.single())
    }
}
