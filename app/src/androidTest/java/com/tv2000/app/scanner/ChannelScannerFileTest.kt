package com.tv2000.app.scanner

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.tv2000.app.storage.UsbStorageResolver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelScannerFileTest {
    @Test
    fun fileFallbackScansEachVideoAndKeepsNaturalOrder() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "channel-scanner-${System.nanoTime()}")
        try {
            video(root, "TV2000/频道10/10.mp4")
            video(root, "TV2000/频道2/2.mp4")
            video(root, "TV2000/频道2/10.mp4")
            video(root, "TV2000/频道2/.hidden.mp4")
            File(root, "TV2000/频道2/empty.mp4").createNewFile()

            val result = ChannelScanner().scan(context, Uri.fromFile(root))
            val success = result as ScanResult.Success
            val channels = success.channels

            assertTrue(success.isAuthoritative)
            assertEquals(listOf("频道2", "频道10"), channels.map { it.name })
            assertEquals(
                listOf("2", "10"),
                channels.first().episodes.map { it.title },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun mediaStoreRootUsesDirectFileSnapshotBeforeSystemIndex() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-store-fallback-${System.nanoTime()}")
        try {
            (1..84).forEach { episodeNumber ->
                video(
                    root,
                    "TV2000/三国演义.1994.全84集.国语.简体中字/" +
                        episodeNumber.toString().padStart(3, '0') + ".mkv",
                )
            }
            (2..15).forEach { channelNumber ->
                video(root, "TV2000/频道$channelNumber/001.mp4")
            }
            val rootUri = UsbStorageResolver.mediaStoreRootUri(
                volumeName = "TEST-0001",
                fallbackPath = root.absolutePath,
            )

            val result = ChannelScanner().scan(context, rootUri)
            val success = result as ScanResult.Success

            assertTrue(success.isAuthoritative)
            assertEquals(15, success.channels.size)
            assertEquals(
                84,
                success.channels.single { channel -> channel.name == "三国演义" }.episodes.size,
            )
            assertTrue(
                success.channels.all { channel ->
                    channel.episodes.all { episode -> episode.uri.scheme == "file" }
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun video(root: File, relativePath: String) {
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
    }
}
