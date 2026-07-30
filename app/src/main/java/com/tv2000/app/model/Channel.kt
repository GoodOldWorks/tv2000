package com.tv2000.app.model

import android.net.Uri

data class Episode(
    val id: String,
    val legacyId: String? = null,
    val title: String,
    val uri: Uri,
)

data class Channel(
    val id: String,
    val legacyId: String? = null,
    val number: Int,
    val name: String,
    val episodes: List<Episode>,
)
