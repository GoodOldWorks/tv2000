package com.tv2000.app.storage

internal object ChannelNumberAllocator {
    fun assign(
        channelIdsInDiscoveryOrder: List<String>,
        existingNumbers: Map<String, Int>,
        maximumExistingNumber: Int,
    ): Map<String, Int> {
        var nextNumber = maximumExistingNumber + 1
        return buildMap {
            channelIdsInDiscoveryOrder.forEach { channelId ->
                put(
                    channelId,
                    existingNumbers[channelId] ?: nextNumber++,
                )
            }
        }
    }
}
