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
}
