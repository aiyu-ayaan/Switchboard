package com.aiyu.switchboard.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.aiyu.switchboard.data.PairTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/** Desktops announcing `_switchboard._tcp` on the local network. */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null
    private val resolver = Executors.newSingleThreadExecutor()
    private val _found = MutableStateFlow<Map<String, PairTarget>>(emptyMap())
    val found: StateFlow<Map<String, PairTarget>> = _found.asStateFlow()
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        lock = wifi?.createMulticastLock("switchboard")?.apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = resolve(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                _found.update { it - info.serviceName }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        lock?.release()
        lock = null
    }

    // shortcut: resolveService is deprecated on API 34+ in favour of
    // registerServiceInfoCallback; it still works and covers API 26 too.
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo) = resolver.execute {
        val done = java.util.concurrent.CountDownLatch(1)
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, errorCode: Int) = done.countDown()
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val attr = i.attributes.mapValues { (_, v) -> v?.decodeToString().orEmpty() }
                    val id = attr["id"].orEmpty()
                    val pk = attr["pk"].orEmpty()
                    val addrs = (if (Build.VERSION.SDK_INT >= 34) i.hostAddresses.mapNotNull { it.hostAddress } else listOfNotNull(i.host?.hostAddress))
                        .filter(::urlSafeAddr)
                    if (id.isNotEmpty() && pk.isNotEmpty() && addrs.isNotEmpty()) {
                        val t = PairTarget(id, attr["name"].orEmpty().ifEmpty { i.serviceName }, pk, addrs.sortedBy { ':' in it }, i.port)
                        _found.update { it + (id to t) }
                    }
                    done.countDown()
                }
            },
        )
        // One resolve at a time: older Android fails concurrent resolves.
        done.await(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    private companion object {
        const val SERVICE = "_switchboard._tcp"
    }
}
