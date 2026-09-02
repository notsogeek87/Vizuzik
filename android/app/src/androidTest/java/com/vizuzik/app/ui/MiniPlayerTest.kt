package com.vizuzik.app.ui

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vizuzik.app.domain.model.PlayerState
import com.vizuzik.app.testutil.testTrack
import com.vizuzik.app.ui.components.MiniPlayer
import org.junit.Rule
import org.junit.Test

class MiniPlayerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysCurrentTrackTitleAndArtist() {
        val track = testTrack(id = "1", title = "Sur la route", artist = "Les Voyageurs")
        composeRule.setContent {
            MiniPlayer(
                state = PlayerState(currentTrack = track, isPlaying = true),
                onClick = {},
                onPlayPause = {},
                onNext = {},
            )
        }

        composeRule.onNodeWithText("Sur la route").assertIsDisplayed()
        composeRule.onNodeWithText("Les Voyageurs").assertIsDisplayed()
    }

    @Test
    fun playPauseButtonTogglesCallback() {
        var pauseClicked = false
        val track = testTrack(id = "1")
        composeRule.setContent {
            MiniPlayer(
                state = PlayerState(currentTrack = track, isPlaying = true),
                onClick = {},
                onPlayPause = { pauseClicked = true },
                onNext = {},
            )
        }

        composeRule.onNodeWithContentDescription("Pause").performClick()

        assert(pauseClicked)
    }

    @Test
    fun rendersNothingWhenNoTrackIsPlaying() {
        composeRule.setContent {
            MiniPlayer(state = PlayerState(currentTrack = null), onClick = {}, onPlayPause = {}, onNext = {})
        }

        composeRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }
}
