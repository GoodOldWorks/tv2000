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
    val exitPromptVisible: Boolean = false,
    val mainMenuSelection: Int = 0,
    val mainMenuVisible: Boolean = false,
    val resourceSettingsSelection: Int = 0,
    val resourceSettingsVisible: Boolean = false,
    val usbResourceActionsVisible: Boolean = false,
    val smbResourceActionsSelection: Int = 0,
    val smbResourceActionsVisible: Boolean = false,
    val managedSmbResourceId: String? = null,
    val advancedSettingsSelection: Int = 0,
    val advancedSettingsVisible: Boolean = false,
    val activeResourceKind: ResourceKind? = null,
    val activeResourceId: String? = null,
    val usbResourceConfigured: Boolean = false,
    val usbVideoDirectory: String = "TV2000",
    val smbResources: List<SmbResourceSummary> = emptyList(),
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
    REQUEST_USB_EDIT,
    REQUEST_SMB_SETUP,
    REQUEST_SMB_VIEW,
    REQUEST_SMB_EDIT,
    REQUEST_CONFIRMATION,
    EXIT,
    NOT_HANDLED,
}

enum class AdvancedSettingsAction {
    CLEAR_INDEX,
    RESET_CURRENT_CHANNEL,
    RESET_ALL_CHANNELS,
    RESET_APP_DATA,
}

enum class ResourceKind {
    USB,
    SMB,
}

data class SmbResourceSummary(
    val id: String,
    val name: String,
)

data class ConfirmationRequest(
    val title: String,
    val message: String,
)

sealed interface AddSmbResourceResult {
    data object Success : AddSmbResourceResult

    data class Failure(val message: String) : AddSmbResourceResult
}
