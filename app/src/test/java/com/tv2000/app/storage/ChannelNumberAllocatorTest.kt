package com.tv2000.app.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelNumberAllocatorTest {
    @Test
    fun `first scan assigns numbers in discovery order`() {
        val actual = ChannelNumberAllocator.assign(
            channelIdsInDiscoveryOrder = listOf("animation", "bbc", "journey-west"),
            existingNumbers = emptyMap(),
            maximumExistingNumber = 0,
        )

        assertEquals(
            mapOf(
                "animation" to 1,
                "bbc" to 2,
                "journey-west" to 3,
            ),
            actual,
        )
    }

    @Test
    fun `known channels retain numbers when sort order changes`() {
        val actual = ChannelNumberAllocator.assign(
            channelIdsInDiscoveryOrder = listOf("new-first", "known-b", "known-a"),
            existingNumbers = mapOf(
                "known-a" to 1,
                "known-b" to 2,
            ),
            maximumExistingNumber = 2,
        )

        assertEquals(3, actual["new-first"])
        assertEquals(2, actual["known-b"])
        assertEquals(1, actual["known-a"])
    }

    @Test
    fun `offline channel numbers are not reused`() {
        val actual = ChannelNumberAllocator.assign(
            channelIdsInDiscoveryOrder = listOf("new-channel"),
            existingNumbers = emptyMap(),
            maximumExistingNumber = 7,
        )

        assertEquals(mapOf("new-channel" to 8), actual)
    }
}
