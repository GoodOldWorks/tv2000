package com.tv2000.app.scanner

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tv2000.app.model.ScannedChannel
import com.tv2000.app.model.ScannedEpisode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelScannerMergeTest {
    @Test
    fun mediaStorePartialSnapshotIsCompletedFromFileSystem() {
        val mediaStoreChannels = listOf(
            channel("动画", "001.mp4", "content://video/1"),
            channel("西游记", "001.mp4", "content://video/2"),
        )
        val fileChannels = listOf(
            channel("动画", "001.mp4", "file:///usb/tv2000/动画/001.mp4"),
            channel("西游记", "001.mp4", "file:///usb/tv2000/西游记/001.mp4"),
            channel("猫和老鼠", "001.mp4", "file:///usb/tv2000/猫和老鼠/001.mp4"),
            channel("BBC", "001.mp4", "file:///usb/tv2000/BBC/001.mp4"),
            channel("三国演义", "001.mp4", "file:///usb/tv2000/三国演义/001.mp4"),
            channel("纪录片", "001.mp4", "file:///usb/tv2000/纪录片/001.mp4"),
        )

        val merged = mergeScannedChannels(mediaStoreChannels, fileChannels)

        assertEquals(6, merged.size)
        assertEquals(
            Uri.parse("content://video/1"),
            merged.first { it.name == "动画" }.episodes.single().uri,
        )
        assertEquals(
            Uri.parse("file:///usb/tv2000/猫和老鼠/001.mp4"),
            merged.first { it.name == "猫和老鼠" }.episodes.single().uri,
        )
    }

    @Test
    fun fileSystemAddsEpisodesMissingFromMediaStore() {
        val mediaStoreChannel = channel("动画", "001.mp4", "content://video/1")
        val fileChannel = mediaStoreChannel.copy(
            episodes = listOf(
                episode("001.mp4", "file:///usb/tv2000/动画/001.mp4"),
                episode("002.mp4", "file:///usb/tv2000/动画/002.mp4"),
            ),
        )

        val merged = mergeScannedChannels(
            primary = listOf(mediaStoreChannel),
            secondary = listOf(fileChannel),
        ).single()

        assertEquals(listOf("001.mp4", "002.mp4"), merged.episodes.map { it.relativePath })
        assertEquals(Uri.parse("content://video/1"), merged.episodes.first().uri)
        assertEquals(
            Uri.parse("file:///usb/tv2000/动画/002.mp4"),
            merged.episodes.last().uri,
        )
    }

    private fun channel(
        name: String,
        episodeName: String,
        episodeUri: String,
    ): ScannedChannel = ScannedChannel(
        relativePath = name,
        name = name,
        sourceUri = Uri.parse("file:///usb/tv2000/$name"),
        episodes = listOf(episode(episodeName, episodeUri)),
    )

    private fun episode(name: String, uri: String): ScannedEpisode = ScannedEpisode(
        relativePath = name,
        title = name.substringBeforeLast('.'),
        uri = Uri.parse(uri),
        sizeBytes = 1_024L,
        modifiedAt = 1L,
    )
}
