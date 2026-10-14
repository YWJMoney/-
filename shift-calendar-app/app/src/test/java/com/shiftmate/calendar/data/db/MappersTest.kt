package com.shiftmate.calendar.data.db

import com.shiftmate.core.memo.ChecklistItem
import com.shiftmate.core.memo.Memo
import com.shiftmate.core.model.DayEntry
import com.shiftmate.core.model.ShiftOverride
import com.shiftmate.core.model.ShiftType
import com.shiftmate.core.model.WorkPattern
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
        )
        for (e in entries) assertEquals(e, e.toEntity().toModel())
    }

    @Test
    fun shiftTypesRoundTrip() {
        val t = ShiftType(5, "야간", "야", 0xFF6C5CE7, LocalTime.of(22, 0), LocalTime.of(7, 0), 60,
            alarmEnabled = true, alarmOffsetMinutes = 90, alarmSoundUri = "content://x", sortOrder = 2)
        assertEquals(t, t.toEntity().toModel())
        val off = ShiftType(6, "휴무", "휴", 0xFF8E9AAF, null, null, isOff = true)
        assertEquals(off, off.toEntity().toModel())
    }

    @Test
    fun memosRoundTrip() {
        val m = Memo(3, date, "제목", "", listOf(ChecklistItem("a\nb", true), ChecklistItem("c")), true, 2, true, 10, 20)
        assertEquals(m, m.toEntity().toModel())
        val plain = Memo(4, null, "", "본문", emptyList(), false, 0, false, 1, 2)
        assertEquals(plain, plain.toEntity().toModel())
    }
}
