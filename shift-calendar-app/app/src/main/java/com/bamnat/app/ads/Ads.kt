package com.bamnat.app.ads

import android.app.Activity
import android.content.Context
import com.bamnat.app.BuildConfig
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [MON-07] AdMob ad unit ids from gradle.properties (bamnat.admobBanner, bamnat.admobRewarded; public
 * values the owner gives). Blank in a release build means no ads at all; a debug build uses Google's
 * own test units so the slot can be seen without real ads.
 */
object AdIds {
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"
    private const val TEST_REWARDED = "ca-app-pub-3940256099942544/5224354917"

    val banner: String = BuildConfig.ADMOB_BANNER.ifBlank { if (BuildConfig.DEBUG) TEST_BANNER else "" }
    val rewarded: String = BuildConfig.ADMOB_REWARDED.ifBlank { if (BuildConfig.DEBUG) TEST_REWARDED else "" }
    val configured: Boolean get() = banner.isNotBlank() || rewarded.isNotBlank()
}

/**
 * [MON-07] Google's consent (UMP) and the ads SDK, started only when an ad may show at all: after the
 * 정식 출시 switch, for a phone without 프로, in a build with ad ids. Where the law asks (EEA, UK and
 * the like) Google's own form asks first; 설정 › 개인정보와 권한 › 광고 개인정보 선택 opens it again.
 */
class Ads(private val scope: CoroutineScope) {
    private val _ready = MutableStateFlow(false)
    /** Consent is settled and the SDK is up: ads may be asked for. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    private val _privacyOptions = MutableStateFlow(false)
    /** Google says this phone's region needs a way back to the consent form. */
    val privacyOptions: StateFlow<Boolean> = _privacyOptions.asStateFlow()
    @Volatile private var asked = false
    @Volatile private var started = false

    /** Once per run, from the activity coming to the front. */
    fun start(activity: Activity) {
        if (asked || !AdIds.configured) return
        asked = true
        val info = UserMessagingPlatform.getConsentInformation(activity)
        val params = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build()
        info.requestConsentInfoUpdate(activity, params, {
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { settled(activity, info) }
        }, { settled(activity, info) })
        // A choice made on an earlier run counts at once.
        if (info.canRequestAds()) startSdk(activity.applicationContext)
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            val info = UserMessagingPlatform.getConsentInformation(activity)
            if (info.canRequestAds()) startSdk(activity.applicationContext) else _ready.value = false
        }
    }

    private fun settled(context: Context, info: ConsentInformation) {
        _privacyOptions.value = info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (info.canRequestAds()) startSdk(context.applicationContext)
    }

    private fun startSdk(context: Context) {
        if (started) return
        started = true
        // Google asks for this off the main thread.
        scope.launch { MobileAds.initialize(context) { _ready.value = true } }
    }
}
