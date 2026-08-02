package com.tv2000.app.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCodecPreferenceTest {
    @Test
    fun smbMkvPrefersSoftwareAvc() {
        assertTrue(shouldPreferSoftwareAvcForSmbPath(isSmb = true, path = "episode.MKV"))
    }

    @Test
    fun mp4AndLocalMkvKeepDefaultDecoderOrder() {
        assertFalse(shouldPreferSoftwareAvcForSmbPath(isSmb = true, path = "episode.mp4"))
        assertFalse(shouldPreferSoftwareAvcForSmbPath(isSmb = false, path = "episode.mkv"))
    }
}
