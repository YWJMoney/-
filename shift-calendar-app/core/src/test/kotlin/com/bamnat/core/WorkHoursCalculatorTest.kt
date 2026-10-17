package com.bamnat.core

import com.bamnat.core.Fixtures.day
import com.bamnat.core.Fixtures.long
import com.bamnat.core.Fixtures.night
import com.bamnat.core.hours.HourLimits
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class WorkHoursCalculatorTest {
    // 2026-10-05 is a Monday.
    private val monday = LocalDate.of(2026, 10, 5)

    private fun special(minutes: Int) = ExtraEntry(ExtraKind.SPECIAL_ID, minutes = minutes)

    /** Monday–Friday 주간 (8h), weekends off: 40h a week. */
    private fun fiveDay(start: LocalDate = LocalDate.of(2026, 1, 1)) =
        WorkPattern.Weekly.fromSteps(start, listOf(day.id, day.id, day.id, day.id, day.id, null, null))

    private fun calc(
        pattern: WorkPattern?,
        entries: List<DayEntry> = emptyList(),
        weekStart: DayOfWeek = DayOfWeek.MONDAY,
        limits: HourLimits = HourLimits(),
    ) = WorkHoursCalculator(ScheduleResolver(Fixtures.all, pattern, entries), weekStart, limits)

    @Test
    fun weekStartsOnConfiguredDay() {
        val c = calc(fiveDay())
        assertEquals(monday, c.weekStartOf(monday))
        assertEquals(monday, c.weekStartOf(monday.plusDays(6)))
        val sundayCalc = calc(fiveDay(), weekStart = DayOfWeek.SUNDAY)
        assertEquals(monday.minusDays(1), sundayCalc.weekStartOf(monday))
    }

    @Test
    fun plainFortyHourWeek() {
        val w = calc(fiveDay()).week(monday.plusDays(2))
        assertEquals(monday, w.start)
        assertEquals(monday.plusDays(6), w.end)
        assertEquals(40 * 60, w.totalMinutes)
        assertEquals(5, w.workDays)
        assertFalse(w.overLimit)
    }

    @Test
    fun extraWorkIncludedInWeek() {
        val entries = listOf(
            DayEntry(monday, extras = listOf(ExtraEntry(ExtraKind.OVERTIME_ID, minutes = 120))),
            DayEntry(monday.plusDays(5), extras = listOf(special(6 * 60))), // Saturday
        )
        val w = calc(fiveDay(), entries).week(monday)
        assertEquals((40 + 2 + 6) * 60, w.totalMinutes)
        assertEquals(40 * 60, w.scheduledMinutes)
        assertEquals(480, w.extraMinutes)
        assertEquals(360, w.restDayExtraMinutes)
        assertEquals(6, w.workDays)
        assertEquals(0, w.overLegalMinutes)
    }

    @Test
    fun weeklyLimitIsStrictlyGreaterThan64() {
        // 7 x 12h shifts = 84h; with 4 x 12h + 16h holiday = 64h exactly.
        val p = WorkPattern.Rotation(LocalDate.of(2026, 1, 1), listOf(long.id))
        assertTrue(calc(p).week(monday).overLimit)

        val exactly64 = WorkPattern.Weekly.fromSteps(LocalDate.of(2026, 1, 1),
            listOf(long.id, long.id, long.id, long.id, null, null, null))
        val entries = listOf(DayEntry(monday.plusDays(5), extras = listOf(special(16 * 60))))
        val w = calc(exactly64, entries).week(monday)
        assertEquals(64 * 60, w.totalMinutes)
        assertFalse(w.overLimit)

        val oneMore = entries + DayEntry(monday.plusDays(6), extras = listOf(special(1)))
        assertTrue(calc(exactly64, oneMore).week(monday).overLimit)
    }

    @Test
    fun customLimits() {
        val w = calc(fiveDay(), limits = HourLimits(weeklyLimitHours = 39)).week(monday)
        assertTrue(w.overLimit)
    }

    @Test
    fun overnightShiftCountsOnStartDate() {
        // Night shift only on Sunday 2026-10-11 (22:00 → Mon 07:00) belongs to the week of 10-05.
        val entries = listOf(DayEntry(LocalDate.of(2026, 10, 11), override = ShiftOverride.Shift(night.id)))
        val c = calc(null, entries)
        assertEquals(480, c.week(monday).totalMinutes)
        assertEquals(0, c.week(monday.plusWeeks(1)).totalMinutes)
    }

    @Test
    fun monthTotalsOnlyCountDaysInTheMonth() {
        val m = calc(fiveDay()).month(YearMonth.of(2026, 10))
        // October 2026 has 22 weekdays.
        assertEquals(22, m.workDays)
        assertEquals(22 * 8 * 60, m.totalMinutes)
        // Weeks touching October: starting 9/28, 10/5, 10/12, 10/19, 10/26.
        assertEquals(5, m.weeks.size)
        assertEquals(LocalDate.of(2026, 9, 28), m.weeks.first().start)
        assertEquals(LocalDate.of(2026, 10, 26), m.weeks.last().start)
        assertTrue(m.weeks.all { it.totalMinutes == 40 * 60 })
    }

    @Test
    fun monthCountsWorkOnDaysOff() {
        val entries = listOf(
            DayEntry(LocalDate.of(2026, 10, 3), extras = listOf(special(240))),
            DayEntry(LocalDate.of(2026, 10, 4), extras = listOf(ExtraEntry(ExtraKind.COVER_ID, minutes = 180))),
            DayEntry(LocalDate.of(2026, 11, 1), extras = listOf(special(600))), // other month
        )
        val m = calc(fiveDay(), entries).month(YearMonth.of(2026, 10))
        assertEquals(2, m.restDayWorkDays)
        assertEquals(420, m.restDayExtraMinutes)
        assertEquals(420, m.extraMinutes)
        assertEquals(22 * 480, m.scheduledMinutes)
        assertEquals(mapOf(ExtraKind.SPECIAL_ID to 240, ExtraKind.COVER_ID to 180), m.extraByKind)
        assertEquals(mapOf(ExtraKind.SPECIAL_ID to 1, ExtraKind.COVER_ID to 1), m.extraCountByKind)
        assertEquals(22 * 480 + 420, m.totalMinutes)
    }

    @Test
    fun threeMonthAverageOfFortyHourWeeks() {
        val t = calc(fiveDay()).threeMonths(YearMonth.of(2026, 10))
        assertEquals(YearMonth.of(2026, 8), t.firstMonth)
        assertEquals(LocalDate.of(2026, 8, 1), t.start)
        assertEquals(LocalDate.of(2026, 10, 31), t.end)
        assertEquals(92, t.days)
        // Aug 21 + Sep 22 + Oct 22 weekdays = 65 days x 8h.
        assertEquals(65 * 480, t.totalMinutes)
        assertEquals(65 * 480 / (92 / 7.0), t.averageWeeklyMinutes, 1e-9)
        assertEquals(65 * 480 / 3.0, t.averageMonthlyMinutes, 1e-9)
        assertFalse(t.overLimit)
    }

    @Test
    fun threeMonthAverageWarnsAbove52IncludingHolidayWork() {
        // 40h weeks plus 13h of holiday work every Saturday = 53h most weeks.
        val start = LocalDate.of(2026, 8, 1)
        val saturdays = generateSequence(LocalDate.of(2026, 8, 1)) { it.plusWeeks(1) } // 8/1 is Saturday
            .takeWhile { !it.isAfter(LocalDate.of(2026, 10, 31)) }
            .map { DayEntry(it, extras = listOf(special(13 * 60))) }
            .toList()
        assertEquals(DayOfWeek.SATURDAY, start.dayOfWeek)
        val t = calc(fiveDay(), saturdays).threeMonths(YearMonth.of(2026, 10))
        assertEquals(14, saturdays.size)
        val expected = (65 * 480 + 14 * 780) / (92 / 7.0)
        assertEquals(expected, t.averageWeeklyMinutes, 1e-9)
        assertTrue(expected > 52 * 60)
        assertTrue(t.overLimit)

        // Without the holiday work the same schedule stays under the limit.
        assertFalse(calc(fiveDay()).threeMonths(YearMonth.of(2026, 10)).overLimit)
    }

    @Test
    fun threeMonthWindowCrossesYearBoundary() {
        val t = calc(null).threeMonths(YearMonth.of(2026, 1))
        assertEquals(LocalDate.of(2025, 11, 1), t.start)
        assertEquals(LocalDate.of(2026, 1, 31), t.end)
        assertEquals(92, t.days)
        assertEquals(0.0, t.averageWeeklyMinutes, 0.0)
        assertFalse(t.overLimit)
    }

    @Test
    fun formatting() {
        assertEquals("0시간", HoursFormat.korean(0))
        assertEquals("8시간", HoursFormat.korean(480))
        assertEquals("30분", HoursFormat.korean(30))
        assertEquals("41시간 30분", HoursFormat.korean(41 * 60 + 30))
        assertEquals("8h", HoursFormat.short(480))
        assertEquals("7.5h", HoursFormat.short(450))
        assertEquals("0.3h", HoursFormat.short(20))
        assertEquals("52.0", HoursFormat.decimalHours(52 * 60.0))
        assertEquals("52.3", HoursFormat.decimalHours(52 * 60.0 + 18))
    }
}
