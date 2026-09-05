package com.switchboard.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The phone's half of remote unlock.
 *
 * The desktop cannot see a fingerprint and must not have to take the app's
 * word that one was checked — a tampered build would simply lie. So the claim
 * is made with a key instead of a boolean: this is an ECDSA P-256 key pair
 * generated inside the hardware keystore with `setUserAuthenticationRequired`,
 * which means the private half is unusable until a biometric prompt succeeds.
 * Signing a nonce the desktop issued is therefore something only a real,
 * present finger can produce, and no amount of patching the APK changes that.
 *
 * P-256 is chosen because every Android keystore can hold it in hardware and
 * Go verifies it from the standard library, so both ends agree with no
 * dependency; see backend/internal/server/unlock.go.
 */
object UnlockKey {

    private const val ALIAS = "switchboard-unlock"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /**
     * Whether this device can take part at all.
     *
     * Both halves matter. [PackageManager.FEATURE_FINGERPRINT] is what makes
     * this a fingerprint feature rather than any strong biometric, and
     * [BiometricManager.Authenticators.BIOMETRIC_STRONG] is what makes the
     * keystore willing to gate a key on it — a device whose sensor is only
     * class 2 cannot back one. A phone missing either simply never sees the
     * unlock control.
     */
    fun isSupported(context: Context): Boolean {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return false
        return BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /** True once a key exists, which is the local trace of having enrolled. */
    fun isEnrolled(): Boolean = keystore().containsAlias(ALIAS)

    /**
     * Creates the key pair and returns its public half as base64 PKIX DER,
     * ready for `system.unlock.enroll`. Any earlier key is discarded, so
     * re-enrolling recovers a device whose key was invalidated.
     */
    fun enroll(): String {
        forget()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            // Enrolling a new finger must kill the key. Without this, someone
            // who could add their own fingerprint to a borrowed phone would
            // inherit the right to open the desktop.
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                // From API 30 the authenticator class is stated outright; a
                // validity of 0 keeps it per-signature, so every unlock costs
                // its own prompt rather than riding a recent one.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        generator.initialize(spec)
        val pair = generator.generateKeyPair()
        return android.util.Base64.encodeToString(
            pair.public.encoded,
            android.util.Base64.NO_WRAP
        )
    }

    /** Drops the key, which is the phone-side half of forgetting a desktop. */
    fun forget() {
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    /**
     * Signs [message] behind a biometric prompt, returning base64 DER or null
     * if the user cancelled or failed.
     *
     * The prompt carries the [Signature] as its CryptoObject rather than being
     * a gate the caller checks afterwards: the keystore itself only unlocks
     * that exact object, so a success here is inseparable from a real
     * authentication.
     */
    suspend fun sign(activity: FragmentActivity, message: ByteArray): Result<String> {
        val signature = try {
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initSign(loadPrivateKey() ?: return Result.failure(IllegalStateException("not enrolled")))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // The fingerprint set changed. The key is gone for good, so say so
            // plainly instead of failing at the prompt.
            forget()
            return Result.failure(e)
        }

        return suspendCoroutine { continuation ->
            val prompt = BiometricPrompt(
                activity,
                androidx.core.content.ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        continuation.resume(
                            runCatching {
                                val signer = requireNotNull(result.cryptoObject?.signature)
                                signer.update(message)
                                android.util.Base64.encodeToString(
                                    signer.sign(),
                                    android.util.Base64.NO_WRAP
                                )
                            }
                        )
                    }

                    override fun onAuthenticationError(code: Int, message: CharSequence) {
                        continuation.resume(Result.failure(UnlockCancelled(message.toString())))
                    }

                    // onAuthenticationFailed is a rejected finger, not the end
                    // of the attempt: the prompt stays up for another try.
                }
            )
            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock desktop")
                    .setSubtitle("Confirm with your fingerprint")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build(),
                BiometricPrompt.CryptoObject(signature)
            )
        }
    }

    private fun loadPrivateKey() = keystore().getKey(ALIAS, null) as? java.security.PrivateKey

    private fun keystore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
}

/** The user dismissed or failed the prompt; not an error worth a crash. */
class UnlockCancelled(message: String) : Exception(message)
