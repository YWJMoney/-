package com.bamnat.app.ui.calendar

import com.bamnat.core.model.Attendance
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** [HRLY-08][DAYW-03] Work run past midnight is clocked out on the day it started, from either day's sheet. */
class LateClockOutTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val yesterday = today.minusDays(1)

    private fun day(date: LocalDate, from: Int?, to: Int?, absent: Boolean = false): ResolvedDay {
        val works = from != null && to != null
        val type = ShiftType(1, if (works) "근무" else "휴무", "근", 0, from?.let { LocalTime.of(it, 0) }, to?.let { LocalTime.of(it, 0) }, isOff = !works)
        return ResolvedDay(
            date, type, DaySource.PATTERN, type.start, type.end, 0, scheduledMinutes = 0, alarmSkipped = false,
            entry = if (absent) DayEntry(date, attendance = Attendance.ABSENT) else null,
        )
    }

    private fun at(date: LocalDate, h: Int, m: Int = 0) = LocalDateTime.of(date, LocalTime.of(h, m))

    @Test fun anOvernightShiftIsClockedOutOnTheDayItStarted() {
        val night = day(yesterday, 18, 2)
        val off = day(today, null, null)
        // From today's sheet, with no work today: 어제 퇴근.
        assertEquals(yesterday, clockOutDay(off, today, at(today, 1, 30)) { night }?.date)
        // From yesterday's own sheet.
        assertEquals(yesterday, clockOutDay(night, today, at(today, 1, 30)) { night }?.date)
        // Long after the planned end it is today's again (and today has no work).
        assertNull(clockOutDay(off, today, at(today, 7)) { night })
        assertNull(clockOutDay(night, today, at(today, 7)) { night })
    }

    @Test fun todaysShiftTakesOverOnceItHasStarted() {
        val evening = day(yesterday, 17, 23)
        val early = day(today, 0, 4)
        // Running late past midnight, before today's work starts: still yesterday.
        assertEquals(yesterday, clockOutDay(day(today, 9, 15), today, at(today, 0, 40)) { evening }?.date)
        // Today's work already running: today's.
        assertEquals(today, clockOutDay(early, today, at(today, 1)) { evening }?.date)
    }

    @Test fun anAbsentOrFreeYesterdayIsLeftAlone() {
        val work = day(today, 9, 18)
        assertEquals(today, clockOutDay(work, today, at(today, 1)) { day(yesterday, 18, 2, absent = true) }?.date)
        assertEquals(today, clockOutDay(work, today, at(today, 1)) { day(yesterday, null, null) }?.date)
        assertNull(clockOutDay(day(today.minusDays(2), 18, 2), today, at(today, 1)) { day(yesterday, null, null) })
    }

    @Test fun aDayWorkersLeaveNowAfterMidnightEndsYesterday() {
        val work = day(yesterday, 9, 18)
        val tomorrowWork = day(today, 9, 18)
        assertEquals(yesterday, leaveNowDay(WorkStyle.DAY, tomorrowWork, today, at(today, 1)) { work }?.date)
        assertEquals(yesterday, leaveNowDay(WorkStyle.DAY, work, today, at(today, 1)) { work }?.date)
        // In the morning it is today's again; yesterday's sheet has none.
        assertEquals(today, leaveNowDay(WorkStyle.DAY, tomorrowWork, today, at(today, 8)) { work }?.date)
        assertNull(leaveNowDay(WorkStyle.DAY, work, today, at(today, 8)) { work })
        // Only on a day calendar.
        assertNull(leaveNowDay(WorkStyle.SHIFT, tomorrowWork, today, at(today, 1)) { work })
    }
}
