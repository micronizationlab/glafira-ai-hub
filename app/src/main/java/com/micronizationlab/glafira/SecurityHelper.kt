package com.micronizationlab.glafira

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

fun isBiometricEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("biometric_enabled", false)
}

fun setBiometricEnabled(context: Context, enabled: Boolean) {
    val prefs = context.getSharedPreferences("glafira_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("biometric_enabled", enabled).apply()
}

fun canUseBiometric(context: Context): Boolean {
    val manager = BiometricManager.from(context)
    val result = manager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    return result == BiometricManager.BIOMETRIC_SUCCESS
}

fun showBiometricPrompt(
    activity: FragmentActivity,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) = showBiometricPromptWithResult(activity, { onSuccess() }, onError)

/**
 * Delivers the platform authentication result to a caller that must mint a one-use Core
 * owner session. Callers must not persist or serialize the result/session.
 */
fun showBiometricPromptWithResult(
    activity: FragmentActivity,
    onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
    onError: (String) -> Unit
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess(result)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onError(errString.toString())
            }
        }
    )

    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("GLAFIRA AI HUB")
        .setSubtitle("Подтвердите действие владельца")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
        .build()

    prompt.authenticate(info)
}
