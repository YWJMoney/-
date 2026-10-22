package com.bamnat.core.schedule

import com.bamnat.core.model.Attendance
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ExtraEntry
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.ExtraTiming
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.model.WorkTime
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class DaySource { PATTERN, OVERRIDE, NONE }

/**
 * [EXTRA-01] One piece of extra work placed on the clock: its own times, right after or before the
 * shift for 잔업·조출, or (an old record of hours only on a day without a shift) nowhere.
 */
data class ResolvedExtra(
    val entry: ExtraEntry,
    val kind: ExtraKind,
    /** Null when the record has no times and there is no shift to place it next to. */
    val from: LocalDateTime?,
    val to: LocalDateTime?,
    val breakMinutes: Int,
    val breakStart: LocalTime?,
    /** Paid minutes: the span less the break. */
    val minutes: Int,
    /** The shift type this work covered (대근), if the user said. */
    val covered: ShiftType?,
) {
    /** No clock times, so night minutes are unknown and taken as none. */
    val estimated: Boolean get() = from == null
    val start: LocalTime? get() = from?.toLocalTime()
    val end: LocalTime? get() = to?.toLocalTime()
}

/** The final picture of one calendar day after applying pattern and user changes. */
data class ResolvedDay(
    val date: LocalDate,
    /** Shift type shown on the calendar, including off types; null when nothing is scheduled. */
    val shiftType: ShiftType?,
    val source: DaySource,
    val start: LocalTime?,
    val end: LocalTime?,
    val breakMinutes: Int,
    /** When the break starts, if the shift says; null means unknown (taken out evenly). */
    val breakStart: LocalTime? = null,
    /** Minutes of the scheduled shift (after break). */
    val scheduledMinutes: Int,
    val alarmSkipped: Boolean,
    val entry: DayEntry?,
    /** True when start/end come from this day's entry instead of the shift type. */
    val hasCustomTimes: Boolean = false,
    /** Leave taken on a working day [FEAT-10]; null on days off, where leave means nothing. */
    val leave: Leave? = null,
    /** Shift minutes the leave covers; paid leave pays them, unpaid leave deducts them. */
    val leaveMinutes: Int = 0,
    /** [EXTRA-01] Extra work this day, in the order the user added it. */
    val extras: List<ResolvedExtra> = emptyList(),
) {
    val isWorkDay: Boolean get() = scheduledMinutes > 0 || (start != null && end != null)
    val isOff: Boolean get() = !isWorkDay
    /**
     * A day with no shift and no leave: work on it is 휴무일 or 휴일 work [EXTRA-02]. [HRLY-01] A 결근
     * is a missed working day, not a rest day, so it is never picked as 주휴일 or paid as 휴일 work.
     */
    val isRestDay: Boolean get() = !isWorkDay && leave == null && entry?.attendance != Attendance.ABSENT
    val hasExtraWork: Boolean get() = extras.isNotEmpty()
    val extraMinutes: Int get() = extras.sumOf { it.minutes }

    /** [EXTRA-01] The first shift type some extra work covered (a 대근 for a 나이트), for two-colour cells. */
    val coveredShift: ShiftType? get() = extras.firstNotNullOfOrNull { it.covered }

    /** [EXTRA-01] Up to [max] marks of the day's kinds of extra work, e.g. "잔대". */
    fun extraMarks(max: Int = 2): String = extras.map { it.kind.mark }.distinct().take(max).joinToString("")

    /** Everything that counts toward weekly / monthly hours. */
    val totalMinutes: Int get() = scheduledMinutes + extraMinutes

    /** The shift on the clock, if it has times. */
    val shiftFrom: LocalDateTime? get() = if (start != null && end != null) date.atTime(start) else null
    val shiftTo: LocalDateTime? get() = shiftFrom?.let { endAfter(it, end!!) }

    companion object {
        /** [end] on the day of [from], or the next day when that is not after it (overnight; equal is 24 hours). */
        fun endAfter(from: LocalDateTime, end: LocalTime): LocalDateTime {
            val sameDay = from.toLocalDate().atTime(end)
            return if (sameDay.isAfter(from)) sameDay else sameDay.plusDays(1)
        }
    }
}

