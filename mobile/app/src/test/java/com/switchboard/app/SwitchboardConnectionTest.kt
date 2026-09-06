package com.switchboard.app

import com.switchboard.app.net.FileProgress
import com.switchboard.app.net.TransferStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchboardConnectionTest {

    private fun transfer(status: String) = FileProgress(transferId = status, status = status)

    @Test
    fun tearsDown_whenTheUiIsGoneAndNothingIsMoving() {
        assertTrue(SwitchboardConnection.shouldTearDown(uiActive = false, transfers = emptyList()))
    }

    @Test
    fun tearsDown_whenEveryTransferHasFinished() {
        val history = listOf(
            transfer(TransferStatus.COMPLETED),
            transfer(TransferStatus.FAILED),
            transfer(TransferStatus.CANCELLED)
        )
        assertTrue(SwitchboardConnection.shouldTearDown(uiActive = false, transfers = history))
    }

    @Test
    fun keepsTheSession_whileATransferIsInFlight() {
        // The case the swipe-away fix exists for: no UI left, bytes still moving.
        listOf(TransferStatus.PENDING, TransferStatus.ACTIVE, TransferStatus.PAUSED).forEach { status ->
            assertFalse(
                status,
                SwitchboardConnection.shouldTearDown(
                    uiActive = false,
                    transfers = listOf(transfer(TransferStatus.COMPLETED), transfer(status))
                )
            )
        }
    }

    @Test
    fun keepsTheSession_whileAScreenIsAttached() {
        assertFalse(SwitchboardConnection.shouldTearDown(uiActive = true, transfers = emptyList()))
    }

    @Test
    fun keepsTheSession_whileAlwaysOnIsSet() {
        // The whole point of the setting: no UI, nothing moving, still held.
        assertFalse(
            SwitchboardConnection.shouldTearDown(
                uiActive = false,
                transfers = emptyList(),
                alwaysOn = true
            )
        )
    }

    @Test
    fun retryDelay_backsOffToACeiling() {
        assertEquals(1_000L, SwitchboardConnection.retryDelayMs(0))
        assertEquals(2_000L, SwitchboardConnection.retryDelayMs(1))
        assertEquals(16_000L, SwitchboardConnection.retryDelayMs(4))
        // Capped, and stays capped however long the desktop stays off.
        assertEquals(30_000L, SwitchboardConnection.retryDelayMs(5))
        assertEquals(30_000L, SwitchboardConnection.retryDelayMs(50))
    }
}
