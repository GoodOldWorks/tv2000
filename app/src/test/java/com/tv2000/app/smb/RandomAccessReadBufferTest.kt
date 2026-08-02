package com.tv2000.app.smb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RandomAccessReadBufferTest {
    @Test
    fun sequentialSmallReadsAreCoalesced() {
        val sourceData = ByteArray(1024 * 1024) { index -> (index % 251).toByte() }
        val reader = RandomAccessReadBuffer(capacityBytes = 64 * 1024)
        val source = RecordingSource(sourceData)
        val destination = ByteArray(32 * 1024)

        var position = 0L
        repeat(8) {
            val read = reader.read(
                position = position,
                destination = destination,
                destinationOffset = it * 4096,
                requestedLength = 4096,
                availableLength = sourceData.size - position,
                source = source,
            )
            assertEquals(4096, read)
            position += read
        }

        assertEquals(1, source.readCount)
        assertArrayEquals(sourceData.copyOfRange(0, destination.size), destination)
    }

    @Test
    fun randomReadsAtFileTailAndBeginningReturnExactBytes() {
        val sourceData = ByteArray(300_000) { index -> (index % 239).toByte() }
        val reader = RandomAccessReadBuffer(capacityBytes = 32 * 1024)
        val source = RecordingSource(sourceData)
        val destination = ByteArray(128)

        val tailPosition = sourceData.size - 64L
        assertEquals(
            64,
            reader.read(
                position = tailPosition,
                destination = destination,
                destinationOffset = 0,
                requestedLength = 64,
                availableLength = 64,
                source = source,
            ),
        )
        assertArrayEquals(
            sourceData.copyOfRange(sourceData.size - 64, sourceData.size),
            destination.copyOfRange(0, 64),
        )

        assertEquals(
            128,
            reader.read(
                position = 0,
                destination = destination,
                destinationOffset = 0,
                requestedLength = destination.size,
                availableLength = sourceData.size.toLong(),
                source = source,
            ),
        )
        assertArrayEquals(sourceData.copyOfRange(0, 128), destination)
        assertEquals(2, source.readCount)
    }

    @Test
    fun readAheadDoesNotCrossTheRequestedDataRange() {
        val sourceData = ByteArray(100_000) { index -> index.toByte() }
        val reader = RandomAccessReadBuffer(capacityBytes = 32 * 1024)
        val source = RecordingSource(sourceData)

        assertEquals(
            100,
            reader.read(
                position = 50_000,
                destination = ByteArray(100),
                destinationOffset = 0,
                requestedLength = 100,
                availableLength = 100,
                source = source,
            ),
        )
        assertEquals(100, source.lastRequestedLength)
    }

    private class RecordingSource(
        private val data: ByteArray,
    ) : RandomAccessSource {
        var readCount = 0
            private set
        var lastRequestedLength = 0
            private set

        override fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int {
            readCount += 1
            lastRequestedLength = length
            if (position >= data.size) return -1
            val readLength = minOf(length, data.size - position.toInt())
            data.copyInto(buffer, offset, position.toInt(), position.toInt() + readLength)
            return readLength
        }
    }
}
