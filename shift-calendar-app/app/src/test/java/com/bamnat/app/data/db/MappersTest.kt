package com.bamnat.app.data.db

import com.bamnat.core.memo.ChecklistItem
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MappersTest {
    private val date = LocalDate.of(2026, 10, 14)

    @Test
    fun stepsRoundTripWithDaysOff() {
        val steps = listOf(1L, null, 3L, null, null)
        assertEquals(steps, decodeSteps(encodeSteps(steps)))
        assertEquals(listOf<Long?>(null), decodeSteps(encodeSteps(listOf(null))))
    }

    @Test
    fun patternsRoundTrip() {
        val rotation = WorkPattern.Rotation(date, listOf(1L, 1L, 2L, null), date.plusMonths(3))
        assertEquals(rotation, rotation.toEntity().toModel())
        // A period that starts after its step-0 day keeps both dates.
        val shifted = rotation.copy(startDate = date.plusDays(40))
        assertEquals(shifted, shifted.toEntity().toModel())
        val weekly = WorkPattern.Weekly.fromSteps(date, listOf(1L, 1L, 1L, 1L, 1L, null, null))
        assertEquals(weekly, weekly.toEntity().toModel())
        // A corrupt weekly row is ignored rather than crashing the calendar.
        assertNull(weekly.toEntity().copy(steps = "1,2").toModel())
    }

    @Test
    fun dayEntriesRoundTrip() {
        val entries = listOf(
            DayEntry(date, ShiftOverride.Off),
            DayEntry(date, ShiftOverride.Shift(7), LocalTime.of(10, 30), LocalTime.of(2, 15), 45, 30, 0, true),
            DayEntry(date, holidayWorkMinutes = 300),
            DayEntry(date, customAlarms = listOf(DayAlarm(LocalTime.of(6, 10)), DayAlarm(LocalTime.of(6, 40), false))),
            DayEntry(date, customAlarms = emptyList()),
            DayEntry(date, holidayWorkMinutes = 480, extraWork = ExtraWork.COVER),
        )
        for (e in entries) assertEquals(e, e.toEntity().toModel())
    }

    @Test
    fun shiftTypesRoundTrip() {
        val t = ShiftType(5, "야간", "야", 0xFF6C5CE7, LocalTime.of(22, 0), LocalTime.of(7, 0), 60,
            alarmEnabled = true, alarms = listOf(AlarmSpec(90), AlarmSpec(30, enabled = false)), alarmSoundUri = "content://x", sortOrder = 2)
        assertEquals(t, t.toEntity().toModel())
        // Rows from before several alarms existed still carry their single alarm.
        val legacy = t.toEntity().copy(alarmOffsets = "", alarmOffsetMinutes = 45).toModel()
        assertEquals(listOf(AlarmSpec(45)), legacy.alarms)
        val off = ShiftType(6, "휴무", "휴", 0xFF8E9AAF, null, null, isOff = true)
        assertEquals(off, off.toEntity().toModel())
    }

    @Test
    fun memosRoundTrip() {
        val m = Memo(3, date, "제목", "", listOf(ChecklistItem("a\nb", true), ChecklistItem("c")), true, 2, true, 10, 20)
        assertEquals(m, m.toEntity().toModel())
        val plain = Memo(4, null, "", "본문", emptyList(), false, 0, false, 1, 2)
        assertEquals(plain, plain.toEntity().toModel())
        val trashed = plain.copy(deletedAtMillis = 99)
        assertEquals(trashed, trashed.toEntity().toModel())
    }
}
