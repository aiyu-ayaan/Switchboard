package com.aiyu.switchboard.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.aiyu.switchboard.core.NativeCore
import com.aiyu.switchboard.net.WireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Identity key and paired computers, AES-GCM encrypted under a key that never
 * leaves the Android Keystore. Backups are off in the manifest, so the file
 * and its key stay on this device.
 */
class Vault(context: Context) {
    private val file = File(context.filesDir, "vault.bin")
    private val _data = MutableStateFlow(load())
    val data: StateFlow<VaultData> = _data.asStateFlow()

    fun update(f: (VaultData) -> VaultData) {
        _data.update(f)
        save(_data.value)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun load(): VaultData {
        val existing = runCatching {
            val bytes = file.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
            WireJson.decodeFromString<VaultData>(c.doFinal(bytes, 12, bytes.size - 12).decodeToString())
        }.getOrNull()
        return existing ?: fresh().also(::save)
    }

    private fun fresh(): VaultData {
        val kp = NativeCore.keypair()
        return VaultData(Identity(UUID.randomUUID().toString(), B64.encode(kp.copyOfRange(0, 32))))
    }

    private fun save(d: VaultData) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(WireJson.encodeToString(VaultData.serializer(), d).encodeToByteArray())
        val tmp = File(file.parentFile, "vault.tmp")
        tmp.writeBytes(c.iv + ct)
        tmp.renameTo(file)
    }

    private companion object {
        const val ALIAS = "switchboard.vault"
    }
}
