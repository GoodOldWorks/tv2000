package com.tv2000.app.playback

import com.tv2000.app.storage.UsbStorageResolver

internal sealed interface UsbSwapDecision {
    data object KeepCurrent : UsbSwapDecision

    data class RestoreCurrent(val rootUri: String) : UsbSwapDecision

    data object WaitForUsb : UsbSwapDecision

    data object MultipleVolumes : UsbSwapDecision

    data class SwitchToSingle(val rootUri: String) : UsbSwapDecision
}

internal fun decideUsbSwap(
    selectedRootUri: String?,
    mountedRootUris: List<String>,
    selectedRootNeedsRestore: Boolean = false,
): UsbSwapDecision {
    val distinctMountedRoots = mountedRootUris.distinctBy { rootUri ->
        UsbStorageResolver.volumeIdentity(rootUri)
    }
    val selectedIdentity = selectedRootUri?.let(UsbStorageResolver::volumeIdentity)
    if (selectedIdentity != null) {
        val mountedSelectedRoot = distinctMountedRoots.firstOrNull { rootUri ->
            UsbStorageResolver.volumeIdentity(rootUri) == selectedIdentity
        }
        if (mountedSelectedRoot != null) {
            return if (selectedRootNeedsRestore) {
                UsbSwapDecision.RestoreCurrent(mountedSelectedRoot)
            } else {
                UsbSwapDecision.KeepCurrent
            }
        }
    }

    return when (distinctMountedRoots.size) {
        0 -> UsbSwapDecision.WaitForUsb
        1 -> UsbSwapDecision.SwitchToSingle(distinctMountedRoots.single())
        else -> UsbSwapDecision.MultipleVolumes
    }
}
