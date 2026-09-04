package com.switchboard.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.switchboard.app.crypto.SessionCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A desktop this phone has paired with. */
@Serializable
data class KnownHost(
    val daemonId: String,
    val hostName: String,
    val host: String,
    val port: Int,
    /** The host's identity public key, base64url. Authenticates reconnects. */
    val hostKey: String,
    val deviceId: String = "",
    val lastConnected: Long = 0
) {
    val endpoint: String get() = "ws://$host:$port/ws"
}

/**
 * Persists this device's identity key and the desktops it trusts.
 *
 * Backed by EncryptedSharedPreferences: the private key is the sole credential
 * for reconnecting to a paired host, so it is held under a Keystore-wrapped
 * master key rather than in plain preferences.
 */
class HostStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "switchboard-secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * This device's long-lived keypair, created once on first launch. Its public
     * half is what the desktop stores as the paired device.
     */
    val identity: SessionCrypto.KeyPair by lazy {
        val stored = prefs.getString(KEY_IDENTITY, null)
        if (stored != null) {
            SessionCrypto.KeyPair.fromSeed(SessionCrypto.decode(stored))
        } else {
            SessionCrypto.KeyPair.generate().also {
                prefs.edit().putString(KEY_IDENTITY, SessionCrypto.encode(it.seed)).apply()
            }
        }
    }

    fun hosts(): List<KnownHost> {
        val raw = prefs.getString(KEY_HOSTS, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(KnownHost.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    /** Adds or updates a host, keyed by daemon ID so re-pairing does not duplicate. */
    fun save(host: KnownHost) {
        val updated = hosts().filterNot { it.daemonId == host.daemonId } + host
        write(updated)
    }

    /** "Forget system": drops the host and every credential tied to it. */
    fun forget(daemonId: String) {
        write(hosts().filterNot { it.daemonId == daemonId })
    }

    private fun write(hosts: List<KnownHost>) {
        prefs.edit()
            .putString(KEY_HOSTS, json.encodeToString(ListSerializer(KnownHost.serializer()), hosts))
            .apply()
    }

    var lastHostId: String?
        get() = prefs.getString(KEY_LAST_HOST, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_HOST, value).apply()
        }

    private companion object {
        const val KEY_IDENTITY = "identity-seed"
        const val KEY_HOSTS = "known-hosts"
        const val KEY_LAST_HOST = "last-host"
    }
}
