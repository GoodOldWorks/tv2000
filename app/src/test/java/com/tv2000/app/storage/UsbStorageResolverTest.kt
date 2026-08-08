package com.tv2000.app.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `media store identity ignores fallback mount path`() {
        assertEquals(
            UsbStorageResolver.volumeIdentity(
                "tv2000-mediastore://ABCD-1234?fallbackPath=%2Fstorage%2FABCD-1234",
            ),
            UsbStorageResolver.volumeIdentity(
                "tv2000-mediastore://abcd-1234?fallbackPath=%2Fmnt%2Fmedia_rw%2FABCD-1234",
            ),
        )
    }

    @Test
    fun `saf and media store roots use the same filesystem uuid`() {
        assertEquals(
            UsbStorageResolver.volumeIdentity(
                "content://com.android.externalstorage.documents/tree/ABCD-1234%3A",
            ),
            UsbStorageResolver.volumeIdentity("tv2000-mediastore://abcd-1234"),
        )
    }

    @Test
    fun `reformatted volume uuid is a new disk identity`() {
        assertNotEquals(
            UsbStorageResolver.volumeIdentity("tv2000-mediastore://ABCD-1234"),
            UsbStorageResolver.volumeIdentity("tv2000-mediastore://EF56-7890"),
        )
    }

    @Test
    fun `unreadable mounted directory remains visible as a permission candidate`() {
        assertEquals(
            true,
            shouldExposeMountedFileRoot(
                isDirectory = true,
                isReadable = false,
                requireReadAccess = false,
            ),
        )
    }

    @Test
    fun `unreadable mounted directory is excluded from playable roots`() {
        assertEquals(
            false,
            shouldExposeMountedFileRoot(
                isDirectory = true,
                isReadable = false,
                requireReadAccess = true,
            ),
        )
    }
}
