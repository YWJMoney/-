package com.bamnat.core

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.schedule.HolidayWork
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.schedule.datesBetween
import com.bamnat.core.work.AgendaRow
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.MonthAgenda
import com.bamnat.core.work.MonthOverview
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.work.asksHolidaysOff
import com.bamnat.core.work.calendarStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** [CAL-05]~[CAL-09] The calendar follows the kind of work: its words, its month, its list, its holidays. */
class CalendarFocusTest {
    private val october = YearMonth.of(2026, 10)
    private val weekdays = WorkPattern.Weekly.fromSteps(LocalDate.of(2026, 1, 1), listOf(1L, 1L, 1L, 1L, 1L, null, null))

    @Test
    fun `each kind of work names its calendar`() {
        assertEquals(CalendarFocus.SHIFTS, CalendarFocus.of(null))
        assertEquals(CalendarFocus.SHIFTS, CalendarFocus.of(WorkStyle.SHIFT))
        assertEquals(CalendarFocus.SHIFTS, CalendarFocus.of(WorkStyle.DAY))
        assertEquals(CalendarFocus.SHIFTS, CalendarFocus.of(WorkStyle.HOURLY))
        assertEquals(CalendarFocus.JOBS, CalendarFocus.of(WorkStyle.FIELD))
        assertEquals(CalendarFocus.SALES, CalendarFocus.of(WorkStyle.BUSINESS))
        // A freelancer's empty day is not a day off, and nothing is stamped with shifts.
        assertEquals("잡힌 일감 없음", CalendarFocus.JOBS.emptyDay)
        assertEquals("일감 적기", CalendarFocus.JOBS.addLabel)
        assertEquals("매출 적기", CalendarFocus.SALES.addLabel)
        assertEquals(null, CalendarFocus.SHIFTS.addLabel)
        // Each kind starts from its own first step.
        assertEquals("일하는 요일을 정해 주세요", WorkStyle.DAY.calendarStart().title)
        assertEquals("빠른 입력", WorkStyle.HOURLY.calendarStart().action)
        assertEquals("일감 적기", WorkStyle.FIELD.calendarStart().action)
        assertEquals("근무 주기 정하기", (null as WorkStyle?).calendarStart().action)
        assertTrue(WorkStyle.DAY.asksHolidaysOff)
        assertFalse(WorkStyle.SHIFT.asksHolidaysOff)
        assertFalse(WorkStyle.HOURLY.asksHolidaysOff)
    }

