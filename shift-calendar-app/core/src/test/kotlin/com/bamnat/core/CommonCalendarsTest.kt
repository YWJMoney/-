package com.bamnat.core

import com.bamnat.core.model.Attendance
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.money.AllCalendarsMonth
import com.bamnat.core.money.Budgets
import com.bamnat.core.money.CalendarMonth
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobStatus
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.MoneyBudget
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.work.CombinedView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** [COMN-01] 합쳐 보기, [COMN-02][COMN-03] 모든 달력 합계 and [COMN-04] 확인 대기함. */
class CommonCalendarsTest {
    private val mon = LocalDate.of(2026, 10, 19)
    private val tue = mon.plusDays(1)

    private fun resolver(vararg entries: DayEntry) = ScheduleResolver(Fixtures.all, null, entries.toList())

    @Test
    fun aNightShiftRunningIntoTheNextMorningsJobClashes() {
        val hospital = resolver(DayEntry(mon, ShiftOverride.Shift(Fixtures.night.id)))
        val job = Job(date = tue, title = "과외", start = LocalTime.of(6, 0), end = LocalTime.of(8, 0))
        val spans = listOf(mon, tue).flatMap { d ->
            CombinedView.spansOf("병원", hospital.resolve(d), emptyList()) + CombinedView.spansOf("과외", resolver().resolve(d), listOf(job))
        }
        assertEquals(listOf("야간", "과외"), spans.map { it.label })
        assertEquals(1, CombinedView.clashes(spans).size)
        assertEquals(setOf(mon, tue), CombinedView.clashDays(spans))
    }

    @Test
    fun workThatOnlyTouchesOrHasNoTimesDoesNotClash() {
        val day = resolver(DayEntry(mon, ShiftOverride.Shift(Fixtures.day.id)))
        val after = Job(date = mon, title = "배달", start = LocalTime.of(18, 0), end = LocalTime.of(21, 0))
        val allDay = Job(date = mon, title = "촬영")
        val cancelled = Job(date = mon, title = "취소된 일", start = LocalTime.of(10, 0), end = LocalTime.of(11, 0), status = JobStatus.CANCELLED)
        val spans = CombinedView.spansOf("회사", day.resolve(mon), emptyList()) +
            CombinedView.spansOf("부업", resolver().resolve(mon), listOf(after, allDay, cancelled))
        assertEquals(listOf("주간", "배달", "촬영"), spans.map { it.label })
        assertTrue(CombinedView.clashDays(spans).isEmpty())
        // The same calendar's own pieces never clash with each other.
        val twice = CombinedView.spansOf("부업", resolver().resolve(mon), listOf(after, after.copy(title = "또")))
        assertTrue(CombinedView.clashes(twice).isEmpty())
        // A missed day is no work.
        val absent = resolver(DayEntry(mon, ShiftOverride.Shift(Fixtures.day.id), attendance = Attendance.ABSENT))
        assertTrue(CombinedView.spansOf("회사", absent.resolve(mon), emptyList()).isEmpty())
    }

    @Test
    fun workDaysAndHoursPerCalendar() {
        val r = resolver(DayEntry(mon, ShiftOverride.Shift(Fixtures.day.id)), DayEntry(tue, ShiftOverride.Shift(Fixtures.day.id)))
        val job = Job(date = tue.plusDays(1), title = "강의", start = LocalTime.of(19, 0), end = LocalTime.of(21, 0))
        val total = CombinedView.workOf(listOf(mon, tue, tue.plusDays(1), tue.plusDays(2)).map { r.resolve(it) }, listOf(job))
        assertEquals(CombinedView.WorkTotal(3, 8 * 60 * 2 + 120), total)
    }

