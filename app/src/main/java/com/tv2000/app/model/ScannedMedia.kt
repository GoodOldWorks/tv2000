package com.tv2000.app.model

import android.net.Uri

data class ScannedEpisode(
    val relativePath: String,
    val title: String,
    val uri: Uri,
    val sizeBytes: Long,
    val modifiedAt: Long,
)

data class ScannedChannel(
    val relativePath: String,
    val name: String,
    val sourceUri: Uri,
    val episodes: List<ScannedEpisode>,
)
