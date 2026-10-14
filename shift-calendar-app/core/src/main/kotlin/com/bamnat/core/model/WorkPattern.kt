package com.bamnat.core.model

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

    /** The same schedule over a different stretch of days. */
    fun withRange(start: LocalDate, end: LocalDate?): WorkPattern = when (this) {
        // The anchor stays, so the order lines up with the same dates as before.
        is Rotation -> copy(startDate = start, endDate = end)
        is Weekly -> copy(startDate = start, endDate = end)
    }

    /**
     * Rotation such as 주주야야비휴 for shift workers. Step 0 falls on [anchorDate] (and on every
     * [steps].size days before and after it); the rotation fills the calendar from [startDate].
     */
    data class Rotation(
        override val startDate: LocalDate,
        val steps: List<Long?>,
        override val endDate: LocalDate? = null,
        val anchorDate: LocalDate = startDate,
    ) : WorkPattern {
        init {
            require(steps.isNotEmpty()) { "A rotation needs at least one step" }
        }

        fun stepIndexOn(date: LocalDate): Int {
            val days = ChronoUnit.DAYS.between(anchorDate, date)
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
