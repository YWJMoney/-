package com.bamnat.core

import com.bamnat.core.model.AlarmShift
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.ShiftType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalTime

/** [SET-05] Moving every shift's alarms at once. */
class AlarmShiftTest {
    private val day = ShiftType(1, "주간", "주", 0xFF2F80ED, LocalTime.of(8, 0), LocalTime.of(20, 0),
        alarms = listOf(AlarmSpec(90), AlarmSpec(60), AlarmSpec(30, enabled = false)))
    private val night = ShiftType(2, "야간", "야", 0xFF6C5CE7, LocalTime.of(20, 0), LocalTime.of(8, 0), alarms = listOf(AlarmSpec(60)))
    private val off = ShiftType(3, "휴무", "휴", 0xFF8E9AAF, null, null, isOff = true)

    @Test fun tenMinutesEarlierMovesEveryAlarmKeepingWhichAreOn() {
        val moved = AlarmShift.move(day, 10)
        assertEquals(listOf(AlarmSpec(100), AlarmSpec(70), AlarmSpec(40, enabled = false)), moved.alarms)
    }

    @Test fun laterStopsAtTheStartAndTwoOnOneMinuteBecomeOne() {
        val moved = AlarmShift.move(day, -70)
        assertEquals(listOf(AlarmSpec(20), AlarmSpec(0)), moved.alarms)
    }

    @Test fun neverMoreThanTwelveHoursBefore() {
        assertEquals(AlarmShift.MAX_BEFORE, AlarmShift.move(night, 24 * 60).alarms.single().offsetMinutes)
    }

    @Test fun offDaysAndNoMoveStayAsTheyAre() {
        assertSame(off, AlarmShift.move(off, 30))
        assertSame(day, AlarmShift.move(day, 0))
        assertEquals(listOf(1L, 2L), AlarmShift.moveAll(listOf(day, night, off), 15).map { it.id })
    }
}
