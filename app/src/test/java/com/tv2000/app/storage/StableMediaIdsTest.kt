package com.tv2000.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StableMediaIdsTest {
    @Test
    fun `equivalent unicode paths produce the same identifier`() {
        val volumeId = StableMediaIds.volume("content://usb/root")

        val halfWidth = StableMediaIds.channel(volumeId, "Channel 1")
        val fullWidth = StableMediaIds.channel(volumeId, "Ｃｈａｎｎｅｌ １")

        assertEquals(halfWidth, fullWidth)
    }

    @Test
    fun `path separators are normalized`() {
        val channelId = StableMediaIds.channel("volume", "shows")

        assertEquals(
            StableMediaIds.episode(channelId, "season/001.mp4"),
            StableMediaIds.episode(channelId, "season\\001.mp4"),
        )
    }

    @Test
    fun `different volumes keep same paths independent`() {
        assertNotEquals(
            StableMediaIds.channel("volume-a", "动画"),
            StableMediaIds.channel("volume-b", "动画"),
        )
    }
}
