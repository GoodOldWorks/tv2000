package com.tv2000.app.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.tv2000.app.storage.db.AppStateEntity
import com.tv2000.app.storage.db.ChannelPlaybackStateEntity
import com.tv2000.app.storage.db.EpisodePlaybackEntity
import com.tv2000.app.storage.db.Tv2000Database
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.MessageDigest

private val Context.tv2000DataStore by preferencesDataStore(name = "tv2000")

class PlaybackHistoryStore(
    private val context: Context,
    private val database: Tv2000Database = Tv2000Database.get(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun rootUri(): String? =
        context.tv2000DataStore.data.first()[ROOT_URI]

    suspend fun saveRootUri(uri: String) {
        context.tv2000DataStore.edit { preferences ->
            preferences[ROOT_URI] = uri
        }
    }

    suspend fun clearRootUri() {
        context.tv2000DataStore.edit { preferences ->
            preferences.remove(ROOT_URI)
        }
    }

    suspend fun activeChannelId(): String? =
        database.playbackStateDao().activeChannelId()
            ?: context.tv2000DataStore.data.first()[LEGACY_ACTIVE_CHANNEL]

    suspend fun saveActiveChannel(channelId: String) {
        database.playbackStateDao().upsertAppState(
            AppStateEntity(
                activeChannelId = channelId,
                updatedAt = clock(),
            ),
        )
    }

    suspend fun channelPlayback(
        channelId: String,
        legacyChannelId: String? = null,
    ): ChannelPlayback? {
        database.playbackStateDao().channelPlayback(channelId)?.let { state ->
            return state.toPlayback()
        }

        val legacyIds = listOfNotNull(channelId, legacyChannelId).distinct()
        val preferences = context.tv2000DataStore.data.first()
        val legacy = legacyIds.firstNotNullOfOrNull { legacyId ->
            val encoded = preferences[historyKey(legacyId)] ?: return@firstNotNullOfOrNull null
            runCatching {
                val json = JSONObject(encoded)
                ChannelPlayback(
                    episodeId = json.getString("episodeId"),
                    positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                    wasPlaying = json.optBoolean("wasPlaying", true),
                )
            }.getOrNull()
        } ?: return null

        database.playbackStateDao().upsertChannelPlayback(
            legacy.toEntity(channelId, clock()),
        )
        return legacy
    }

    suspend fun saveChannelPlayback(
        channelId: String,
        episodeId: String,
        positionMs: Long,
        wasPlaying: Boolean,
        completed: Boolean = false,
        subtitlePreferenceJson: String? = null,
        audioPreferenceJson: String? = null,
        playbackSpeed: Float = DEFAULT_PLAYBACK_SPEED,
    ) {
        val now = clock()
        val safePositionMs = positionMs.coerceAtLeast(0L)
        database.withTransaction {
            val dao = database.playbackStateDao()
            dao.upsertChannelPlayback(
                ChannelPlaybackStateEntity(
                    channelId = channelId,
                    episodeId = episodeId,
                    positionMs = safePositionMs,
                    episodeCompleted = completed,
                    subtitlePrefJson = subtitlePreferenceJson,
                    audioPrefJson = audioPreferenceJson,
                    playbackSpeed = playbackSpeed.coerceIn(
                        MIN_PLAYBACK_SPEED,
                        MAX_PLAYBACK_SPEED,
                    ),
                    wasPlaying = wasPlaying,
                    updatedAt = now,
                ),
            )
            dao.upsertEpisodePlayback(
                EpisodePlaybackEntity(
                    episodeId = episodeId,
                    lastPositionMs = safePositionMs,
                    completed = completed,
                    lastPlayedAt = now,
                ),
            )
        }
    }

    suspend fun moveChannelCursor(
        channelId: String,
        outgoingEpisodeId: String,
        outgoingPositionMs: Long,
        targetEpisodeId: String,
        wasPlaying: Boolean,
    ) {
        val now = clock()
        database.withTransaction {
            val dao = database.playbackStateDao()
            val existing = dao.channelPlayback(channelId)
            dao.upsertEpisodePlayback(
                EpisodePlaybackEntity(
                    episodeId = outgoingEpisodeId,
                    lastPositionMs = outgoingPositionMs.coerceAtLeast(0L),
                    completed = false,
                    lastPlayedAt = now,
                ),
            )
            dao.upsertChannelPlayback(
                ChannelPlaybackStateEntity(
                    channelId = channelId,
                    episodeId = targetEpisodeId,
                    positionMs = 0L,
                    episodeCompleted = false,
                    subtitlePrefJson = existing?.subtitlePrefJson,
                    audioPrefJson = existing?.audioPrefJson,
                    playbackSpeed = existing?.playbackSpeed ?: DEFAULT_PLAYBACK_SPEED,
                    wasPlaying = wasPlaying,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun historyKey(channelId: String) =
        stringPreferencesKey("history_${sha256(channelId)}")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        val ROOT_URI = stringPreferencesKey("root_uri")
        val LEGACY_ACTIVE_CHANNEL = stringPreferencesKey("active_channel")
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2.0f
    }
}

data class ChannelPlayback(
    val episodeId: String,
    val positionMs: Long,
    val wasPlaying: Boolean,
    val completed: Boolean = false,
    val subtitlePreferenceJson: String? = null,
    val audioPreferenceJson: String? = null,
    val playbackSpeed: Float = 1.0f,
)

private fun ChannelPlaybackStateEntity.toPlayback() = ChannelPlayback(
    episodeId = episodeId.orEmpty(),
    positionMs = positionMs,
    wasPlaying = wasPlaying,
    completed = episodeCompleted,
    subtitlePreferenceJson = subtitlePrefJson,
    audioPreferenceJson = audioPrefJson,
    playbackSpeed = playbackSpeed,
)

private fun ChannelPlayback.toEntity(
    channelId: String,
    now: Long,
) = ChannelPlaybackStateEntity(
    channelId = channelId,
    episodeId = episodeId,
    positionMs = positionMs,
    episodeCompleted = completed,
    subtitlePrefJson = subtitlePreferenceJson,
    audioPrefJson = audioPreferenceJson,
    playbackSpeed = playbackSpeed,
    wasPlaying = wasPlaying,
    updatedAt = now,
)
