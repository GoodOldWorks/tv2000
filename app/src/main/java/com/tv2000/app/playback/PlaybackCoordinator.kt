package com.tv2000.app.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tv2000.app.R
import com.tv2000.app.model.Channel
import com.tv2000.app.scanner.ChannelScanner
import com.tv2000.app.scanner.ScanFailure
import com.tv2000.app.scanner.ScanResult
import com.tv2000.app.storage.MediaCatalogRepository
import com.tv2000.app.storage.PlaybackHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackCoordinator(
    private val context: Context,
    val player: ExoPlayer,
    private val scanner: ChannelScanner,
    private val catalogRepository: MediaCatalogRepository,
    private val historyStore: PlaybackHistoryStore,
    private val scope: CoroutineScope,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private val mutableState = MutableStateFlow(Tv2000UiState())
    val state: StateFlow<Tv2000UiState> = mutableState.asStateFlow()

    private var overlayJob: Job? = null
    private var tuneJob: Job? = null
    private var pendingLeftPressJob: Job? = null
    private var pendingRightPressJob: Job? = null
    private var leftPressPending = false
    private var rightPressPending = false
    private var pendingChannelIndex = 0
    private var playingChannelIndex: Int? = null
    private val doublePressDetector = DirectionalDoublePressDetector(
        timeoutMs = DIRECTIONAL_DOUBLE_PRESS_TIMEOUT_MS,
    )

    init {
        player.addListener(this)
        scope.launch {
            while (isActive) {
                delay(HISTORY_SAVE_INTERVAL_MS)
                checkpoint()
            }
        }
    }

    suspend fun restore(): Boolean {
        val rootUri = historyStore.rootUri()
        if (rootUri == null) {
            mutableState.value = Tv2000UiState(mode = AppMode.NEEDS_STORAGE_ACCESS)
            return false
        }

        return scanAndPlay(Uri.parse(rootUri), restoringApp = true)
    }

    suspend fun onStorageGranted(rootUri: Uri) {
        historyStore.saveRootUri(rootUri.toString())
        scanAndPlay(rootUri, restoringApp = false)
    }

    fun handleKey(keyCode: Int): RemoteResult {
        val current = mutableState.value
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            flushDirectionalGestures()
        }

        if (current.mode == AppMode.NEEDS_STORAGE_ACCESS ||
            current.mode == AppMode.STORAGE_UNAVAILABLE ||
            current.mode == AppMode.NO_CONTENT
        ) {
            return when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                -> RemoteResult.REQUEST_STORAGE

                KeyEvent.KEYCODE_BACK -> RemoteResult.EXIT
                else -> RemoteResult.NOT_HANDLED
            }
        }

        if (current.mode != AppMode.READY) {
            return if (keyCode == KeyEvent.KEYCODE_BACK) {
                RemoteResult.EXIT
            } else {
                RemoteResult.NOT_HANDLED
            }
        }

        if (current.channelListVisible) {
            return handleChannelListKey(keyCode)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                requestRelativeTune(-1)
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                requestRelativeTune(1)
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handleDirectionalPress(DirectionalPress.LEFT)
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleDirectionalPress(DirectionalPress.RIGHT)
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                playAdjacentEpisode(delta = -1)
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                playAdjacentEpisode(delta = 1)
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> {
                if (player.isPlaying) player.pause() else player.play()
                showChannelOverlay()
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_BACK -> {
                mutableState.value = current.copy(
                    channelListVisible = true,
                    channelListSelection = current.currentChannelIndex,
                    channelOverlayVisible = false,
                )
                RemoteResult.CONSUMED
            }

            else -> RemoteResult.NOT_HANDLED
        }
    }

    fun onBackground() {
        cancelDirectionalGestures()
        persistCurrentSnapshot()
        player.pause()
    }

    fun release() {
        cancelDirectionalGestures()
        overlayJob?.cancel()
        tuneJob?.cancel()
        player.removeListener(this)
        player.release()
    }

    suspend fun checkpoint() {
        val snapshot = capturePlaybackSnapshot() ?: return

        historyStore.saveChannelPlayback(
            channelId = snapshot.channelId,
            episodeId = snapshot.episodeId,
            positionMs = snapshot.positionMs,
            wasPlaying = snapshot.wasPlaying,
        )
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        cancelDirectionalGestures()
        val current = mutableState.value
        if (current.mode != AppMode.READY) return

        mutableState.value = current.copy(
            currentEpisodeIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            channelOverlayPositionMs = 0L,
            message = null,
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) {
            mutableState.value = mutableState.value.copy(
                message = "本频道节目已播放完毕",
                channelOverlayVisible = true,
            )
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        val current = mutableState.value
        val channel = current.currentChannel ?: return
        val nextIndex = player.currentMediaItemIndex + 1

        if (nextIndex < channel.episodes.size) {
            mutableState.value = current.copy(
                message = "无法播放，正在尝试下一集",
                channelOverlayVisible = true,
            )
            player.seekToDefaultPosition(nextIndex)
            player.prepare()
            player.play()
            scheduleOverlayDismiss()
        } else {
            mutableState.value = current.copy(
                message = "此频道没有可播放的节目",
                channelOverlayVisible = true,
            )
        }
    }

    private suspend fun scanAndPlay(rootUri: Uri, restoringApp: Boolean): Boolean {
        mutableState.value = Tv2000UiState(mode = AppMode.SCANNING)

        return when (val result = scanner.scan(context, rootUri)) {
            is ScanResult.Success -> {
                val channels = runCatching {
                    catalogRepository.replaceSnapshot(rootUri, result.channels)
                }.getOrElse {
                    mutableState.value = Tv2000UiState(mode = AppMode.STORAGE_UNAVAILABLE)
                    return true
                }

                if (channels.isEmpty()) {
                    mutableState.value = Tv2000UiState(mode = AppMode.NO_CONTENT)
                    return true
                }

                val activeChannelId = historyStore.activeChannelId()
                val initialIndex = channels.indexOfFirst {
                    it.id == activeChannelId || it.legacyId == activeChannelId
                }
                    .takeIf { it >= 0 }
                    ?: 0

                pendingChannelIndex = initialIndex
                mutableState.value = Tv2000UiState(
                    mode = AppMode.READY,
                    channels = channels,
                    currentChannelIndex = initialIndex,
                    channelListSelection = initialIndex,
                )
                tuneTo(initialIndex, restoringApp)
                true
            }

            is ScanResult.Failure -> {
                if (result.reason == ScanFailure.PERMISSION_LOST) {
                    historyStore.clearRootUri()
                    mutableState.value = Tv2000UiState(mode = AppMode.NEEDS_STORAGE_ACCESS)
                    false
                } else {
                    mutableState.value = Tv2000UiState(mode = AppMode.STORAGE_UNAVAILABLE)
                    true
                }
            }
        }
    }

    private fun handleChannelListKey(keyCode: Int): RemoteResult {
        val current = mutableState.value
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                mutableState.value = current.copy(
                    channelListSelection = wrappedIndex(
                        current.channelListSelection,
                        -1,
                        current.channels.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                mutableState.value = current.copy(
                    channelListSelection = wrappedIndex(
                        current.channelListSelection,
                        1,
                        current.channels.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                mutableState.value = current.copy(channelListVisible = false)
                requestTune(current.channelListSelection)
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_BACK -> RemoteResult.EXIT
            else -> RemoteResult.NOT_HANDLED
        }
    }

    private fun requestRelativeTune(delta: Int) {
        val channels = mutableState.value.channels
        if (channels.isEmpty()) return
        if (channels.size == 1) {
            showChannelOverlay()
            return
        }

        pendingChannelIndex = wrappedIndex(pendingChannelIndex, delta, channels.size)
        requestTune(pendingChannelIndex)
    }

    private fun requestTune(channelIndex: Int) {
        val channels = mutableState.value.channels
        if (channels.isEmpty()) return

        pendingChannelIndex = channelIndex
        cancelDirectionalGestures()
        persistCurrentSnapshot()
        tuneJob?.cancel()

        mutableState.value = mutableState.value.copy(
            currentChannelIndex = channelIndex,
            currentEpisodeIndex = 0,
            channelOverlayVisible = true,
            channelOverlayPositionMs = 0L,
            message = null,
        )

        tuneJob = scope.launch {
            tuneTo(channelIndex, restoringApp = false)
        }
    }

    private suspend fun tuneTo(channelIndex: Int, restoringApp: Boolean) {
        val channel = mutableState.value.channels.getOrNull(channelIndex) ?: return
        val history = historyStore.channelPlayback(
            channelId = channel.id,
            legacyChannelId = channel.legacyId,
        )
        val episodeIndex = history?.episodeId
            ?.let { episodeId ->
                channel.episodes.indexOfFirst {
                    it.id == episodeId || it.legacyId == episodeId
                }
            }
            ?.takeIf { it >= 0 }
            ?: 0
        val positionMs = history?.positionMs
            ?.takeIf { it >= MINIMUM_RESUME_POSITION_MS }
            ?: 0L

        val mediaItems = channel.episodes.map { episode ->
            MediaItem.Builder()
                .setMediaId(episode.id)
                .setUri(episode.uri)
                .setMediaMetadata(
                    androidx.media3.common.MediaMetadata.Builder()
                        .setTitle(episode.title)
                        .build(),
                )
                .build()
        }

        player.stop()
        player.setMediaItems(mediaItems, episodeIndex, positionMs)
        player.prepare()
        player.setPlaybackSpeed(history?.playbackSpeed ?: 1.0f)
        player.playWhenReady = if (restoringApp) history?.wasPlaying ?: true else true
        playingChannelIndex = channelIndex

        historyStore.saveActiveChannel(channel.id)
        mutableState.value = mutableState.value.copy(
            mode = AppMode.READY,
            currentChannelIndex = channelIndex,
            currentEpisodeIndex = episodeIndex,
            channelListSelection = channelIndex,
            channelOverlayVisible = true,
            channelOverlayPositionMs = positionMs,
            message = null,
        )
        scheduleOverlayDismiss()
    }

    private fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
        player.seekTo(target)
        showChannelOverlay(target)
    }

    private fun handleDirectionalPress(press: DirectionalPress) {
        val nowMs = monotonicClock()
        val isDoublePress = doublePressDetector.register(press, nowMs)
        if (!isDoublePress) {
            scheduleSingleDirectionalPress(press)
            return
        }

        cancelPendingSinglePress(press)
        when (press) {
            DirectionalPress.RIGHT -> {
                playAdjacentEpisode(delta = 1)
            }

            DirectionalPress.LEFT -> {
                when (
                    resolveDoubleLeftAction(
                        playbackPositionMs = player.currentPosition.coerceAtLeast(0L),
                        previousEpisodeThresholdMs = PREVIOUS_EPISODE_POSITION_THRESHOLD_MS,
                    )
                ) {
                    DoubleLeftAction.RESTART_CURRENT_EPISODE -> restartCurrentEpisode()
                    DoubleLeftAction.PLAY_PREVIOUS_EPISODE -> {
                        playAdjacentEpisode(delta = -1)
                    }
                }
            }
        }
    }

    private fun scheduleSingleDirectionalPress(press: DirectionalPress) {
        when (press) {
            DirectionalPress.LEFT -> {
                if (leftPressPending) {
                    pendingLeftPressJob?.cancel()
                    leftPressPending = false
                    seekBy(-SEEK_BACKWARD_MS)
                }
                leftPressPending = true
                pendingLeftPressJob = scope.launch {
                    delay(DIRECTIONAL_DOUBLE_PRESS_TIMEOUT_MS)
                    leftPressPending = false
                    seekBy(-SEEK_BACKWARD_MS)
                }
            }

            DirectionalPress.RIGHT -> {
                if (rightPressPending) {
                    pendingRightPressJob?.cancel()
                    rightPressPending = false
                    seekBy(SEEK_FORWARD_MS)
                }
                rightPressPending = true
                pendingRightPressJob = scope.launch {
                    delay(DIRECTIONAL_DOUBLE_PRESS_TIMEOUT_MS)
                    rightPressPending = false
                    seekBy(SEEK_FORWARD_MS)
                }
            }
        }
    }

    private fun cancelPendingSinglePress(press: DirectionalPress) {
        when (press) {
            DirectionalPress.LEFT -> {
                pendingLeftPressJob?.cancel()
                pendingLeftPressJob = null
                leftPressPending = false
            }

            DirectionalPress.RIGHT -> {
                pendingRightPressJob?.cancel()
                pendingRightPressJob = null
                rightPressPending = false
            }
        }
    }

    private fun restartCurrentEpisode() {
        player.seekTo(0L)
        persistCurrentSnapshot()
        showChannelOverlay(positionMs = 0L)
    }

    private fun playAdjacentEpisode(delta: Int) {
        val channel = mutableState.value.currentChannel ?: return
        val currentEpisodeIndex = player.currentMediaItemIndex
            .takeIf { it in channel.episodes.indices }
            ?: mutableState.value.currentEpisodeIndex
        val targetEpisodeIndex = currentEpisodeIndex + delta

        if (targetEpisodeIndex !in channel.episodes.indices) {
            mutableState.value = mutableState.value.copy(
                channelOverlayVisible = true,
                message = if (delta > 0) {
                    context.getString(R.string.already_last_episode)
                } else {
                    context.getString(R.string.already_first_episode)
                },
            )
            scheduleOverlayDismiss()
            return
        }

        val outgoingEpisode = channel.episodes[currentEpisodeIndex]
        val targetEpisode = channel.episodes[targetEpisodeIndex]
        val outgoingPositionMs = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady

        scope.launch {
            historyStore.moveChannelCursor(
                channelId = channel.id,
                outgoingEpisodeId = outgoingEpisode.id,
                outgoingPositionMs = outgoingPositionMs,
                targetEpisodeId = targetEpisode.id,
                wasPlaying = shouldPlay,
            )
        }

        player.seekToDefaultPosition(targetEpisodeIndex)
        player.playWhenReady = shouldPlay
        mutableState.value = mutableState.value.copy(
            currentEpisodeIndex = targetEpisodeIndex,
            channelOverlayVisible = true,
            channelOverlayPositionMs = 0L,
            message = null,
        )
        scheduleOverlayDismiss()
    }

    private fun flushDirectionalGestures() {
        if (leftPressPending) {
            cancelPendingSinglePress(DirectionalPress.LEFT)
            seekBy(-SEEK_BACKWARD_MS)
        }
        if (rightPressPending) {
            cancelPendingSinglePress(DirectionalPress.RIGHT)
            seekBy(SEEK_FORWARD_MS)
        }
        doublePressDetector.reset()
    }

    private fun cancelDirectionalGestures() {
        cancelPendingSinglePress(DirectionalPress.LEFT)
        cancelPendingSinglePress(DirectionalPress.RIGHT)
        doublePressDetector.reset()
    }

    private fun showChannelOverlay(
        positionMs: Long = player.currentPosition.coerceAtLeast(0L),
    ) {
        mutableState.value = mutableState.value.copy(
            channelOverlayVisible = true,
            channelOverlayPositionMs = positionMs,
            message = null,
        )
        scheduleOverlayDismiss()
    }

    private fun scheduleOverlayDismiss() {
        overlayJob?.cancel()
        overlayJob = scope.launch {
            delay(CHANNEL_OVERLAY_DURATION_MS)
            mutableState.value = mutableState.value.copy(
                channelOverlayVisible = false,
                message = null,
            )
        }
    }

    private fun persistCurrentSnapshot() {
        val snapshot = capturePlaybackSnapshot() ?: return
        scope.launch {
            historyStore.saveChannelPlayback(
                channelId = snapshot.channelId,
                episodeId = snapshot.episodeId,
                positionMs = snapshot.positionMs,
                wasPlaying = snapshot.wasPlaying,
            )
        }
    }

    private fun capturePlaybackSnapshot(): PlaybackSnapshot? {
        val current = mutableState.value
        val channelIndex = playingChannelIndex ?: return null
        val channel = current.channels.getOrNull(channelIndex) ?: return null
        val episode = channel.episodes.getOrNull(player.currentMediaItemIndex) ?: return null

        return PlaybackSnapshot(
            channelId = channel.id,
            episodeId = episode.id,
            positionMs = player.currentPosition,
            wasPlaying = player.playWhenReady,
        )
    }

    private data class PlaybackSnapshot(
        val channelId: String,
        val episodeId: String,
        val positionMs: Long,
        val wasPlaying: Boolean,
    )

    companion object {
        const val CHANNEL_OVERLAY_DURATION_MS = 2_000L
        const val HISTORY_SAVE_INTERVAL_MS = 5_000L
        const val SEEK_BACKWARD_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L
        const val MINIMUM_RESUME_POSITION_MS = 5_000L
        const val DIRECTIONAL_DOUBLE_PRESS_TIMEOUT_MS = 350L
        const val PREVIOUS_EPISODE_POSITION_THRESHOLD_MS = 5_000L
    }
}

internal fun wrappedIndex(current: Int, delta: Int, size: Int): Int {
    if (size <= 0) return 0
    return ((current + delta) % size + size) % size
}
