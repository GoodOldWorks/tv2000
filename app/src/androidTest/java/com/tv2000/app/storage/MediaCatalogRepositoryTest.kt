package com.tv2000.app.storage

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tv2000.app.model.ScannedChannel
import com.tv2000.app.model.ScannedEpisode
import com.tv2000.app.storage.db.Tv2000Database
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCatalogRepositoryTest {
    private lateinit var database: Tv2000Database
    private lateinit var repository: MediaCatalogRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            Tv2000Database::class.java,
        ).build()
        repository = MediaCatalogRepository(
            database = database,
            clock = { 123_456L },
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun indexedChannelsAreLoadedOnlyForTheSelectedResource() = runBlocking {
        val selectedRoot = Uri.parse("file:///usb-a")
        repository.replaceSnapshot(
            rootUri = selectedRoot,
            scannedChannels = listOf(
                ScannedChannel(
                    relativePath = "西游记",
                    name = "西游记",
                    sourceUri = Uri.parse("file:///usb-a/西游记"),
                    episodes = listOf(
                        ScannedEpisode(
                            relativePath = "001.mp4",
                            title = "001",
                            uri = Uri.parse("file:///usb-a/西游记/001.mp4"),
                            sizeBytes = 1_024L,
                            modifiedAt = 100L,
                        ),
                    ),
                ),
            ),
        )

        val indexed = repository.loadIndexedChannels(selectedRoot)

        assertEquals(1, indexed.size)
        assertEquals("西游记", indexed.single().name)
        assertEquals("001", indexed.single().episodes.single().title)
        assertEquals(
            Uri.parse("file:///usb-a/西游记/001.mp4"),
            indexed.single().episodes.single().uri,
        )
        assertTrue(
            repository.loadIndexedChannels(Uri.parse("file:///usb-b")).isEmpty(),
        )
    }

    @Test
    fun partialMediaStoreSnapshotsOnlyGrowUntilAnAuthoritativeReplace() = runBlocking {
        val rootUri = Uri.parse("tv2000-mediastore://TEST-0001")

        val firstPartial = repository.mergeSnapshot(
            rootUri = rootUri,
            scannedChannels = listOf(scannedChannel(1, episodeCount = 31)),
        )
        assertEquals(1, firstPartial.size)
        assertEquals(31, firstPartial.single().episodes.size)

        val secondPartial = repository.mergeSnapshot(
            rootUri = rootUri,
            scannedChannels = (1..6).map { channelNumber ->
                scannedChannel(channelNumber, episodeCount = 84)
            },
        )
        assertEquals(6, secondPartial.size)
        assertEquals(84, secondPartial.first().episodes.size)

        val complete = repository.mergeSnapshot(
            rootUri = rootUri,
            scannedChannels = (1..16).map { channelNumber ->
                scannedChannel(channelNumber, episodeCount = 84)
            },
        )
        assertEquals(16, complete.size)
        assertEquals((1..16).toList(), complete.map { it.number })

        val laterPartial = repository.mergeSnapshot(
            rootUri = rootUri,
            scannedChannels = listOf(scannedChannel(1, episodeCount = 31)),
        )
        assertEquals(16, laterPartial.size)
        assertEquals(84, laterPartial.first().episodes.size)

        val authoritative = repository.replaceSnapshot(
            rootUri = rootUri,
            scannedChannels = listOf(scannedChannel(1, episodeCount = 31)),
        )
        assertEquals(1, authoritative.size)
        assertEquals(31, authoritative.single().episodes.size)
    }

    private fun scannedChannel(number: Int, episodeCount: Int): ScannedChannel {
        val channelPath = "频道$number"
        return ScannedChannel(
            relativePath = channelPath,
            name = channelPath,
            sourceUri = Uri.parse("tv2000-mediastore://TEST-0001/$channelPath"),
            episodes = (1..episodeCount).map { episodeNumber ->
                val episodeName = episodeNumber.toString().padStart(3, '0') + ".mp4"
                ScannedEpisode(
                    relativePath = episodeName,
                    title = episodeName.substringBeforeLast('.'),
                    uri = Uri.parse("content://media/test/$number/$episodeNumber"),
                    sizeBytes = 1_024L,
                    modifiedAt = 100L,
                )
            },
        )
    }
}
