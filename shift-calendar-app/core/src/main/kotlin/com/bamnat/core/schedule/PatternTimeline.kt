package com.bamnat.core.schedule

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import java.time.LocalDate

/**
 * The patterns a user worked over time, e.g. 주주휴휴야야 until November and a new order after a
 * team change from December. Each period covers its own dates, so a change never rewrites
 * the days before it.
 */
object PatternTimeline {
    /** How far back a first pattern fills the calendar, so past dates are not empty. */
    const val BACKFILL_YEARS = 2L

    /** A first pattern, stretched back [BACKFILL_YEARS] years before its start. */
    fun first(pattern: WorkPattern, today: LocalDate): WorkPattern {
        val from = minOf(pattern.startDate, today).minusYears(BACKFILL_YEARS)
        return pattern.withRange(from, pattern.endDate)
    }

    /**
     * Applies [pattern] from [from] on. Earlier periods are kept as they are, cut short the day
     * before [from]; periods that would only start on or after [from] are replaced.
     */
    fun changeFrom(existing: List<WorkPattern>, pattern: WorkPattern, from: LocalDate): List<WorkPattern> {
        val dayBefore = from.minusDays(1)
        val kept = existing.mapNotNull { p ->
            when {
                !p.startDate.isBefore(from) -> null
                p.endDate != null && p.endDate!!.isBefore(from) -> p
                else -> p.withRange(p.startDate, dayBefore)
            }
        }
        return (kept + pattern.withRange(from, pattern.endDate)).sortedBy { it.startDate }
    }

    /** The period that fills [date], or null on a date no pattern covers. */
    fun on(patterns: List<WorkPattern>, date: LocalDate): WorkPattern? =
        patterns.lastOrNull { it.covers(date) }

    /** The period shown and edited as "the" pattern: the one for [today], else the latest. */
    fun current(patterns: List<WorkPattern>, today: LocalDate): WorkPattern? =
        on(patterns, today) ?: patterns.lastOrNull()

    /**
     * [SCHED-02] I 리뷰 I10·G P20: the past days that "지금 순서를 고쳐요" would move under a 연차, 잔업·특근 or the
     * day's own times. Rewriting [current] in place with [draft] changes every day before [today] it covers;
     * a day the user changed by hand keeps its own shift and is left out. Non-empty means the save must ask
     * "언제부터" and say what the fix would touch, rather than rewrite the past quietly.
     */
    fun recordedDaysMoved(current: WorkPattern, draft: WorkPattern, entries: List<DayEntry>, today: LocalDate): List<LocalDate> {
        val fixed = draft.withRange(current.startDate, current.endDate)
        return entries.asSequence()
            .filter { it.date.isBefore(today) && current.covers(it.date) && it.override == ShiftOverride.None }
            .filter { it.leave != null || it.extras.isNotEmpty() || it.customStart != null || it.customEnd != null || it.customBreakMinutes != null }
            .filter { current.shiftTypeIdOn(it.date) != fixed.shiftTypeIdOn(it.date) }
            .map { it.date }
            .sorted()
            .toList()
    }

    /**
     * [SCHED-03] I 리뷰 I5·G P26 "이 날부터 다시 맞추기": [rotation]'s order restarted so that [date] is step
     * [step] (0-based), to be applied with [changeFrom] from [date]; every day before stays as it was.
     */
    fun realign(rotation: WorkPattern.Rotation, date: LocalDate, step: Int): WorkPattern.Rotation =
        rotation.copy(anchorDate = date.minusDays(Math.floorMod(step, rotation.steps.size).toLong()))

    /**
     * [SCHED-03] The days from [date] on that the user set by hand ([ShiftOverride] other than none). After a
     * realign they usually were the workaround for the order being off, so the sheet offers to clear them.
     */
    fun handChangedFrom(entries: List<DayEntry>, date: LocalDate, limit: LocalDate? = null): List<LocalDate> =
        entries.filter { !it.date.isBefore(date) && (limit == null || !it.date.isAfter(limit)) && it.override != ShiftOverride.None }
            .map { it.date }
            .sorted()
}
