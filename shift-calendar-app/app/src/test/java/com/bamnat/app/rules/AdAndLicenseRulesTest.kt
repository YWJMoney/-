package com.bamnat.app.rules

import com.bamnat.app.ui.settings.OPEN_SOURCE_NOTICES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [REL-02] The ad rules held by a test: ads only ever go in the one banner slot ([AdSlot] between the
 * content and the bottom bar), never at launch, full screen, as video or reward, or on the alarm.
 * [OPS-23] Every library in the build has an entry in 오픈소스 라이선스.
 */
class AdAndLicenseRulesTest {
    // Gradle runs unit tests in the module directory.
    private val main = File("src/main")
    private val sources by lazy { main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList() }

    @Test fun noAdFormatOtherThanTheBanner() {
        val banned = Regex("""\b(InterstitialAd|RewardedAd|RewardedInterstitialAd|AppOpenAd|NativeAd|AdManagerInterstitialAd)\b""")
        val hits = sources.filter { banned.containsMatchIn(it.readText()) }.map { it.path }
        assertEquals("전면·보상형·앱 열기·네이티브 광고는 쓰지 않아요", emptyList<String>(), hits)
    }

    @Test fun theBannerSlotIsUsedOnlyInTheAppFrame() {
        val users = sources.filter { it.name != "AdSlot.kt" && Regex("""\bAdSlot\(""").containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(listOf("AppRoot.kt"), users)
        val alarmScreens = File(main, "java/com/bamnat/app/alarm").walkTopDown().filter { it.isFile }
        assertTrue("알람 화면에는 광고가 없어요", alarmScreens.none { it.readText().contains("AdSlot") || it.readText().contains("com.google.android.gms.ads") })
    }

    @Test fun adsAreStillOffUntilBillingAndConsentExist() {
        val slot = File(main, "java/com/bamnat/app/ui/components/AdSlot.kt").readText()
        assertTrue(slot.contains("const val ENABLED = false"))
        val build = File("build.gradle.kts").readText()
        assertTrue("광고 SDK는 결제·동의 화면과 함께 넣어요(M17)", !build.contains("play-services-ads"))
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
        )
        for (g in groups) {
            val name = covered[g] ?: error("오픈소스 라이선스 목록에 '$g'을(를) 더해 주세요 (OpenSourceLicenses.kt)")
            assertTrue("$g → $name", OPEN_SOURCE_NOTICES.any { it.name.startsWith(name) })
        }
    }
}
