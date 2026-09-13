package com.switchboard.app.auth

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Manages device authentication using Android's BiometricPrompt with
 * fallback to device credentials (PIN, pattern, or password).
 */
class DeviceAuthManager(private val activity: FragmentActivity) {

    private val keyguardManager: KeyguardManager? =
        activity.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

    /**
     * Whether the device has a secure lock screen (PIN, pattern, password, or biometric).
     */
    val isDeviceSecure: Boolean
        get() = keyguardManager?.isDeviceSecure == true

    /**
     * Whether the device currently has biometric hardware and enrolled biometrics (e.g. fingerprint).
     */
    val hasBiometricsEnrolled: Boolean
        get() {
            val bm = BiometricManager.from(activity)
            return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
        }

    /**
     * Prompts the user to authenticate using biometric (fingerprint/face) if enrolled,
     * or the device's system PIN, pattern, or password.
     */
    fun authenticate(
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {},
        onError: (errorCode: Int, errString: String) -> Unit = { _, _ -> }
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED -> onCancel()
                        else -> onError(errorCode, errString.toString())
                    }
                }
            }
        )

        // Allow biometric with fallback to device credentials (PIN/pattern/password).
        // Note: Do NOT set negative button text when DEVICE_CREDENTIAL is used.
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        prompt.authenticate(promptInfo)
    }
}
