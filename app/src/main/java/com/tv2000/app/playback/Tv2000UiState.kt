package com.tv2000.app.playback

import com.tv2000.app.model.Channel
import com.tv2000.app.scanner.ScanFailure

data class Tv2000UiState(
    val mode: AppMode = AppMode.LOADING,
    val channels: List<Channel> = emptyList(),
    val currentChannelIndex: Int = 0,
    val currentEpisodeIndex: Int = 0,
    val channelListSelection: Int = 0,
    val channelListVisible: Boolean = false,
    val settingsMenuSelection: Int = 0,
    val settingsMenuVisible: Boolean = false,
    val resourceMenuSelection: Int = 0,
    val resourceMenuVisible: Boolean = false,
    val activeResourceKind: ResourceKind? = null,
    val smbResourceName: String? = null,
    val channelOverlayVisible: Boolean = false,
    val channelOverlayPositionMs: Long = 0L,
    val message: String? = null,
    val scanFailure: ScanFailure? = null,
    val scanDiagnostic: String? = null,
) {
    val currentChannel: Channel?
        get() = channels.getOrNull(currentChannelIndex)

    val currentEpisodeTitle: String?
        get() = currentChannel?.episodes?.getOrNull(currentEpisodeIndex)?.title
}

enum class AppMode {
    LOADING,
    NEEDS_STORAGE_ACCESS,
    SCANNING,
    NO_CONTENT,
    READY,
    STORAGE_UNAVAILABLE,
}

enum class RemoteResult {
    CONSUMED,
    REQUEST_STORAGE,
    REQUEST_SMB_SETUP,
    EXIT,
    NOT_HANDLED,
}

enum class SettingsAction {
    SELECT_RESOURCE,
    CLEAR_INDEX,
    RESET_CURRENT_CHANNEL,
    RESET_ALL_CHANNELS,
}

enum class ResourceKind {
    USB,
    SMB,
}

enum class ResourceAction {
    USB,
    SMB,
    ADD_OR_EDIT_SMB,
}
