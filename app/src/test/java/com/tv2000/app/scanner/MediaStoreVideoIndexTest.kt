package com.tv2000.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaStoreVideoIndexTest {
    @Test
    fun `indexes direct videos as naturally sorted channels and episodes`() {
        val channels = indexMediaStoreVideos(
            listOf(
                video(id = 1, path = "西游记/", name = "10.mp4"),
                video(id = 2, path = "动画/", name = "第02集.mkv"),
                video(id = 3, path = "西游记/", name = "2.mp4"),
                video(id = 4, path = "动画/", name = "第01集.mkv"),
            ),
        )

        assertEquals(listOf("动画", "西游记"), channels.map { it.name })
        assertEquals(listOf("动画", "西游记"), channels.map { it.relativePath })
        assertEquals(
            listOf("2.mp4", "10.mp4"),
            channels.last().episodes.map { it.displayName },
        )
    }

    @Test
    fun `formats channel display name without changing its MediaStore path`() {
        val channel = indexMediaStoreVideos(
            listOf(
                video(
                    id = 1,
                    path = "还珠格格.II.1999.WEB-DL.1080p.H265.AAC-HDCTV/",
                    name = "001.mp4",
                ),
            ),
        ).single()

        assertEquals("还珠格格 II", channel.name)
        assertEquals(
            "还珠格格.II.1999.WEB-DL.1080p.H265.AAC-HDCTV",
            channel.relativePath,
        )
    }

    @Test
    fun `ignores root videos nested videos hidden channels and unsupported files`() {
        val channels = indexMediaStoreVideos(
            listOf(
                video(id = 1, path = "", name = "root.mp4"),
                video(id = 2, path = "西游记/第一季/", name = "001.mp4"),
                video(id = 3, path = ".hidden/", name = "001.mp4"),
                video(id = 4, path = "西游记/", name = "cover.jpg"),
                video(id = 5, path = "西游记/", name = "001.mp4", size = 0L),
            ),
        )

        assertEquals(emptyList<IndexedMediaStoreChannel>(), channels)
    }

    @Test
    fun `extracts only one visible channel segment`() {
        assertEquals("西游记", directChannelName("西游记/"))
        assertEquals("西游记", directChannelName("/西游记//"))
        assertNull(directChannelName(""))
        assertNull(directChannelName("西游记/第一季/"))
        assertNull(directChannelName(".hidden/"))
    }

    private fun video(
        id: Long,
        path: String,
        name: String,
        size: Long = 1_024L,
    ) = MediaStoreVideoRecord(
        id = id,
        displayName = name,
        relativePath = path,
        sizeBytes = size,
        modifiedAtSeconds = 1L,
    )
}
