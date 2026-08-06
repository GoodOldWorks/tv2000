package com.tv2000.app.playback

internal fun nextUsbIndexRefreshDelayMs(
    attempt: Int,
    firstDelayMs: Long,
    watchMediaStoreContinuously: Boolean,
): Long? {
    require(attempt >= 0)
    require(firstDelayMs >= 0L)
    if (attempt == 0) return firstDelayMs

    if (!watchMediaStoreContinuously) {
        return DIRECT_STORAGE_RETRY_DELAYS_MS.getOrNull(attempt - 1)
    }
    return MEDIA_STORE_RETRY_DELAYS_MS.getOrElse(attempt - 1) {
        MEDIA_STORE_POLL_INTERVAL_MS
    }
}

internal fun includeDirectFileSnapshotForBackgroundScan(
    isMediaStore: Boolean,
    remainingAttempts: Int,
): Boolean {
    require(remainingAttempts >= 0)
    return isMediaStore && remainingAttempts > 0
}

internal fun shouldValidateCachedMediaStoreSnapshot(
    isMediaStore: Boolean,
    currentSourceRevision: String?,
    validatedSourceRevision: String?,
): Boolean {
    if (!isMediaStore) return false
    return currentSourceRevision == null || currentSourceRevision != validatedSourceRevision
}

private val DIRECT_STORAGE_RETRY_DELAYS_MS = listOf(3_000L, 3_000L)
private val MEDIA_STORE_RETRY_DELAYS_MS = listOf(
    3_000L,
    5_000L,
    10_000L,
    20_000L,
    40_000L,
)
private const val MEDIA_STORE_POLL_INTERVAL_MS = 60_000L
