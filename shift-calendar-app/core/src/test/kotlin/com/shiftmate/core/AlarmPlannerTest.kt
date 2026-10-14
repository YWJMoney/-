package com.shiftmate.core

import com.shiftmate.core.Fixtures.day
import com.shiftmate.core.Fixtures.evening
import com.shiftmate.core.Fixtures.night
import com.shiftmate.core.alarm.AlarmPlanner
import com.shiftmate.core.model.DayEntry
import com.shiftmate.core.model.ShiftOverride
import com.shiftmate.core.model.ShiftType
import com.shiftmate.core.model.WorkPattern
import com.shiftmate.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class AlarmPlannerTest {
    private val start = LocalDate.of(2026, 10, 1)
    // 주 주 야 야 휴 휴
    private val pattern = WorkPattern.Rotation(start, listOf(day.id, day.id, night.id, night.id, null, null))

    private fun resolver(entries: List<DayEntry> = emptyList(), types: List<ShiftType> = Fixtures.all) =
        ScheduleResolver(types, pattern, entries)

    @Test
    fun nextAlarmIsOffsetBeforeShiftStart() {
        val a = AlarmPlanner.nextAlarm(resolver(), LocalDateTime.of(2026, 10, 1, 0, 0))!!
        assertEquals(LocalDateTime.of(2026, 10, 1, 8, 0), a.triggerAt)
        assertEquals(day.id, a.shiftTypeId)
        assertEquals(start, a.shiftDate)
    }

    @Test
    fun alarmExactlyAtNowIsSkipped() {
        val a = AlarmPlanner.nextAlarm(resolver(), LocalDateTime.of(2026, 10, 1, 8, 0))!!
        assertEquals(LocalDateTime.of(2026, 10, 2, 8, 0), a.triggerAt)
    }

    @Test
    fun nightShiftAlarmUsesItsOwnOffset() {
        val a = AlarmPlanner.nextAlarm(resolver(), LocalDateTime.of(2026, 10, 2, 9, 0))!!
        assertEquals(LocalDateTime.of(2026, 10, 3, 20, 30), a.triggerAt)
        assertEquals(night.id, a.shiftTypeId)
    }

    @Test
    fun skipsDaysOffAndSkippedAlarmsAcrossCycle() {
        val entries = listOf(DayEntry(LocalDate.of(2026, 10, 7), alarmSkipped = true))
        // After the 10/4 night shift alarm: 10/5, 10/6 off, 10/7 skipped → 10/8 08:00.
        val a = AlarmPlanner.nextAlarm(resolver(entries), LocalDateTime.of(2026, 10, 4, 21, 0))!!
        assertEquals(LocalDateTime.of(2026, 10, 8, 8, 0), a.triggerAt)
    }

    @Test
    fun alarmOnPreviousEveningForEarlyShift() {
        val early = ShiftType(10, "새벽", "새", 0, LocalTime.of(0, 30), LocalTime.of(8, 30),
            alarmEnabled = true, alarmOffsetMinutes = 60)
        val entries = listOf(DayEntry(LocalDate.of(2026, 10, 6), override = ShiftOverride.Shift(early.id)))
        val r = resolver(entries, Fixtures.all + early)
        val a = AlarmPlanner.nextAlarm(r, LocalDateTime.of(2026, 10, 5, 12, 0))!!
        assertEquals(LocalDateTime.of(2026, 10, 5, 23, 30), a.triggerAt)
        assertEquals(LocalDate.of(2026, 10, 6), a.shiftDate)
    }

    @Test
    fun largerOffsetOnLaterDateCanRingFirst() {
        val far = ShiftType(11, "출장", "출", 0, LocalTime.of(6, 0), LocalTime.of(12, 0),
            alarmEnabled = true, alarmOffsetMinutes = 23 * 60)
        val entries = listOf(DayEntry(LocalDate.of(2026, 10, 3), override = ShiftOverride.Shift(far.id)))
        val r = resolver(entries, Fixtures.all + far)
        // 10/2 day shift alarm at 08:00; 10/3 06:00 minus 23h = 10/2 07:00 comes first.
        val a = AlarmPlanner.nextAlarm(r, LocalDateTime.of(2026, 10, 1, 9, 0))!!
        assertEquals(LocalDateTime.of(2026, 10, 2, 7, 0), a.triggerAt)
        assertEquals(far.id, a.shiftTypeId)
    }

    @Test
    fun customTimesMoveTheAlarm() {
        val entries = listOf(DayEntry(LocalDate.of(2026, 10, 2), customStart = LocalTime.of(10, 0),
            customEnd = LocalTime.of(16, 0)))
        val a = AlarmPlanner.nextAlarm(resolver(entries), LocalDateTime.of(2026, 10, 1, 9, 0))!!
        assertEquals(LocalDateTime.of(2026, 10, 2, 9, 0), a.triggerAt)
    }

    @Test
    fun noAlarmWhenNothingEnabled() {
        val types = Fixtures.all.map { it.copy(alarmEnabled = false) }
        assertNull(AlarmPlanner.nextAlarm(resolver(types = types), LocalDateTime.of(2026, 10, 1, 0, 0)))
        // Evening type has alarms off in the fixtures.
        val eveningOnly = WorkPattern.Rotation(start, listOf(evening.id))
        assertNull(AlarmPlanner.nextAlarm(ScheduleResolver(Fixtures.all, eveningOnly, emptyList()),
            LocalDateTime.of(2026, 10, 1, 0, 0)))
    }

    @Test
    fun noAlarmBeyondLookahead() {
        val late = WorkPattern.Rotation(LocalDate.of(2027, 6, 1), listOf(day.id))
        val r = ScheduleResolver(Fixtures.all, late, emptyList())
        assertNull(AlarmPlanner.nextAlarm(r, LocalDateTime.of(2026, 10, 1, 0, 0)))
        assertEquals(LocalDateTime.of(2027, 6, 1, 8, 0),
            AlarmPlanner.nextAlarm(r, LocalDateTime.of(2027, 5, 1, 0, 0))!!.triggerAt)
    }
}
