package com.bamnat.core

import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.model.ShiftType
import java.time.LocalTime

object Fixtures {
    val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(9, 0), LocalTime.of(18, 0), breakMinutes = 60,
        alarmEnabled = true, alarms = listOf(AlarmSpec(60)))
    val evening = ShiftType(2, "오후", "오", 0xFFFB8C00, LocalTime.of(14, 0), LocalTime.of(22, 0), breakMinutes = 30)
    val night = ShiftType(3, "야간", "야", 0xFF5E35B1, LocalTime.of(22, 0), LocalTime.of(7, 0), breakMinutes = 60,
        alarmEnabled = true, alarms = listOf(AlarmSpec(90)))
    val off = ShiftType(4, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    /** 12-hour shift without break, 12h work. */
    val long = ShiftType(5, "장주간", "장", 0xFF00897B, LocalTime.of(7, 0), LocalTime.of(19, 0))

    val all = listOf(day, evening, night, off, long)
}
