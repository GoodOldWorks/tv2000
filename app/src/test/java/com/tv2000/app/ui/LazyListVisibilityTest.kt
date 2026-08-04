package com.tv2000.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyListVisibilityTest {
    @Test
    fun `item touching both viewport edges is fully visible`() {
        assertTrue(
            isLazyListItemFullyVisible(
                itemOffset = 100,
                itemSize = 80,
                viewportStartOffset = 100,
                viewportEndOffset = 180,
            ),
        )
    }

    @Test
    fun `item clipped by top edge is not fully visible`() {
        assertFalse(
            isLazyListItemFullyVisible(
                itemOffset = 90,
                itemSize = 80,
                viewportStartOffset = 100,
                viewportEndOffset = 500,
            ),
        )
    }

    @Test
    fun `item clipped by bottom edge is not fully visible`() {
        assertFalse(
            isLazyListItemFullyVisible(
                itemOffset = 450,
                itemSize = 80,
                viewportStartOffset = 100,
                viewportEndOffset = 500,
            ),
        )
    }
}
