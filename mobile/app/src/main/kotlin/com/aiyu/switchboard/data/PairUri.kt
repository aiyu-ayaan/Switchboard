package com.aiyu.switchboard.data

import com.aiyu.switchboard.net.PROTOCOL_VERSION
import java.net.URLDecoder

/** What a phone needs to reach and pair with a desktop. */
data class PairTarget(val id: String, val name: String, val pk: String, val addrs: List<String>, val port: Int)

sealed interface PairSecret {
    /** 32-byte token from the QR code. */
    class Token(val bytes: ByteArray) : PairSecret
    class Code(val code: String) : PairSecret
}

data class PairOffer(val target: PairTarget, val secret: PairSecret.Token)

/**
 * `switchboard://pair?v=2&id=…&n=…&k=…&t=…&a=ip1,ip2&p=9427`, the QR payload
 * built by backend/src/app.rs `pair_uri`. Null for anything else.
 */
fun parsePairUri(raw: String): PairOffer? {
    val s = raw.trim()
    if (!s.startsWith("switchboard://pair?")) return null
    val q = s.substringAfter('?').split('&').mapNotNull {
        val k = it.substringBefore('=', "")
        if (k.isEmpty()) null else k to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }.toMap()
    if (q["v"] != PROTOCOL_VERSION.toString()) return null
    val id = q["id"]?.takeIf { it.isNotBlank() } ?: return null
    val pk = q["k"]?.takeIf { B64.decode(it)?.size == 32 } ?: return null
    val token = q["t"]?.let(B64::decode)?.takeIf { it.size == 32 } ?: return null
    val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    val addrs = q["a"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
    if (addrs.isEmpty()) return null
    return PairOffer(PairTarget(id, q["n"]?.takeIf { it.isNotBlank() } ?: "Computer", pk, addrs, port), PairSecret.Token(token))
}

/** Typed codes use the desktop's alphabet; case and dashes do not matter. */
/** Characters in a typed code; the desktop shows them as three groups of four. */
const val CODE_LENGTH = 12

const val CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

fun normaliseCode(input: String): String =
    input.uppercase().filter { it in CODE_ALPHABET }.take(CODE_LENGTH)
