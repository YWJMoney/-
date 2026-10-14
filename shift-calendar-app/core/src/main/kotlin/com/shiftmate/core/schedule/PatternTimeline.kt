package com.shiftmate.core.schedule

import com.shiftmate.core.model.WorkPattern
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
}
