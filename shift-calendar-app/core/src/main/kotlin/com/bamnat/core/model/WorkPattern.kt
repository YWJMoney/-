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

    /**
     * Fixed weekly schedule, typical for part-time and office work. [SCHED-04] G P18·BC5: [holidaysOff] makes
     * every 공휴일 (and 대체공휴일) a day off, so a weekday worker's alarm stays quiet then; [altSaturday] is a
     * Saturday that is worked when Saturdays alternate (격주 토요일), and the Saturdays an odd number of weeks
     * from it are off. Both are kept in the stored order's text, so the database stays as it is.
     */
    data class Weekly(
        override val startDate: LocalDate,
        val byDay: Map<DayOfWeek, Long?>,
        override val endDate: LocalDate? = null,
        val holidaysOff: Boolean = false,
        val altSaturday: LocalDate? = null,
    ) : WorkPattern {
        override fun shiftTypeIdOn(date: LocalDate): Long? {
            if (!covers(date)) return null
            if (holidaysOff && com.bamnat.core.holiday.KoreanHolidays.isHoliday(date)) return null
            if (date.dayOfWeek == DayOfWeek.SATURDAY && altSaturday != null &&
                Math.floorMod(ChronoUnit.WEEKS.between(altSaturday, date), 2L) == 1L
            ) return null
            return byDay[date.dayOfWeek]
        }

        /** Steps in Monday..Sunday order, the storage form. */
        val steps: List<Long?> get() = DayOfWeek.entries.map { byDay[it] }

        companion object {
            fun fromSteps(
                startDate: LocalDate,
                steps: List<Long?>,
                endDate: LocalDate? = null,
                holidaysOff: Boolean = false,
                altSaturday: LocalDate? = null,
            ): Weekly {
                require(steps.size == 7) { "A weekly pattern needs 7 steps (Mon..Sun)" }
                return Weekly(startDate, DayOfWeek.entries.associateWith { steps[it.ordinal] }, endDate, holidaysOff,
                    altSaturday?.takeIf { it.dayOfWeek == DayOfWeek.SATURDAY })
            }

            /** [SCHED-04] The word after the seven days in the stored order that turns [holidaysOff] on. */
            const val HOLIDAYS_OFF = "h"
        }
    }
}
