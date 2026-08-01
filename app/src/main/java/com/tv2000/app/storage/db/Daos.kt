package com.tv2000.app.storage.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface MediaCatalogDao {
    @Upsert
    suspend fun upsertStorageVolume(volume: StorageVolumeEntity)

    @Query("SELECT * FROM storage_volume WHERE volume_id = :volumeId")
    suspend fun storageVolume(volumeId: String): StorageVolumeEntity?

    @Query("UPDATE storage_volume SET is_online = 0")
    suspend fun markAllVolumesOffline()

    @Query("SELECT * FROM channel WHERE volume_id = :volumeId")
    suspend fun channelsForVolume(volumeId: String): List<ChannelEntity>

    @Query("SELECT COALESCE(MAX(channel_number), 0) FROM channel")
    suspend fun maximumChannelNumber(): Int

    @Query("UPDATE channel SET is_visible = 0")
    suspend fun markAllChannelsInvisible()

    @Query("UPDATE channel SET is_visible = 0 WHERE volume_id = :volumeId")
    suspend fun invalidateChannelsForVolume(volumeId: String)

    @Upsert
    suspend fun upsertChannel(channel: ChannelEntity)

    @Query("UPDATE episode SET is_available = 0 WHERE channel_id = :channelId")
    suspend fun markEpisodesUnavailable(channelId: String)

    @Upsert
    suspend fun upsertEpisodes(episodes: List<EpisodeEntity>)

    @Query("SELECT * FROM channel WHERE is_visible = 1 ORDER BY channel_number")
    suspend fun visibleChannels(): List<ChannelEntity>

    @Query(
        """
        SELECT * FROM channel
        WHERE volume_id = :volumeId AND is_visible = 1
        ORDER BY channel_number
        """,
    )
    suspend fun visibleChannelsForVolume(volumeId: String): List<ChannelEntity>

    @Query(
        """
        SELECT * FROM episode
        WHERE channel_id = :channelId AND is_available = 1
        ORDER BY sort_key, relative_path, episode_id
        """,
    )
    suspend fun availableEpisodes(channelId: String): List<EpisodeEntity>
}

@Dao
interface PlaybackStateDao {
    @Query(
        """
        SELECT active_channel_id FROM app_state
        WHERE singleton_id = 1
        """,
    )
    suspend fun activeChannelId(): String?

    @Upsert
    suspend fun upsertAppState(state: AppStateEntity)

    @Query(
        """
        SELECT * FROM channel_playback_state
        WHERE channel_id = :channelId
        """,
    )
    suspend fun channelPlayback(channelId: String): ChannelPlaybackStateEntity?

    @Upsert
    suspend fun upsertChannelPlayback(state: ChannelPlaybackStateEntity)

    @Upsert
    suspend fun upsertEpisodePlayback(state: EpisodePlaybackEntity)

    @Query("DELETE FROM channel_playback_state WHERE channel_id = :channelId")
    suspend fun deleteChannelPlayback(channelId: String)

    @Query(
        """
        DELETE FROM episode_playback
        WHERE episode_id IN (
            SELECT episode_id FROM episode WHERE channel_id = :channelId
        )
        """,
    )
    suspend fun deleteEpisodePlaybackForChannel(channelId: String)

    @Query("DELETE FROM channel_playback_state")
    suspend fun deleteAllChannelPlayback()

    @Query("DELETE FROM episode_playback")
    suspend fun deleteAllEpisodePlayback()
}
