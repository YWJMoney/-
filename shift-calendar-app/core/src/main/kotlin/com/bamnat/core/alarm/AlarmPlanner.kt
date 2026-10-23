package com.bamnat.core.alarm

import com.bamnat.core.schedule.ScheduleResolver
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** One upcoming alarm. */
data class PlannedAlarm(
    val triggerAt: LocalDateTime,
    val shiftDate: LocalDate,
    /** Start of that day's shift, or the alarm time itself on a day without work. */
    val shiftStart: LocalTime,
    /** 0 when the day has no shift. */
    val shiftTypeId: Long,
    /** Empty when the day has no shift. */
    val shiftName: String,
    /** Minutes before the shift start; negative when a hand-set alarm rings after it. */
    val offsetMinutes: Int,
    val soundUri: String?,
    /** Set by hand for this date from the calendar. */
    val custom: Boolean = false,
)

/**
 * Finds the next alarm. Only one alarm is registered with the system at a time;
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
            for (alarm in alarmsOn(resolver, date)) {
                if (alarm.triggerAt.isAfter(after) && (best == null || alarm.triggerAt.isBefore(best.triggerAt))) {
                    best = alarm
                }
            }
            // Triggers lie within a day of their shift date, so dates this far past the
            // best candidate cannot ring earlier.
            if (best != null && date.isAfter(best.triggerAt.toLocalDate().plusDays(maxOffsetDays))) break
            date = date.plusDays(1)
        }
        return best
    }

    /**
     * Every alarm belonging to [date], earliest first, ignoring whether it is in the past.
     * Alarms set by hand for the date replace the shift's own alarms on that day.
     * [includeSkipped] gives what a day with its alarms turned off would ring [SLP-14].
     */
    fun alarmsOn(resolver: ScheduleResolver, date: LocalDate, includeSkipped: Boolean = false): List<PlannedAlarm> {
        val day = resolver.resolve(date)
        if (day.alarmSkipped && !includeSkipped) return emptyList()
        val type = day.shiftType?.takeIf { day.isWorkDay }
        val start = day.start
        val custom = day.entry?.customAlarms
        val list = if (custom != null) {
            custom.filter { it.enabled }.map { alarm ->
                val at = alarm.at(date) // [DAYW-09] the evening before, for one set so
                PlannedAlarm(
                    triggerAt = at,
                    shiftDate = date,
                    shiftStart = start ?: alarm.time,
                    shiftTypeId = type?.id ?: 0,
                    shiftName = type?.name.orEmpty(),
                    offsetMinutes = if (start != null) Duration.between(at, date.atTime(start)).toMinutes().toInt() else 0,
                    soundUri = type?.alarmSoundUri,
                    custom = true,
                )
            }
        } else {
            // [FEAT-02][EXTRA-01] A 대근 for one of the user's shifts rings with that shift's alarms.
            val second = day.extras.flatMap { e ->
                val t = e.covered ?: return@flatMap emptyList()
                val at = e.from ?: return@flatMap emptyList()
                t.activeAlarms.map { spec ->
                    val offset = spec.offsetMinutes.coerceIn(0, MAX_OFFSET_MINUTES)
                    PlannedAlarm(
                        triggerAt = at.minusMinutes(offset.toLong()),
                        shiftDate = date,
                        shiftStart = at.toLocalTime(),
                        shiftTypeId = t.id,
                        shiftName = t.name,
                        offsetMinutes = offset,
                        soundUri = t.alarmSoundUri,
                    )
                }
            }
            if (type == null || start == null) return second.distinctBy { it.triggerAt }.sortedBy { it.triggerAt }
            second + type.activeAlarms.map { spec ->
                val offset = spec.offsetMinutes.coerceIn(0, MAX_OFFSET_MINUTES)
                PlannedAlarm(
                    triggerAt = date.atTime(start).minusMinutes(offset.toLong()),
                    shiftDate = date,
                    shiftStart = start,
                    shiftTypeId = type.id,
                    shiftName = type.name,
                    offsetMinutes = offset,
                    soundUri = type.alarmSoundUri,
                )
            }
        }
        return list.distinctBy { it.triggerAt }.sortedBy { it.triggerAt }
    }

    /** The first alarm of the shift on [date], or null. */
    fun alarmOn(resolver: ScheduleResolver, date: LocalDate): PlannedAlarm? = alarmsOn(resolver, date).firstOrNull()

    /** Offsets are limited to 24h so the two-day search window above is always enough. */
    const val MAX_OFFSET_MINUTES = 24 * 60
}
