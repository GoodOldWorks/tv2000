package com.tv2000.app.storage

import android.net.Uri
import androidx.room.withTransaction
import com.tv2000.app.model.Channel
import com.tv2000.app.model.Episode
import com.tv2000.app.model.ScannedChannel
import com.tv2000.app.storage.db.ChannelEntity
import com.tv2000.app.storage.db.EpisodeEntity
import com.tv2000.app.storage.db.MediaCatalogDao
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
        val dao = database.mediaCatalogDao()
        val volumeId = resolveVolumeId(rootUriString, dao)
        val now = clock()

        database.withTransaction {
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
            dao.invalidateChannelsForVolume(volumeId)
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

        return loadVisibleChannels(volumeId)
    }

    suspend fun loadIndexedChannels(rootUri: Uri): List<Channel> {
        val dao = database.mediaCatalogDao()
        val volumeId = resolveVolumeId(rootUri.toString(), dao)
        return dao.visibleChannelsForVolume(volumeId).map { channel ->
            channel.toModel(
                episodes = dao.availableEpisodes(channel.channelId),
            )
        }
    }

    suspend fun invalidateIndex(rootUri: Uri) {
        val dao = database.mediaCatalogDao()
        val volumeId = resolveVolumeId(rootUri.toString(), dao)
        dao.invalidateChannelsForVolume(volumeId)
    }

    suspend fun markVolumeOffline(rootUri: Uri) {
        val dao = database.mediaCatalogDao()
        val volumeId = resolveVolumeId(rootUri.toString(), dao)
        dao.markVolumeOffline(volumeId)
    }

    private suspend fun resolveVolumeId(
        rootUri: String,
        dao: MediaCatalogDao,
    ): String {
        val volumeIdentity = UsbStorageResolver.volumeIdentity(rootUri)
        val canonicalSource = volumeIdentity.takeIf { identity ->
            identity.startsWith(UUID_IDENTITY_PREFIX)
        } ?: rootUri
        val canonicalVolumeId = StableMediaIds.volume(canonicalSource)
        dao.storageVolume(canonicalVolumeId)?.let { return it.volumeId }
        return dao.storageVolumes().firstOrNull { storedVolume ->
            UsbStorageResolver.volumeIdentity(storedVolume.rootUri) == volumeIdentity
        }?.volumeId ?: canonicalVolumeId
    }

    private suspend fun loadVisibleChannels(volumeId: String): List<Channel> {
        val dao = database.mediaCatalogDao()
        return dao.visibleChannelsForVolume(volumeId).map { channel ->
            channel.toModel(
                episodes = dao.availableEpisodes(channel.channelId),
            )
        }
    }

    private fun ChannelEntity.toModel(
        episodes: List<EpisodeEntity>,
    ): Channel = Channel(
        id = channelId,
        legacyId = sourceUri,
        number = channelNumber,
        name = displayName,
        episodes = episodes.map { episode ->
            Episode(
                id = episode.episodeId,
                legacyId = episode.mediaUri,
                title = episode.displayName,
                uri = Uri.parse(episode.mediaUri),
            )
        },
    )

    private companion object {
        const val SORT_KEY_WIDTH = 10
        const val UUID_IDENTITY_PREFIX = "uuid:"
    }
}
