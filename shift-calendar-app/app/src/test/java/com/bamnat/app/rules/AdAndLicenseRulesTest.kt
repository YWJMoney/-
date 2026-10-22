package com.bamnat.app.rules

import com.bamnat.app.ui.settings.OPEN_SOURCE_NOTICES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [REL-02][MON-07] The ad rules held by a test: a banner only in the one slot ([AdSlot] between the
 * content and the bottom bar), a rewarded ad only when the user asks for a day of 프로, never at launch,
 * full screen on its own, as native ads or on the alarm. Both follow [com.bamnat.core.tier.AdRules].
 * [OPS-23] Every library in the build has an entry in 오픈소스 라이선스.
 */
class AdAndLicenseRulesTest {
    // Gradle runs unit tests in the module directory.
    private val main = File("src/main")
    private val sources by lazy { main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList() }
    private fun read(path: String) = File(main, "java/com/bamnat/app/$path").readText()

    @Test fun noAdFormatOtherThanTheBannerAndTheAskedForReward() {
        val banned = Regex("""\b(InterstitialAd|RewardedInterstitialAd|AppOpenAd|NativeAd|AdManagerInterstitialAd)\b""")
        val hits = sources.filter { banned.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals("전면·앱 열기·네이티브 광고는 쓰지 않아요", emptyList<String>(), hits)
        val rewarded = sources.filter { Regex("""\bRewardedAd\b""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals("보상형 광고는 ‘광고 보고 오늘 하루 열기’ 버튼에서만", listOf("RewardedAds.kt"), rewarded)
    }

    @Test fun theBannerSlotIsUsedOnlyInTheAppFrame() {
        val users = sources.filter { it.name != "AdSlot.kt" && Regex("""\bAdSlot\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("AppRoot.kt"), users)
        val alarmScreens = File(main, "java/com/bamnat/app/alarm").walkTopDown().filter { it.isFile }
        assertTrue("알람 화면에는 광고가 없어요", alarmScreens.none { it.readText().contains("AdSlot") || it.readText().contains("com.google.android.gms.ads") })
    }

    @Test fun theAdsSdkIsOnlyInItsOwnPlaces() {
        val users = sources.filter { it.extension == "kt" && it.readText().contains("com.google.android.gms.ads") }.map { it.name }.sorted()
        assertEquals(listOf("AdSlot.kt", "Ads.kt", "RewardedAds.kt"), users)
    }

    @Test fun adsFollowTheRules() {
        assertTrue("배너는 AdRules.banner를 따라요", read("ui/components/AdSlot.kt").contains("AdRules.banner("))
        assertTrue("보상형은 AdRules.rewardedOffer를 따라요", read("ads/RewardedAds.kt").contains("AdRules.rewardedOffer("))
        // Nothing before the 정식 출시 switch, and nothing for 프로: the SDK and consent start only then.
        val start = read("MainActivity.kt").lines().first { it.contains("ads.start(") }
        assertTrue(start, start.contains("billingLive") && start.contains("Feature.NO_ADS"))
        // The SDK's own measurement waits for MobileAds.initialize, so nothing goes before the switch.
        assertTrue(File(main, "AndroidManifest.xml").readText().contains("com.google.android.gms.ads.DELAY_APP_MEASUREMENT_INIT"))
        // Google's test units are for debug builds only; a release without ids has no ads at all.
        val ids = read("ads/Ads.kt")
        assertTrue(ids.contains("ifBlank { if (BuildConfig.DEBUG) TEST_BANNER else \"\" }"))
        assertTrue(ids.contains("ifBlank { if (BuildConfig.DEBUG) TEST_REWARDED else \"\" }"))
        // Such a release leaves out the advertising ID permissions the SDK brings (no 광고 ID declaration needed).
        val noAdId = File("src/noAdId/AndroidManifest.xml").readText()
        for (p in listOf("com.google.android.gms.permission.AD_ID", "android.permission.ACCESS_ADSERVICES_AD_ID")) {
            assertTrue(p, noAdId.contains("<uses-permission android:name=\"$p\" tools:node=\"remove\" />"))
        }
        assertTrue(File("build.gradle.kts").readText()
            .contains("if (!adUnitsGiven) variant.sources.manifests.addStaticManifestFile(\"src/noAdId/AndroidManifest.xml\")"))
    }

    @Test fun everyBundledLibraryIsInTheNotices() {
        val build = File("build.gradle.kts").readText() + File("../core/build.gradle.kts").readText()
        val groups = Regex("""implementation\("([a-z0-9.\-]+):""").findAll(build).map { it.groupValues[1] }
            .filter { it != "com.bamnat" }.toSet()
        val covered = mapOf(
            "androidx.compose.ui" to "Jetpack Compose", "androidx.compose.foundation" to "Jetpack Compose",
            "androidx.compose.material3" to "Jetpack Compose", "androidx.compose.material" to "Jetpack Compose",
            "androidx.compose" to "Jetpack Compose",
            "androidx.core" to "AndroidX Core", "androidx.activity" to "AndroidX Core", "androidx.lifecycle" to "AndroidX Core",
            "androidx.navigation" to "AndroidX Core", "androidx.glance" to "Jetpack Glance", "androidx.room" to "AndroidX Room",
            "androidx.health.connect" to "Health Connect", "org.jetbrains.kotlinx" to "Kotlin",
            "com.google.mlkit" to "ML Kit", "com.google.android.play" to "Google Play",
            "com.google.android.gms" to "Google Play 서비스",
            "com.android.billingclient" to "Google Play 결제 라이브러리",
            "com.google.android.ump" to "Google 사용자 메시지 플랫폼",
        )
        for (g in groups) {
            val name = covered[g] ?: error("오픈소스 라이선스 목록에 '$g'을(를) 더해 주세요 (OpenSourceLicenses.kt)")
            assertTrue("$g → $name", OPEN_SOURCE_NOTICES.any { it.name.startsWith(name) })
        }
    }
}
