package com.switchboard.app

import com.switchboard.app.net.FileProgress
import com.switchboard.app.net.TransferStatus
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
}
