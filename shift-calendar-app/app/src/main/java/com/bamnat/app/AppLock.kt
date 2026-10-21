package com.bamnat.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [LOCK-01] 앱 잠금: with it on, the app opens behind a cover until the phone's own lock (fingerprint,
 * face, PIN or pattern) is passed, on a cold start and after [AWAY_MILLIS] away. Alarms, the widget
 * and notices are not covered: an alarm must ring whoever holds the phone.
 */
object AppLock {
    /** Away this long (a call, a look at a message is shorter) and the app locks again. */
    const val AWAY_MILLIS = 60_000L

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var stoppedAt = 0L
    private var started = false

    /** The phone has a lock of its own; without one there is nothing to ask, so the switch is not offered. */
    fun available(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun onStart(context: Context, on: Boolean, awaitingResult: Boolean) {
        val away = stoppedAt > 0 && SystemClock.elapsedRealtime() - stoppedAt >= AWAY_MILLIS
        if (on && available(context) && (!started || away && !awaitingResult)) _locked.value = true
        if (!on) _locked.value = false
        started = true
        stoppedAt = 0L
    }

    fun onStop(changingConfigurations: Boolean) {
        if (!changingConfigurations) stoppedAt = SystemClock.elapsedRealtime()
    }

    /** Turned off in settings: no cover from now on. */
    fun release() {
        _locked.value = false
    }

    /**
     * Asks for the phone's lock. Android 11 and later show the fingerprint or face with the PIN as the
     * way around; before that the phone's own confirm screen, through [confirm] and [onConfirmed].
     */
    fun ask(activity: Activity, confirm: (Intent) -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            val prompt = android.hardware.biometrics.BiometricPrompt.Builder(activity)
                .setTitle("밤낮 잠금 풀기")
                .setSubtitle("휴대폰 잠금과 같은 방법으로 풀어요")
                .setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
            prompt.authenticate(CancellationSignal(), activity.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult?) {
                    _locked.value = false
                }
            })
        } else {
            val keyguard = activity.getSystemService(KeyguardManager::class.java)
            @Suppress("DEPRECATION")
            val intent = keyguard?.createConfirmDeviceCredentialIntent("밤낮 잠금 풀기", "휴대폰 잠금과 같은 방법으로 풀어요")
            if (intent == null) _locked.value = false else confirm(intent)
        }
    }

    fun onConfirmed(ok: Boolean) {
        if (ok) _locked.value = false
    }
}
