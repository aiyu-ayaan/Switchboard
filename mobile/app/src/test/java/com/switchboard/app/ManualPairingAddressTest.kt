package com.switchboard.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ManualPairingAddressTest {
    /**
     * Unit tests run against the debug variant, which is the development
     * client: it must dial the "dev" profile's daemon rather than the shipped
     * port, or a checkout of the desktop app and an installed copy become
     * indistinguishable from the phone.
     */
    @Test
    fun debugClient_dialsTheDevProfilePort() {
        assertEquals(9428, SwitchboardViewModel.DEFAULT_PORT)
        assertEquals(BuildConfig.DEFAULT_PORT, SwitchboardViewModel.DEFAULT_PORT)
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
