package com.bamnat.core

import com.bamnat.core.Fixtures.day
import com.bamnat.core.Fixtures.night
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.PatternTimeline
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class PatternTimelineTest {
    private val d = day.id
    private val n = night.id
    // 주주휴휴야야 starting on Sep 1.
    private val old = WorkPattern.Rotation(LocalDate.of(2026, 9, 1), listOf(d, d, null, null, n, n))

    @Test
    fun teamChangeKeepsEveryDayBeforeIt() {
        val dec1 = LocalDate.of(2026, 12, 1)
        // 휴휴야야주주, with its first day on Dec 1.
        val new = WorkPattern.Rotation(dec1, listOf(null, null, n, n, d, d))
        val timeline = PatternTimeline.changeFrom(listOf(old), new, dec1)
        val before = ScheduleResolver(Fixtures.all, old, emptyList())
        val after = ScheduleResolver(Fixtures.all, timeline, emptyList())

        var date = LocalDate.of(2026, 9, 1)
        while (date.isBefore(dec1)) {
            assertEquals(date.toString(), before.resolve(date).shiftType, after.resolve(date).shiftType)
            date = date.plusDays(1)
        }
        assertNull(after.resolve(dec1).shiftType)
        assertEquals(night, after.resolve(dec1.plusDays(2)).shiftType)
        assertEquals(day, after.resolve(dec1.plusDays(10)).shiftType)
        assertEquals(LocalDate.of(2026, 11, 30), timeline.first().endDate)
    }

    @Test
    fun aLaterChangeReplacesOnlyWhatComesAfterIt() {
        val dec1 = LocalDate.of(2026, 12, 1)
        val first = PatternTimeline.changeFrom(listOf(old), WorkPattern.Rotation(dec1, listOf(d)), dec1)
        val nov1 = LocalDate.of(2026, 11, 1)
        val second = PatternTimeline.changeFrom(first, WorkPattern.Rotation(nov1, listOf(n)), nov1)
        assertEquals(2, second.size)
        assertEquals(night, ScheduleResolver(Fixtures.all, second, emptyList()).resolve(dec1.plusDays(5)).shiftType)
    }

    @Test
    fun aFirstPatternFillsTheLastTwoYears() {
        val today = LocalDate.of(2026, 10, 14)
        val p = PatternTimeline.first(WorkPattern.Rotation(today, listOf(d, d, null, null, n, n)), today)
        val r = ScheduleResolver(Fixtures.all, p, emptyList())
        // The order stays lined up with the chosen first day.
        assertEquals(day, r.resolve(today).shiftType)
        assertEquals(night, r.resolve(today.minusDays(2)).shiftType)
        assertEquals(day, r.resolve(today.minusDays(6 * 100)).shiftType)
        assertEquals(today.minusYears(2), p.startDate)
    }

    @Test
    fun extraWorkKindIsKeptOnTheDay() {
        val date = LocalDate.of(2026, 9, 3)
        val entry = DayEntry(date, holidayWorkMinutes = 8 * 60, extraWork = ExtraWork.COVER)
        val resolved = ScheduleResolver(Fixtures.all, old, listOf(entry)).resolve(date)
        assertEquals(ExtraWork.COVER, resolved.extraWork)
        assertEquals(8 * 60, resolved.extraMinutes)
        assertEquals(ExtraWork.SPECIAL, ExtraWork.fromCode(ExtraWork.SPECIAL.code))
    }
}
