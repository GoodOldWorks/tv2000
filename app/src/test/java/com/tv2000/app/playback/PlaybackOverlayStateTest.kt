package com.tv2000.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackOverlayStateTest {
    @Test
    fun `position discontinuity updates the overlay time`() {
        val updated = Tv2000UiState(
            mode = AppMode.READY,
            currentEpisodeIndex = 1,
            channelOverlayPositionMs = 12_000L,
        ).syncPlaybackPosition(
            mediaItemIndex = 1,
            positionMs = 42_000L,
        )

        assertEquals(42_000L, updated.channelOverlayPositionMs)
        assertEquals(1, updated.currentEpisodeIndex)
    }

    @Test
    fun `unset item keeps the episode and clamps a negative position`() {
        val updated = Tv2000UiState(
            mode = AppMode.READY,
            currentEpisodeIndex = 2,
            channelOverlayPositionMs = 12_000L,
        ).syncPlaybackPosition(
            mediaItemIndex = -1,
            positionMs = -1L,
        )

        assertEquals(0L, updated.channelOverlayPositionMs)
        assertEquals(2, updated.currentEpisodeIndex)
    }
}
