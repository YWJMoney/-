package com.bamnat.app.share

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BuildConfig
import com.bamnat.app.ui.calendar.SHARE_SERVER_NOTICE
import com.bamnat.app.ui.settings.REPORT_EMAIL
import com.bamnat.app.ui.settings.REPORT_NOTICE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** [MKT-09][OPS-13] 1.8.0 talks to the live link server, and the dialog and privacy policy say so. */
@RunWith(AndroidJUnit4::class)
class ShareServerTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test fun linksGoToTheLiveServerOverHttps() {
        assertTrue(BuildConfig.SHARE_BASE_URL, BuildConfig.SHARE_BASE_URL.matches(Regex("https://[a-z0-9-]+\\.[a-z0-9-]+\\.workers\\.dev")))
        assertTrue(ShareLinkClient().enabled)
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertTrue(requested.contains(Manifest.permission.INTERNET))
    }

    @Test fun theDialogSaysWhereTheScheduleGoesAndWhoCanSeeIt() {
        assertTrue(SHARE_SERVER_NOTICE.contains("Cloudflare"))
        assertTrue(SHARE_SERVER_NOTICE.contains("미국"))
        assertTrue(SHARE_SERVER_NOTICE.contains("누구나"))
        assertTrue(SHARE_SERVER_NOTICE.contains("이미지로"))
    }

    /** The published policy (repo root privacy/) must match an app that can go online. */
    @Test fun thePrivacyPolicyCoversTheLinkServer() {
        val policy = File("../../privacy/index.html")
        assumeTrue(policy.exists())
        val text = policy.readText()
        assertTrue(text.contains("처리방침 1.5"))
        assertTrue(text.contains("Cloudflare, Inc."))
        assertTrue(text.contains("<td>인터넷</td>"))
        assertTrue(text.contains("7일·30일·90일"))
        assertFalse(text.contains("인터넷 접속 권한 자체가 없습니다"))
        assertFalse(text.contains("인터넷 권한은 쓰지 않습니다"))
        // The day counts the policy states are the ones the app sends.
        assertTrue(text.contains("${ShareLinkPayload.DAYS}일"))
        assertEquals(listOf(7, 30, 90), ShareLinkPayload.TTL_DAYS)
    }

    /** [EXTRA-06] A company-rule report goes by the user's mail app to the policy's contact, and both say what goes. */
    @Test fun thePrivacyPolicyCoversCompanyReports() {
        val policy = File("../../privacy/index.html")
        assumeTrue(policy.exists())
        val text = policy.readText()
        assertTrue(text.contains("회사 방식 알려 주기"))
        assertTrue(text.contains("mailto:$REPORT_EMAIL"))
        assertTrue(text.contains("1년 안에 지웁니다"))
        assertTrue(REPORT_NOTICE.contains("메일 주소"))
        assertTrue(REPORT_NOTICE.contains("지워요"))
    }
}
