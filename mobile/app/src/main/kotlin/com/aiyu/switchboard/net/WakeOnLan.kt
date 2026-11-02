package com.aiyu.switchboard.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** The magic packet: six 0xFF bytes, then the hardware address sixteen times. */
fun magicPacket(mac: String): ByteArray? {
    val parts = mac.trim().split(':', '-')
    if (parts.size != 6) return null
    val bytes = parts.map { it.toIntOrNull(16)?.takeIf { v -> v in 0..255 }?.toByte() ?: return null }
    return ByteArray(6) { 0xFF.toByte() } + (0 until 16).flatMap { bytes }.toByteArray()
}

/** Broadcast addresses to try: the whole network, and each known address's own /24. */
fun wakeTargets(addrs: List<String>): List<String> {
    val local = addrs.mapNotNull { a ->
        val p = a.split('.')
        if (p.size == 4 && p.all { it.toIntOrNull() in 0..255 }) "${p[0]}.${p[1]}.${p[2]}.255" else null
    }
    return (listOf("255.255.255.255") + local).distinct()
}

/** Sends the packet for every known card to the usual Wake-on-LAN ports; true if anything was sent. */
suspend fun wake(macs: List<String>, addrs: List<String>): Boolean = withContext(Dispatchers.IO) {
    var sent = false
    runCatching {
        DatagramSocket().use { s ->
            s.broadcast = true
            for (mac in macs) {
                val data = magicPacket(mac) ?: continue
                for (target in wakeTargets(addrs)) for (port in intArrayOf(9, 7)) {
                    runCatching {
                        s.send(DatagramPacket(data, data.size, InetAddress.getByName(target), port))
                        sent = true
                    }
                }
            }
        }
    }
    sent
}
