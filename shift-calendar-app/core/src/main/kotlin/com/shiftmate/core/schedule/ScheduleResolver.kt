package com.shiftmate.core.schedule

import com.shiftmate.core.model.DayEntry
import com.shiftmate.core.model.ShiftOverride
import com.shiftmate.core.model.ShiftType
import com.shiftmate.core.model.WorkPattern
import com.shiftmate.core.model.WorkTime
import java.time.LocalDate
import java.time.LocalTime

enum class DaySource { PATTERN, OVERRIDE, NONE }

/** The final picture of one calendar day after applying pattern and user changes. */
data class ResolvedDay(
    val date: LocalDate,
    /** Shift type shown on the calendar, including off types; null when nothing is scheduled. */
    val shiftType: ShiftType?,
    val source: DaySource,
    val start: LocalTime?,
    val end: LocalTime?,
    val breakMinutes: Int,
    /** Minutes of the scheduled shift (after break). */
    val scheduledMinutes: Int,
    val overtimeMinutes: Int,
    val holidayWorkMinutes: Int,
    val alarmSkipped: Boolean,
    val entry: DayEntry?,
    /** True when start/end come from this day's entry instead of the shift type. */
    val hasCustomTimes: Boolean = false,
) {
    val isWorkDay: Boolean get() = scheduledMinutes > 0 || (start != null && end != null)
    val isOff: Boolean get() = !isWorkDay
    val isHolidayWork: Boolean get() = holidayWorkMinutes > 0


    /** Everything that counts toward weekly / monthly hours. */
    val totalMinutes: Int get() = scheduledMinutes + overtimeMinutes + holidayWorkMinutes
}

/**
 * Combines shift types, the active pattern and per-day entries into [ResolvedDay]s.
 * Pure and cheap: resolving a date is a couple of map lookups.
 */
class ScheduleResolver(
    shiftTypes: Collection<ShiftType>,
    private val pattern: WorkPattern?,
    entries: Collection<DayEntry>,
) {
    private val typesById: Map<Long, ShiftType> = shiftTypes.associateBy { it.id }
    private val entriesByDate: Map<LocalDate, DayEntry> = entries.associateBy { it.date }

    fun entryOn(date: LocalDate): DayEntry? = entriesByDate[date]

    fun resolve(date: LocalDate): ResolvedDay {
        val entry = entriesByDate[date]
        val (type, source) = when (val o = entry?.override ?: ShiftOverride.None) {
            ShiftOverride.Off -> null to DaySource.OVERRIDE
            // An override pointing at a deleted type falls back to "nothing scheduled".
            is ShiftOverride.Shift -> typesById[o.shiftTypeId] to DaySource.OVERRIDE
            ShiftOverride.None -> {
                val id = pattern?.shiftTypeIdOn(date)
                val t = id?.let { typesById[it] }
                t to if (pattern?.covers(date) == true) DaySource.PATTERN else DaySource.NONE
            }
        }

        // Custom times only adjust a working day. On a day off they are ignored, so switching a
        // day to 휴무 can never leave hours behind.
        val dayOff = entry?.override == ShiftOverride.Off || type == null || type.isOff
        val customTimes = entry?.hasCustomTimes == true && !dayOff
        val start: LocalTime?
        val end: LocalTime?
        val breakMinutes: Int
        if (customTimes) {
            start = entry!!.customStart
            end = entry.customEnd
            breakMinutes = entry.customBreakMinutes ?: type?.takeIf { it.hasTimes }?.breakMinutes ?: 0
        } else if (type != null && type.hasTimes) {
            start = type.start
            end = type.end
            breakMinutes = entry?.customBreakMinutes ?: type.breakMinutes
        } else {
            start = null
            end = null
            breakMinutes = 0
        }

        val scheduled = if (start != null && end != null) WorkTime.workMinutes(start, end, breakMinutes) else 0

        return ResolvedDay(
            date = date,
            shiftType = type,
            source = source,
            start = start,
            end = end,
            breakMinutes = breakMinutes,
            scheduledMinutes = scheduled,
            overtimeMinutes = entry?.overtimeMinutes?.coerceAtLeast(0) ?: 0,
            holidayWorkMinutes = entry?.holidayWorkMinutes?.coerceAtLeast(0) ?: 0,
            alarmSkipped = entry?.alarmSkipped == true,
            entry = entry,
            hasCustomTimes = customTimes,
        )
    }

    fun resolveRange(from: LocalDate, toInclusive: LocalDate): List<ResolvedDay> {
        if (toInclusive.isBefore(from)) return emptyList()
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(toInclusive) }
            .map(::resolve)
            .toList()
    }
}
