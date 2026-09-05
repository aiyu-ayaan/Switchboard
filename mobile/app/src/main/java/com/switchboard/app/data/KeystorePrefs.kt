package com.switchboard.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * String preferences encrypted with an AES-256-GCM key that lives in the
 * AndroidKeyStore and is never exported. Values are stored in an ordinary
 * SharedPreferences file as base64(iv ‖ ciphertext), so the file on disk is
 * useless without the hardware-backed key.
 *
 * Replaces the deprecated androidx.security:security-crypto. No user
 * authentication is required on the key: the app must reconnect to paired
 * desktops on launch without prompting.
 */
class KeystorePrefs(context: Context, name: String) {

    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val key: SecretKey by lazy { secretKey() }

    /** Returns null if the value is missing, corrupt, or no longer decryptable. */
    fun getString(name: String): String? {
        val blob = prefs.getString(name, null) ?: return null
        return runCatching {
            val (iv, ct) = unpack(blob)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrElse {
            // Unreadable record: drop it. A lost pairing is re-scannable, a
            // crash loop on launch is not.
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun putString(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        prefs.edit().putString(name, pack(cipher.iv, cipher.doFinal(value.toByteArray()))).apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { existing ->
            // A key invalidated by a lock-screen change can neither encrypt nor
            // decrypt; replace it and abandon the values it sealed.
            if (runCatching { Cipher.getInstance(TRANSFORM).init(Cipher.ENCRYPT_MODE, existing) }.isSuccess) {
                return existing
            }
            ks.deleteEntry(ALIAS)
            prefs.edit().clear().apply()
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        private const val ALIAS = "switchboard-prefs-key"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12

        /** iv ‖ ciphertext, base64. Pure; the Keystore itself needs a device. */
        fun pack(iv: ByteArray, ciphertext: ByteArray): String {
            require(iv.size == IV_BYTES) { "GCM iv must be $IV_BYTES bytes, was ${iv.size}" }
            return Base64.getEncoder().encodeToString(iv + ciphertext)
        }

        /** Splits a packed blob; throws on anything too short to hold iv + GCM tag. */
        fun unpack(blob: String): Pair<ByteArray, ByteArray> {
            val raw = Base64.getDecoder().decode(blob)
            require(raw.size > IV_BYTES + TAG_BITS / 8) { "truncated blob: ${raw.size} bytes" }
            return raw.copyOfRange(0, IV_BYTES) to raw.copyOfRange(IV_BYTES, raw.size)
        }
    }
}
