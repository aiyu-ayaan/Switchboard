package com.switchboard.app.data

import android.content.Context
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
 * Backed by [KeystorePrefs]: the private key is the sole credential for
 * reconnecting to a paired host, so every value is sealed under an
 * AndroidKeyStore AES-256-GCM key rather than stored in plain preferences.
 */
class HostStore(context: Context) {

    private val prefs = KeystorePrefs(context, "switchboard-keystore")

    private val json = Json { ignoreUnknownKeys = true }

    init {
        migrateLegacyPrefs(context, prefs)
    }

    /**
     * This device's long-lived keypair, created once on first launch. Its public
     * half is what the desktop stores as the paired device.
     */
    val identity: SessionCrypto.KeyPair by lazy {
        val stored = prefs.getString(KEY_IDENTITY)
        if (stored != null) {
            SessionCrypto.KeyPair.fromSeed(SessionCrypto.decode(stored))
        } else {
            SessionCrypto.KeyPair.generate().also {
                prefs.putString(KEY_IDENTITY, SessionCrypto.encode(it.seed))
            }
        }
    }

    fun hosts(): List<KnownHost> {
        val raw = prefs.getString(KEY_HOSTS) ?: return emptyList()
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
        prefs.putString(KEY_HOSTS, json.encodeToString(ListSerializer(KnownHost.serializer()), hosts))
    }

    var lastHostId: String?
        get() = prefs.getString(KEY_LAST_HOST)
        set(value) {
            prefs.putString(KEY_LAST_HOST, value)
        }

    /**
     * Whether the session should outlive the UI: keep the socket up, and keep
     * redialling the last desktop, even with the task swiped away.
     *
     * Stored here rather than in a preferences file because it is read the
     * instant the process starts -- a foreground service the system restarts
     * has to know whether it is meant to be running before anything else is
     * built -- and this store is already the one thing the connection loads
     * first.
     */
    var alwaysOn: Boolean
        get() = prefs.getString(KEY_ALWAYS_ON) == "1"
        set(value) {
            prefs.putString(KEY_ALWAYS_ON, if (value) "1" else "0")
        }

    /**
     * Whether the background connection service should automatically start
     * up on device reboot if alwaysOn is enabled.
     */
    var startOnBoot: Boolean
        get() = prefs.getString(KEY_START_ON_BOOT) != "0"
        set(value) {
            prefs.putString(KEY_START_ON_BOOT, if (value) "1" else "0")
        }

    private companion object {
        const val KEY_IDENTITY = "identity-seed"
        const val KEY_HOSTS = "known-hosts"
        const val KEY_ALWAYS_ON = "always-on"
        const val KEY_START_ON_BOOT = "start-on-boot"
        const val KEY_LAST_HOST = "last-host"
        const val LEGACY_PREFS = "switchboard-secure"

        /**
         * Moves records out of the deprecated EncryptedSharedPreferences file
         * once, then deletes it. Losing them would silently unpair every
         * desktop the user has, so this runs before the store is read.
         *
         * No-op on a fresh install and on every launch after the first. If the
         * old file cannot be opened -- the failure mode that got the library
         * deprecated -- it is dropped and we continue with an empty store
         * rather than crashing on launch.
         */
        fun migrateLegacyPrefs(context: Context, target: KeystorePrefs) {
            val file = java.io.File(context.applicationInfo.dataDir, "shared_prefs/$LEGACY_PREFS.xml")
            if (!file.exists()) return
            runCatching {
                val legacy = EncryptedSharedPreferences.create(
                    context,
                    LEGACY_PREFS,
                    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                for (key in listOf(KEY_IDENTITY, KEY_HOSTS, KEY_LAST_HOST)) {
                    legacy.getString(key, null)?.let { target.putString(key, it) }
                }
                legacy.edit().clear().commit()
            }
            file.delete()
        }
    }
}
