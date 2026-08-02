package com.tv2000.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbStorageResolverTest {
    @Test
    fun `normalizes relative video directories`() {
        assertEquals("TV2000", UsbStorageResolver.normalizeVideoDirectory(" /TV2000/ "))
        assertEquals(
            "视频/儿童",
            UsbStorageResolver.normalizeVideoDirectory("视频\\儿童"),
        )
        assertEquals("", UsbStorageResolver.normalizeVideoDirectory("  /  "))
    }

    @Test
    fun `rejects parent and current directory segments`() {
        assertNull(UsbStorageResolver.normalizeVideoDirectory("../TV2000"))
        assertNull(UsbStorageResolver.normalizeVideoDirectory("TV2000/./儿童"))
        assertNull(UsbStorageResolver.normalizeVideoDirectory("TV2000//儿童"))
    }
}
