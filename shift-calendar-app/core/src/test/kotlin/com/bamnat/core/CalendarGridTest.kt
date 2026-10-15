package com.bamnat.core

import com.bamnat.core.schedule.CalendarGrid
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

class CalendarGridTest {
    @Test
    fun `month weeks cover the whole month and start on the chosen day`() {
        // October 2026 starts on a Thursday and ends on a Saturday.
        val monday = CalendarGrid.monthWeeks(YearMonth.of(2026, 10), DayOfWeek.MONDAY)
        assertEquals(5, monday.size)
        assertEquals(LocalDate.of(2026, 9, 28), monday.first().first())
        assertEquals(LocalDate.of(2026, 11, 1), monday.last().last())
        assertEquals(true, monday.all { it.size == 7 && it.first().dayOfWeek == DayOfWeek.MONDAY })

        val sunday = CalendarGrid.monthWeeks(YearMonth.of(2026, 10), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 9, 27), sunday.first().first())
        assertEquals(LocalDate.of(2026, 10, 31), sunday.last().last())
    }

    @Test
    fun `a month can need six weeks`() {
        // August 2026 starts on a Saturday and has 31 days.
        assertEquals(6, CalendarGrid.monthWeeks(YearMonth.of(2026, 8), DayOfWeek.MONDAY).size)
    }

    @Test
    fun `week is seven consecutive days`() {
        val w = CalendarGrid.week(LocalDate.of(2026, 12, 29))
        assertEquals(7, w.size)
        assertEquals(LocalDate.of(2027, 1, 4), w.last())
    }
}
