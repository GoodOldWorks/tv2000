package com.tv2000.app.playback

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tv2000.app.DebugStorageFallback
import com.tv2000.app.R
import com.tv2000.app.model.Channel
import com.tv2000.app.scanner.ChannelScanner
import com.tv2000.app.scanner.ScanFailure
import com.tv2000.app.scanner.ScanResult
import com.tv2000.app.storage.MediaCatalogRepository
import com.tv2000.app.storage.PlaybackHistoryStore
import com.tv2000.app.storage.UsbStorageResolver
import com.tv2000.app.smb.SmbMediaUri
import com.tv2000.app.smb.SmbResource
import com.tv2000.app.smb.SmbjMediaClient
import com.tv2000.app.smb.classifySmbFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PlaybackCoordinator(
    private val context: Context,
    val player: ExoPlayer,
    private val scanner: ChannelScanner,
    private val catalogRepository: MediaCatalogRepository,
    private val historyStore: PlaybackHistoryStore,
    private val smbClient: SmbjMediaClient,
    private val scope: CoroutineScope,
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
    private val onFirstFrameRendered: () -> Unit = {},
) : Player.Listener {
    private val mutableState = MutableStateFlow(Tv2000UiState())
    val state: StateFlow<Tv2000UiState> = mutableState.asStateFlow()

    private var overlayJob: Job? = null
    private var tuneJob: Job? = null
    private var backgroundScanJob: Job? = null
    private var settingsJob: Job? = null
    private var backExitPromptJob: Job? = null
    private var pendingLeftPressJob: Job? = null
    private var pendingRightPressJob: Job? = null
    private var leftPressPending = false
    private var rightPressPending = false
    private var pendingChannelIndex = 0
    private var playingChannelIndex: Int? = null
    private var configuredUsbRootUri: String? = null
    private var configuredUsbVideoDirectory = UsbStorageResolver.DEFAULT_VIDEO_DIRECTORY
    private var configuredSmbResources: List<SmbResource> = emptyList()
    private var activeResourceKind: ResourceKind? = null
    private var activeResourceId: String? = null
    private var activeRootUri: Uri? = null
    private var latestMountedUsbRootUris: List<String> = emptyList()
    private var pendingConfirmationAction: PendingConfirmationAction? = null
    private var pendingConfirmationRequest: ConfirmationRequest? = null
    private val historyWriteMutex = Mutex()
    private val usbSwapMutex = Mutex()
    private val backgroundResumeState = BackgroundPlaybackResumeState()
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
        configuredSmbResources = historyStore.smbResources()
        configuredUsbRootUri = historyStore.usbRootUri()
        configuredUsbVideoDirectory = historyStore.usbVideoDirectory()
        val rootUri = historyStore.rootUri()
        if (rootUri == null) {
            mutableState.value = freshState(AppMode.NEEDS_STORAGE_ACCESS)
            return false
        }

        val parsedRootUri = Uri.parse(rootUri)
        activeRootUri = parsedRootUri
        activeResourceKind = if (SmbMediaUri.isSmb(parsedRootUri)) {
            ResourceKind.SMB
        } else {
            ResourceKind.USB
        }
        activeResourceId = if (activeResourceKind == ResourceKind.SMB) {
            SmbMediaUri.resourceId(parsedRootUri)
        } else {
            USB_RESOURCE_ID
        }
        historyStore.setActiveSmbResource(
            activeResourceId.takeIf { activeResourceKind == ResourceKind.SMB },
        )
        if (activeResourceKind == ResourceKind.USB && configuredUsbRootUri == null) {
            configuredUsbRootUri = rootUri
            historyStore.saveUsbRootUri(rootUri)
        }
        if (DebugStorageFallback.shouldDiscardStoredRoot(context, parsedRootUri)) {
            historyStore.clearRootUri()
            activeResourceKind = null
            activeResourceId = null
            activeRootUri = null
            mutableState.value = freshState(AppMode.NEEDS_STORAGE_ACCESS)
            return false
        }

        return restoreIndexedOrScan(parsedRootUri, restoringApp = true)
    }

    private suspend fun restoreIndexedOrScan(
        rootUri: Uri,
        restoringApp: Boolean,
    ): Boolean {
        val indexedChannels = runCatching {
            catalogRepository.loadIndexedChannels(rootUri)
        }.getOrDefault(emptyList())
        if (indexedChannels.isNotEmpty() && canResumeFromIndex(indexedChannels)) {
            showChannelsAndTune(indexedChannels, restoringApp)
            scheduleBackgroundScan(rootUri)
            return true
        }

        return scanAndPlay(rootUri, restoringApp)
    }

    suspend fun onStorageGranted(rootUri: Uri) {
        configuredUsbRootUri = rootUri.toString()
        historyStore.saveUsbRootUri(rootUri.toString())
        activateResource(ResourceKind.USB, USB_RESOURCE_ID, rootUri)
    }

    fun onMountedUsbRootsChanged(mountedRoots: List<Uri>) {
        latestMountedUsbRootUris = mountedRoots
            .map(Uri::toString)
            .distinctBy { rootUri -> UsbStorageResolver.volumeIdentity(rootUri) }
        if (activeResourceKind == ResourceKind.SMB) return

        val decision = decideUsbSwap(
            selectedRootUri = configuredUsbRootUri,
            mountedRootUris = latestMountedUsbRootUris,
            selectedRootNeedsRestore = isWaitingForRemovedUsb(),
        )
        if (decision == UsbSwapDecision.KeepCurrent) return

        val outgoingSnapshot = if (activeResourceKind == ResourceKind.USB) {
            val snapshot = capturePlaybackSnapshot()
            stopForUsbRemoval()
            snapshot
        } else {
            null
        }

        scope.launch {
            usbSwapMutex.withLock {
                outgoingSnapshot?.let { snapshot ->
                    withContext(NonCancellable) {
                        persistPlaybackSnapshot(snapshot)
                    }
                }
                reconcileLatestMountedUsbRoots()
            }
        }
    }

    private fun stopForUsbRemoval() {
        backgroundScanJob?.cancel()
        backgroundScanJob = null
        tuneJob?.cancel()
        tuneJob = null
        overlayJob?.cancel()
        overlayJob = null
        cancelDirectionalGestures()
        cancelBackExitPrompt()
        player.pause()
        player.stop()
        player.clearMediaItems()
        playingChannelIndex = null
        mutableState.value = freshState(
            mode = AppMode.STORAGE_UNAVAILABLE,
            scanFailure = ScanFailure.USB_REMOVED,
        )
    }

    private suspend fun reconcileLatestMountedUsbRoots() {
        if (activeResourceKind == ResourceKind.SMB) return

        when (
            val decision = decideUsbSwap(
                selectedRootUri = configuredUsbRootUri,
                mountedRootUris = latestMountedUsbRootUris,
                selectedRootNeedsRestore = isWaitingForRemovedUsb(),
            )
        ) {
            UsbSwapDecision.KeepCurrent -> Unit
            UsbSwapDecision.WaitForUsb,
            UsbSwapDecision.MultipleVolumes,
            -> configuredUsbRootUri
                ?.takeIf { activeResourceKind == ResourceKind.USB }
                ?.let(Uri::parse)
                ?.let { rootUri -> catalogRepository.markVolumeOffline(rootUri) }

            is UsbSwapDecision.RestoreCurrent -> {
                activateMountedUsbRoot(decision.rootUri)
            }

            is UsbSwapDecision.SwitchToSingle -> {
                configuredUsbRootUri
                    ?.takeIf { selectedRootUri ->
                        UsbStorageResolver.volumeIdentity(selectedRootUri) !=
                            UsbStorageResolver.volumeIdentity(decision.rootUri)
                    }
                    ?.let(Uri::parse)
                    ?.let { outgoingRoot ->
                        catalogRepository.markVolumeOffline(outgoingRoot)
                    }
                activateMountedUsbRoot(decision.rootUri)
            }
        }
    }

    private fun isWaitingForRemovedUsb(): Boolean =
        activeResourceKind == ResourceKind.USB &&
            mutableState.value.mode == AppMode.STORAGE_UNAVAILABLE &&
            mutableState.value.scanFailure == ScanFailure.USB_REMOVED

    private suspend fun activateMountedUsbRoot(rootUri: String) {
        configuredUsbRootUri = rootUri
        historyStore.saveUsbRootUri(rootUri)
        activateResource(
            kind = ResourceKind.USB,
            resourceId = USB_RESOURCE_ID,
            rootUri = Uri.parse(rootUri),
            restoringApp = true,
            preferIndex = true,
        )
    }

    fun usbVideoDirectory(): String = configuredUsbVideoDirectory

    suspend fun updateUsbVideoDirectory(directory: String) {
        val normalized = requireNotNull(UsbStorageResolver.normalizeVideoDirectory(directory))
        val changed = normalized != configuredUsbVideoDirectory
        configuredUsbVideoDirectory = normalized
        historyStore.saveUsbVideoDirectory(normalized)

        if (changed && activeResourceKind == ResourceKind.USB) {
            selectConfiguredUsbResource()
        } else {
            mutableState.value = mutableState.value.copy(
                resourceSettingsVisible = true,
                usbResourceActionsVisible = false,
            )
            refreshResourceConfiguration()
        }
    }

    suspend fun addSmbResource(resource: SmbResource): AddSmbResourceResult {
        validateSmbResource(resource)?.let { failure -> return failure }

        configuredSmbResources = historyStore.saveSmbResource(resource)
        activateResource(ResourceKind.SMB, resource.id, SmbMediaUri.root(resource))
        return AddSmbResourceResult.Success
    }

    suspend fun updateSmbResource(
        originalResourceId: String,
        resource: SmbResource,
    ): AddSmbResourceResult {
        validateSmbResource(resource)?.let { failure -> return failure }

        val affectsActiveResource = activeResourceKind == ResourceKind.SMB &&
            (activeResourceId == originalResourceId || activeResourceId == resource.id)
        configuredSmbResources = historyStore.saveSmbResource(resource)
        if (resource.id != originalResourceId) {
            configuredSmbResources = historyStore.deleteSmbResource(originalResourceId)
        }

        if (affectsActiveResource) {
            activateResource(ResourceKind.SMB, resource.id, SmbMediaUri.root(resource))
        } else {
            mutableState.value = mutableState.value.copy(
                resourceSettingsVisible = true,
                smbResourceActionsVisible = false,
                managedSmbResourceId = resource.id,
            )
            refreshResourceConfiguration()
        }
        return AddSmbResourceResult.Success
    }

    fun selectedManagedSmbResource(): SmbResource? {
        val resourceId = mutableState.value.managedSmbResourceId ?: return null
        return configuredSmbResources.firstOrNull { resource -> resource.id == resourceId }
    }

    fun confirmationRequest(): ConfirmationRequest? = pendingConfirmationRequest

    fun cancelPendingConfirmation() {
        pendingConfirmationAction = null
        pendingConfirmationRequest = null
    }

    fun confirmPendingAction() {
        val action = pendingConfirmationAction ?: return
        cancelPendingConfirmation()
        when (action) {
            is PendingConfirmationAction.DELETE_SMB -> {
                if (activeResourceKind == ResourceKind.SMB &&
                    activeResourceId == action.resourceId
                ) {
                    return
                }
                mutableState.value = mutableState.value.copy(
                    resourceSettingsVisible = true,
                    resourceSettingsSelection = 0,
                    smbResourceActionsVisible = false,
                    managedSmbResourceId = null,
                )
                settingsJob?.cancel()
                settingsJob = scope.launch { deleteSmbResource(action.resourceId) }
            }

            is PendingConfirmationAction.ADVANCED -> {
                mutableState.value = mutableState.value.copy(advancedSettingsVisible = false)
                performAdvancedSettingsAction(action.action)
            }
        }
    }

    private suspend fun validateSmbResource(
        resource: SmbResource,
    ): AddSmbResourceResult.Failure? {
        val validationError = withContext(Dispatchers.IO) {
            runCatching { smbClient.validate(resource) }.exceptionOrNull()
        } ?: return null
        val failure = classifySmbFailure(validationError)
        return AddSmbResourceResult.Failure(
            context.getString(R.string.smb_validation_failed, failure.diagnostic),
        )
    }

    fun handleKey(keyCode: Int): RemoteResult {
        val current = mutableState.value
        if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
            keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
        ) {
            flushDirectionalGestures()
        }

        if (current.mode != AppMode.LOADING &&
            current.mode != AppMode.SCANNING &&
            keyCode == KeyEvent.KEYCODE_MENU
        ) {
            cancelBackExitPrompt()
            mutableState.value = current.copy(
                mainMenuVisible = !current.mainMenuVisible,
                mainMenuSelection = if (current.mainMenuVisible) {
                    current.mainMenuSelection
                } else {
                    initialMainMenuSelection()
                },
                channelListVisible = false,
                exitPromptVisible = false,
                resourceSettingsVisible = false,
                usbResourceActionsVisible = false,
                smbResourceActionsVisible = false,
                advancedSettingsVisible = false,
                channelOverlayVisible = false,
            )
            return RemoteResult.CONSUMED
        }

        if (current.mainMenuVisible) {
            return handleMainMenuKey(keyCode)
        }

        if (current.resourceSettingsVisible) {
            return handleResourceSettingsKey(keyCode)
        }

        if (current.usbResourceActionsVisible) {
            return handleUsbResourceActionsKey(keyCode)
        }

        if (current.smbResourceActionsVisible) {
            return handleSmbResourceActionsKey(keyCode)
        }

        if (current.advancedSettingsVisible) {
            return handleAdvancedSettingsKey(keyCode)
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
                cancelBackExitPrompt()
                mutableState.value = current.copy(
                    channelListVisible = true,
                    channelListSelection = current.currentChannelIndex,
                    exitPromptVisible = true,
                    channelOverlayVisible = false,
                )
                scheduleBackExitPromptDismiss()
                RemoteResult.CONSUMED
            }

            else -> RemoteResult.NOT_HANDLED
        }
    }

    fun onBackground() {
        cancelDirectionalGestures()
        persistCurrentSnapshot()
        backgroundResumeState.onBackground(player.playWhenReady)
        player.pause()
    }

    fun onForeground() {
        val shouldResume = backgroundResumeState.consumeResumeRequest()
        if (shouldResume && mutableState.value.mode == AppMode.READY) {
            player.play()
        }
    }

    fun release() {
        cancelDirectionalGestures()
        overlayJob?.cancel()
        tuneJob?.cancel()
        backgroundScanJob?.cancel()
        settingsJob?.cancel()
        backExitPromptJob?.cancel()
        player.removeListener(this)
        player.release()
        smbClient.close()
    }

    suspend fun checkpoint() {
        historyWriteMutex.withLock {
            val snapshot = capturePlaybackSnapshot() ?: return

            historyStore.saveChannelPlayback(
                channelId = snapshot.channelId,
                episodeId = snapshot.episodeId,
                positionMs = snapshot.positionMs,
                wasPlaying = snapshot.wasPlaying,
            )
        }
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

    override fun onRenderedFirstFrame() {
        onFirstFrameRendered()
    }

    override fun onPlayerError(error: PlaybackException) {
        if (activeResourceKind == ResourceKind.USB) {
            val mountedRoots = UsbStorageResolver.findMountedUsbRoots(context)
            if (decideUsbSwap(configuredUsbRootUri, mountedRoots.map(Uri::toString)) !=
                UsbSwapDecision.KeepCurrent
            ) {
                onMountedUsbRootsChanged(mountedRoots)
                return
            }
        }

        val current = mutableState.value
        val channel = current.currentChannel ?: return
        if (player.currentMediaItem?.localConfiguration?.uri?.let(SmbMediaUri::isSmb) == true) {
            mutableState.value = current.copy(
                message = context.getString(R.string.smb_resource_unavailable),
                channelOverlayVisible = true,
            )
            return
        }
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
        mutableState.value = freshState(AppMode.SCANNING)

        return when (
            val result = scanner.scan(
                context = context,
                rootUri = rootUri,
                usbVideoDirectory = configuredUsbVideoDirectory,
            )
        ) {
            is ScanResult.Success -> {
                val channels = runCatching {
                    catalogRepository.replaceSnapshot(rootUri, result.channels)
                }.getOrElse {
                    mutableState.value = freshState(
                        mode = AppMode.STORAGE_UNAVAILABLE,
                        scanFailure = ScanFailure.UNAVAILABLE,
                    )
                    return true
                }

                if (channels.isEmpty()) {
                    mutableState.value = freshState(AppMode.NO_CONTENT)
                    scheduleUsbMountSettlingScans(rootUri)
                    return true
                }

                showChannelsAndTune(channels, restoringApp)
                scheduleUsbMountSettlingScans(rootUri)
                true
            }

            is ScanResult.Failure -> {
                if (activeResourceKind == ResourceKind.USB &&
                    result.reason == ScanFailure.UNAVAILABLE
                ) {
                    val mountedRoots = UsbStorageResolver.findMountedUsbRoots(context)
                    if (decideUsbSwap(
                            configuredUsbRootUri,
                            mountedRoots.map(Uri::toString),
                        ) != UsbSwapDecision.KeepCurrent
                    ) {
                        onMountedUsbRootsChanged(mountedRoots)
                        return true
                    }
                }

                if (result.reason == ScanFailure.PERMISSION_LOST) {
                    historyStore.clearRootUri()
                    activeResourceKind = null
                    activeResourceId = null
                    activeRootUri = null
                    historyStore.setActiveSmbResource(null)
                    mutableState.value = freshState(AppMode.NEEDS_STORAGE_ACCESS)
                    false
                } else {
                    mutableState.value = freshState(
                        mode = AppMode.STORAGE_UNAVAILABLE,
                        scanFailure = result.reason,
                        scanDiagnostic = result.diagnostic,
                    )
                    scheduleUsbMountSettlingScans(rootUri)
                    true
                }
            }
        }
    }

    private suspend fun showChannelsAndTune(
        channels: List<Channel>,
        restoringApp: Boolean,
    ) {
        val activeChannelId = historyStore.activeChannelId(activeRootUri?.toString())
        val initialIndex = channels.indexOfFirst {
            it.id == activeChannelId || it.legacyId == activeChannelId
        }
            .takeIf { it >= 0 }
            ?: 0

        pendingChannelIndex = initialIndex
        mutableState.value = freshState(
            mode = AppMode.READY,
            channels = channels,
            currentChannelIndex = initialIndex,
            channelListSelection = initialIndex,
        )
        tuneTo(initialIndex, restoringApp)
    }

    private suspend fun canResumeFromIndex(channels: List<Channel>): Boolean {
        val activeChannelId = historyStore.activeChannelId(activeRootUri?.toString())
        val channel = channels.firstOrNull {
            it.id == activeChannelId || it.legacyId == activeChannelId
        } ?: channels.firstOrNull() ?: return false
        val history = historyStore.channelPlayback(
            channelId = channel.id,
            legacyChannelId = channel.legacyId,
        )
        val episode = history?.episodeId
            ?.let { episodeId ->
                channel.episodes.firstOrNull {
                    it.id == episodeId || it.legacyId == episodeId
                }
            }
            ?: channel.episodes.firstOrNull()
            ?: return false

        return isReadable(episode.uri)
    }

    private suspend fun isReadable(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            when (uri.scheme) {
                "file" -> uri.path
                    ?.let(::File)
                    ?.let { it.isFile && it.canRead() }
                    ?: false

                "content" -> context.contentResolver
                    .openFileDescriptor(uri, "r")
                    ?.use { true }
                    ?: false

                SmbMediaUri.SCHEME -> {
                    historyStore.smbResource(uri) != null &&
                        SmbMediaUri.relativePath(uri) != null
                }

                else -> false
            }
        }.getOrDefault(false)
    }

    private fun scheduleUsbMountSettlingScans(rootUri: Uri) {
        if (!SmbMediaUri.isSmb(rootUri)) {
            scheduleBackgroundScan(
                rootUri = rootUri,
                firstDelayMs = USB_INITIAL_RESCAN_DELAY_MS,
            )
        }
    }

    private fun scheduleBackgroundScan(
        rootUri: Uri,
        firstDelayMs: Long = 0L,
    ) {
        backgroundScanJob?.cancel()
        backgroundScanJob = scope.launch {
            val isSmb = SmbMediaUri.isSmb(rootUri)
            val attempts = if (isSmb) 1 else USB_BACKGROUND_SCAN_ATTEMPTS
            repeat(attempts) { attempt ->
                val waitMs = if (attempt == 0) {
                    firstDelayMs
                } else {
                    USB_BACKGROUND_SCAN_RETRY_DELAY_MS
                }
                if (waitMs > 0L) delay(waitMs)

                val scanResult = scanner.scan(
                    context = context,
                    rootUri = rootUri,
                    usbVideoDirectory = configuredUsbVideoDirectory,
                )
                if (!isSelectedResource(rootUri)) return@launch

                val scannedChannels = when (scanResult) {
                    is ScanResult.Failure -> {
                        if (isSmb) {
                            showBackgroundScanFailure(rootUri, scanResult)
                            return@launch
                        }
                        return@repeat
                    }

                    is ScanResult.Success -> scanResult.channels
                }

                if (scannedChannels.isEmpty()) {
                    if (isSmb) {
                        runCatching {
                            catalogRepository.replaceSnapshot(rootUri, emptyList())
                        }
                        showBackgroundNoContent(rootUri)
                        return@launch
                    }
                    return@repeat
                }

                val refreshedChannels = runCatching {
                    catalogRepository.replaceSnapshot(rootUri, scannedChannels)
                }.getOrNull() ?: return@repeat
                if (refreshedChannels.isEmpty()) return@repeat

                applyBackgroundRefresh(rootUri, refreshedChannels)
            }
        }
    }

    private suspend fun isSelectedResource(rootUri: Uri): Boolean =
        runCatching { historyStore.rootUri() == rootUri.toString() }
            .getOrDefault(false)

    private suspend fun showBackgroundScanFailure(
        rootUri: Uri,
        failure: ScanResult.Failure,
    ) {
        checkpoint()
        if (!isSelectedResource(rootUri)) return
        player.stop()
        playingChannelIndex = null
        mutableState.value = freshState(
            mode = AppMode.STORAGE_UNAVAILABLE,
            scanFailure = failure.reason,
            scanDiagnostic = failure.diagnostic,
        )
    }

    private suspend fun showBackgroundNoContent(rootUri: Uri) {
        checkpoint()
        if (!isSelectedResource(rootUri)) return
        player.stop()
        playingChannelIndex = null
        mutableState.value = freshState(AppMode.NO_CONTENT)
    }

    private suspend fun applyBackgroundRefresh(
        rootUri: Uri,
        refreshedChannels: List<Channel>,
    ) {
        if (!isSelectedResource(rootUri)) return
        val current = mutableState.value
        if (current.mode != AppMode.READY) {
            if (current.mode == AppMode.NO_CONTENT ||
                current.mode == AppMode.STORAGE_UNAVAILABLE
            ) {
                showChannelsAndTune(refreshedChannels, restoringApp = false)
            }
            return
        }

        val currentChannel = current.currentChannel ?: return
        val currentMediaId = player.currentMediaItem?.mediaId
        val refreshedChannelIndex = refreshedChannels.indexOfFirst {
            it.id == currentChannel.id
        }

        if (refreshedChannelIndex < 0 || currentMediaId == null) {
            showChannelsAndTune(refreshedChannels, restoringApp = false)
            return
        }

        val refreshedChannel = refreshedChannels[refreshedChannelIndex]
        val refreshedEpisodeIndex = refreshedChannel.episodes.indexOfFirst {
            it.id == currentMediaId
        }
        if (refreshedEpisodeIndex < 0) {
            showChannelsAndTune(refreshedChannels, restoringApp = false)
            return
        }

        val selectedChannelId = current.channels
            .getOrNull(current.channelListSelection)
            ?.id
        val refreshedSelection = refreshedChannels.indexOfFirst {
            it.id == selectedChannelId
        }.takeIf { it >= 0 } ?: refreshedChannelIndex

        pendingChannelIndex = refreshedChannelIndex
        playingChannelIndex = refreshedChannelIndex
        mutableState.value = current.copy(
            channels = refreshedChannels,
            currentChannelIndex = refreshedChannelIndex,
            currentEpisodeIndex = refreshedEpisodeIndex,
            channelListSelection = refreshedSelection,
        )

        if (currentChannel.episodes == refreshedChannel.episodes) return

        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        val playbackSpeed = player.playbackParameters.speed
        player.setMediaItems(
            mediaItemsFor(refreshedChannel),
            refreshedEpisodeIndex,
            positionMs,
        )
        player.prepare()
        player.setPlaybackSpeed(playbackSpeed)
        player.playWhenReady = wasPlaying
    }

    private fun handleChannelListKey(keyCode: Int): RemoteResult {
        val current = mutableState.value
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                cancelBackExitPrompt()
                mutableState.value = current.copy(
                    channelListSelection = wrappedIndex(
                        current.channelListSelection,
                        -1,
                        current.channels.size,
                    ),
                    exitPromptVisible = false,
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                cancelBackExitPrompt()
                mutableState.value = current.copy(
                    channelListSelection = wrappedIndex(
                        current.channelListSelection,
                        1,
                        current.channels.size,
                    ),
                    exitPromptVisible = false,
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                cancelBackExitPrompt()
                mutableState.value = current.copy(
                    channelListVisible = false,
                    exitPromptVisible = false,
                )
                requestTune(current.channelListSelection)
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_BACK -> {
                cancelBackExitPrompt()
                if (current.exitPromptVisible) {
                    RemoteResult.EXIT
                } else {
                    mutableState.value = current.copy(
                        channelListVisible = false,
                        exitPromptVisible = false,
                    )
                    RemoteResult.CONSUMED
                }
            }

            else -> RemoteResult.NOT_HANDLED
        }
    }

    private fun handleMainMenuKey(keyCode: Int): RemoteResult {
        val current = mutableState.value
        val actions = mainMenuActions()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                mutableState.value = current.copy(
                    mainMenuSelection = wrappedIndex(
                        current.mainMenuSelection,
                        -1,
                        actions.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                mutableState.value = current.copy(
                    mainMenuSelection = wrappedIndex(
                        current.mainMenuSelection,
                        1,
                        actions.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> when (val action = actions[current.mainMenuSelection.coerceIn(actions.indices)]) {
                MainMenuAction.USB -> {
                    mutableState.value = current.copy(mainMenuVisible = false)
                    settingsJob?.cancel()
                    settingsJob = scope.launch { selectConfiguredUsbResource() }
                    RemoteResult.CONSUMED
                }

                is MainMenuAction.SMB -> {
                    mutableState.value = current.copy(mainMenuVisible = false)
                    settingsJob?.cancel()
                    settingsJob = scope.launch { selectConfiguredSmbResource(action.resourceId) }
                    RemoteResult.CONSUMED
                }

                MainMenuAction.RESOURCE_MANAGEMENT -> {
                    mutableState.value = current.copy(
                        mainMenuVisible = false,
                        resourceSettingsVisible = true,
                        resourceSettingsSelection = 0,
                    )
                    RemoteResult.CONSUMED
                }

                MainMenuAction.ADVANCED_SETTINGS -> {
                    mutableState.value = current.copy(
                        mainMenuVisible = false,
                        advancedSettingsVisible = true,
                        advancedSettingsSelection = 0,
                    )
                    RemoteResult.CONSUMED
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                mutableState.value = current.copy(mainMenuVisible = false)
                RemoteResult.CONSUMED
            }

            else -> RemoteResult.NOT_HANDLED
        }
    }

    private fun handleResourceSettingsKey(keyCode: Int): RemoteResult {
        val current = mutableState.value
        val actions = resourceSettingsActions()
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                mutableState.value = current.copy(
                    resourceSettingsSelection = wrappedIndex(
                        current.resourceSettingsSelection,
                        -1,
                        actions.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                mutableState.value = current.copy(
                    resourceSettingsSelection = wrappedIndex(
                        current.resourceSettingsSelection,
                        1,
                        actions.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> when (val action = actions[current.resourceSettingsSelection.coerceIn(actions.indices)]) {
                ResourceManagementAction.MANAGE_USB -> {
                    mutableState.value = current.copy(
                        resourceSettingsVisible = false,
                        usbResourceActionsVisible = true,
                    )
                    RemoteResult.CONSUMED
                }

                ResourceManagementAction.ADD_REMOTE_RESOURCE -> {
                    RemoteResult.REQUEST_SMB_SETUP
                }

                is ResourceManagementAction.MANAGE_SMB -> {
                    mutableState.value = current.copy(
                        resourceSettingsVisible = false,
                        smbResourceActionsVisible = true,
                        smbResourceActionsSelection = 0,
                        managedSmbResourceId = action.resourceId,
                    )
                    RemoteResult.CONSUMED
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                mutableState.value = current.copy(
                    resourceSettingsVisible = false,
                    mainMenuVisible = true,
                    mainMenuSelection = mainMenuActions()
                        .indexOf(MainMenuAction.RESOURCE_MANAGEMENT)
                        .coerceAtLeast(0),
                )
                RemoteResult.CONSUMED
            }

            else -> RemoteResult.NOT_HANDLED
        }
    }

    private fun handleUsbResourceActionsKey(keyCode: Int): RemoteResult = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        -> RemoteResult.REQUEST_USB_EDIT

        KeyEvent.KEYCODE_BACK -> {
            mutableState.value = mutableState.value.copy(
                usbResourceActionsVisible = false,
                resourceSettingsVisible = true,
                resourceSettingsSelection = resourceSettingsActions()
                    .indexOf(ResourceManagementAction.MANAGE_USB)
                    .coerceAtLeast(0),
            )
            RemoteResult.CONSUMED
        }

        else -> RemoteResult.NOT_HANDLED
    }

    private fun handleSmbResourceActionsKey(keyCode: Int): RemoteResult {
        val current = mutableState.value
        val resource = selectedManagedSmbResource()
            ?: run {
                mutableState.value = current.copy(
                    smbResourceActionsVisible = false,
                    resourceSettingsVisible = true,
                )
                return RemoteResult.CONSUMED
            }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                mutableState.value = current.copy(
                    smbResourceActionsSelection = wrappedIndex(
                        current.smbResourceActionsSelection,
                        -1,
                        SmbResourceAction.entries.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                mutableState.value = current.copy(
                    smbResourceActionsSelection = wrappedIndex(
                        current.smbResourceActionsSelection,
                        1,
                        SmbResourceAction.entries.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> when (
                SmbResourceAction.entries[
                    current.smbResourceActionsSelection.coerceIn(
                        SmbResourceAction.entries.indices,
                    )
                ]
            ) {
                SmbResourceAction.VIEW -> RemoteResult.REQUEST_SMB_VIEW
                SmbResourceAction.EDIT -> RemoteResult.REQUEST_SMB_EDIT
                SmbResourceAction.DELETE -> {
                    if (activeResourceKind == ResourceKind.SMB &&
                        activeResourceId == resource.id
                    ) {
                        RemoteResult.CONSUMED
                    } else {
                        requestConfirmation(
                            action = PendingConfirmationAction.DELETE_SMB(resource.id),
                            title = context.getString(R.string.confirm_delete_resource_title),
                            message = context.getString(
                                R.string.confirm_delete_resource_message,
                                resource.displayName,
                            ),
                        )
                        RemoteResult.REQUEST_CONFIRMATION
                    }
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                val resourceIndex = configuredSmbResources.indexOfFirst { configured ->
                    configured.id == resource.id
                }
                mutableState.value = current.copy(
                    smbResourceActionsVisible = false,
                    resourceSettingsVisible = true,
                    resourceSettingsSelection = resourceSettingsActions()
                        .indexOf(ResourceManagementAction.MANAGE_SMB(resource.id))
                        .takeIf { it >= 0 }
                        ?: resourceIndex.coerceAtLeast(0),
                )
                RemoteResult.CONSUMED
            }

            else -> RemoteResult.NOT_HANDLED
        }
    }

    private fun handleAdvancedSettingsKey(keyCode: Int): RemoteResult {
        val current = mutableState.value
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                mutableState.value = current.copy(
                    advancedSettingsSelection = wrappedIndex(
                        current.advancedSettingsSelection,
                        -1,
                        AdvancedSettingsAction.entries.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                mutableState.value = current.copy(
                    advancedSettingsSelection = wrappedIndex(
                        current.advancedSettingsSelection,
                        1,
                        AdvancedSettingsAction.entries.size,
                    ),
                )
                RemoteResult.CONSUMED
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> {
                val action = AdvancedSettingsAction.entries[
                    current.advancedSettingsSelection.coerceIn(
                        AdvancedSettingsAction.entries.indices,
                    )
                ]
                requestAdvancedSettingsConfirmation(action)
                RemoteResult.REQUEST_CONFIRMATION
            }

            KeyEvent.KEYCODE_BACK -> {
                mutableState.value = current.copy(
                    advancedSettingsVisible = false,
                    mainMenuVisible = true,
                    mainMenuSelection = mainMenuActions()
                        .indexOf(MainMenuAction.ADVANCED_SETTINGS)
                        .coerceAtLeast(0),
                )
                RemoteResult.CONSUMED
            }

            else -> RemoteResult.NOT_HANDLED
        }
    }

    private fun mainMenuActions(): List<MainMenuAction> = buildList {
        if (configuredUsbRootUri != null) add(MainMenuAction.USB)
        configuredSmbResources.forEach { resource -> add(MainMenuAction.SMB(resource.id)) }
        add(MainMenuAction.RESOURCE_MANAGEMENT)
        add(MainMenuAction.ADVANCED_SETTINGS)
    }

    private fun initialMainMenuSelection(): Int {
        val actions = mainMenuActions()
        val activeIndex = when (activeResourceKind) {
            ResourceKind.USB -> actions.indexOf(MainMenuAction.USB)
            ResourceKind.SMB -> actions.indexOfFirst { action ->
                action is MainMenuAction.SMB && action.resourceId == activeResourceId
            }

            null -> -1
        }
        return activeIndex.takeIf { it >= 0 }
            ?: actions.indexOf(MainMenuAction.RESOURCE_MANAGEMENT).coerceAtLeast(0)
    }

    private fun resourceSettingsActions(): List<ResourceManagementAction> = buildList {
        if (configuredUsbRootUri != null) add(ResourceManagementAction.MANAGE_USB)
        add(ResourceManagementAction.ADD_REMOTE_RESOURCE)
        configuredSmbResources.forEach { resource ->
            add(ResourceManagementAction.MANAGE_SMB(resource.id))
        }
    }

    private suspend fun selectConfiguredUsbResource() {
        val rootUri = configuredUsbRootUri
            ?.let(Uri::parse)
            ?: return
        activateResource(ResourceKind.USB, USB_RESOURCE_ID, rootUri)
    }

    private suspend fun selectConfiguredSmbResource(resourceId: String) {
        val resource = configuredSmbResources.firstOrNull { it.id == resourceId } ?: return
        activateResource(ResourceKind.SMB, resource.id, SmbMediaUri.root(resource))
    }

    private suspend fun activateResource(
        kind: ResourceKind,
        resourceId: String,
        rootUri: Uri,
        restoringApp: Boolean = false,
        preferIndex: Boolean = false,
    ) {
        backgroundScanJob?.cancel()
        checkpoint()
        player.stop()
        activeResourceKind = kind
        activeResourceId = resourceId
        activeRootUri = rootUri
        historyStore.setActiveSmbResource(resourceId.takeIf { kind == ResourceKind.SMB })
        historyStore.saveRootUri(rootUri.toString())
        if (preferIndex) {
            restoreIndexedOrScan(rootUri, restoringApp)
        } else {
            scanAndPlay(rootUri, restoringApp)
        }
    }

    private suspend fun deleteSmbResource(resourceId: String) {
        if (activeResourceKind == ResourceKind.SMB && activeResourceId == resourceId) return
        configuredSmbResources.firstOrNull { resource -> resource.id == resourceId }
            ?.let(SmbMediaUri::root)
            ?.let { rootUri -> catalogRepository.invalidateIndex(rootUri) }
        configuredSmbResources = historyStore.deleteSmbResource(resourceId)
        refreshResourceConfiguration()
    }

    private fun refreshResourceConfiguration() {
        mutableState.value = mutableState.value.copy(
            activeResourceKind = activeResourceKind,
            activeResourceId = activeResourceId,
            usbResourceConfigured = configuredUsbRootUri != null,
            usbVideoDirectory = configuredUsbVideoDirectory,
            smbResources = configuredSmbResources.map { resource ->
                SmbResourceSummary(resource.id, resource.displayName)
            },
        )
    }

    private fun performAdvancedSettingsAction(action: AdvancedSettingsAction) {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            when (action) {
                AdvancedSettingsAction.CLEAR_INDEX -> clearAndRebuildIndex()
                AdvancedSettingsAction.RESET_CURRENT_CHANNEL -> resetCurrentChannelProgress()
                AdvancedSettingsAction.RESET_ALL_CHANNELS -> resetAllChannelProgress()
                AdvancedSettingsAction.RESET_APP_DATA -> resetAppData()
            }
        }
    }

    private fun requestAdvancedSettingsConfirmation(action: AdvancedSettingsAction) {
        val (titleRes, messageRes) = when (action) {
            AdvancedSettingsAction.CLEAR_INDEX -> {
                R.string.confirm_clear_index_title to R.string.confirm_clear_index_message
            }

            AdvancedSettingsAction.RESET_CURRENT_CHANNEL -> {
                R.string.confirm_reset_current_title to R.string.confirm_reset_current_message
            }

            AdvancedSettingsAction.RESET_ALL_CHANNELS -> {
                R.string.confirm_reset_all_title to R.string.confirm_reset_all_message
            }

            AdvancedSettingsAction.RESET_APP_DATA -> {
                R.string.confirm_reset_app_data_title to R.string.confirm_reset_app_data_message
            }
        }
        requestConfirmation(
            action = PendingConfirmationAction.ADVANCED(action),
            title = context.getString(titleRes),
            message = context.getString(messageRes),
        )
    }

    private fun requestConfirmation(
        action: PendingConfirmationAction,
        title: String,
        message: String,
    ) {
        pendingConfirmationAction = action
        pendingConfirmationRequest = ConfirmationRequest(title, message)
    }

    private suspend fun clearAndRebuildIndex() {
        val rootUri = historyStore.rootUri()
            ?.let(Uri::parse)
            ?: return
        backgroundScanJob?.cancel()
        backgroundScanJob = null
        checkpoint()
        catalogRepository.invalidateIndex(rootUri)
        scanAndPlay(rootUri, restoringApp = true)
        if (mutableState.value.mode == AppMode.READY) {
            showActionFeedback(R.string.index_rebuilt)
        }
    }

    private suspend fun resetCurrentChannelProgress() {
        val current = mutableState.value
        val channel = current.currentChannel ?: return
        historyWriteMutex.withLock {
            historyStore.resetChannelPlayback(
                channelId = channel.id,
                legacyChannelId = channel.legacyId,
            )
        }
        tuneTo(current.currentChannelIndex, restoringApp = false)
        showActionFeedback(R.string.current_channel_progress_reset)
    }

    private suspend fun resetAllChannelProgress() {
        val currentChannelIndex = mutableState.value.currentChannelIndex
        historyWriteMutex.withLock {
            historyStore.resetAllPlayback()
        }
        tuneTo(currentChannelIndex, restoringApp = false)
        showActionFeedback(R.string.all_channel_progress_reset)
    }

    private suspend fun resetAppData() {
        backgroundScanJob?.cancel()
        backgroundScanJob = null
        tuneJob?.cancel()
        tuneJob = null
        overlayJob?.cancel()
        overlayJob = null
        cancelDirectionalGestures()
        cancelBackExitPrompt()
        player.stop()
        player.clearMediaItems()
        playingChannelIndex = null
        mutableState.value = freshState(AppMode.LOADING)

        historyWriteMutex.withLock {
            historyStore.resetAppData()
        }

        configuredUsbRootUri = null
        configuredUsbVideoDirectory = UsbStorageResolver.DEFAULT_VIDEO_DIRECTORY
        configuredSmbResources = emptyList()
        activeResourceKind = null
        activeResourceId = null
        activeRootUri = null
        latestMountedUsbRootUris = emptyList()
        pendingChannelIndex = 0
        pendingConfirmationAction = null
        pendingConfirmationRequest = null
        mutableState.value = freshState(AppMode.NEEDS_STORAGE_ACCESS)
    }

    private fun showActionFeedback(messageRes: Int) {
        mutableState.value = mutableState.value.copy(
            channelOverlayVisible = true,
            channelOverlayPositionMs = player.currentPosition.coerceAtLeast(0L),
            message = context.getString(messageRes),
        )
        scheduleOverlayDismiss()
    }

    private fun freshState(
        mode: AppMode,
        channels: List<Channel> = emptyList(),
        currentChannelIndex: Int = 0,
        channelListSelection: Int = 0,
        scanFailure: ScanFailure? = null,
        scanDiagnostic: String? = null,
    ): Tv2000UiState = Tv2000UiState(
        mode = mode,
        channels = channels,
        currentChannelIndex = currentChannelIndex,
        channelListSelection = channelListSelection,
        activeResourceKind = activeResourceKind,
        activeResourceId = activeResourceId,
        usbResourceConfigured = configuredUsbRootUri != null,
        usbVideoDirectory = configuredUsbVideoDirectory,
        smbResources = configuredSmbResources.map { resource ->
            SmbResourceSummary(resource.id, resource.displayName)
        },
        scanFailure = scanFailure,
        scanDiagnostic = scanDiagnostic,
    )

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

        val mediaItems = mediaItemsFor(channel)

        player.stop()
        player.setMediaItems(mediaItems, episodeIndex, positionMs)
        player.prepare()
        player.setPlaybackSpeed(history?.playbackSpeed ?: 1.0f)
        player.playWhenReady = if (restoringApp) history?.wasPlaying ?: true else true
        playingChannelIndex = channelIndex

        historyStore.saveActiveChannel(channel.id, activeRootUri?.toString())
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

    private fun mediaItemsFor(channel: Channel): List<MediaItem> =
        channel.episodes.map { episode ->
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
            historyWriteMutex.withLock {
                historyStore.moveChannelCursor(
                    channelId = channel.id,
                    outgoingEpisodeId = outgoingEpisode.id,
                    outgoingPositionMs = outgoingPositionMs,
                    targetEpisodeId = targetEpisode.id,
                    wasPlaying = shouldPlay,
                )
            }
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

    private fun scheduleBackExitPromptDismiss() {
        backExitPromptJob?.cancel()
        backExitPromptJob = scope.launch {
            delay(BACK_EXIT_PROMPT_DURATION_MS)
            mutableState.value = mutableState.value.copy(exitPromptVisible = false)
        }
    }

    private fun cancelBackExitPrompt() {
        backExitPromptJob?.cancel()
        backExitPromptJob = null
    }

    private fun persistCurrentSnapshot() {
        val snapshot = capturePlaybackSnapshot() ?: return
        scope.launch {
            persistPlaybackSnapshot(snapshot)
        }
    }

    private suspend fun persistPlaybackSnapshot(snapshot: PlaybackSnapshot) {
        historyWriteMutex.withLock {
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
        const val BACK_EXIT_PROMPT_DURATION_MS = 3_000L
        const val USB_INITIAL_RESCAN_DELAY_MS = 2_000L
        const val USB_BACKGROUND_SCAN_RETRY_DELAY_MS = 3_000L
        const val USB_BACKGROUND_SCAN_ATTEMPTS = 3
        const val USB_RESOURCE_ID = "usb"
    }
}

private sealed interface MainMenuAction {
    data object USB : MainMenuAction
    data class SMB(val resourceId: String) : MainMenuAction
    data object RESOURCE_MANAGEMENT : MainMenuAction
    data object ADVANCED_SETTINGS : MainMenuAction
}

private sealed interface ResourceManagementAction {
    data object MANAGE_USB : ResourceManagementAction
    data object ADD_REMOTE_RESOURCE : ResourceManagementAction
    data class MANAGE_SMB(val resourceId: String) : ResourceManagementAction
}

private enum class SmbResourceAction {
    VIEW,
    EDIT,
    DELETE,
}

private sealed interface PendingConfirmationAction {
    data class DELETE_SMB(val resourceId: String) : PendingConfirmationAction
    data class ADVANCED(val action: AdvancedSettingsAction) : PendingConfirmationAction
}

internal fun wrappedIndex(current: Int, delta: Int, size: Int): Int {
    if (size <= 0) return 0
    return ((current + delta) % size + size) % size
}
