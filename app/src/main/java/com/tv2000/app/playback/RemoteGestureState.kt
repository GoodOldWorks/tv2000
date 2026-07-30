package com.tv2000.app.playback

internal enum class DirectionalPress {
    LEFT,
    RIGHT,
}

internal class DirectionalDoublePressDetector(
    private val timeoutMs: Long,
) {
    private var pendingPress: DirectionalPress? = null
    private var pendingAtMs: Long = 0L

    fun register(
        press: DirectionalPress,
        nowMs: Long,
    ): Boolean {
        val elapsedMs = nowMs - pendingAtMs
        val isDoublePress =
            pendingPress == press && elapsedMs in 0..timeoutMs

        if (isDoublePress) {
            reset()
        } else {
            pendingPress = press
            pendingAtMs = nowMs
        }
        return isDoublePress
    }

    fun reset() {
        pendingPress = null
        pendingAtMs = 0L
    }
}

internal enum class DoubleLeftAction {
    RESTART_CURRENT_EPISODE,
    PLAY_PREVIOUS_EPISODE,
}

internal fun resolveDoubleLeftAction(
    playbackPositionMs: Long,
    previousEpisodeThresholdMs: Long,
): DoubleLeftAction =
    if (playbackPositionMs <= previousEpisodeThresholdMs) {
        DoubleLeftAction.PLAY_PREVIOUS_EPISODE
    } else {
        DoubleLeftAction.RESTART_CURRENT_EPISODE
    }
