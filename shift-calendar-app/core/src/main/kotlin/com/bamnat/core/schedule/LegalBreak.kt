package com.bamnat.core.schedule

/**
 * [HRLY-05] 법정 휴게 자동 빼기 (근로기준법 제54조): a working day with no break set loses 30 minutes from
 * 4 hours on the clock and an hour from 8. A break the user set, even 0 on purpose, is left alone, and so
 * is a day with leave, whose break is already worked out.
 */
object LegalBreak {
    /** The break the law asks for on a span of [minutes] without one. */
    fun minutesFor(minutes: Int): Int = when {
        minutes >= 8 * 60 -> 60
        minutes >= 4 * 60 -> 30
        else -> 0
    }

    fun apply(day: ResolvedDay): ResolvedDay {
        if (day.start == null || day.end == null || day.breakMinutes > 0 || day.leave != null) return day
        if (day.entry?.customBreakMinutes != null) return day
        val brk = minutesFor(day.scheduledMinutes)
        if (brk == 0) return day
        return day.copy(breakMinutes = brk, breakStart = null, scheduledMinutes = day.scheduledMinutes - brk)
    }
}
