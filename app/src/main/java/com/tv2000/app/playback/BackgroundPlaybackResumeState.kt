package com.tv2000.app.playback

internal class BackgroundPlaybackResumeState {
    private var resumeRequested = false

    fun onBackground(playWhenReady: Boolean) {
        resumeRequested = playWhenReady
    }

    fun consumeResumeRequest(): Boolean = resumeRequested.also {
        resumeRequested = false
    }
}
