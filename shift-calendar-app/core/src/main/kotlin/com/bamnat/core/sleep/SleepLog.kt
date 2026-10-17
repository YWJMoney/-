package com.bamnat.core.sleep

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** [SLP-06] One sleep the user logged with two taps: fell asleep, woke up. */
data class SleepEntry(val id: Long = 0, val start: LocalDateTime, val end: LocalDateTime?) {
    val minutes: Long? get() = end?.let { Duration.between(start, it).toMinutes().coerceAtLeast(0) }
    /** A sleep belongs to the day the user woke up on. */
    val wakeDate: LocalDate? get() = end?.toLocalDate()
}

/** [SLP-06] Sleep over the last days against the user's target; only days with a log count. */
data class SleepDebt(val days: Int, val sleptMinutes: Long, val shortMinutes: Long) {
    val averageMinutes: Long get() = if (days == 0) 0 else sleptMinutes / days

    companion object {
        const val WINDOW_DAYS = 7L
        /** Longer than this is a missed "woke up" tap, not a sleep. */
        const val MAX_SLEEP_MINUTES = 16 * 60L

        fun of(entries: List<SleepEntry>, today: LocalDate, targetMinutes: Int): SleepDebt {
            val from = today.minusDays(WINDOW_DAYS - 1)
            val byDay = entries
                .filter { e -> e.wakeDate?.let { !it.isBefore(from) && !it.isAfter(today) } == true }
                .filter { (it.minutes ?: 0) in 1..MAX_SLEEP_MINUTES }
                .groupBy { it.wakeDate!! }
                .mapValues { (_, list) -> list.sumOf { it.minutes!! } }
            val slept = byDay.values.sum()
            val short = byDay.values.sumOf { (targetMinutes - it).coerceAtLeast(0) }
            return SleepDebt(byDay.size, slept, short)
        }
    }
}
