package com.bamnat.core

import com.bamnat.core.Fixtures.day
import com.bamnat.core.Fixtures.night
import com.bamnat.core.Fixtures.off
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.model.WorkTime
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class ScheduleResolverTest {
    private val start = LocalDate.of(2026, 10, 1)
    private val rotation = WorkPattern.Rotation(start, listOf(day.id, day.id, night.id, night.id, off.id, null))

    @Test
    fun shiftMinutes() {
        assertEquals(480, day.workMinutes)
        assertEquals(480, night.workMinutes) // 22:00-07:00 = 9h minus 1h
        assertEquals(0, off.workMinutes)
        assertEquals(24 * 60, WorkTime.spanMinutes(LocalTime.of(8, 0), LocalTime.of(8, 0)))
        assertEquals(0, WorkTime.workMinutes(LocalTime.of(9, 0), LocalTime.of(10, 0), 120))
        assertTrue(WorkTime.isOvernight(LocalTime.of(22, 0), LocalTime.of(7, 0)))
        assertFalse(WorkTime.isOvernight(LocalTime.of(9, 0), LocalTime.of(18, 0)))
    }

    @Test
    fun rotationRepeatsForwardAndStopsBeforeStart() {
        val r = ScheduleResolver(Fixtures.all, rotation, emptyList())
        val ids = (0 until 12).map { r.resolve(start.plusDays(it.toLong())).shiftType?.id }
        assertEquals(listOf(1L, 1L, 3L, 3L, 4L, null, 1L, 1L, 3L, 3L, 4L, null), ids)

        val before = r.resolve(start.minusDays(1))
        assertNull(before.shiftType)
        assertEquals(DaySource.NONE, before.source)
        assertEquals(DaySource.PATTERN, r.resolve(start).source)
    }

    @Test
    fun rotationStepIndexIsStableFarInTheFuture() {
        val idx = rotation.stepIndexOn(start.plusDays(6L * 1000 + 2))
        assertEquals(2, idx)
    }

    @Test
    fun patternEndDateIsInclusive() {
        val p = rotation.copy(endDate = start.plusDays(2))
        val r = ScheduleResolver(Fixtures.all, p, emptyList())
        assertEquals(night.id, r.resolve(start.plusDays(2)).shiftType?.id)
        assertNull(r.resolve(start.plusDays(3)).shiftType)
    }

    @Test
    fun weeklyPattern() {
        val p = WorkPattern.Weekly.fromSteps(start, listOf(day.id, day.id, day.id, day.id, day.id, null, null))
        val r = ScheduleResolver(Fixtures.all, p, emptyList())
        // 2026-10-05 is a Monday
        assertEquals(DayOfWeek.MONDAY, LocalDate.of(2026, 10, 5).dayOfWeek)
        assertEquals(day.id, r.resolve(LocalDate.of(2026, 10, 5)).shiftType?.id)
        assertNull(r.resolve(LocalDate.of(2026, 10, 10)).shiftType)
        assertEquals(p, WorkPattern.Weekly.fromSteps(start, p.steps))
    }

    @Test
    fun overridesBeatPattern() {
        val entries = listOf(
            DayEntry(start, override = ShiftOverride.Off),
            DayEntry(start.plusDays(4), override = ShiftOverride.Shift(day.id)),
            DayEntry(start.plusDays(5), override = ShiftOverride.Shift(999)), // deleted type
        )
        val r = ScheduleResolver(Fixtures.all, rotation, entries)
        val d0 = r.resolve(start)
        assertTrue(d0.isOff)
        assertEquals(0, d0.totalMinutes)
        assertEquals(DaySource.OVERRIDE, d0.source)
        assertEquals(480, r.resolve(start.plusDays(4)).scheduledMinutes)
        assertNull(r.resolve(start.plusDays(5)).shiftType)
        assertEquals(0, r.resolve(start.plusDays(5)).totalMinutes)
    }

    @Test
    fun customTimesAndBreakForOneDay() {
        val entries = listOf(
            DayEntry(start, customStart = LocalTime.of(9, 0), customEnd = LocalTime.of(15, 0)),
            DayEntry(start.plusDays(1), customBreakMinutes = 0),
            DayEntry(start.plusDays(4), customStart = LocalTime.of(10, 0), customEnd = LocalTime.of(14, 0),
                customBreakMinutes = 0),
        )
        val r = ScheduleResolver(Fixtures.all, rotation, entries)
        assertEquals(300, r.resolve(start).scheduledMinutes) // 6h - day's 60m break
        assertEquals(540, r.resolve(start.plusDays(1)).scheduledMinutes) // break removed
        // Custom times on a day off are ignored: holiday work is recorded as hours instead.
        val offDayCustom = r.resolve(start.plusDays(4))
        assertTrue(offDayCustom.isOff)
        assertEquals(0, offDayCustom.totalMinutes)
        assertFalse(offDayCustom.hasCustomTimes)
    }

    @Test
    fun switchingToDayOffDropsCustomTimes() {
        val custom = DayEntry(start, customStart = LocalTime.of(10, 0), customEnd = LocalTime.of(18, 0))
        val forcedOff = ScheduleResolver(Fixtures.all, rotation, listOf(custom.copy(override = ShiftOverride.Off)))
        assertEquals(0, forcedOff.resolve(start).totalMinutes)
        val offType = ScheduleResolver(Fixtures.all, rotation, listOf(custom.copy(override = ShiftOverride.Shift(off.id))))
        assertEquals(0, offType.resolve(start).totalMinutes)
        assertTrue(offType.resolve(start).isOff)
        val stillWork = ScheduleResolver(Fixtures.all, rotation, listOf(custom))
        assertEquals(420, stillWork.resolve(start).scheduledMinutes)
        assertTrue(stillWork.resolve(start).hasCustomTimes)
    }

    @Test
    fun overtimeAndHolidayWorkAreCounted() {
        val entries = listOf(
            DayEntry(start, overtimeMinutes = 90),
            DayEntry(start.plusDays(5), holidayWorkMinutes = 300),
            DayEntry(start.plusDays(4), holidayWorkMinutes = -10), // never negative
        )
        val r = ScheduleResolver(Fixtures.all, rotation, entries)
        assertEquals(570, r.resolve(start).totalMinutes)
        val holiday = r.resolve(start.plusDays(5))
        assertTrue(holiday.isOff)
        assertTrue(holiday.isHolidayWork)
        assertEquals(300, holiday.totalMinutes)
        assertEquals(0, r.resolve(start.plusDays(4)).totalMinutes)
    }

    @Test
    fun noPatternMeansEmptyCalendar() {
        val r = ScheduleResolver(Fixtures.all, null, emptyList())
        val d = r.resolve(start)
        assertNull(d.shiftType)
        assertEquals(DaySource.NONE, d.source)
        assertTrue(r.resolveRange(start, start.minusDays(1)).isEmpty())
        assertEquals(31, r.resolveRange(start, LocalDate.of(2026, 10, 31)).size)
    }

    @Test
    fun emptyEntryDetection() {
        assertTrue(DayEntry(start).isEmpty)
        assertFalse(DayEntry(start, alarmSkipped = true).isEmpty)
        assertFalse(DayEntry(start, override = ShiftOverride.Off).isEmpty)
    }
}