/**
 * Combines shift types, the active pattern and per-day entries into [ResolvedDay]s.
 * Pure and cheap: resolving a date is a couple of map lookups.
 */
class ScheduleResolver(
    shiftTypes: Collection<ShiftType>,
    patterns: List<WorkPattern>,
    entries: Collection<DayEntry>,
    /** [EXTRA-01] The user's kinds of extra work, for where each piece sits on the clock. */
    val kinds: ExtraKinds = ExtraKinds(),
    /** [HRLY-05] Take the legal break off days that have none set ([LegalBreak]). */
    val legalBreaks: Boolean = false,
) {
    constructor(shiftTypes: Collection<ShiftType>, pattern: WorkPattern?, entries: Collection<DayEntry>, kinds: ExtraKinds = ExtraKinds()) :
        this(shiftTypes, listOfNotNull(pattern), entries, kinds)

    /** Pattern periods, earliest first. */
    private val patterns: List<WorkPattern> = patterns.sortedBy { it.startDate }
    private val typesById: Map<Long, ShiftType> = shiftTypes.associateBy { it.id }
    private val entriesByDate: Map<LocalDate, DayEntry> = entries.associateBy { it.date }

    fun entryOn(date: LocalDate): DayEntry? = entriesByDate[date]

    /** The pattern period that fills [date], if any. */
    fun patternOn(date: LocalDate): WorkPattern? = PatternTimeline.on(patterns, date)

    /** Shift type id the pattern gives [date] (null for a day off or no pattern). */
    fun patternShiftIdOn(date: LocalDate): Long? = patternOn(date)?.shiftTypeIdOn(date)

    fun resolve(date: LocalDate): ResolvedDay {
        val day = resolveDay(date)
        return if (legalBreaks) LegalBreak.apply(day) else day
    }

    private fun resolveDay(date: LocalDate): ResolvedDay {
        val entry = entriesByDate[date]
        val (type, source) = when (val o = entry?.override ?: ShiftOverride.None) {
            ShiftOverride.Off -> null to DaySource.OVERRIDE
            // An override pointing at a deleted type falls back to "nothing scheduled".
            is ShiftOverride.Shift -> typesById[o.shiftTypeId] to DaySource.OVERRIDE
            ShiftOverride.None -> {
                val pattern = patternOn(date)
                val id = pattern?.shiftTypeIdOn(date)
                val t = id?.let { typesById[it] }
                t to if (pattern != null) DaySource.PATTERN else DaySource.NONE
            }
        }

        // Custom times only adjust a working day. On a day off they are ignored, so switching a
        // day to 휴무 can never leave hours behind. [HRLY-01] A 결근 is not worked either: the shift
        // stays on the calendar but has no hours and no pay.
        val absent = entry?.attendance == Attendance.ABSENT
        val dayOff = entry?.override == ShiftOverride.Off || type == null || type.isOff || absent
        val customTimes = entry?.hasCustomTimes == true && !dayOff
        val start: LocalTime?
        val end: LocalTime?
        val breakMinutes: Int
        if (customTimes) {
            start = entry!!.customStart
            end = entry.customEnd
            breakMinutes = entry.customBreakMinutes ?: type?.takeIf { it.hasTimes }?.breakMinutes ?: 0
        } else if (type != null && type.hasTimes && !absent) {
            start = type.start
            end = type.end
            breakMinutes = entry?.customBreakMinutes ?: type.breakMinutes
        } else {
            start = null
            end = null
            breakMinutes = 0
        }

        val fullScheduled = if (start != null && end != null) WorkTime.workMinutes(start, end, breakMinutes) else 0
        // Leave only counts on a day with work to take off. Partial leave comes off the end of
        // the shift and the break shrinks in proportion, so 반차 of 09~18 (1h break) is 09~13:30.
        val leave = entry?.leave?.takeIf { fullScheduled > 0 }
        val leaveMinutes = leave?.let { Math.round(fullScheduled * it.quarters / 4.0).toInt() } ?: 0
        var workEnd = end
        var workBreak = breakMinutes
        if (leave != null && start != null) {
            if (leave.isFullDay) {
                workEnd = null
                workBreak = 0
            } else {
                workBreak = Math.round(breakMinutes * (4 - leave.quarters) / 4.0).toInt()
                workEnd = start.plusMinutes((fullScheduled - leaveMinutes + workBreak).toLong())
            }
        }
        val workStart = if (workEnd == null) null else start
        val scheduled = fullScheduled - leaveMinutes
        val shiftFrom = if (workStart != null && workEnd != null) date.atTime(workStart) else null
        val shiftTo = shiftFrom?.let { ResolvedDay.endAfter(it, workEnd!!) }

        return ResolvedDay(
            date = date,
            shiftType = type,
            source = source,
            start = workStart,
            end = workEnd,
            breakMinutes = workBreak,
            // A partial leave shrinks the break, so its clock time is no longer known.
            breakStart = type?.breakStart?.takeIf { workStart != null && workBreak == breakMinutes && breakMinutes > 0 },
            scheduledMinutes = scheduled,
            alarmSkipped = entry?.alarmSkipped == true,
            entry = entry,
            hasCustomTimes = customTimes,
            leave = leave,
            leaveMinutes = leaveMinutes,
            extras = entry?.extras?.takeIf { it.isNotEmpty() }?.let { placeExtras(date, it, shiftFrom, shiftTo) }.orEmpty(),
        )
    }

    /**
     * [EXTRA-01] Puts each piece of extra work on the clock. Pieces with times keep them; a 대근 with
     * only the covered shift takes that shift's hours; 잔업 follows the shift and 조출 comes before it,
     * one after another when there are several; an old record of hours only follows the shift too,
     * or has no place on a day without one.
     */
    private fun placeExtras(date: LocalDate, list: List<ExtraEntry>, shiftFrom: LocalDateTime?, shiftTo: LocalDateTime?): List<ResolvedExtra> {
        var after = shiftTo
        var before = shiftFrom
        return list.map { e ->
            val kind = kinds.of(e.kindId)
            val covered = e.coveredShiftId?.let { typesById[it] }
            val asCovered = covered?.takeIf { !e.hasTimes && it.hasTimes }
            if (e.hasTimes || asCovered != null) {
                val from = date.atTime(e.start ?: asCovered!!.start!!)
                val to = ResolvedDay.endAfter(from, e.end ?: asCovered!!.end!!)
                val span = Duration.between(from, to).toMinutes().toInt()
                val brk = (if (e.hasTimes) e.breakMinutes else asCovered!!.breakMinutes).coerceIn(0, span)
                val brkStart = if (e.hasTimes) e.breakStart else asCovered!!.breakStart
                ResolvedExtra(e, kind, from, to, brk, brkStart.takeIf { brk > 0 }, span - brk, covered)
            } else {
                val minutes = e.minutes.coerceIn(0, WorkTime.MINUTES_PER_DAY).toLong()
                var from: LocalDateTime? = null
                var to: LocalDateTime? = null
                val b = before
                val a = after
                if (kind.timing is ExtraTiming.BeforeShift && b != null) {
                    from = b.minusMinutes(minutes); to = b; before = from
                } else if (a != null) {
                    from = a; to = a.plusMinutes(minutes); after = to
                }
                ResolvedExtra(e, kind, from, to, 0, null, minutes.toInt(), covered)
            }
        }
    }

    fun resolveRange(from: LocalDate, toInclusive: LocalDate): List<ResolvedDay> {
        if (toInclusive.isBefore(from)) return emptyList()
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(toInclusive) }
            .map(::resolve)
            .toList()
    }
}
