package com.tv2000.app.playback

import com.tv2000.app.model.Channel

data class Tv2000UiState(
    val mode: AppMode = AppMode.LOADING,
    val channels: List<Channel> = emptyList(),
    val currentChannelIndex: Int = 0,
    val currentEpisodeIndex: Int = 0,
    val channelListSelection: Int = 0,
    val channelListVisible: Boolean = false,
    val channelOverlayVisible: Boolean = false,
    val channelOverlayPositionMs: Long = 0L,
    val message: String? = null,
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
    EXIT,
    NOT_HANDLED,
}
