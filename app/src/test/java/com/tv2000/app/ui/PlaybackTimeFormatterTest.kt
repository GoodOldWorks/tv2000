package com.tv2000.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimeFormatterTest {
    @Test
    fun `formats zero`() {
        assertEquals("00:00", formatPlaybackTime(0L))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("23:41", formatPlaybackTime(1_421_000L))
    }

    @Test
    fun `formats hours when needed`() {
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L))
    }

    @Test
    fun `negative positions are clamped`() {
        assertEquals("00:00", formatPlaybackTime(-1L))
    }

    @Test
    fun `visible channel overlay enables TV repaint compatibility`() {
        assertTrue(
            needsSoftwareOverlayRendering(
                com.tv2000.app.playback.Tv2000UiState(channelOverlayVisible = true),
            ),
        )
        assertFalse(
            needsSoftwareOverlayRendering(com.tv2000.app.playback.Tv2000UiState()),
        )
    }
}
