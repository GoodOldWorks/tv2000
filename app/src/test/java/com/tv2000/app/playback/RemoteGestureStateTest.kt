package com.tv2000.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteGestureStateTest {
    @Test
    fun `same direction inside timeout is a double press`() {
        val detector = DirectionalDoublePressDetector(timeoutMs = 350L)

        assertFalse(detector.register(DirectionalPress.RIGHT, nowMs = 1_000L))
        assertTrue(detector.register(DirectionalPress.RIGHT, nowMs = 1_350L))
    }

    @Test
    fun `same direction after timeout starts a new sequence`() {
        val detector = DirectionalDoublePressDetector(timeoutMs = 350L)

        assertFalse(detector.register(DirectionalPress.LEFT, nowMs = 1_000L))
        assertFalse(detector.register(DirectionalPress.LEFT, nowMs = 1_351L))
    }

    @Test
    fun `opposite directions do not form a double press`() {
        val detector = DirectionalDoublePressDetector(timeoutMs = 350L)

        assertFalse(detector.register(DirectionalPress.LEFT, nowMs = 1_000L))
        assertFalse(detector.register(DirectionalPress.RIGHT, nowMs = 1_100L))
    }

    @Test
    fun `double left after threshold restarts current episode`() {
        assertEquals(
            DoubleLeftAction.RESTART_CURRENT_EPISODE,
            resolveDoubleLeftAction(
                playbackPositionMs = 5_001L,
                previousEpisodeThresholdMs = 5_000L,
            ),
        )
    }

    @Test
    fun `double left at threshold plays previous episode`() {
        assertEquals(
            DoubleLeftAction.PLAY_PREVIOUS_EPISODE,
            resolveDoubleLeftAction(
                playbackPositionMs = 5_000L,
                previousEpisodeThresholdMs = 5_000L,
            ),
        )
    }

    @Test
    fun `double left before threshold plays previous episode`() {
        assertEquals(
            DoubleLeftAction.PLAY_PREVIOUS_EPISODE,
            resolveDoubleLeftAction(
                playbackPositionMs = 4_999L,
                previousEpisodeThresholdMs = 5_000L,
            ),
        )
    }
}
