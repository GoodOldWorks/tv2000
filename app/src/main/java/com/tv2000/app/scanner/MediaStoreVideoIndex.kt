package com.tv2000.app.scanner

internal data class MediaStoreVideoRecord(
    val id: Long,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtSeconds: Long,
)

internal data class IndexedMediaStoreChannel(
    val relativePath: String,
    val name: String,
    val episodes: List<MediaStoreVideoRecord>,
)

internal fun indexMediaStoreVideos(
    records: List<MediaStoreVideoRecord>,
): List<IndexedMediaStoreChannel> =
    records
        .asSequence()
        .filter { record ->
            record.sizeBytes > 0L && isSupportedVideoFile(record.displayName)
        }
        .mapNotNull { record ->
            val channelName = directChannelName(record.relativePath) ?: return@mapNotNull null
            channelName to record
        }
        .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        .map { (channelName, episodes) ->
            IndexedMediaStoreChannel(
                relativePath = channelName,
                name = displayChannelName(channelName),
                episodes = episodes.sortedWith { left, right ->
                    NaturalOrderComparator.compare(left.displayName, right.displayName)
                },
            )
        }
        .sortedWith { left, right ->
            NaturalOrderComparator.compare(left.name, right.name)
        }

internal fun directChannelName(relativePath: String): String? {
    val segments = relativePath
        .replace('\\', '/')
        .split('/')
        .map(String::trim)
        .filter(String::isNotEmpty)

    return segments.singleOrNull()
        ?.takeIf { channelName -> !channelName.startsWith('.') }
}

internal fun isSupportedVideoFile(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
    return extension.lowercase() in SUPPORTED_VIDEO_EXTENSIONS
}

internal val SUPPORTED_VIDEO_EXTENSIONS = setOf(
    "mp4",
    "mkv",
    "avi",
    "mov",
    "ts",
    "m2ts",
    "webm",
)
