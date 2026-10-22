package com.bamnat.core.model

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SHFT-01][SHFT-05] Night work by the clock: a shift whose hours reach into 22:00~06:00
 * (근로기준법 제56조 제3항), e.g. 야간 22:00–07:00 or 이브닝 15:00–23:00. A shift that ends at 22:00
 * or starts at 06:00 only touches the window and is not one.
 */
object NightShift {
    private const val DAY = WorkTime.MINUTES_PER_DAY
    private const val NIGHT_START = 22 * 60
    private const val NIGHT_END = 6 * 60

    /** True when [start]..[end] (overnight when end is not after start) overlaps 22:00~06:00. */
    fun crosses(start: LocalTime, end: LocalTime): Boolean {
        val s = start.hour * 60 + start.minute
        val e = s + WorkTime.spanMinutes(start, end)
        // The night windows a shift of up to 24 hours starting on this day can meet.
        val windows = listOf(NIGHT_START - DAY to NIGHT_END, NIGHT_START to NIGHT_END + DAY, NIGHT_START + DAY to NIGHT_END + 2 * DAY)
        return windows.any { (ws, we) -> minOf(e, we) - maxOf(s, ws) > 0 }
    }

    /** A kind of work day with times that reach into the night; off types never are. */
    fun of(type: ShiftType): Boolean = type.hasTimes && crosses(type.start!!, type.end!!)

    /**
     * [SHFT-05] True while [now] is inside a night shift on the calendar [resolve]: the day's own shift
     * (yesterday's too, for one that runs past midnight) or extra work on the clock such as a 야간 대근.
     */
    fun isOn(now: LocalDateTime, resolve: (java.time.LocalDate) -> ResolvedDay): Boolean {
        val today = now.toLocalDate()
        return listOf(today.minusDays(1), today).any { d ->
            val day = resolve(d)
            val shift = day.isWorkDay && day.start != null && day.end != null && crosses(day.start, day.end) &&
                inside(now, day.shiftFrom, day.shiftTo)
            shift || day.extras.any { e ->
                val s = e.start; val t = e.end
                s != null && t != null && crosses(s, t) && inside(now, e.from, e.to)
            }
        }
    }

    private fun inside(now: LocalDateTime, from: LocalDateTime?, to: LocalDateTime?): Boolean =
        from != null && to != null && !now.isBefore(from) && now.isBefore(to)
}

/** [SHFT-01] Whether this kind of day is night work (야간 mark in the shift editor). */
val ShiftType.isNight: Boolean get() = NightShift.of(this)
