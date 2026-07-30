package com.tv2000.app.storage

import android.net.Uri
import androidx.room.withTransaction
import com.tv2000.app.model.Channel
import com.tv2000.app.model.Episode
import com.tv2000.app.model.ScannedChannel
import com.tv2000.app.storage.db.ChannelEntity
import com.tv2000.app.storage.db.EpisodeEntity
import com.tv2000.app.storage.db.StorageVolumeEntity
import com.tv2000.app.storage.db.Tv2000Database

class MediaCatalogRepository(
    private val database: Tv2000Database,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun replaceSnapshot(
        rootUri: Uri,
        scannedChannels: List<ScannedChannel>,
    ): List<Channel> {
        val rootUriString = rootUri.toString()
        val volumeId = StableMediaIds.volume(rootUriString)
        val now = clock()

        database.withTransaction {
            val dao = database.mediaCatalogDao()
            val existingVolume = dao.storageVolume(volumeId)
            val existingChannels = dao.channelsForVolume(volumeId)
            val existingNumbers = existingChannels.associate {
                it.channelId to it.channelNumber
            }
            val existingCreatedAt = existingChannels.associate {
                it.channelId to it.createdAt
            }
            val channelIds = scannedChannels.map { scanned ->
                StableMediaIds.channel(volumeId, scanned.relativePath)
            }
            val assignedNumbers = ChannelNumberAllocator.assign(
                channelIdsInDiscoveryOrder = channelIds,
                existingNumbers = existingNumbers,
                maximumExistingNumber = dao.maximumChannelNumber(),
            )

            dao.markAllVolumesOffline()
            dao.markAllChannelsInvisible()
            dao.upsertStorageVolume(
                StorageVolumeEntity(
                    volumeId = volumeId,
                    displayName = rootUri.lastPathSegment.orEmpty().ifBlank { "U 盘" },
                    rootUri = rootUriString,
                    permissionPersisted = rootUri.scheme != "file",
                    isOnline = true,
                    firstSeenAt = existingVolume?.firstSeenAt ?: now,
                    lastSeenAt = now,
                ),
            )

            scannedChannels.zip(channelIds).forEach { (scanned, channelId) ->
                dao.upsertChannel(
                    ChannelEntity(
                        channelId = channelId,
                        volumeId = volumeId,
                        relativePath = scanned.relativePath,
                        sourceUri = scanned.sourceUri.toString(),
                        displayName = scanned.name,
                        channelNumber = requireNotNull(assignedNumbers[channelId]),
                        isVisible = true,
                        lastScannedAt = now,
                        createdAt = existingCreatedAt[channelId] ?: now,
                    ),
                )
                dao.markEpisodesUnavailable(channelId)
                dao.upsertEpisodes(
                    scanned.episodes.mapIndexed { index, episode ->
                        EpisodeEntity(
                            episodeId = StableMediaIds.episode(
                                channelId,
                                episode.relativePath,
                            ),
                            channelId = channelId,
                            relativePath = episode.relativePath,
                            displayName = episode.title,
                            mediaUri = episode.uri.toString(),
                            sizeBytes = episode.sizeBytes,
                            modifiedAt = episode.modifiedAt,
                            durationMs = null,
                            sortKey = index.toString().padStart(SORT_KEY_WIDTH, '0'),
                            scanGeneration = now,
                            isAvailable = true,
                        )
                    },
                )
            }
        }

        return loadVisibleChannels()
    }

    private suspend fun loadVisibleChannels(): List<Channel> {
        val dao = database.mediaCatalogDao()
        return dao.visibleChannels().map { channel ->
            Channel(
                id = channel.channelId,
                legacyId = channel.sourceUri,
                number = channel.channelNumber,
                name = channel.displayName,
                episodes = dao.availableEpisodes(channel.channelId).map { episode ->
                    Episode(
                        id = episode.episodeId,
                        legacyId = episode.mediaUri,
                        title = episode.displayName,
                        uri = Uri.parse(episode.mediaUri),
                    )
                },
            )
        }
    }

    private companion object {
        const val SORT_KEY_WIDTH = 10
    }
}