    @Test
    fun everyCalendarAddsUp() {
        val month = YearMonth.of(2026, 10)
        val a = MoneyBook(listOf(MoneyEntry(date = mon, kind = MoneyKind.INCOME, amount = 300_000, category = "용돈"),
            MoneyEntry(date = mon, kind = MoneyKind.EXPENSE, amount = 50_000, category = "식비"))).month(month, payEstimate = 2_000_000)
        val b = MoneyBook(listOf(MoneyEntry(date = tue, kind = MoneyKind.EXPENSE, amount = 20_000, category = "교통"))).month(month)
        val all = AllCalendarsMonth(listOf(CalendarMonth("a", "병원", a, 20, 9600), CalendarMonth("b", "과외", b, 4, 480)))
        assertEquals(2_300_000, all.income)
        assertEquals(70_000, all.spent)
        assertEquals(2_230_000, all.balance)
        assertEquals(24, all.workDays)
    }

    @Test
    fun unconfirmedLinesAreLeftOutOnlyWhenAskedTo() {
        val month = YearMonth.of(2026, 10)
        val seen = MoneyEntry(date = mon, kind = MoneyKind.EXPENSE, amount = 10_000, category = "식비")
        val waiting = MoneyEntry(id = 2, date = tue, kind = MoneyKind.EXPENSE, amount = 40_000, category = "식비",
            source = MoneySource.NOTIFICATION, reviewed = false)
        val book = MoneyBook(listOf(seen, waiting), budgets = listOf(MoneyBudget("식비", 30_000)))

        // 바로 적기 (off): as before, every line counts.
        val now = book.holdUnconfirmed(null)
        assertEquals(50_000, now.month(month).expenses)
        assertEquals(emptyList<MoneyEntry>(), now.pending)

        // 확인하고 적기 (on): the waiting line is out of the month, the budget and the days until confirmed.
        val held = book.holdUnconfirmed(mon)
        assertEquals(listOf(waiting), held.pending)
        assertEquals(10_000, held.month(month).expenses)
        assertEquals(null, held.days(month)[tue])
        val budget = Budgets.status(held.budgets, held.entries, month, tue)
        assertEquals(10_000, budget.categories.single().spent)
        val confirmed = MoneyBook(listOf(seen, waiting.copy(reviewed = true))).holdUnconfirmed(mon)
        assertEquals(50_000, confirmed.month(month).expenses)
        // Lines from before the switch went on were counted already and stay in the totals.
        val older = book.holdUnconfirmed(tue.plusDays(1))
        assertEquals(emptyList<MoneyEntry>(), older.pending)
        assertEquals(50_000, older.month(month).expenses)
    }

    @Test
    fun widgetFiguresForEachKindOfWork() {
        val hourly = com.bamnat.core.work.WidgetFigures.HourlyWeek(12 * 60 + 30)
        assertEquals(150, hourly.left)
        assertEquals(false, hourly.reached)
        assertTrue(com.bamnat.core.work.WidgetFigures.HourlyWeek(15 * 60).reached)

        val jobs = listOf(
            Job(date = mon, endDate = tue, title = "욕실", amount = 600_000),
            Job(date = tue.plusDays(1), title = "도배", amount = 200_000, balancePaid = true),
            Job(date = tue.plusDays(2), title = "취소", amount = 100_000, status = JobStatus.CANCELLED),
            Job(date = mon.minusDays(7), title = "지난주", amount = 50_000),
        )
        val week = com.bamnat.core.work.WidgetFigures.fieldWeek(jobs, mon, tue)
        assertEquals(3, week.days)
        assertEquals(null, week.units)
        // Owed up to today: this week's first job and last week's; the cancelled one and the paid one are not.
        assertEquals(650_000, week.unpaid)
        val units = com.bamnat.core.work.WidgetFigures.fieldWeek(jobs.map { if (it.title == "도배") it.copy(units = 0.5) else it }, mon, tue)
        assertEquals(2.5, units.units!!, 0.001)
        assertEquals("2.5공수", com.bamnat.core.work.WidgetFigures.units(2.5))
        assertEquals("2공수", com.bamnat.core.work.WidgetFigures.units(2.0))
    }
}
