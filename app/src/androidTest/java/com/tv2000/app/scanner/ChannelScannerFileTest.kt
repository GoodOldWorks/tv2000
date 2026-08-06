package com.tv2000.app.scanner

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            val channels = (result as ScanResult.Success).channels

            assertEquals(listOf("频道2", "频道10"), channels.map { it.name })
            assertEquals(
                listOf("2", "10"),
                channels.first().episodes.map { it.title },
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
