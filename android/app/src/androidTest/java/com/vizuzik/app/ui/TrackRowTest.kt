package com.vizuzik.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vizuzik.app.testutil.testTrack
import com.vizuzik.app.ui.components.TrackRow
import org.junit.Rule
import org.junit.Test

class TrackRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun clickingTheRowInvokesTheCallback() {
        var clicked = false
        val track = testTrack(id = "1", title = "Nuit blanche")
        composeRule.setContent {
            TrackRow(track = track, onClick = { clicked = true })
        }

        composeRule.onNodeWithText("Nuit blanche").performClick()

        assert(clicked)
    }

    @Test
    fun displaysFormattedDuration() {
        val track = testTrack(id = "1", title = "Titre")
        composeRule.setContent {
            TrackRow(track = track, onClick = {})
        }

        // 200_000 ms == 3:20
        composeRule.onNodeWithText("3:20").assertIsDisplayed()
    }
}
