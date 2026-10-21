package com.bamnat.app.share

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BuildConfig
import com.bamnat.app.ui.calendar.SHARE_SERVER_NOTICE
import com.bamnat.app.ui.calendar.partNames
import com.bamnat.app.ui.settings.REPORT_EMAIL
import com.bamnat.app.ui.settings.REPORT_NOTICE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** [MKT-09][OPS-13] The app talks to the live link server, and the share sheet and privacy policy say so. */
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

    /** [COMM-04][ADMIN-02] 회사 찾기 and 문의하기 reach the live company server. */
    @Test fun companyAndInquiriesGoToTheLiveServerOverHttps() {
        assertTrue(BuildConfig.COMPANY_BASE_URL, BuildConfig.COMPANY_BASE_URL.matches(Regex("https://[a-z0-9-]+\\.[a-z0-9-]+\\.workers\\.dev")))
        assertTrue(com.bamnat.app.company.CompanyClient().enabled)
        assertTrue(com.bamnat.app.company.InquiryClient().enabled)
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
        assertTrue(text.contains("처리방침 2.4"))
        assertTrue(text.contains("Cloudflare, Inc."))
        assertTrue(text.contains("<td>인터넷</td>"))
        assertTrue(text.contains("7일·30일·90일"))
        assertFalse(text.contains("인터넷 접속 권한 자체가 없습니다"))
        assertFalse(text.contains("인터넷 권한은 쓰지 않습니다"))
        // The day counts the policy states are the ones the app sends.
        assertTrue(text.contains("${ShareLinkPayload.DAYS}일"))
        assertEquals(listOf(7, 30, 90), ShareLinkPayload.TTL_DAYS)
    }

    /**
     * [SHR-01][SHR-02] What a link carries only when ticked, in the numbers the app uses, and received
     * calendars kept on the receiver's phone until the sharer cuts the link.
     */
    @Test fun thePrivacyPolicyCoversChosenPartsAndReceivedCalendars() {
        val policy = File("../../privacy/index.html")
        assumeTrue(policy.exists())
        val text = policy.readText()
        assertTrue(text.contains("‘보여 줄 것’에서 고른 것만"))
        assertTrue(text.contains("하루 ${ShareLinkPayload.EXTRAS_PER_DAY}개까지"))
        assertTrue(text.contains("하루 ${ShareLinkPayload.MEMO_CHARS}자 이내"))
        assertTrue(text.contains("세전 급여 총액 하나"))
        // [SHR-11] The money in each kind of work, and a freelancer's jobs without client, place or price.
        assertTrue(text.contains("달마다 일감 수입 합계 하나"))
        assertTrue(text.contains("달마다 매출 합계 하나"))
        assertTrue(text.contains("일감의 이름·시간(하루 ${ShareLinkPayload.JOBS_PER_DAY}개까지, 고객·장소·일감별 금액 없이)"))
        assertTrue(text.contains("처음에는 아무것도 골라져 있지 않습니다"))
        assertFalse(ShareOptions().any)
        assertTrue(text.contains("받은 근무표"))
        assertTrue(text.contains("받는 사람의 휴대폰에서도 지움"))
        // The share sheet names the same parts the policy does.
        listOf("휴가 종류", "연장·휴무일 근무", "메모", "급여").forEach { part ->
            assertTrue(part, ShareOptions(true, true, true, true).partNames().contains(part))
            assertTrue(part, text.contains(part))
        }
        com.bamnat.core.work.MoneyShare.entries.forEach { kind ->
            assertTrue(kind.label, ShareOptions(pay = true).partNames(kind).contains(kind.label))
            assertTrue(kind.label, text.contains(kind.label))
        }
    }

    /** [COMM-04] The company server's section names what goes and what never does, as the server enforces. */
    @Test fun thePrivacyPolicyCoversTheCompanyServer() {
        val policy = File("../../privacy/index.html")
        assumeTrue(policy.exists())
        val text = policy.readText()
        assertTrue(text.contains("1-2. 회사 찾기"))
        assertTrue(text.contains("기본급, 연봉, 수당 금액, 1회 정액, 명세서 금액, 입사일, 연차, 알람, 날짜별 근무표, 이름, 연락처는 보내지 않습니다"))
        assertTrue(text.contains("감시·단속 근로 승인 여부"))
        assertTrue(text.contains("IP 주소를 저장하지 않으며"))
        assertTrue(text.contains("이 휴대폰이 보낸 정보 모두 지우기"))
    }

    /** [PAYS-02][SETUP-01] The payslip a user matched and the unfinished first setup stay on the phone, as the policy says. */
    @Test fun thePrivacyPolicyCoversPayslipsAndTheFirstSetup() {
        val policy = File("../../privacy/index.html")
        assumeTrue(policy.exists())
        val text = policy.readText()
        assertTrue(text.contains("명세서로 맞춰 본 달과 그 달 지급 총액"))
        assertTrue(text.contains("첫 설정에서 아직 저장하지 않은 답"))
        assertTrue(text.contains("이용자가 적을 때만 조 이름"))
        assertTrue(text.contains("연봉과 명세서 금액은 운영자에게 보내지 않으며"))
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
