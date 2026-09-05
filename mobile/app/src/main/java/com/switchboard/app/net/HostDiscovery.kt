package com.switchboard.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.switchboard.app.SwitchboardConnection
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A Switchboard desktop found on the local network.
 *
 * Discovery carries no authority: this says a daemon claiming [daemonId]
 * answers at [host], not that it is the daemon it claims to be. Pairing still
 * proves the code and pins the key seen during the handshake, so a spoofed
 * record produces a failed handshake rather than a connection.
 */
data class DiscoveredHost(
    val daemonId: String,
    val hostName: String,
    val host: String,
    val port: Int
) {
    /** What the address field is filled with when a user taps this entry. */
    val address: String
        get() = if (port == SwitchboardConnection.DEFAULT_PORT) host else "$host:$port"
}

/**
 * Browses the LAN for `_switchboard._tcp` through Android's NSD service.
 *
 * Resolution is single-flight below API 34 — a second `resolveService` while
 * one is outstanding fails with "listener already in use" — so every resolve
 * goes through one mutex rather than branching on the API level.
 */
class HostDiscovery(context: Context) {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private val resolveLock = Mutex()

    /**
     * Emits the hosts currently visible, updated as records appear and expire.
     *
     * Browsing runs only while the flow is collected, so a screen that is not
     * asking costs no multicast traffic and no wakeups.
     */
    fun hosts(): Flow<List<DiscoveredHost>> = callbackFlow {
        // Touched from NSD's callback thread and from resolve coroutines.
        val found = linkedMapOf<String, DiscoveredHost>()

        fun publish() {
            trySend(synchronized(found) { found.values.toList() })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // A network that will not carry mDNS costs discovery, not
                // pairing: the manual address field is still there.
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(service: NsdServiceInfo) {
                launch {
                    val resolved = resolveLock.withLock { resolve(service) } ?: return@launch
                    synchronized(found) { found[resolved.daemonId] = resolved }
                    publish()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                // Only the instance name survives a loss notification, so a
                // record is dropped by the label it was found under.
                val removed = synchronized(found) {
                    found.entries.removeAll { it.value.hostName == service.serviceName }
                }
                if (removed) publish()
            }
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        publish()

        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
    }

    /** One resolve, suspended until NSD answers or gives up. */
    private suspend fun resolve(service: NsdServiceInfo): DiscoveredHost? =
        suspendCancellableCoroutine { continuation ->
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(toHost(info)))
                }
            })
        }

    private fun toHost(info: NsdServiceInfo): DiscoveredHost? {
        val address = addressOf(info) ?: return null
        val attributes = info.attributes.orEmpty()
        // A record without a daemon id cannot be matched to a stored host, and
        // is not something this app knows how to talk to.
        val daemonId = attributes.text(TXT_DAEMON_ID)?.takeIf { it.isNotBlank() } ?: return null
        val name = attributes.text(TXT_HOST_NAME)?.takeIf { it.isNotBlank() } ?: info.serviceName
        return DiscoveredHost(daemonId = daemonId, hostName = name, host = address, port = info.port)
    }

    /**
     * IPv4 is preferred: the endpoint is built as a `ws://host:port` URL, where
     * a bare IPv6 literal would need bracketing.
     */
    private fun addressOf(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                info.hostAddresses
            } else {
                @Suppress("DEPRECATION")
                listOfNotNull(info.host)
            }
        return (addresses.firstOrNull { it is Inet4Address } ?: addresses.firstOrNull())?.hostAddress
    }

    private fun Map<String, ByteArray?>.text(key: String): String? =
        this[key]?.toString(Charsets.UTF_8)

    companion object {
        /** Matches `discovery.Service` on the daemon. */
        const val SERVICE_TYPE = "_switchboard._tcp"

        private const val TXT_DAEMON_ID = "id"
        private const val TXT_HOST_NAME = "name"
    }
}
