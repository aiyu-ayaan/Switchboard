package com.switchboard.app.net

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Sockets with Nagle's algorithm switched off.
 *
 * Java leaves `TCP_NODELAY` false and OkHttp does not change it, so the kernel
 * holds a small write back until the previous segment has been acknowledged. A
 * bulk transfer never notices. The touchpad does: a drag is a stream of tiny
 * `input.move` frames, each one small enough to be held, and the host's delayed
 * ACK means the wait can run to tens of milliseconds. The cursor then arrives
 * in clumps rather than following the finger — which is exactly what the pad
 * felt like, however fast the link underneath it was.
 *
 * Every frame this app sends is either latency-critical (input, camera) or big
 * enough that Nagle would not batch it anyway, so this applies to the whole
 * client rather than to one route.
 */
object NoDelaySocketFactory : SocketFactory() {

    private fun Socket.noDelay(): Socket = apply {
        // Not fatal if the platform refuses: the connection is still correct,
        // only less responsive.
        runCatching { tcpNoDelay = true }
    }

    override fun createSocket(): Socket = Socket().noDelay()

    override fun createSocket(host: String, port: Int): Socket =
        Socket(host, port).noDelay()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        Socket(host, port, localHost, localPort).noDelay()

    override fun createSocket(host: InetAddress, port: Int): Socket =
        Socket(host, port).noDelay()

    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int
    ): Socket = Socket(address, port, localAddress, localPort).noDelay()
}
