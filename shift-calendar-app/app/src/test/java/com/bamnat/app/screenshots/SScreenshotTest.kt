package com.bamnat.app.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.company.CompanyDraft
import com.bamnat.app.company.InquiryKind
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.data.RecommendedSettings
import com.bamnat.app.ui.company.CompanyBoardScreen
import com.bamnat.app.ui.company.CompanyBoardViewModel
import com.bamnat.app.ui.company.CompanyDetailScreen
import com.bamnat.app.ui.company.CompanyDetailViewModel
import com.bamnat.app.ui.company.PlazaEntry
import com.bamnat.app.ui.company.PlazaEntryCard
import com.bamnat.app.ui.company.PlazaEntryLine
import com.bamnat.app.ui.settings.InquiryStart
import com.bamnat.core.consent.ConsentItem
import com.bamnat.core.consent.RecommendedFeature
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [SETUP-15][COMM-12][COMM-13][COMM-14][INQ-06][INQ-07] 묶음 S's screenshots, numbers 780~799: "무슨 일 하세요?" before
 * the first-start screen and what each kind of work then gets, 광장 entry from the pay screen, two reports of one
 * department told apart, the phase sheet's hint, 문의 공개 여부 and the help home's 문의하기, on a small phone, at
 * 200%, on its side and dark.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SScreenshotTest : ScreenshotBase() {
    private val c get() = app.container

    private companion object {
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
        const val LATER = "허용은 나중에 할게요"
    }

    /** A new phone on its very first screen, "무슨 일 하세요?". */
    private fun firstScreen(then: Screen.() -> Unit = {}): Screen.() -> Unit {
        c.consentStore.forget()
        c.consentStore.availableForTest = setOf(ConsentItem.CALENDAR)
        return fresh(null, then)
    }

    // ---- 357 [SETUP-15] ----
    @Test fun firstStyleFolded() = shoot("780_s_first_style", FOLDED, firstScreen {
        assertTrue(shows("무슨 일 하세요?"))
        assertTrue("the setup's own question is not on screen", !shows("어떻게 일하세요?"))
    })
    @Test fun firstStyleSmallPhone() = shoot("781_s_first_style_small", SMALL_PHONE, firstScreen())
    @Test fun firstStyleHugeText() = shoot("782_s_first_style_huge_text", PHONE, fontScale = 2f, firstScreen {
        reveal("백업이나 회사 파일로 시작할게요", exact = true)
    })
    @Test fun firstStyleLandscape() = shoot("783_s_first_style_landscape", PHONE_LANDSCAPE, firstScreen())

    @Test fun dayWorkGetsDayNotices() = shoot("784_s_recommended_day", FOLDED, firstScreen {
        tapText("평일 근무", exact = true)
        assertTrue(shows("평일 근무에 맞춰 골라 두었어요."))
        assertFalse("no night notice to switch", shows(RecommendedFeature.COMMUTE.title, exact = true))
        reveal("꺼 두었어요")
    })
    @Test fun dayWorkThenTheSetupGoesOn() = shoot("785_s_day_then_setup", FOLDED, firstScreen {
        tapText("평일 근무", exact = true)
        tapText(LATER)
        // The work told once is not asked again: the setup starts on its next question.
        assertEquals(WorkStyle.DAY, c.setupDrafts.draft.value?.style)
        assertFalse(shows("어떻게 일하세요?"))
        assertEquals(setOf(RecommendedFeature.BEDTIME, RecommendedFeature.TODAY_SHIFT), RecommendedSettings.on(c.settingsRepository.current))
    })
    @Test fun hourlyWorkGetsDayNotices() = shoot("786_s_recommended_hourly_dark", FOLDED_NIGHT, firstScreen {
        tapText("아르바이트 · 시급", exact = true)
        tapText(LATER)
        assertEquals(WorkStyle.HOURLY, c.setupDrafts.draft.value?.style)
        assertEquals(setOf(RecommendedFeature.BEDTIME, RecommendedFeature.TODAY_SHIFT), RecommendedSettings.on(c.settingsRepository.current))
    })
    @Test fun fieldWorkGetsNoShiftNotices() = shoot("787_s_recommended_field", FOLDED, firstScreen {
        tapText("현장 · 프리랜서", exact = true)
        assertTrue(shows("입금 약속일과 미수 알림", exact = false))
        assertFalse("no shift notice to switch", shows(RecommendedFeature.BRIEFING.title, exact = true))
    })
    @Test fun fieldWorkStartsAtOnce() = shoot("788_s_field_started", FOLDED, firstScreen {
        tapText("현장 · 프리랜서", exact = true)
        tapText(LATER)
        settle()
        assertEquals(WorkStyle.FIELD, c.settingsRepository.current.workStyle)
        assertTrue(RecommendedSettings.on(c.settingsRepository.current).isEmpty())
    })
    @Test fun shopAndInvestStartAtOnce() {
        for ((title, style) in listOf("자영업" to WorkStyle.BUSINESS, "투자 · 주식·코인" to WorkStyle.INVEST)) {
            shoot("789_s_started_${style.name.lowercase()}", FOLDED, firstScreen {
                tapText(title, exact = true)
                tapText(LATER)
                settle()
                assertEquals(style, c.settingsRepository.current.workStyle)
            })
        }
    }
    @Test fun monthlyRosterKeepsTheRoster() = shoot("790_s_roster_then_setup", FOLDED, firstScreen {
        tapText("교대 근무 · 근무표가 매달 나와요", exact = true)
        tapText(LATER)
        val d = c.setupDrafts.draft.value
        assertEquals(WorkStyle.SHIFT, d?.style)
        assertTrue(d!!.rosterMonthly)
        assertEquals(RecommendedFeature.entries.toSet(), RecommendedSettings.on(c.settingsRepository.current))
    })
    @Test fun backFromRecommendedAsksAgain() = shoot("791_s_back_to_first_style", FOLDED, firstScreen {
        tapText("평일 근무", exact = true)
        back()
        assertTrue(shows("무슨 일 하세요?"))
    })

    // ---- 358/359 [COMM-12][COMM-13][COMM-14] ----
    @Test fun plazaEntryOnPay() = shoot("792_s_plaza_entry", FOLDED) {
        val c = companyOn()
        c.companyStore.setDraft(CompanyDraft("(주)예시정밀", "생산팀"))
        content {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlazaEntryCard(c, PersonalCalendar.MAIN, onOpen = {})
                PlazaEntryLine(c, onOpen = {})
            }
        }
        // The calendar shown first asked its question today, and the tip waits for a day without one.
        c.asks.clear(PersonalCalendar.MAIN)
        settle()
        tapText("같은 회사 분들 규칙 보기", exact = true)
        // The plaza opens with the name in its search box.
        assertEquals("(주)예시정밀", CompanyBoardViewModel(c).query.value)
        assertEquals("", PlazaEntry.takeQuery())
    }
    @Test fun boardTellsTwinsApart() = shoot("793_s_board_twins", FOLDED) {
        company.twins = true
        val vm = CompanyBoardViewModel(companyOn())
        content { CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        reveal("다른 점:")
        assertTrue(shows("제보 1"))
    }
    @Test fun boardTwinsHugeText() = shoot("794_s_board_twins_huge_text", PHONE, fontScale = 2f) {
        company.twins = true
        val vm = CompanyBoardViewModel(companyOn())
        content { CompanyBoardScreen(vm, onBack = {}, onOpen = {}, onShare = {}) }
        reveal("다른 점:")
    }
    @Test fun detailTellsTwinsApartDark() = shoot("795_s_detail_twins_dark", FOLDED_NIGHT) {
        company.twins = true
        val vm = CompanyDetailViewModel(companyOn(), "c1")
        content { CompanyDetailScreen(vm, setup = true, onBack = {}, onApplied = { _, _ -> }, onByHand = {}) }
        reveal("다른 점:")
    }
    @Test fun phaseSheetSaysWhatToPick() = shoot("796_s_phase_sheet", FOLDED) {
        val vm = CompanyDetailViewModel(companyOn(), "c1")
        content { CompanyDetailScreen(vm, setup = true, onBack = {}, onApplied = { _, _ -> }, onByHand = {}, openPhasesFor = "s1") }
        reveal("내 근무와 같은 줄을 먼저 골라 주세요")
    }

    // ---- 360 [INQ-06][INQ-07] ----
    @Test fun inquiryShareSwitch() {
        inquiriesOn()
        shoot("797_s_inquiry_share_off", FOLDED) {
            askFromScreen(InquiryStart(about = "달력", kind = InquiryKind.QUESTION))
            tapText("모두의 문의에 올리기", exact = true)
            reveal("모두의 문의에 올리기", exact = true)
        }
    }
    @Test fun helpHomeAsksFirstHugeText() {
        inquiriesOn()
        shoot("798_s_help_home_ask_huge_text", PHONE, fontScale = 2f) {
            askFromScreen(InquiryStart(help = true))
            assertTrue(shows("문의하기", exact = true))
        }
    }
    @Test fun inquiryShareSwitchLandscape() {
        inquiriesOn()
        shoot("799_s_inquiry_share_landscape", PHONE_LANDSCAPE) {
            askFromScreen(InquiryStart(about = "달력", kind = InquiryKind.BUG))
            reveal("모두의 문의에 올리기", exact = true)
        }
    }
}
