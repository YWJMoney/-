package com.bamnat.core.schedule

/**
 * [HRLY-05] 법정 휴게 자동 빼기 (근로기준법 제54조): a working day with no break set loses 30 minutes from
 * 4 hours on the clock and an hour from 8½. A break the user set, even 0 on purpose, is left alone, and so
 * is a day with leave, whose break is already worked out.
 *
 * From 2026-12-10 제54조 ① 단서 (법률 제21784호) lets a worker on a 4시간 day ask for no break; that day's
 * break set to 0 keeps the whole span as work, so the rule itself does not change.
 */
object LegalBreak {
    /**
     * The break the law asks for on a span of [minutes] without one. An hour only from 8시간 30분 on the clock:
     * 8 hours less 30 minutes is 7시간 30분 worked, which needs 30 minutes, not an hour.
     */
    fun minutesFor(minutes: Int): Int = when {
        minutes >= 8 * 60 + 30 -> 60
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
