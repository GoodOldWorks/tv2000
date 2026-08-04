package com.tv2000.app.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbSwapDecisionTest {
    private val diskA = "tv2000-mediastore://AAAA-0001"
    private val diskB = "tv2000-mediastore://BBBB-0002"

    @Test
    fun `keeps the current disk when its uuid is still mounted`() {
        assertEquals(
            UsbSwapDecision.KeepCurrent,
            decideUsbSwap(diskA, listOf(diskA)),
        )
    }

    @Test
    fun `keeps current disk even when another disk is also mounted`() {
        assertEquals(
            UsbSwapDecision.KeepCurrent,
            decideUsbSwap(diskA, listOf(diskA, diskB)),
        )
    }

    @Test
    fun `restores the current disk when it reappears while waiting`() {
        assertEquals(
            UsbSwapDecision.RestoreCurrent(diskA),
            decideUsbSwap(
                selectedRootUri = diskA,
                mountedRootUris = listOf(diskA),
                selectedRootNeedsRestore = true,
            ),
        )
    }

    @Test
    fun `restores the current disk through its latest mount uri`() {
        val selectedRoot = "file:///storage/AAAA-0001"

        assertEquals(
            UsbSwapDecision.RestoreCurrent(diskA),
            decideUsbSwap(
                selectedRootUri = selectedRoot,
                mountedRootUris = listOf(diskA),
                selectedRootNeedsRestore = true,
            ),
        )
    }

    @Test
    fun `waits after the current disk is removed`() {
        assertEquals(
            UsbSwapDecision.WaitForUsb,
            decideUsbSwap(diskA, emptyList()),
        )
    }

    @Test
    fun `switches automatically when exactly one different disk is mounted`() {
        assertEquals(
            UsbSwapDecision.SwitchToSingle(diskB),
            decideUsbSwap(diskA, listOf(diskB)),
        )
    }

    @Test
    fun `does not choose automatically when multiple replacement disks exist`() {
        assertEquals(
            UsbSwapDecision.MultipleVolumes,
            decideUsbSwap(diskA, listOf(diskB, "tv2000-mediastore://CCCC-0003")),
        )
    }

    @Test
    fun `fresh setup accepts a single mounted disk`() {
        assertEquals(
            UsbSwapDecision.SwitchToSingle(diskA),
            decideUsbSwap(selectedRootUri = null, mountedRootUris = listOf(diskA)),
        )
    }
}
