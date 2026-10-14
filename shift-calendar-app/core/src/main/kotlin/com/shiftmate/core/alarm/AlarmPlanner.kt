package com.shiftmate.core.alarm

import com.shiftmate.core.schedule.ScheduleResolver
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One upcoming shift alarm. */
data class PlannedAlarm(
    val triggerAt: LocalDateTime,
    val shiftDate: LocalDate,
    val shiftStart: LocalTime,
    val shiftTypeId: Long,
    val shiftName: String,
    val offsetMinutes: Int,
    val soundUri: String?,
)

/**
 * Finds the next shift alarm. Only one alarm is registered with the system at a time;
 * once it rings, the next one is planned from its trigger time. This keeps the system
 * alarm in sync with any schedule edit without tracking dozens of pending intents.
 */
object AlarmPlanner {
    /** How far ahead to search. Covers long vacations and sparse part-time schedules. */
    const val LOOKAHEAD_DAYS = 120L

    /**
     * The first alarm that triggers strictly after [after].
     *
     * The search starts a day early because an alarm for an early shift can fall on
     * the previous evening (e.g. 00:30 shift with a 60-minute offset).
     */
    fun nextAlarm(
        resolver: ScheduleResolver,
        after: LocalDateTime,
        lookaheadDays: Long = LOOKAHEAD_DAYS,
    ): PlannedAlarm? {
        val maxOffsetDays = 2L
        var date = after.toLocalDate().minusDays(maxOffsetDays)
        val last = after.toLocalDate().plusDays(lookaheadDays)
        var best: PlannedAlarm? = null
        while (!date.isAfter(last)) {
            val alarm = alarmOn(resolver, date)
            if (alarm != null && alarm.triggerAt.isAfter(after)) {
                if (best == null || alarm.triggerAt.isBefore(best.triggerAt)) best = alarm
            }
            // Triggers lie within a day of their shift date, so dates this far past the
            // best candidate cannot ring earlier.
            if (best != null && date.isAfter(best.triggerAt.toLocalDate().plusDays(maxOffsetDays))) break
            date = date.plusDays(1)
        }
        return best
    }

    /** The alarm for the shift starting on [date], ignoring whether it is in the past. */
    fun alarmOn(resolver: ScheduleResolver, date: LocalDate): PlannedAlarm? {
        val day = resolver.resolve(date)
        val type = day.shiftType ?: return null
        val start = day.start ?: return null
        if (!type.alarmEnabled || day.alarmSkipped) return null
        val offset = type.alarmOffsetMinutes.coerceIn(0, MAX_OFFSET_MINUTES)
        return PlannedAlarm(
            triggerAt = date.atTime(start).minusMinutes(offset.toLong()),
            shiftDate = date,
            shiftStart = start,
            shiftTypeId = type.id,
            shiftName = type.name,
            offsetMinutes = offset,
            soundUri = type.alarmSoundUri,
        )
    }

    /** Offsets are limited to 24h so the two-day search window above is always enough. */
    const val MAX_OFFSET_MINUTES = 24 * 60
}
