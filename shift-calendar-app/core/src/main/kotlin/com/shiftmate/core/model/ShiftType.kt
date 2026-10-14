package com.shiftmate.core.model

import java.time.Duration
import java.time.LocalTime

/**
 * A kind of shift the user works, e.g. 주간 09:00–18:00 or 야간 22:00–07:00.
 *
 * A shift whose [end] is not after its [start] runs past midnight (overnight shift).
 * Equal start and end means a full 24-hour shift.
 */
data class ShiftType(
    val id: Long,
    val name: String,
    /** One or two characters drawn inside calendar cells, e.g. "주", "D". */
    val label: String,
    val colorArgb: Long,
    val start: LocalTime?,
    val end: LocalTime?,
    val breakMinutes: Int = 0,
    /** Off-duty type (휴무, 비번). Off types never count toward work hours. */
    val isOff: Boolean = false,
    /** Turns all of this shift's alarms on or off at once. */
    val alarmEnabled: Boolean = false,
    /** Alarms before [start]; a shift can ring several times (e.g. 90 and 60 minutes before). */
    val alarms: List<AlarmSpec> = listOf(AlarmSpec(60)),
    /** Alarm sound for this shift; null uses the default sound from settings. */
    val alarmSoundUri: String? = null,
    val sortOrder: Int = 0,
) {
    val hasTimes: Boolean get() = !isOff && start != null && end != null

    /** Length from start to end, 1 minute to 24 hours (equal start and end is a 24-hour shift). */
    val durationMinutes: Int
        get() = if (!hasTimes) 0 else WorkTime.spanMinutes(start!!, end!!)

    /** Alarms that will actually ring, earliest first. */
    val activeAlarms: List<AlarmSpec>
        get() = if (!alarmEnabled || !hasTimes) emptyList()
        else alarms.filter { it.enabled }.distinctBy { it.offsetMinutes }.sortedByDescending { it.offsetMinutes }

    /** Same start, new length. 24 hours ends at the start time the next day. */
    fun withDuration(minutes: Int): ShiftType {
        val s = start ?: return this
        val m = minutes.coerceIn(WorkTime.MIN_DURATION_MINUTES, WorkTime.MINUTES_PER_DAY)
        return copy(end = s.plusMinutes(m.toLong()))
    }

    /** Paid working minutes of one occurrence of this shift. */
    val workMinutes: Int
        get() = if (!hasTimes) 0 else WorkTime.workMinutes(start!!, end!!, breakMinutes)
}

/** One alarm of a shift type, [offsetMinutes] before the shift starts. */
data class AlarmSpec(val offsetMinutes: Int, val enabled: Boolean = true)

object WorkTime {
    const val MINUTES_PER_DAY = 24 * 60
    const val MIN_DURATION_MINUTES = 30

    /** Span between [start] and [end] in minutes, treating end <= start as crossing midnight. */
    fun spanMinutes(start: LocalTime, end: LocalTime): Int {
        val raw = Duration.between(start, end).toMinutes().toInt()
        return if (raw <= 0) raw + MINUTES_PER_DAY else raw
    }

    /** Span minus break, never negative. */
    fun workMinutes(start: LocalTime, end: LocalTime, breakMinutes: Int): Int =
        (spanMinutes(start, end) - breakMinutes.coerceAtLeast(0)).coerceAtLeast(0)

    fun isOvernight(start: LocalTime, end: LocalTime): Boolean = !end.isAfter(start)
}
