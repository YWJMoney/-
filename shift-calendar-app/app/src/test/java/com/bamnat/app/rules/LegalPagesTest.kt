package com.bamnat.app.rules

import com.bamnat.app.ui.pro.PRIVACY_URL
import com.bamnat.app.ui.pro.TERMS_URL
import com.bamnat.core.tier.AdRules
import com.bamnat.core.tier.TrialConsent
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [MON-05][MON-11][OPS-13][OPS-15] The published pages (repo root privacy/ and terms/) say what 1.19.0 does
 * about 프로, the trial's consent and ads, in the app's own numbers, and the app links to them.
 */
class LegalPagesTest {
    // Gradle runs unit tests in the module directory; the site is the repository root.
    private val site = File("../..")

    @Test fun thePrivacyPolicyCoversProAndAds() {
        val policy = File(site, "privacy/index.html")
        assumeTrue(policy.exists())
        val text = policy.readText()
        assertTrue(text.contains("처리방침 2.5 · 앱 버전 1.19.0"))
        listOf("1-5. 정식 출시 확인", "1-6. 프로 구독과 무료 체험 동의", "1-7. 보상 이용권, 복구 코드, 관리자 휴대폰 연결", "1-8. 광고").forEach {
            assertTrue(it, text.contains(it))
        }
        assertTrue("구매 확인 토큰과 보관", text.contains("구매 확인 토큰") && text.contains("구독이 끝난 날부터 30일 뒤 삭제"))
        assertTrue("동의 기록 5년", text.contains("답한 날부터 5년"))
        assertTrue("광고 ID 권한", text.contains("<td>광고 ID</td>"))
        assertTrue("광고 개인정보 선택", text.contains("광고 개인정보 선택"))
        assertTrue("체험 질문은 ${TrialConsent.ASK_BEFORE.toDays()}일 전", text.contains("체험이 끝나기 ${TrialConsent.ASK_BEFORE.toDays()}일 전부터"))
        assertTrue("설치 뒤 ${AdRules.QUIET_DAYS}일 광고 없음", text.contains("설치 뒤 ${AdRules.QUIET_DAYS}일 동안은 광고가 없습니다"))
        assertTrue(PRIVACY_URL.endsWith("/privacy/"))
    }

    /** [FEAT-07][FEAT-15][PROV-04] 2.6 for 1.20.0: the phone calendar, the feed address and more links, in the app's numbers. */
    @Test fun thePrivacyPolicyCoversThePhoneCalendarAndTheFeed() {
        val policy = File(site, "privacy/index.html")
        assumeTrue(policy.exists())
        val text = policy.readText()
        assertTrue(text.contains("처리방침 2.6 · 앱 버전 1.20.0"))
        assertTrue(text.contains("2-7. 휴대폰 캘린더에 근무 쓰기"))
        assertTrue("쓰기 권한", text.contains("<td>캘린더 쓰기 (선택, 이용자가 허용)</td>"))
        assertTrue("‘${com.bamnat.app.calendar.PhoneCalendar.ACCOUNT}’ 캘린더", text.contains("‘${com.bamnat.app.calendar.PhoneCalendar.ACCOUNT}’ 캘린더"))
        // The span the phone calendar is written over, as IcsWriter.defaultRange has it.
        val (from, to) = com.bamnat.core.schedule.IcsWriter.defaultRange(java.time.LocalDate.of(2026, 10, 22))
        assertTrue(from == java.time.LocalDate.of(2026, 7, 22) && to == java.time.LocalDate.of(2027, 10, 22))
        assertTrue(text.contains("3개월 전부터 1년 뒤까지"))
        assertTrue("구독 주소", text.contains("캘린더 구독 주소") && text.contains(".ics"))
        assertTrue("시간대 이름", text.contains("시간대 이름 하나"))
        assertTrue("링크 수", text.contains("링크를 ${com.bamnat.app.share.ShareLinkKey.MAX}개까지"))
    }

    @Test fun theTermsCoverSubscriptionTrialAndRefunds() {
        val terms = File(site, "terms/index.html")
        assumeTrue(File(site, "privacy/index.html").exists())
        assertTrue("이용약관 페이지가 있어요", terms.exists())
        val text = terms.readText()
        assertTrue(text.contains("이용약관 1.0 · 앱 버전 1.19.0"))
        assertTrue(text.contains("1개월 무료 체험"))
        assertTrue(text.contains("체험이 끝나기 ${TrialConsent.ASK_BEFORE.toDays()}일 전부터"))
        assertTrue(text.contains("결제한 날부터 7일 안에 청약철회"))
        assertTrue(text.contains("제17조 제2항 제5호"))
        assertTrue(text.contains("Google Play › 결제 및 정기 결제"))
        assertTrue(text.contains("설치 뒤 ${AdRules.QUIET_DAYS}일 동안은 광고가 없습니다"))
        assertTrue(TERMS_URL.endsWith("/terms/"))
    }
}
