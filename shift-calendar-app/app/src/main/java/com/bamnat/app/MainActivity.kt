package com.bamnat.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.bamnat.app.data.ThemeMode
import com.bamnat.app.share.ShareLinkId
import com.bamnat.app.ui.AppRoot
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.core.diag.DiagEvents

class MainActivity : ComponentActivity() {
    // [LOCK-01] The phone's own confirm screen before Android 11.
    private val confirmLock = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        AppLock.onConfirmed(it.resultCode == RESULT_OK)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        StartupTrace.mark(StartupTrace.Step.ACTIVITY)
        HomeRequests.onCreate(
            restoring = savedInstanceState != null,
            awaitingResult = savedInstanceState?.getBoolean(KEY_AWAITING) == true,
            leftAt = savedInstanceState?.getLong(KEY_LEFT_AT) ?: 0L,
        )
        if (savedInstanceState == null) {
            appContainer.telemetry.event(DiagEvents.APP_OPEN)
            openLink(intent)
            NoticeLinks.read(intent)
            IncomingFiles.read(intent) // [CAL-47]
        }
        enableEdgeToEdge()
        val container = appContainer
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            // [SHFT-05] 야간 근무 중 어둡게 turns into dark or the phone's theme as the night shift starts and ends.
            val themeMode = com.bamnat.app.ui.theme.rememberThemeMode(settings.themeMode, container)
            val dark = when (themeMode) {
                ThemeMode.SYSTEM, ThemeMode.NIGHT_SHIFT -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // [SRV-08] P9: with 앱 잠금 on, the recents screen and screenshots show no calendar or money.
            DisposableEffect(settings.appLock) {
                AppLock.secure(window, settings.appLock)
                onDispose { }
            }
            // Status bar icons follow the app theme, not only the system one.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            BamnatTheme(themeMode = themeMode, dynamicColor = settings.dynamicColor) {
                androidx.compose.runtime.CompositionLocalProvider(com.bamnat.app.ui.calendar.LocalExtraKinds provides settings.extraKinds) {
                    AppRoot(windowSizeClass = calculateWindowSizeClass(this), container = container)
                    // [LOCK-01] Over the app, so the screen the user was on is still there after.
                    val locked by AppLock.locked.collectAsStateWithLifecycle()
                    if (locked) com.bamnat.app.ui.LockCover { AppLock.ask(this, confirmLock::launch) }
                }
            }
        }
    }

    // Every ActivityResult launcher (file pickers, permission requests) goes through here.
    @Deprecated("Called by the ActivityResult API")
    override fun startActivityForResult(intent: android.content.Intent, requestCode: Int, options: Bundle?) {
        HomeRequests.awaitingResult = true
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openLink(intent)
        // [SLP-15] A tapped notice opens its own screen, so coming back is not sent to the calendar.
        if (NoticeLinks.read(intent)) HomeRequests.followingLink()
    }

    /** [SHR-02] A shared calendar link tapped in a message or on the shared page: the calendar offers it. */
    private fun openLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW || !appContainer.shareApi.enabled) return
        // Reopened from Recents, Android hands back the link the app was first opened with: it was offered then.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        ShareLinkId.find(intent.dataString)?.let(appContainer.received::open)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_AWAITING, HomeRequests.awaitingResult)
        // [NAV-01] When the app went to the background, so a return soon after keeps this screen.
        outState.putLong(KEY_LEFT_AT, HomeRequests.leftAt)
    }

    override fun onStart() {
        super.onStart()
        // [LOCK-01] Read before HomeRequests clears whether a screen the app opened is being returned from.
        AppLock.onStart(this, appContainer.settingsRepository.settings.value.appLock, HomeRequests.awaitingResult)
        HomeRequests.onStart()
        appContainer.verifyAlarms()
        // [TYPE-04] Shop days that passed with no sales become 임시휴무 when the app is opened.
        val c = appContainer
        // [PERF-03] 324-2 (검토 A6): at most once in [CATCH_UP_EVERY]; a day turns over far less often than the app
        // comes back. 알람 점검 and the 정식 출시 switch below keep running every time (they keep their own pace).
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastCatchUp == 0L || now - lastCatchUp >= CATCH_UP_EVERY) {
            lastCatchUp = now
            c.appScope.launch { runCatching { com.bamnat.app.ui.work.ShopKeeper.catchUp(c) } }
        }
        // [MON-02] The 정식 출시 switch, passes and Play's purchases. [MON-07] Google's consent form and
        // the ads SDK start only when an ad may show: after the switch, without 프로, with ad ids, and
        // [SRV-07] not in the first three days after install.
        c.syncPro()
        if (com.bamnat.core.tier.AdRules.sdkMayStart(c.remoteConfig.current.billingLive, c.featureGate.state.value.allows(com.bamnat.app.tier.Feature.NO_ADS),
                c.installedAt, java.time.Instant.now(), c.remoteConfig.current.adQuietDays)) c.ads.start(this)
    }

    override fun onStop() {
        super.onStop()
        HomeRequests.onStop(isChangingConfigurations)
        AppLock.onStop(isChangingConfigurations)
    }

    private companion object {
        const val CATCH_UP_EVERY = 30 * 60 * 1000L
        @Volatile var lastCatchUp = 0L
        const val KEY_AWAITING = "awaiting_result"
        const val KEY_LEFT_AT = "left_at"
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
