package com.dotrino.sdk.ui

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import com.dotrino.sdk.R

/**
 * «Is it you?», asked right before an action that weighs: approving a request, showing a
 * secret, changing who can do what. The phone's own prompt — fingerprint or face, or the
 * screen lock's PIN/pattern when there is no biometry — so an unlocked phone in someone
 * else's hand cannot do it.
 *
 * ONE piece for every native app (same role as `Presence.swift`). The framework prompt, no
 * androidx: the shared pieces of this library carry no AppCompat.
 *
 * WHAT THIS IS NOT: it does not bind the key. The key in the Keystore still signs without it;
 * this is a check in the app, before the call. It closes «the phone was left unlocked», not
 * «the app was tampered with».
 *
 * NO WAY AROUND IT. A phone with no screen lock cannot confirm anyone, so the answer is
 * [Result.Unavailable] and the action does not happen — it is never «nothing to ask, go on».
 */
object Presence {
    sealed interface Result {
        data object Confirmed : Result
        /** The person closed the prompt. Nothing to say on screen: they know. */
        data object Cancelled : Result
        /** This phone has no screen lock: there is nothing to confirm with. */
        data object Unavailable : Result
        /** The system refused (locked out after too many tries, sensor error…), in its own words. */
        data class Failed(val message: String) : Result
    }

    private const val ALLOWED = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /** Whether this phone can confirm at all (it has a screen lock). */
    fun available(activity: Activity): Boolean =
        activity.getSystemService(BiometricManager::class.java)?.canAuthenticate(ALLOWED) == BiometricManager.BIOMETRIC_SUCCESS

    /** Shows the prompt saying [what] is about to happen; [done] runs on the main thread, once. */
    fun confirm(activity: Activity, what: String, done: (Result) -> Unit) {
        if (!available(activity)) { done(Result.Unavailable); return }
        val prompt = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.dotrino_presence_title))
            .setSubtitle(what)
            .setAllowedAuthenticators(ALLOWED)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = done(Result.Confirmed)
            // A finger that did not match is not the end: the prompt stays up and asks again.
            override fun onAuthenticationFailed() {}
            override fun onAuthenticationError(code: Int, message: CharSequence) = done(when (code) {
                BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> Result.Cancelled
                BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL -> Result.Unavailable
                else -> Result.Failed(message.toString())
            })
        })
    }

    /** What to show for a result that did not confirm; null when there is nothing to say. */
    fun message(activity: Activity, r: Result): String? = when (r) {
        Result.Confirmed, Result.Cancelled -> null
        Result.Unavailable -> activity.getString(R.string.dotrino_presence_unavailable)
        is Result.Failed -> r.message
    }
}
