package com.pranav.securevault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {

    private var locked by mutableStateOf(true)
    private var everUnlocked by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var authInProgress = false
    private var stoppedAt = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            locked = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Blocks screenshots, screen recording and the preview in the recent-apps list.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

        val repo = VaultRepository(applicationContext)

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Box(Modifier.fillMaxSize()) {
                    // The vault is only built after the first successful unlock.
                    // After that it stays underneath the lock screen so your work is not lost.
                    if (everUnlocked) {
                        VaultApp(repo = repo, locked = locked, onLockNow = { locked = true })
                    }
                    if (locked) {
                        LockScreen(message = message, onUnlock = { promptUnlock() })
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!locked && stoppedAt != 0L &&
            SystemClock.elapsedRealtime() - stoppedAt > AUTO_LOCK_MS
        ) {
            locked = true
        }
        if (locked) promptUnlock()
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = SystemClock.elapsedRealtime()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenOffReceiver)
    }

    private fun promptUnlock() {
        if (authInProgress) return

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (BiometricManager.from(this).canAuthenticate(authenticators)
            != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            message = "Set a screen lock (PIN, pattern or fingerprint) in Settings to use SecureVault."
            return
        }

        authInProgress = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authInProgress = false
                    message = ""
                    everUnlocked = true
                    locked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authInProgress = false
                    message = errString.toString()
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock SecureVault")
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    companion object {
        /** Re-lock if the app was in the background longer than this. */
        private const val AUTO_LOCK_MS = 60_000L
    }
}
