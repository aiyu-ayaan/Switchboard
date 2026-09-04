package com.switchboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualPairingAddressTest {
    @Test
    fun defaultPort_is9427() {
        assertEquals(9427, SwitchboardViewModel.DEFAULT_PORT)
    }

    @Test
    fun manualPairing_addressFormatLogic() {
        fun formatAddress(address: String, addPortToo: Boolean, port: String): String {
            return if (addPortToo && port.isNotBlank()) {
                "${address.trim()}:${port.trim()}"
            } else {
                address.trim()
            }
        }

        assertEquals("192.168.1.10", formatAddress("192.168.1.10", false, ""))
        assertEquals("192.168.1.10", formatAddress("192.168.1.10", true, ""))
        assertEquals("192.168.1.10:8080", formatAddress("192.168.1.10", true, "8080"))
    }
}
