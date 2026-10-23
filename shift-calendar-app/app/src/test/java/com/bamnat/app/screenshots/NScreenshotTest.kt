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

/**
 * [EASY-01][EASY-02] 묶음 N's screenshots, numbers 680~709 (N-1: 680~684): 우선 둘러볼게요 from the first page and
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

    @Test fun browsePicker() = shoot("680_n_browse_pick", FOLDED, recommended(setOf()) { tapText("우선 둘러볼게요", exact = true) })

    @Test fun exampleCalendar() = shoot("681_n_example_calendar", FOLDED, recommended(setOf()) {
        tapText("우선 둘러볼게요", exact = true)
        tapText("교대 근무", exact = true)
        reveal("예시 달력을 보고 있어요")
        assertTrue(c.browse.current.browsing)
        assertEquals(PersonalCalendar.EXAMPLE, c.calendars.active.value)
        assertNull("nothing set for the user yet", c.settingsRepository.current.workStyle)
    })

    @Test fun firstWriteAsks() = shoot("682_n_write_gate", FOLDED, recommended(setOf()) {
        tapText("우선 둘러볼게요", exact = true)
        tapText("평일 근무", exact = true)
        reveal("예시 달력을 보고 있어요")
        var ran = false
        WriteGate.request(c, WriteKind.MEMO) { ran = true }
        assertFalse("waits for the answer", ran)
        assertEquals(WriteKind.MEMO, WriteGate.pending.value?.kind)
    })

    @Test fun startMineOpensTheSetup() = shoot("683_n_start_mine_setup", FOLDED, recommended(setOf()) {
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

    @Test fun setupStyleOffersBrowse() = shoot("684_n_setup_style_browse", FOLDED, fresh(null) { reveal("우선 둘러볼게요") })
}
