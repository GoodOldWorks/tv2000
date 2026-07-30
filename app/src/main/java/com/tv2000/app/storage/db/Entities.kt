package com.tv2000.app.storage.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "storage_volume")
data class StorageVolumeEntity(
    @PrimaryKey
    @ColumnInfo(name = "volume_id")
    val volumeId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "root_uri")
    val rootUri: String,
    @ColumnInfo(name = "permission_persisted")
    val permissionPersisted: Boolean,
    @ColumnInfo(name = "is_online")
    val isOnline: Boolean,
    @ColumnInfo(name = "first_seen_at")
    val firstSeenAt: Long,
    @ColumnInfo(name = "last_seen_at")
    val lastSeenAt: Long,
)

@Entity(
    tableName = "channel",
    foreignKeys = [
        ForeignKey(
            entity = StorageVolumeEntity::class,
            parentColumns = ["volume_id"],
            childColumns = ["volume_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["channel_number"], unique = true),
        Index(value = ["volume_id", "relative_path"], unique = true),
        Index(value = ["is_visible", "channel_number"]),
    ],
)
data class ChannelEntity(
    @PrimaryKey
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "volume_id")
    val volumeId: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "source_uri")
    val sourceUri: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "channel_number")
    val channelNumber: Int,
    @ColumnInfo(name = "is_visible")
    val isVisible: Boolean,
    @ColumnInfo(name = "last_scanned_at")
    val lastScannedAt: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

@Entity(
    tableName = "episode",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["channel_id"],
            childColumns = ["channel_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["channel_id", "relative_path"], unique = true),
        Index(value = ["channel_id", "is_available"]),
    ],
)
data class EpisodeEntity(
    @PrimaryKey
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "relative_path")
    val relativePath: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    @ColumnInfo(name = "media_uri")
    val mediaUri: String,
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,
    @ColumnInfo(name = "modified_at")
    val modifiedAt: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long?,
    @ColumnInfo(name = "sort_key")
    val sortKey: String,
    @ColumnInfo(name = "scan_generation")
    val scanGeneration: Long,
    @ColumnInfo(name = "is_available")
    val isAvailable: Boolean,
)

@Entity(
    tableName = "channel_playback_state",
    foreignKeys = [
        ForeignKey(
            entity = ChannelEntity::class,
            parentColumns = ["channel_id"],
            childColumns = ["channel_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ChannelPlaybackStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "channel_id")
    val channelId: String,
    @ColumnInfo(name = "episode_id")
    val episodeId: String?,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "episode_completed")
    val episodeCompleted: Boolean,
    @ColumnInfo(name = "subtitle_pref_json")
    val subtitlePrefJson: String?,
    @ColumnInfo(name = "audio_pref_json")
    val audioPrefJson: String?,
    @ColumnInfo(name = "playback_speed")
    val playbackSpeed: Float,
    @ColumnInfo(name = "was_playing")
    val wasPlaying: Boolean,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(
    tableName = "episode_playback",
    foreignKeys = [
        ForeignKey(
            entity = EpisodeEntity::class,
            parentColumns = ["episode_id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EpisodePlaybackEntity(
    @PrimaryKey
    @ColumnInfo(name = "episode_id")
    val episodeId: String,
    @ColumnInfo(name = "last_position_ms")
    val lastPositionMs: Long,
    val completed: Boolean,
    @ColumnInfo(name = "last_played_at")
    val lastPlayedAt: Long,
)

@Entity(tableName = "app_state")
data class AppStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "singleton_id")
    val singletonId: Int = SINGLETON_ID,
    @ColumnInfo(name = "active_channel_id")
    val activeChannelId: String?,
    @ColumnInfo(name = "schema_version")
    val schemaVersion: Int = 1,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
