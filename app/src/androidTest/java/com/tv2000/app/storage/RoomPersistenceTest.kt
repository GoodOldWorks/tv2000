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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: Tv2000Database
    private lateinit var catalogRepository: MediaCatalogRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(
            context,
            Tv2000Database::class.java,
        ).build()
        catalogRepository = MediaCatalogRepository(database) { TEST_TIME }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun snapshotKeepsExistingNumbersAndDoesNotReuseOfflineNumbers() = runBlocking {
        val rootUri = Uri.parse("file:///storage/usb")

        val first = catalogRepository.replaceSnapshot(
            rootUri,
            listOf(
                scannedChannel("动画", "001.mp4"),
                scannedChannel("西游记", "第01集.mp4"),
            ),
        )
        assertEquals(listOf(1, 2), first.map { it.number })

        val second = catalogRepository.replaceSnapshot(
            rootUri,
            listOf(
                scannedChannel("BBC", "S01E01.mp4"),
                scannedChannel("动画", "001.mp4"),
            ),
        )

        assertEquals(3, second.first { it.name == "BBC" }.number)
        assertEquals(1, second.first { it.name == "动画" }.number)
        val allChannels = database.mediaCatalogDao().channelsForVolume(
            StableMediaIds.volume(rootUri.toString()),
        )
        assertFalse(allChannels.first { it.displayName == "西游记" }.isVisible)
        assertTrue(allChannels.first { it.displayName == "BBC" }.isVisible)
    }

    @Test
    fun channelPlaybackIsStoredInRoom() = runBlocking {
        val channels = catalogRepository.replaceSnapshot(
            Uri.parse("file:///storage/usb"),
            listOf(scannedChannel("动画", "001.mp4")),
        )
        val channel = channels.single()
        val episode = channel.episodes.single()
        val historyStore = PlaybackHistoryStore(
            context = context,
            database = database,
            clock = { TEST_TIME },
        )

        historyStore.saveChannelPlayback(
            channelId = channel.id,
            episodeId = episode.id,
            positionMs = 23_410L,
            wasPlaying = true,
        )
        historyStore.saveActiveChannel(channel.id)

        assertEquals(channel.id, historyStore.activeChannelId())
        assertEquals(
            ChannelPlayback(
                episodeId = episode.id,
                positionMs = 23_410L,
                wasPlaying = true,
            ),
            historyStore.channelPlayback(channel.id),
        )
    }

    private fun scannedChannel(
        name: String,
        episodeName: String,
    ): ScannedChannel {
        val channelUri = Uri.parse("file:///storage/usb/$name")
        return ScannedChannel(
            relativePath = name,
            name = name,
            sourceUri = channelUri,
            episodes = listOf(
                ScannedEpisode(
                    relativePath = episodeName,
                    title = episodeName.substringBeforeLast('.'),
                    uri = Uri.withAppendedPath(channelUri, episodeName),
                    sizeBytes = 1_024L,
                    modifiedAt = TEST_TIME,
                ),
            ),
        )
    }

    private companion object {
        const val TEST_TIME = 1_700_000_000_000L
    }
}
