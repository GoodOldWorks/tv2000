package com.tv2000.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPlaybackRecoveryStateTest {
    @Test
    fun `failure arms exactly one manual retry`() {
        val state = SmbPlaybackRecoveryState()

        state.onPlaybackFailure()

        assertTrue(state.consumeManualRetry())
        assertFalse(state.consumeManualRetry())
        assertTrue(state.onPlaybackReady())
        assertFalse(state.onPlaybackReady())
    }

    @Test
    fun `ready playback clears pending retry`() {
        val state = SmbPlaybackRecoveryState()
        state.onPlaybackFailure()

        assertTrue(state.onPlaybackReady())
        assertFalse(state.consumeManualRetry())
    }
}
