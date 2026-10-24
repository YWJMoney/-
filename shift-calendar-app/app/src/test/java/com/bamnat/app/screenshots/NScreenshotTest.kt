package com.bamnat.app.screenshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.guide.WriteGate
import com.bamnat.core.example.WriteKind
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.bamnat.core.time.Today

/**
 * [EASY-01][EASY-02][EASY-10] 묶음 N's screenshots, numbers 680~709 (N-1: 680~684, N-4: 707~709): 우선 둘러볼게요 from the first page and
 * from the first setup question, the example calendar with its band, the card a first write brings up, and
 * 내 정보로 시작 landing on the setup with the example's kind of work chosen.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class NScreenshotTest : ScreenshotBase() {
    private val c get() = app.container

    private companion object {
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
    }

    @Test fun browsePicker() = shoot("680_n_browse_pick", FOLDED, recommended(setOf(), firstStyle = null) { tapText("우선 둘러볼게요", exact = true) })

    @Test fun exampleCalendar() = shoot("681_n_example_calendar", FOLDED, recommended(setOf(), firstStyle = null) {
        tapText("우선 둘러볼게요", exact = true)
        tapText("교대 근무", exact = true)
        reveal("예시 달력을 보고 있어요")
        assertTrue(c.browse.current.browsing)
        assertEquals(PersonalCalendar.EXAMPLE, c.calendars.active.value)
        assertNull("nothing set for the user yet", c.settingsRepository.current.workStyle)
    })

    @Test fun firstWriteAsks() = shoot("682_n_write_gate", FOLDED, recommended(setOf(), firstStyle = null) {
        tapText("우선 둘러볼게요", exact = true)
        tapText("평일 근무", exact = true)
        reveal("예시 달력을 보고 있어요")
        var ran = false
        WriteGate.request(c, WriteKind.MEMO) { ran = true }
        assertFalse("waits for the answer", ran)
        assertEquals(WriteKind.MEMO, WriteGate.pending.value?.kind)
    })

    @Test fun startMineOpensTheSetup() = shoot("683_n_start_mine_setup", FOLDED, recommended(setOf(), firstStyle = null) {
        tapText("우선 둘러볼게요", exact = true)
        tapText("아르바이트", exact = true)
        reveal("예시 달력을 보고 있어요")
        WriteGate.request(c, WriteKind.SHIFT) {}
        tapText("내 정보로 시작", exact = true)
        reveal("어떻게 일하세요?")
        assertFalse(c.browse.current.browsing)
        assertNull("the example went", c.calendars.get(PersonalCalendar.EXAMPLE))
        assertEquals(WorkStyle.HOURLY, c.setupDrafts.draft.value?.style)
    })

    // ---- N-2: the question card, 추정 and the check before a report leaves (685~693) ----
    private fun (Screen.() -> Unit).then(next: Screen.() -> Unit): Screen.() -> Unit = { this@then(this); next() }

    private fun asking(then: Screen.() -> Unit = {}): Screen.() -> Unit = clean {
        c.asks.clear(PersonalCalendar.MAIN)
        then()
    }

    @Test fun askHomeFolded() = shoot("685_n_ask_home_folded", FOLDED, asking { reveal("나중에", exact = true) })
    @Test fun askHomeUnfolded() = shoot("686_n_ask_home_unfolded", UNFOLDED, asking { reveal("나중에", exact = true) })
    @Test fun askHomeBig() = shoot("687_n_ask_home_200", FOLDED, 2f, asking { reveal("나중에", exact = true) })
    @Test fun askHomeDark() = shoot("688_n_ask_home_dark", FOLDED_NIGHT, asking { reveal("나중에", exact = true) })

    @Test fun askAbovePay() = shoot("689_n_ask_pay", FOLDED, payScreen(com.bamnat.app.tier.Tier.PRO, withProfile = true,
        adjust = { it.copy(period = it.period.copy(payDay = null)) }) { vm ->
        com.bamnat.app.ui.pay.PayScreen(vm, onBack = {}, top = {
            com.bamnat.app.ui.guide.AskArea(c, PersonalCalendar.MAIN, WorkStyle.SHIFT,
                com.bamnat.core.profile.AskPlace.Screen(setOf("pay.calc")), inCardOnly = true)
        })
    }.then { c.asks.clear(PersonalCalendar.MAIN); settle(); reveal("급여는 매달 며칠에 들어와요?") })

    @Test fun estimatedOnPay() = shoot("690_n_estimated_pay", FOLDED,
        payScreen(com.bamnat.app.tier.Tier.PRO, withProfile = true).then { reveal("추정으로 계산한 값", exact = false) })

    @Test fun whereFrom() = shoot("691_n_where_from", FOLDED,
        payScreen(com.bamnat.app.tier.Tier.PRO, withProfile = true).then { reveal("추정으로 계산한 값"); tapText("추정", exact = true) })

    @Test fun checkBeforeReport() = shoot("692_n_check_before_report", FOLDED,
        payScreen(com.bamnat.app.tier.Tier.PRO, withProfile = true).then { tapText("월 PDF") })

    @Test fun estimatedOnPayUnfolded() = shoot("693_n_estimated_pay_unfolded", UNFOLDED,
        payScreen(com.bamnat.app.tier.Tier.PRO, withProfile = true).then { reveal("추정으로 계산한 값") })

    // ---- N-3: 내 정보, 밤낮이 한 일 and the board (694~706) ----
    private fun open(place: com.bamnat.core.guide.Place): Screen.() -> Unit = clean {
        com.bamnat.app.ui.guide.FeatureLinks.open(com.bamnat.core.guide.FeatureRoute(place))
        settle()
    }

    /** A day of what the app did, at fixed times: today a backup, new files and the pay again; yesterday the news. */
    private fun journal(then: Screen.() -> Unit): Screen.() -> Unit = clean {
        val zone = Today.zone()
        fun at(d: java.time.LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant()
        kotlinx.coroutines.runBlocking {
            c.journal.clear()
            listOf(
                com.bamnat.core.journal.JournalEntry(at(today.minusDays(1), 9), com.bamnat.core.journal.JournalKind.NEWS),
                com.bamnat.core.journal.JournalEntry(at(today.minusDays(1), 21, 30), com.bamnat.core.journal.JournalKind.MONEY_CAPTURED, count = 2),
                com.bamnat.core.journal.JournalEntry(at(today, 4, 10), com.bamnat.core.journal.JournalKind.BACKUP),
                com.bamnat.core.journal.JournalEntry(at(today, 4, 12), com.bamnat.core.journal.JournalKind.REMOTE_FILE, detail = "2개"),
                com.bamnat.core.journal.JournalEntry(at(today, 4, 12), com.bamnat.core.journal.JournalKind.PAY_RECALC),
                com.bamnat.core.journal.JournalEntry(at(today, 4, 13), com.bamnat.core.journal.JournalKind.ALARMS_PLANNED, detail = "06/30 05:30"),
            ).forEach { c.journal.add(it) }
        }
        then()
    }

    @Test fun settingsBoard() = shoot("694_n_settings_board", FOLDED, clean { tab(4, wide = false); reveal("밤낮이 한 일", exact = true) })
    @Test fun profileFolded() = shoot("695_n_profile_folded", FOLDED, open(com.bamnat.core.guide.Place.PROFILE).then { reveal("알고 있는 것", exact = false) })
    @Test fun profileUnfolded() = shoot("696_n_profile_unfolded", UNFOLDED, open(com.bamnat.core.guide.Place.PROFILE).then { reveal("알고 있는 것", exact = false) })
    @Test fun profileBig() = shoot("697_n_profile_200", FOLDED, 2f, open(com.bamnat.core.guide.Place.PROFILE).then { reveal("알고 있는 것", exact = false) })
    @Test fun profileDark() = shoot("698_n_profile_dark", FOLDED_NIGHT, open(com.bamnat.core.guide.Place.PROFILE).then { reveal("알고 있는 것", exact = false) })
    @Test fun profileEdit() = shoot("699_n_profile_edit", FOLDED, open(com.bamnat.core.guide.Place.PROFILE).then {
        tapText("잠", exact = true)
        tapText("목표 수면", exact = true)
        // No reveal: a scroll would swipe the sheet away.
        assertEquals(1, shownDialogs().size)
    })
    @Test fun profileStartOver() = shoot("700_n_profile_start_over", FOLDED, open(com.bamnat.core.guide.Place.PROFILE).then {
        tapText("모두 처음부터", exact = true)
        assertEquals(1, shownDialogs().size)
    })
    @Test fun journalList() = shoot("701_n_journal", FOLDED, journal { com.bamnat.app.ui.guide.FeatureLinks.open(com.bamnat.core.guide.FeatureRoute(com.bamnat.core.guide.Place.JOURNAL)); settle(); reveal("백업 저장", exact = true) })
    @Test fun journalBig() = shoot("702_n_journal_200", FOLDED, 2f, journal { com.bamnat.app.ui.guide.FeatureLinks.open(com.bamnat.core.guide.FeatureRoute(com.bamnat.core.guide.Place.JOURNAL)); settle(); reveal("백업 저장", exact = true) })
    @Test fun journalDark() = shoot("703_n_journal_dark", FOLDED_NIGHT, journal { com.bamnat.app.ui.guide.FeatureLinks.open(com.bamnat.core.guide.FeatureRoute(com.bamnat.core.guide.Place.JOURNAL)); settle(); reveal("백업 저장", exact = true) })
    @Test fun journalHome() = shoot("704_n_journal_home", FOLDED, journal { settle(); reveal("오늘 밤낮이 한 일", exact = false) })
    @Test fun featureWaits() = shoot("705_n_feature_waits", FOLDED, clean {
        tab(4, wide = false)
        tapText("기능 켜고 끄기", exact = true)
        reveal("이걸 알려 주면 시작해요", exact = false)
    })
    @Test fun settingsBoardUnfolded() = shoot("706_n_settings_board_unfolded", UNFOLDED, clean { tab(4, wide = true); reveal("밤낮이 한 일", exact = true) })

    // N-4: 도움말 answers and the update notice that tell of it.
    @Test fun helpProfile() {
        inquiriesOn()
        shoot("707_n_help_profile", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
            typeInto("기능과 도움말 찾기", "질문 카드")
            tapText("질문 카드가 떠요 · 답한 정보를 바꾸고 싶어요", exact = true)
            reveal("내 정보 열기", exact = true)
        }
    }
    @Test fun helpJournal() {
        inquiriesOn()
        shoot("708_n_help_journal", FOLDED) {
            tab(4, wide = false)
            tapText("도움말과 문의", exact = true)
            typeInto("기능과 도움말 찾기", "밤낮이 한 일")
            tapText("앱이 알아서 무엇을 했는지 보고 싶어요", exact = true)
            reveal("밤낮이 한 일 열기", exact = true)
        }
    }
    @Test fun whatsNew() = shoot("709_n_whats_new_1_23", FOLDED) {
        content { com.bamnat.app.ui.guide.WhatsNewDialog(com.bamnat.core.guide.WhatsNew.releases.take(1), {}, {}, {}) }
    }

    @Test fun setupStyleOffersBrowse() = shoot("684_n_setup_style_browse", FOLDED, fresh(null) { reveal("우선 둘러볼게요") })
}
