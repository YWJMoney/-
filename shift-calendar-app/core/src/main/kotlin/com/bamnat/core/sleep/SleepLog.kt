package com.bamnat.core.sleep

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [SLP-06] One sleep the user logged with two taps: fell asleep, woke up.
 * [SLP-08] Or one read from Health Connect (a watch or the phone measured it): [imported], read only.
 */
data class SleepEntry(val id: Long = 0, val start: LocalDateTime, val end: LocalDateTime?, val imported: Boolean = false) {
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

/** [SLP-08] The two-tap log and the sleep read from Health Connect, as one list. */
object SleepMerge {
    /**
     * The user's own entries always stay; a measured sleep that overlaps one of them is dropped,
     * since the user corrected it by hand. Several apps can write the same night (a watch and the
     * phone), so of overlapping measured sleeps only the longest is kept. Newest first.
     */
    fun merge(manual: List<SleepEntry>, imported: List<SleepEntry>): List<SleepEntry> {
        val logged = manual.filter { it.end != null }
        val kept = ArrayList<SleepEntry>()
        imported
            .filter { it.end != null && it.end > it.start }
            .sortedWith(compareByDescending<SleepEntry> { it.minutes }.thenBy { it.start })
            .forEach { e -> if (logged.none { overlaps(it, e) } && kept.none { overlaps(it, e) }) kept += e.copy(imported = true) }
        return (manual + kept).sortedByDescending { it.start }
    }

    private fun overlaps(a: SleepEntry, b: SleepEntry): Boolean = a.start < b.end && b.start < a.end
}
