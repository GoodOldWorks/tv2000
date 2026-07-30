package com.tv2000.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class WrappedIndexTest {
    @Test
    fun `moves forward`() {
        assertEquals(2, wrappedIndex(current = 1, delta = 1, size = 4))
    }

    @Test
    fun `wraps after last channel`() {
        assertEquals(0, wrappedIndex(current = 3, delta = 1, size = 4))
    }

    @Test
    fun `wraps before first channel`() {
        assertEquals(3, wrappedIndex(current = 0, delta = -1, size = 4))
    }

    @Test
    fun `empty list stays at zero`() {
        assertEquals(0, wrappedIndex(current = 3, delta = 1, size = 0))
    }
}
