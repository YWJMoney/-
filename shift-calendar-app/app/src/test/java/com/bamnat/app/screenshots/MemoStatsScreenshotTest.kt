package com.bamnat.app.screenshots

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** [QA-05] Screenshots: 메모·통계·1년 달력·수면·오늘 흐름. Shot names and numbers are the ones they had in the one big class. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MemoStatsScreenshotTest : ScreenshotBase() {
    @Test fun foldedStats() = shoot("3_folded_stats", FOLDED) { tab(3, wide = false) }
    @Test fun unfoldedStats() = shoot("7_unfolded_stats", UNFOLDED) { tab(3, wide = true) }
    @Test fun foldedCalendarMemos() = shoot("9_folded_memos_calendar", FOLDED) { tab(2, wide = false) }
    @Test fun foldedGeneralMemos() = shoot("10_folded_memos_general", FOLDED) {
        tab(2, wide = false)
        tapText("일반 메모", prefix = true)
    }
    @Test fun foldedTrash() = shoot("11_folded_trash", FOLDED) {
        tab(2, wide = false)
        tapText("휴지통", exact = true)
    }
    @Test fun unfoldedMemos() = shoot("12_unfolded_memos", UNFOLDED) { tab(2, wide = true) }
    @Test fun foldedMemoFirstCalendar() = shoot("71_folded_memo_first_calendar", FOLDED) {
        app.container.settingsRepository.update { it.copy(memoInCells = true) }
        settle()
        scrollUp(450)
    }
    @Test fun foldedCalendarMemoList() = shoot("72_folded_calendar_memo_list", FOLDED) { scrollUp(700); scrollUp(700) }
    @Test fun foldedTodayFlowNight() = shoot("105_folded_today_flow_night", FOLDED, flowCards(
        flowAt(3, 14, 30), flowAt(4, 1, 0), flowAt(4, 7, 20), flowAt(6, 10, 0),
    ))
    @Test fun foldedTodayFlowSleep() = shoot("106_folded_today_flow_sleep", FOLDED, flowCards(
        flowAt(4, 11, 0, listOf(com.bamnat.core.sleep.SleepEntry(1, java.time.LocalDateTime.of(2026, 11, 4, 8, 12), null))),
        flowAt(4, 14, 40, listOf(com.bamnat.core.sleep.SleepEntry(1, java.time.LocalDateTime.of(2026, 11, 4, 8, 12), java.time.LocalDateTime.of(2026, 11, 4, 14, 22)))),
        flowAt(11, 10, 0),
        flowAt(5, 2, 0, listOf(com.bamnat.core.sleep.SleepEntry(1, java.time.LocalDateTime.of(2026, 11, 4, 8, 12), null))),
    ))
    @Test fun foldedTodayFlowDark() = shoot("107_folded_today_flow_dark", "w369dp-h905dp-port-night-420dpi", flowCards(
        flowAt(3, 14, 30), flowAt(4, 7, 20), flowAt(6, 21, 45),
    ))


    // ---- M5 [SLP-03][SLP-04][SLP-06][MKT-11] ----
    @Test fun foldedDaySleepTips() = shoot("85_folded_day_sleep_tips", FOLDED) {
        val repo = app.container.scheduleRepository
        runBlocking {
            val types = repo.shiftTypes.first().associateBy { it.name }
            repo.setOverride(today.minusDays(4), ShiftOverride.Shift(types.getValue("휴무").id))
            repo.setOverride(today.minusDays(3), ShiftOverride.Shift(types.getValue("야간").id))
        }
        settle()
        openDay(dayCell)
        openGroup("몸")
        reveal("몸", exact = true)
    }
    @Test fun foldedTodaySleepLog() = shoot("86_folded_today_sleep_log", FOLDED) {
        runBlocking {
            val dao = app.container.database.sleepDao()
            val zone = java.time.ZoneId.systemDefault()
            fun ms(d: Long, h: Int, m: Int = 0) = today.minusDays(d).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
            dao.insert(com.bamnat.app.data.db.SleepLogEntity(startMillis = ms(3, 23), endMillis = ms(2, 5, 30)))
            dao.insert(com.bamnat.app.data.db.SleepLogEntity(startMillis = ms(2, 23, 30), endMillis = ms(1, 7)))
            dao.insert(com.bamnat.app.data.db.SleepLogEntity(startMillis = ms(1, 22), endMillis = ms(0, 4)))
        }
        settle()
        openDay(today)
        openGroup("몸")
        reveal("몸", exact = true)
    }
    @Test fun foldedStrictRest() = shoot("87_folded_month_rest_strict", FOLDED) {
        app.container.settingsRepository.update { it.copy(strictRest = true) }
        settle()
        scrollUp(700)
    }

    @Test fun foldedTodayHealthSleep() = shoot("90_folded_today_health_sleep", FOLDED) {
        app.container.settingsRepository.update { it.copy(healthSleep = true) }
        runBlocking {
            val zone = java.time.ZoneId.systemDefault()
            fun ms(d: Long, h: Int, m: Int = 0) = today.minusDays(d).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
            // One night tapped by hand; the watch measured the others.
            app.container.database.sleepDao().insert(com.bamnat.app.data.db.SleepLogEntity(startMillis = ms(2, 23, 30), endMillis = ms(1, 7)))
        }
        settle()
        openDay(today)
        openGroup("몸")
        fun at(d: Long, h: Int, m: Int = 0) = today.minusDays(d).atTime(h, m)
        app.container.healthSleep.showForTest(com.bamnat.app.sleep.HealthSleep.State.READY, listOf(
            com.bamnat.core.sleep.SleepEntry(start = at(5, 8, 40), end = at(5, 14, 10), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(4, 9), end = at(4, 15, 20), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(3, 23, 10), end = at(2, 5, 50), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(2, 23, 20), end = at(1, 6, 40), imported = true),
            com.bamnat.core.sleep.SleepEntry(start = at(1, 22, 30), end = at(0, 4, 50), imported = true),
        ))
        settle()
        reveal("몸", exact = true)
    }

    @Test fun phoneStatsHugeText() = shoot("167_phone_stats_huge_text", PHONE, fontScale = 2f) { tab(3, wide = false) }
    @Test fun phoneMemosHugeText() = shoot("171_phone_memos_huge_text", PHONE, fontScale = 2f) { tab(2, wide = false) }
    @Test fun foldedStatsSameMonth() = shoot("147_folded_stats_same_month", FOLDED) {
        tapText("이전 달", exact = true, scrolls = 0)
        tab(3, wide = false)
    }
    // ---- [SCHED-01] A memo that names a time ----
    @Test fun foldedMemoScheduleOffer() = shoot("188_folded_memo_schedule_offer", FOLDED) {
        tab(2, wide = false); tapText("건강검진 오전 9시")
    }
    @Test fun foldedMemoScheduleDialog() = shoot("189_folded_memo_schedule_dialog", FOLDED) {
        tab(2, wide = false); tapText("건강검진 오전 9시"); tapText("일정으로 넣기", exact = true)
    }

    @Test fun smallPhoneStats() = shoot("364_small_phone_stats", SMALL_PHONE) { tab(3, wide = false) }
    @Test fun smallPhoneMemos() = shoot("366_small_phone_memos", SMALL_PHONE) { tab(2, wide = false) }
    @Test fun phoneYear() = shoot("369_phone_year", PHONE) { year() }
    @Test fun foldedYear() = shoot("370_folded_year", FOLDED) { year() }
    @Test fun unfoldedYear() = shoot("371_unfolded_year", UNFOLDED) { year() }
    @Test fun smallPhoneYear() = shoot("372_small_phone_year", SMALL_PHONE) { year() }
    @Test fun phoneStatsCompare() = shoot("387_phone_stats_compare", PHONE) {
        runBlocking { app.container.payStore.save(PayViewModel(app.container).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        tab(3, wide = false); reveal("${java.time.YearMonth.now().monthValue}월 비교", exact = true)
    }
    @Test fun phoneStatsYear() = shoot("388_phone_stats_year", PHONE) {
        runBlocking { app.container.payStore.save(PayViewModel(app.container).sampleProfile().copy(rules = companyRules), keepUndo = false) }
        tab(3, wide = false); reveal("${java.time.Year.now().value}년 근무·급여", exact = true)
    }

    @Test fun storeTabletYear() {
        // The sample pattern starts in July; for the store the same rotation fills the whole year.
        runBlocking {
            val repo = app.container.scheduleRepository
            val p = repo.pattern.first() as WorkPattern.Rotation
            repo.savePattern(p.copy(startDate = LocalDate.of(2026, 1, 1), anchorDate = p.startDate))
        }
        shoot("store_tablet_year", UNFOLDED, clean { year() })
    }

    @Test fun foldedSleepNow() = shoot("344_folded_sleep_now", FOLDED) {
        com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.SLEEP)
        settle()
    }
    @Test fun foldedMemosNewMemo() = shoot("345_folded_memos_new_memo", FOLDED) { tab(2, wide = false) }

    /** [DAILY-05][DAILY-15] 메모: one 새 메모 button, the filters folded into the search box, and 화면 꾸미기. */
    @Test fun e484FoldedMemos() = shoot("484_folded_memos_one_button", FOLDED) { tab(2, wide = false) }
    @Test fun e485UnfoldedMemos() = shoot("485_unfolded_memos_one_button", UNFOLDED) { tab(2, wide = true) }
    @Test fun e486FoldedMemosFilters() = shoot("486_folded_memos_filters_open", FOLDED) {
        tab(2, wide = false)
        tapText("필터 펼치기", exact = true, scrolls = 0)
    }
    @Test fun e488PhoneMemosHugeText() = shoot("488_phone_memos_huge_text", PHONE, 1.5f) { tab(2, wide = false) }

    /** [DAILY-07] 근무·급여's weeks, each a way to its week on the calendar. */
    @Test fun e501FoldedStatsWeeks() = shoot("501_folded_stats_weeks_open_week", FOLDED) {
        tab(3, wide = false)
        reveal("주별 근무시간", across = 0.5f)
    }

    /** [DAILY-15] Unfolded 메모: the list on the left 40%, the memo opened from it on the right 60%. */
    @Test fun e508UnfoldedMemoPanes() = shoot("508_unfolded_memo_list_detail", UNFOLDED) {
        tab(2, wide = true)
        tapText("인수인계", exact = true)
    }
}
