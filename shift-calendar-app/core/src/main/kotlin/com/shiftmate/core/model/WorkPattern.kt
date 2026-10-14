package com.shiftmate.core.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The repeating schedule that fills the calendar automatically.
 *
 * Each step holds a shift type id, or null for a plain day off.
 */
sealed interface WorkPattern {
    val startDate: LocalDate
    /** Last day the pattern applies (inclusive); null means it repeats forever. */
    val endDate: LocalDate?

    fun covers(date: LocalDate): Boolean =
        !date.isBefore(startDate) && (endDate == null || !date.isAfter(endDate))

    /** Shift type id for [date], or null when it is a day off or outside the pattern. */
    fun shiftTypeIdOn(date: LocalDate): Long?

    /**
     * Rotation such as 주주야야비휴 for shift workers. Step 0 falls on [startDate],
     * and the cycle repeats every [steps].size days.
     */
    data class Rotation(
        override val startDate: LocalDate,
        val steps: List<Long?>,
        override val endDate: LocalDate? = null,
    ) : WorkPattern {
        init {
            require(steps.isNotEmpty()) { "A rotation needs at least one step" }
        }

        fun stepIndexOn(date: LocalDate): Int {
            val days = ChronoUnit.DAYS.between(startDate, date)
            return Math.floorMod(days, steps.size.toLong()).toInt()
        }

        override fun shiftTypeIdOn(date: LocalDate): Long? =
            if (covers(date)) steps[stepIndexOn(date)] else null
    }

    /** Fixed weekly schedule, typical for part-time work. */
    data class Weekly(
        override val startDate: LocalDate,
        val byDay: Map<DayOfWeek, Long?>,
        override val endDate: LocalDate? = null,
    ) : WorkPattern {
        override fun shiftTypeIdOn(date: LocalDate): Long? =
            if (covers(date)) byDay[date.dayOfWeek] else null

        /** Steps in Monday..Sunday order, the storage form. */
        val steps: List<Long?> get() = DayOfWeek.entries.map { byDay[it] }

        companion object {
            fun fromSteps(startDate: LocalDate, steps: List<Long?>, endDate: LocalDate? = null): Weekly {
                require(steps.size == 7) { "A weekly pattern needs 7 steps (Mon..Sun)" }
                return Weekly(startDate, DayOfWeek.entries.associateWith { steps[it.ordinal] }, endDate)
            }
        }
    }
}
