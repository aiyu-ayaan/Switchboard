package com.aiyu.switchboard.net

/**
 * Splits an address the way a person writes it: `192.168.1.5`, `pc.tailnet.ts.net:9500`,
 * `[fe80::1]:9500` or a bare IPv6 address. The port falls back to [defaultPort].
 */
fun splitAddr(addr: String, defaultPort: Int): Pair<String, Int> {
    val a = addr.trim()
    if (a.startsWith("[")) {
        val end = a.indexOf(']')
        if (end > 0) return a.substring(1, end) to (a.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort)
    }
    // Exactly one colon is host:port; more than one is a bare IPv6 address.
    if (a.count { it == ':' } == 1) {
        val port = a.substringAfter(':').toIntOrNull()
        if (port != null) return a.substringBefore(':') to port
    }
    return a to defaultPort
}

/** What a person may type as an extra address: a name or IP, optionally with a port. */
fun validRemoteAddr(addr: String): Boolean {
    val (host, port) = splitAddr(addr, 1)
    return host.isNotBlank() && host.length <= 253 && port in 1..65535 && host.all { it.isLetterOrDigit() || it in ".-:_" }
}
