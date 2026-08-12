package com.ngoline.easygpg.ui

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.ngoline.easygpg.AuthenticationRequiredException
import com.ngoline.easygpg.R
import com.ngoline.easygpg.SecretKeyStoreLostException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Biometric / device credential authentication for the Keystore key that protects the secret key
 * rings. Authenticating is what makes that key usable again, so the prompt is not decoration: an
 * operation that needs the key fails with [AuthenticationRequiredException] until it succeeds.
 */
object DeviceAuth {

    /** Shows the prompt and returns true if the user authenticated. */
    suspend fun authenticate(fragment: Fragment): Boolean {
        if (!fragment.isAdded) return false
        val context = fragment.requireContext()
        return prompt(context) { callback ->
            BiometricPrompt(fragment, ContextCompat.getMainExecutor(context), callback)
        }
    }

    /** Shows the prompt and returns true if the user authenticated. */
    suspend fun authenticate(activity: FragmentActivity): Boolean =
        prompt(activity) { callback ->
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        }

    /**
     * Runs [block], and if the Keystore demands a fresh authentication, prompts and runs it once
     * more. Returns null when the user did not authenticate.
     */
    suspend fun <T> withAuthentication(fragment: Fragment, block: suspend () -> T): T? {
        try {
            return block()
        } catch (e: AuthenticationRequiredException) {
            if (!authenticate(fragment)) {
                return null
            }
        }
        return try {
            block()
        } catch (e: AuthenticationRequiredException) {
            null
        }
    }

    /**
     * Runs [block] behind [withAuthentication] and reports to [onLocked] why the secret key rings
     * stayed out of reach, returning null in that case. This is the whole policy for reaching secret
     * key material from the UI; callers only decide how to show the message.
     */
    suspend fun <T> withKeyAccess(
        fragment: Fragment,
        onLocked: (String) -> Unit,
        block: suspend () -> T,
    ): T? {
        val result = try {
            withAuthentication(fragment, block)
        } catch (e: SecretKeyStoreLostException) {
            onLocked(fragment.getString(R.string.keys_unreadable))
            return null
        }
        if (result == null) {
            onLocked(fragment.getString(R.string.authentication_required))
        }
        return result
    }

    private suspend fun prompt(
        context: Context,
        createPrompt: (BiometricPrompt.AuthenticationCallback) -> BiometricPrompt,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val prompt = createPrompt(object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(true)
            }

            // A single wrong attempt leaves the prompt up, so only errors end the wait.
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(false)
            }
        })
        prompt.authenticate(promptInfo(context))
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    private fun promptInfo(context: Context): BiometricPrompt.PromptInfo =
        BiometricPrompt.PromptInfo.Builder()
            .setTitle(context.getString(R.string.unlock_keys_title))
            .setSubtitle(context.getString(R.string.unlock_keys_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
}
