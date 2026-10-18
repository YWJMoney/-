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
import com.bamnat.app.data.ThemeMode
import com.bamnat.app.share.ShareLinkId
import com.bamnat.app.ui.AppRoot
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.core.diag.DiagEvents

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        HomeRequests.onCreate(
            restoring = savedInstanceState != null,
            awaitingResult = savedInstanceState?.getBoolean(KEY_AWAITING) == true,
            leftAt = savedInstanceState?.getLong(KEY_LEFT_AT) ?: 0L,
        )
        if (savedInstanceState == null) {
            appContainer.telemetry.event(DiagEvents.APP_OPEN)
            openLink(intent)
            NoticeLinks.read(intent)
        }
        enableEdgeToEdge()
        val container = appContainer
        setContent {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status bar icons follow the app theme, not only the system one.
            DisposableEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
                onDispose { }
            }
            BamnatTheme(themeMode = settings.themeMode, dynamicColor = settings.dynamicColor) {
                androidx.compose.runtime.CompositionLocalProvider(com.bamnat.app.ui.calendar.LocalExtraKinds provides settings.extraKinds) {
                    AppRoot(windowSizeClass = calculateWindowSizeClass(this), container = container)
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
        HomeRequests.onStart()
    }

    override fun onStop() {
        super.onStop()
        HomeRequests.onStop(isChangingConfigurations)
    }

    private companion object {
        const val KEY_AWAITING = "awaiting_result"
        const val KEY_LEFT_AT = "left_at"
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
