package com.tv2000.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlaybackResumeStateTest {
    @Test
    fun `playing before background resumes once on foreground`() {
        val state = BackgroundPlaybackResumeState()

        state.onBackground(playWhenReady = true)

        assertTrue(state.consumeResumeRequest())
        assertFalse(state.consumeResumeRequest())
    }

    @Test
    fun `user paused playback stays paused after foreground`() {
        val state = BackgroundPlaybackResumeState()

        state.onBackground(playWhenReady = false)

        assertFalse(state.consumeResumeRequest())
    }

    @Test
    fun `latest background state replaces an earlier request`() {
        val state = BackgroundPlaybackResumeState()

        state.onBackground(playWhenReady = true)
        state.onBackground(playWhenReady = false)

        assertFalse(state.consumeResumeRequest())
    }
}
