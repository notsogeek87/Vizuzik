package com.vizuzik.app.domain.player

import com.vizuzik.app.domain.model.Track

sealed interface PlaybackEvent {
    data object PlaybackStarted : PlaybackEvent
    data object PlaybackPaused : PlaybackEvent
    data object PlaybackStopped : PlaybackEvent
    data class TrackChanged(val track: Track?) : PlaybackEvent
    data class PositionChanged(val positionMs: Long) : PlaybackEvent
    data class PlaybackError(val message: String) : PlaybackEvent
    data object QueueChanged : PlaybackEvent
}
