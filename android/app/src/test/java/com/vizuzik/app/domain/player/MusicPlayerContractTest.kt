package com.vizuzik.app.domain.player

import com.vizuzik.app.domain.model.RepeatMode
import com.vizuzik.app.testutil.FakeMusicPlayer
import com.vizuzik.app.testutil.testTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exerce le contrat de [com.vizuzik.app.domain.player.MusicPlayer] — shuffle,
 * répétition et file d'attente — au travers de [FakeMusicPlayer]. Le vrai
 * [com.vizuzik.app.player.Media3MusicPlayer] s'appuie sur un
 * [androidx.media3.session.MediaController] et n'est donc testable qu'en
 * instrumenté ; ce test documente et verrouille le comportement attendu par
 * l'UI (ce que [com.vizuzik.app.ui.player.PlayerViewModel] appelle).
 */
class MusicPlayerContractTest {

    @Test
    fun `shuffle toggles independently of playback state`() {
        val player = FakeMusicPlayer()
        assertFalse(player.state.value.shuffleEnabled)

        player.setShuffleEnabled(!player.state.value.shuffleEnabled)
        assertTrue(player.state.value.shuffleEnabled)

        player.setShuffleEnabled(!player.state.value.shuffleEnabled)
        assertFalse(player.state.value.shuffleEnabled)
    }

    @Test
    fun `repeat mode is cycled off to all to one`() {
        val player = FakeMusicPlayer()

        player.setRepeatMode(player.state.value.repeatMode.next())
        assertEquals(RepeatMode.ALL, player.state.value.repeatMode)

        player.setRepeatMode(player.state.value.repeatMode.next())
        assertEquals(RepeatMode.ONE, player.state.value.repeatMode)
    }

    @Test
    fun `the current track always matches the queue index`() {
        val player = FakeMusicPlayer()
        val tracks = listOf(testTrack("1"), testTrack("2"), testTrack("3"))

        player.setQueue(tracks, startIndex = 2)

        assertEquals(tracks[2], player.state.value.currentTrack)
        assertEquals(2, player.state.value.queueIndex)
        assertEquals(3, player.state.value.queue.size)
    }

    @Test
    fun `clearQueue empties both the queue and the current track`() {
        val player = FakeMusicPlayer()
        player.setQueue(listOf(testTrack("1")), startIndex = 0)

        player.clearQueue()

        assertTrue(player.state.value.queue.isEmpty())
        assertEquals(null, player.state.value.currentTrack)
        assertEquals(-1, player.state.value.queueIndex)
    }
}