    @Test
    fun `weekday work on a public holiday is found until the user decides`() {
        val resolver = ScheduleResolver(Fixtures.all, weekdays, emptyList())
        // 10/3 개천절 is a Saturday; 10/5 대체공휴일 and 10/9 한글날 fall on weekdays.
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9)), HolidayWork.patternDays(resolver, october))
        // Marked off, or worked on purpose, the day is the user's.
        val decided = ScheduleResolver(Fixtures.all, weekdays, listOf(
            DayEntry(LocalDate.of(2026, 10, 5), ShiftOverride.Off),
            DayEntry(LocalDate.of(2026, 10, 9), alarmSkipped = true),
        ))
        assertEquals(emptyList<LocalDate>(), HolidayWork.patternDays(decided, october))
    }

    @Test
    fun `a shift month counts days, hours, leave and holiday work`() {
        val entries = listOf(DayEntry(LocalDate.of(2026, 10, 7), leave = Leave(LeaveKind.ANNUAL, 2)))
        val o = MonthOverview.of(october, CalendarFocus.SHIFTS, ScheduleResolver(Fixtures.all, weekdays, entries))
        // 22 weekdays in October 2026, each 8 hours, less half of the 7th.
        assertEquals(22, o.workDays)
        assertEquals(22 * 480 - 240, o.workMinutes)
        assertEquals(0.5, o.leaveDays, 0.0)
        assertEquals(2, o.holidayWorkDays)
        assertFalse(o.isEmpty)
    }

    @Test
    fun `a field month counts jobs once and every day they run`() {
        val jobs = listOf(
            Job(date = LocalDate.of(2026, 10, 30), endDate = LocalDate.of(2026, 11, 2), title = "욕실", amount = 1_000_000, deposit = 300_000, depositPaid = true),
            Job(date = LocalDate.of(2026, 10, 12), title = "수전", amount = 150_000, balancePaid = true),
            Job(date = LocalDate.of(2026, 9, 29), endDate = LocalDate.of(2026, 10, 1), title = "도배", amount = 500_000),
        )
        val o = MonthOverview.of(october, CalendarFocus.JOBS, ScheduleResolver(Fixtures.all, null, emptyList()), jobs = jobs)
        // The September job is that month's money but runs on 10/1 too.
        assertEquals(2, o.jobs)
        assertEquals(1 + 1 + 2, o.jobDays)
        assertEquals(1_150_000, o.jobEarned)
        assertEquals(450_000, o.jobReceived)
        assertEquals(700_000, o.jobOwed)
    }

    @Test
    fun `a shop month sums sales and finds the best day`() {
        val sales = listOf(
            DailySales(LocalDate.of(2026, 10, 2), 800_000, 200_000),
            DailySales(LocalDate.of(2026, 10, 3), 1_200_000, 300_000),
            DailySales(LocalDate.of(2026, 9, 30), 999_000, 0),
        )
        val o = MonthOverview.of(october, CalendarFocus.SALES, ScheduleResolver(Fixtures.all, null, emptyList()), sales = sales)
        assertEquals(2_000_000, o.sales)
        assertEquals(1_500_000, o.salesNet)
        assertEquals(2, o.openDays)
        assertEquals(1_000_000, o.salesPerDay)
        assertEquals(LocalDate.of(2026, 10, 3), o.bestDay)
        assertTrue(MonthOverview.of(october, CalendarFocus.SALES, ScheduleResolver(Fixtures.all, null, emptyList())).isEmpty)
    }

    @Test
    fun `the month list folds empty days and totals each week`() {
        val jobs = listOf(Job(date = LocalDate.of(2026, 10, 14), endDate = LocalDate.of(2026, 10, 15), title = "타일", amount = 600_000,
            start = LocalTime.of(9, 0)))
        val rows = MonthAgenda.of(october, CalendarFocus.JOBS, ScheduleResolver(Fixtures.all, null, emptyList()), jobs = jobs,
            memoCounts = mapOf(LocalDate.of(2026, 10, 20) to 2), weekStart = DayOfWeek.SUNDAY)
        // Week of 10/11~10/17: an empty run, the two job days, another empty run.
        val week = rows.dropWhile { it !is AgendaRow.Week || it.start != LocalDate.of(2026, 10, 11) }
        val header = week.first() as AgendaRow.Week
        assertEquals(1, header.jobs)
        assertEquals(600_000, header.jobEarned)
        assertEquals(AgendaRow.Gap(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 13)), week[1])
        assertEquals(LocalDate.of(2026, 10, 14), (week[2] as AgendaRow.Day).date)
        assertEquals(LocalDate.of(2026, 10, 15), (week[3] as AgendaRow.Day).date)
        assertEquals(2, (week[4] as AgendaRow.Gap).days)
        // A holiday and a memo keep their day; the first week starts on the 1st.
        assertTrue(rows.any { it is AgendaRow.Day && it.date == LocalDate.of(2026, 10, 9) && it.holiday == "한글날" })
        assertTrue(rows.any { it is AgendaRow.Day && it.date == LocalDate.of(2026, 10, 20) && it.memos == 2 })
        assertEquals(LocalDate.of(2026, 10, 1), (rows.first() as AgendaRow.Week).start)
        // A shift worker's 휴무 is a day to see; weekday work's empty weekend folds.
        val shifts = MonthAgenda.of(october, CalendarFocus.SHIFTS, ScheduleResolver(Fixtures.all, weekdays, emptyList()),
            weekStart = DayOfWeek.MONDAY)
        assertTrue(shifts.any { it is AgendaRow.Gap && it.from == LocalDate.of(2026, 10, 10) && it.days == 2 })
        // 22 weekdays and 개천절 on a Saturday.
        assertEquals(22 + 1, shifts.count { it is AgendaRow.Day })
    }

    @Test
    fun `a range covers both ends in either order`() {
        val a = LocalDate.of(2026, 10, 30)
        val b = LocalDate.of(2026, 11, 2)
        assertEquals(4, datesBetween(a, b).size)
        assertEquals(datesBetween(a, b), datesBetween(b, a))
        assertEquals(listOf(a), datesBetween(a, a))
    }
}
