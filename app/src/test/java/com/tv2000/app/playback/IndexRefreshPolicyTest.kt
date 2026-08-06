package com.tv2000.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexRefreshPolicyTest {
    @Test
    fun `media store uses backoff then keeps observing once per minute`() {
        val delays = (0..8).map { attempt ->
            nextUsbIndexRefreshDelayMs(
                attempt = attempt,
                firstDelayMs = 2_000L,
                watchMediaStoreContinuously = true,
            )
        }

        assertEquals(
            listOf(2_000L, 3_000L, 5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L),
            delays,
        )
    }

    @Test
    fun `direct storage keeps only the short mount settling retries`() {
        val delays = (0..3).map { attempt ->
            nextUsbIndexRefreshDelayMs(
                attempt = attempt,
                firstDelayMs = 2_000L,
                watchMediaStoreContinuously = false,
            )
        }

        assertEquals(listOf(2_000L, 3_000L, 3_000L, null), delays)
    }

    @Test
    fun `MediaStore direct snapshot retries are bounded by remaining attempts`() {
        val decisions = (2 downTo 0).map { remainingAttempts ->
            includeDirectFileSnapshotForBackgroundScan(
                isMediaStore = true,
                remainingAttempts = remainingAttempts,
            )
        }

        assertEquals(listOf(true, true, false), decisions)
        assertEquals(
            false,
            includeDirectFileSnapshotForBackgroundScan(
                isMediaStore = false,
                remainingAttempts = 2,
            ),
        )
    }

    @Test
    fun `cached MediaStore snapshot is validated only when revision changed`() {
        assertEquals(
            false,
            shouldValidateCachedMediaStoreSnapshot(
                isMediaStore = true,
                currentSourceRevision = "generation:42",
                validatedSourceRevision = "generation:42",
            ),
        )
        assertEquals(
            true,
            shouldValidateCachedMediaStoreSnapshot(
                isMediaStore = true,
                currentSourceRevision = "generation:43",
                validatedSourceRevision = "generation:42",
            ),
        )
        assertEquals(
            true,
            shouldValidateCachedMediaStoreSnapshot(
                isMediaStore = true,
                currentSourceRevision = null,
                validatedSourceRevision = "generation:42",
            ),
        )
        assertEquals(
            false,
            shouldValidateCachedMediaStoreSnapshot(
                isMediaStore = false,
                currentSourceRevision = null,
                validatedSourceRevision = null,
            ),
        )
    }
}
