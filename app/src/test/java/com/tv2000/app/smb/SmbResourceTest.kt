package com.tv2000.app.smb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbResourceTest {
    @Test
    fun parsesAddressWithDefaultPortAndDirectory() {
        val resource = SmbResource.parse(
            address = "smb://192.168.1.20/Media/电视剧/亮剑",
            username = "viewer",
            password = "secret",
        ).getOrThrow()

        assertEquals("192.168.1.20", resource.host)
        assertEquals(445, resource.port)
        assertEquals("Media", resource.share)
        assertEquals("电视剧\\亮剑", resource.directory)
        assertEquals("电视剧\\亮剑\\01.mp4", resource.remotePath("01.mp4"))
        assertEquals("viewer", resource.username)
        assertEquals("secret", resource.password)
    }

    @Test
    fun acceptsAddressWithoutSchemeAndWithCustomPort() {
        val resource = SmbResource.parse("nas.local:1445/Videos").getOrThrow()

        assertEquals("nas.local", resource.host)
        assertEquals(1445, resource.port)
        assertEquals("Videos", resource.share)
        assertEquals("smb://nas.local:1445/Videos", resource.address)
    }

    @Test
    fun acceptsDirectoryNamesWithSpaces() {
        val resource = SmbResource.parse("smb://nas/Media/TV Shows").getOrThrow()

        assertEquals("TV Shows", resource.directory)
    }

    @Test
    fun rejectsAddressWithoutShareName() {
        val result = SmbResource.parse("smb://192.168.1.20")

        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsParentDirectoryTraversal() {
        val result = SmbResource.parse("smb://nas/Media/../private")

        assertTrue(result.isFailure)
    }
}
