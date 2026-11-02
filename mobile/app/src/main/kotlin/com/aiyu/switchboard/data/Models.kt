package com.aiyu.switchboard.data

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/** A desktop this phone trusts: its pinned key and where it was last seen. */
@Serializable
data class SavedHost(
    val id: String,
    val name: String,
    /** Base64url X25519 public key, pinned at pairing. */
    val pk: String,
    val addrs: List<String>,
    val port: Int,
    val os: String = "",
    val pairedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Identity(val deviceId: String, val secretKey: String)

@Serializable
data class VaultData(
    val identity: Identity,
    val hosts: List<SavedHost> = emptyList(),
    val selected: String? = null,
    val dynamicColor: Boolean = true,
    val theme: String = "system",
    /** Tree URI of the folder received files go to; null means Downloads/Switchboard. */
    val saveFolder: String? = null,
)

object B64 {
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()
    fun encode(b: ByteArray): String = enc.encodeToString(b)
    fun decode(s: String): ByteArray? = runCatching { dec.decode(s.trim()) }.getOrNull()
}

/** The short key fingerprint the desktop shows too, e.g. `D009 71DB 4803`. */
fun fingerprint(pkB64: String): String {
    val pk = B64.decode(pkB64) ?: return "—"
    val h = MessageDigest.getInstance("SHA-256").digest(pk)
    return (0 until 6).joinToString("") { i -> (if (i > 0 && i % 2 == 0) " " else "") + "%02X".format(h[i]) }
}
