package com.tv2000.app.storage

import android.content.Context
import android.content.Intent
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
import com.tv2000.app.smb.SmbMediaUri
import com.tv2000.app.smb.SmbResource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private val Context.tv2000DataStore by preferencesDataStore(name = "tv2000")

class PlaybackHistoryStore(
    private val context: Context,
    private val database: Tv2000Database = Tv2000Database.get(context),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var cachedSmbResources: List<SmbResource> = emptyList()

    @Volatile
    private var smbResourcesLoaded = false

    @Volatile
    private var activeSmbResourceId: String? = null

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

    suspend fun usbRootUri(): String? =
        context.tv2000DataStore.data.first()[USB_ROOT_URI]

    suspend fun saveUsbRootUri(uri: String) {
        context.tv2000DataStore.edit { preferences ->
            preferences[USB_ROOT_URI] = uri
        }
    }

    suspend fun clearUsbRootUri() {
        context.tv2000DataStore.edit { preferences ->
            preferences.remove(USB_ROOT_URI)
        }
    }

    suspend fun usbVideoDirectory(): String =
        context.tv2000DataStore.data.first()[USB_VIDEO_DIRECTORY]
            ?: UsbStorageResolver.DEFAULT_VIDEO_DIRECTORY

    suspend fun saveUsbVideoDirectory(directory: String) {
        context.tv2000DataStore.edit { preferences ->
            preferences[USB_VIDEO_DIRECTORY] = directory
        }
    }

    suspend fun smbResources(): List<SmbResource> {
        if (smbResourcesLoaded) return cachedSmbResources
        val preferences = context.tv2000DataStore.data.first()
        val resources = preferences[SMB_RESOURCES]
            ?.let(::decodeSmbResources)
            ?: preferences[SMB_RESOURCE]
                ?.let(::decodeSmbResource)
                ?.let { resource -> listOf(resource) }
            ?: emptyList()
        cachedSmbResources = resources
        smbResourcesLoaded = true
        return resources
    }

    suspend fun smbResource(uri: android.net.Uri): SmbResource? {
        val resources = smbResources()
        val resourceId = SmbMediaUri.resourceId(uri) ?: activeSmbResourceId
        return resources.firstOrNull { resource -> resource.id == resourceId }
            ?: resources.singleOrNull()
    }

    fun cachedSmbResource(uri: android.net.Uri): SmbResource? {
        val resourceId = SmbMediaUri.resourceId(uri) ?: activeSmbResourceId
        return cachedSmbResources.firstOrNull { resource -> resource.id == resourceId }
            ?: cachedSmbResources.singleOrNull()
    }

    fun setActiveSmbResource(resourceId: String?) {
        activeSmbResourceId = resourceId
    }

    suspend fun saveSmbResource(resource: SmbResource): List<SmbResource> {
        val resources = smbResources().toMutableList()
        val existingIndex = resources.indexOfFirst { existing -> existing.id == resource.id }
        if (existingIndex >= 0) {
            resources[existingIndex] = resource
        } else {
            resources += resource
        }
        context.tv2000DataStore.edit { preferences ->
            preferences[SMB_RESOURCES] = encodeSmbResources(resources)
            preferences.remove(SMB_RESOURCE)
        }
        cachedSmbResources = resources
        smbResourcesLoaded = true
        return resources
    }

    suspend fun deleteSmbResource(resourceId: String): List<SmbResource> {
        val resources = smbResources().filterNot { resource -> resource.id == resourceId }
        context.tv2000DataStore.edit { preferences ->
            preferences[SMB_RESOURCES] = encodeSmbResources(resources)
            preferences.remove(SMB_RESOURCE)
        }
        cachedSmbResources = resources
        smbResourcesLoaded = true
        if (activeSmbResourceId == resourceId) activeSmbResourceId = null
        return resources
    }

    suspend fun activeChannelId(rootUri: String? = null): String? {
        if (rootUri != null) {
            context.tv2000DataStore.data.first()[activeChannelKey(rootUri)]?.let { channelId ->
                return channelId
            }
        }
        return database.playbackStateDao().activeChannelId()
            ?: context.tv2000DataStore.data.first()[LEGACY_ACTIVE_CHANNEL]
    }

    suspend fun saveActiveChannel(channelId: String, rootUri: String? = null) {
        database.playbackStateDao().upsertAppState(
            AppStateEntity(
                activeChannelId = channelId,
                updatedAt = clock(),
            ),
        )
        if (rootUri != null) {
            context.tv2000DataStore.edit { preferences ->
                preferences[activeChannelKey(rootUri)] = channelId
            }
        }
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

    suspend fun resetAppData() {
        withContext(Dispatchers.IO) {
            database.clearAllTables()
        }
        context.tv2000DataStore.edit(MutablePreferences::clear)
        cachedSmbResources = emptyList()
        smbResourcesLoaded = false
        activeSmbResourceId = null

        context.contentResolver.persistedUriPermissions.forEach { permission ->
            val flags =
                (Intent.FLAG_GRANT_READ_URI_PERMISSION.takeIf { permission.isReadPermission } ?: 0) or
                    (Intent.FLAG_GRANT_WRITE_URI_PERMISSION.takeIf {
                        permission.isWritePermission
                    } ?: 0)
            if (flags == 0) return@forEach
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    permission.uri,
                    flags,
                )
            }
        }
    }

    private fun historyKey(channelId: String) =
        stringPreferencesKey("$HISTORY_KEY_PREFIX${sha256(channelId)}")

    private fun activeChannelKey(rootUri: String) =
        stringPreferencesKey(
            "$ACTIVE_CHANNEL_RESOURCE_KEY_PREFIX${
                StableMediaIds.volume(UsbStorageResolver.volumeIdentity(rootUri))
            }",
        )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        val ROOT_URI = stringPreferencesKey("root_uri")
        val USB_ROOT_URI = stringPreferencesKey("usb_root_uri")
        val USB_VIDEO_DIRECTORY = stringPreferencesKey("usb_video_directory")
        val SMB_RESOURCE = stringPreferencesKey("smb_resource")
        val SMB_RESOURCES = stringPreferencesKey("smb_resources")
        val LEGACY_ACTIVE_CHANNEL = stringPreferencesKey("active_channel")
        const val HISTORY_KEY_PREFIX = "history_"
        const val ACTIVE_CHANNEL_RESOURCE_KEY_PREFIX = "active_channel_resource_"
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

private fun encodeSmbResources(resources: List<SmbResource>): String = JSONArray().apply {
    resources.forEach { resource -> put(JSONObject(encodeSmbResource(resource))) }
}.toString()

private fun decodeSmbResources(encoded: String): List<SmbResource>? = runCatching {
    val array = JSONArray(encoded)
    buildList {
        repeat(array.length()) { index ->
            decodeSmbResource(array.getJSONObject(index).toString())?.let { resource ->
                add(resource)
            }
        }
    }
}.getOrNull()

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
