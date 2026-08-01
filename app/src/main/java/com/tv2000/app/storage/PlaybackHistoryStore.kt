package com.tv2000.app.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.tv2000.app.storage.db.AppStateEntity
import com.tv2000.app.storage.db.ChannelPlaybackStateEntity
import com.tv2000.app.storage.db.EpisodePlaybackEntity
import com.tv2000.app.storage.db.Tv2000Database
import com.tv2000.app.smb.DEFAULT_SMB_PORT
import com.tv2000.app.smb.SmbResource
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.security.MessageDigest

private val Context.tv2000DataStore by preferencesDataStore(name = "tv2000")

class PlaybackHistoryStore(
    private val context: Context,
    private val database: Tv2000Database = Tv2000Database.get(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var cachedSmbResource: SmbResource? = null

    @Volatile
    private var smbResourceLoaded = false

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

    suspend fun smbResource(): SmbResource? {
        if (smbResourceLoaded) return cachedSmbResource
        val resource = context.tv2000DataStore.data.first()[SMB_RESOURCE]
            ?.let(::decodeSmbResource)
        cachedSmbResource = resource
        smbResourceLoaded = true
        return resource
    }

    fun cachedSmbResource(): SmbResource? = cachedSmbResource

    suspend fun saveSmbResource(resource: SmbResource) {
        context.tv2000DataStore.edit { preferences ->
            preferences[SMB_RESOURCE] = encodeSmbResource(resource)
        }
        cachedSmbResource = resource
        smbResourceLoaded = true
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

    suspend fun resetChannelPlayback(
        channelId: String,
        legacyChannelId: String? = null,
    ) {
        database.withTransaction {
            val dao = database.playbackStateDao()
            dao.deleteChannelPlayback(channelId)
            dao.deleteEpisodePlaybackForChannel(channelId)
        }
        context.tv2000DataStore.edit { preferences ->
            listOfNotNull(channelId, legacyChannelId)
                .distinct()
                .forEach { id -> preferences.remove(historyKey(id)) }
        }
    }

    suspend fun resetAllPlayback() {
        database.withTransaction {
            val dao = database.playbackStateDao()
            dao.deleteAllChannelPlayback()
            dao.deleteAllEpisodePlayback()
        }
        context.tv2000DataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { key -> key.name.startsWith(HISTORY_KEY_PREFIX) }
                .forEach(preferences::removeUntyped)
        }
    }

    private fun historyKey(channelId: String) =
        stringPreferencesKey("$HISTORY_KEY_PREFIX${sha256(channelId)}")

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        val ROOT_URI = stringPreferencesKey("root_uri")
        val SMB_RESOURCE = stringPreferencesKey("smb_resource")
        val LEGACY_ACTIVE_CHANNEL = stringPreferencesKey("active_channel")
        const val HISTORY_KEY_PREFIX = "history_"
        const val DEFAULT_PLAYBACK_SPEED = 1.0f
        const val MIN_PLAYBACK_SPEED = 0.5f
        const val MAX_PLAYBACK_SPEED = 2.0f
    }
}

private fun encodeSmbResource(resource: SmbResource): String = JSONObject()
    .put("host", resource.host)
    .put("port", resource.port)
    .put("share", resource.share)
    .put("directory", resource.directory)
    .put("username", resource.username)
    .put("password", resource.password)
    .put("domain", resource.domain)
    .toString()

private fun decodeSmbResource(encoded: String): SmbResource? = runCatching {
    val json = JSONObject(encoded)
    SmbResource(
        host = json.getString("host"),
        port = json.optInt("port", DEFAULT_SMB_PORT),
        share = json.getString("share"),
        directory = json.optString("directory"),
        username = json.optString("username"),
        password = json.optString("password"),
        domain = json.optString("domain"),
    )
}.getOrNull()

@Suppress("UNCHECKED_CAST")
private fun MutablePreferences.removeUntyped(key: Preferences.Key<*>) {
    remove(key as Preferences.Key<Any>)
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
