package com.tv2000.app.storage

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tv2000.app.model.ScannedChannel
import com.tv2000.app.model.ScannedEpisode
import com.tv2000.app.storage.db.ChannelEntity
import com.tv2000.app.storage.db.StorageVolumeEntity
import com.tv2000.app.storage.db.Tv2000Database
import com.tv2000.app.smb.SmbResource
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
            StableMediaIds.volume(UsbStorageResolver.volumeIdentity(rootUri.toString())),
        )
        assertFalse(allChannels.first { it.displayName == "西游记" }.isVisible)
        assertTrue(allChannels.first { it.displayName == "BBC" }.isVisible)
    }

    @Test
    fun diskSwapKeepsEachUuidCatalogChannelNumbersAndHistory() = runBlocking {
        val suffix = System.nanoTime().toString()
        val diskA = Uri.parse(
            "tv2000-mediastore://AAAA-$suffix?fallbackPath=%2Fstorage%2FAAAA-$suffix",
        )
        val diskAWithChangedMountPath = Uri.parse(
            "tv2000-mediastore://AAAA-$suffix?fallbackPath=%2Fmnt%2Fmedia_rw%2FAAAA-$suffix",
        )
        val diskB = Uri.parse("tv2000-mediastore://BBBB-$suffix")
        val reformattedDiskA = Uri.parse("tv2000-mediastore://CCCC-$suffix")
        val historyStore = PlaybackHistoryStore(context, database) { TEST_TIME }

        val diskAChannels = catalogRepository.replaceSnapshot(
            diskA,
            listOf(
                scannedChannel("动画", "001.mp4"),
                scannedChannel("西游记", "第01集.mp4"),
            ),
        )
        historyStore.saveActiveChannel(diskAChannels.last().id, diskA.toString())
        historyStore.saveChannelPlayback(
            channelId = diskAChannels.last().id,
            episodeId = diskAChannels.last().episodes.single().id,
            positionMs = 23_410L,
            wasPlaying = true,
        )

        val diskBChannel = catalogRepository.replaceSnapshot(
            diskB,
            listOf(scannedChannel("动画", "001.mp4")),
        ).single()
        historyStore.saveActiveChannel(diskBChannel.id, diskB.toString())

        assertEquals(listOf(1, 2), diskAChannels.map { it.number })
        assertEquals(3, diskBChannel.number)
        assertEquals(
            listOf(1, 2),
            catalogRepository.loadIndexedChannels(diskAWithChangedMountPath).map { it.number },
        )
        assertEquals(
            diskAChannels.last().id,
            historyStore.activeChannelId(diskAWithChangedMountPath.toString()),
        )
        assertEquals(diskBChannel.id, historyStore.activeChannelId(diskB.toString()))

        val reinsertedDiskA = catalogRepository.replaceSnapshot(
            diskAWithChangedMountPath,
            listOf(
                scannedChannel("动画", "001.mp4"),
                scannedChannel("西游记", "第01集.mp4"),
            ),
        )
        assertEquals(listOf(1, 2), reinsertedDiskA.map { it.number })
        assertEquals(
            23_410L,
            historyStore.channelPlayback(reinsertedDiskA.last().id)?.positionMs,
        )

        val reformattedChannel = catalogRepository.replaceSnapshot(
            reformattedDiskA,
            listOf(scannedChannel("动画", "001.mp4")),
        ).single()
        assertEquals(4, reformattedChannel.number)
    }

    @Test
    fun existingFullUriVolumeIdIsReusedAfterMountPathChanges() = runBlocking {
        val suffix = System.nanoTime().toString()
        val oldRoot = Uri.parse(
            "tv2000-mediastore://LEGACY-$suffix?fallbackPath=%2Fstorage%2FLEGACY-$suffix",
        )
        val remountedRoot = Uri.parse(
            "tv2000-mediastore://LEGACY-$suffix?fallbackPath=%2Fmnt%2Fmedia_rw%2FLEGACY-$suffix",
        )
        val legacyVolumeId = StableMediaIds.volume(oldRoot.toString())
        val legacyChannelId = StableMediaIds.channel(legacyVolumeId, "动画")
        val dao = database.mediaCatalogDao()
        dao.upsertStorageVolume(
            StorageVolumeEntity(
                volumeId = legacyVolumeId,
                displayName = "旧版 U 盘",
                rootUri = oldRoot.toString(),
                permissionPersisted = true,
                isOnline = true,
                firstSeenAt = TEST_TIME,
                lastSeenAt = TEST_TIME,
            ),
        )
        dao.upsertChannel(
            ChannelEntity(
                channelId = legacyChannelId,
                volumeId = legacyVolumeId,
                relativePath = "动画",
                sourceUri = "file:///storage/legacy/动画",
                displayName = "动画",
                channelNumber = 42,
                isVisible = true,
                lastScannedAt = TEST_TIME,
                createdAt = TEST_TIME,
            ),
        )

        val channels = catalogRepository.replaceSnapshot(
            remountedRoot,
            listOf(scannedChannel("动画", "001.mp4")),
        )

        assertEquals(legacyChannelId, channels.single().id)
        assertEquals(42, channels.single().number)
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

    @Test
    fun invalidatingIndexKeepsPlaybackHistory() = runBlocking {
        val rootUri = Uri.parse("file:///storage/usb")
        val channel = catalogRepository.replaceSnapshot(
            rootUri,
            listOf(scannedChannel("动画", "001.mp4")),
        ).single()
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

        catalogRepository.invalidateIndex(rootUri)

        assertTrue(catalogRepository.loadIndexedChannels(rootUri).isEmpty())
        assertEquals(
            23_410L,
            historyStore.channelPlayback(channel.id)?.positionMs,
        )
    }

    @Test
    fun resetCurrentChannelKeepsOtherChannelHistory() = runBlocking {
        val channels = catalogRepository.replaceSnapshot(
            Uri.parse("file:///storage/usb"),
            listOf(
                scannedChannel("动画", "001.mp4"),
                scannedChannel("西游记", "第01集.mp4"),
            ),
        )
        val historyStore = PlaybackHistoryStore(
            context = context,
            database = database,
            clock = { TEST_TIME },
        )
        channels.forEachIndexed { index, channel ->
            historyStore.saveChannelPlayback(
                channelId = channel.id,
                episodeId = channel.episodes.single().id,
                positionMs = (index + 1) * 10_000L,
                wasPlaying = true,
            )
        }

        historyStore.resetChannelPlayback(channels.first().id)

        assertEquals(null, historyStore.channelPlayback(channels.first().id))
        assertEquals(
            20_000L,
            historyStore.channelPlayback(channels.last().id)?.positionMs,
        )
    }

    @Test
    fun resetAllChannelsClearsEveryPlaybackHistory() = runBlocking {
        val channels = catalogRepository.replaceSnapshot(
            Uri.parse("file:///storage/usb"),
            listOf(
                scannedChannel("动画", "001.mp4"),
                scannedChannel("西游记", "第01集.mp4"),
            ),
        )
        val historyStore = PlaybackHistoryStore(
            context = context,
            database = database,
            clock = { TEST_TIME },
        )
        channels.forEach { channel ->
            historyStore.saveChannelPlayback(
                channelId = channel.id,
                episodeId = channel.episodes.single().id,
                positionMs = 10_000L,
                wasPlaying = true,
            )
        }

        historyStore.resetAllPlayback()

        assertTrue(
            channels.all { channel ->
                historyStore.channelPlayback(channel.id) == null
            },
        )
    }

    @Test
    fun storesMultipleSmbResourcesAndUpdatesMatchingLocation() = runBlocking {
        val suffix = System.nanoTime().toString()
        val first = SmbResource(
            host = "first-$suffix.invalid",
            share = "Media",
            username = "viewer",
            password = "old-password",
        )
        val second = SmbResource(
            host = "second-$suffix.invalid",
            share = "Media",
        )
        val historyStore = PlaybackHistoryStore(context, database)

        try {
            historyStore.saveSmbResource(first)
            historyStore.saveSmbResource(second)

            val reloaded = PlaybackHistoryStore(context, database).smbResources()
            assertTrue(reloaded.any { resource -> resource.id == first.id })
            assertTrue(reloaded.any { resource -> resource.id == second.id })

            val updated = first.copy(password = "new-password")
            val afterUpdate = historyStore.saveSmbResource(updated)
            assertEquals(
                1,
                afterUpdate.count { resource -> resource.id == first.id },
            )
            assertEquals(
                "new-password",
                afterUpdate.first { resource -> resource.id == first.id }.password,
            )
        } finally {
            historyStore.deleteSmbResource(first.id)
            historyStore.deleteSmbResource(second.id)
        }
    }

    @Test
    fun directFileSnapshotRevisionIsScopedToVolumeAndDirectory() = runBlocking {
        val suffix = System.nanoTime().toString()
        val disk = "tv2000-mediastore://AAAA-$suffix"
        val remountedDisk = "$disk?fallbackPath=%2Fmnt%2Fmedia_rw%2FAAAA-$suffix"
        val otherDisk = "tv2000-mediastore://BBBB-$suffix"
        val historyStore = PlaybackHistoryStore(context, database)

        historyStore.saveDirectFileSnapshotRevision(
            rootUri = disk,
            videoDirectory = "TV2000",
            sourceRevision = "generation:42",
        )

        assertEquals(
            "generation:42",
            PlaybackHistoryStore(context, database).directFileSnapshotRevision(
                rootUri = disk,
                videoDirectory = "TV2000",
            ),
        )
        assertEquals(
            "generation:42",
            historyStore.directFileSnapshotRevision(remountedDisk, "tv2000"),
        )
        assertEquals(null, historyStore.directFileSnapshotRevision(disk, "其他目录"))
        assertEquals(null, historyStore.directFileSnapshotRevision(otherDisk, "TV2000"))

        historyStore.clearDirectFileSnapshotRevision(remountedDisk, "tv2000")
        assertEquals(null, historyStore.directFileSnapshotRevision(disk, "TV2000"))
    }

    @Test
    fun resetAppDataClearsCatalogPlaybackAndResourceSettings() = runBlocking {
        val rootUri = Uri.parse("file:///storage/usb-reset")
        val channel = catalogRepository.replaceSnapshot(
            rootUri,
            listOf(scannedChannel("动画", "001.mp4")),
        ).single()
        val historyStore = PlaybackHistoryStore(context, database)
        historyStore.saveRootUri(rootUri.toString())
        historyStore.saveUsbRootUri(rootUri.toString())
        historyStore.saveUsbVideoDirectory("儿童节目")
        historyStore.saveSmbResource(
            SmbResource(host = "reset-test.invalid", share = "Media"),
        )
        historyStore.saveChannelPlayback(
            channelId = channel.id,
            episodeId = channel.episodes.single().id,
            positionMs = 10_000L,
            wasPlaying = true,
        )

        historyStore.resetAppData()

        assertEquals(null, historyStore.rootUri())
        assertEquals(null, historyStore.usbRootUri())
        assertEquals(UsbStorageResolver.DEFAULT_VIDEO_DIRECTORY, historyStore.usbVideoDirectory())
        assertTrue(historyStore.smbResources().isEmpty())
        assertTrue(catalogRepository.loadIndexedChannels(rootUri).isEmpty())
        assertEquals(null, historyStore.channelPlayback(channel.id))
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
