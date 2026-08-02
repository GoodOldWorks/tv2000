package com.tv2000.app.smb

internal fun interface RandomAccessSource {
    fun read(buffer: ByteArray, position: Long, offset: Int, length: Int): Int
}

/**
 * Coalesces the small, position-based reads used by media extractors into larger SMB reads.
 * This is especially important for Matroska files, whose indexes can cause frequent jumps
 * between the beginning and the end of the file.
 */
internal class RandomAccessReadBuffer(
    capacityBytes: Int = DEFAULT_CAPACITY_BYTES,
) {
    private val data = ByteArray(capacityBytes.also { require(it > 0) })
    private var startPosition = 0L
    private var dataLength = 0

    fun read(
        position: Long,
        destination: ByteArray,
        destinationOffset: Int,
        requestedLength: Int,
        availableLength: Long,
        source: RandomAccessSource,
    ): Int {
        if (requestedLength == 0) return 0
        if (availableLength <= 0L) return END_OF_INPUT

        val maximumLength = minOf(requestedLength.toLong(), availableLength).toInt()
        if (maximumLength >= data.size) {
            return source.read(destination, position, destinationOffset, maximumLength)
        }

        val bufferedOffset = position - startPosition
        if (bufferedOffset < 0L || bufferedOffset >= dataLength.toLong()) {
            val fillLength = minOf(data.size.toLong(), availableLength).toInt()
            val loaded = source.read(data, position, 0, fillLength)
            if (loaded <= 0) {
                dataLength = 0
                return loaded
            }
            check(loaded <= fillLength) { "Random-access source returned too many bytes" }
            startPosition = position
            dataLength = loaded
        }

        val sourceOffset = (position - startPosition).toInt()
        val copyLength = minOf(maximumLength, dataLength - sourceOffset)
        data.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            startIndex = sourceOffset,
            endIndex = sourceOffset + copyLength,
        )
        return copyLength
    }

    fun reset() {
        startPosition = 0L
        dataLength = 0
    }

    private companion object {
        const val DEFAULT_CAPACITY_BYTES = 256 * 1024
        const val END_OF_INPUT = -1
    }
}
