package com.bamnat.core.pay

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate

/**
 * 월 통상임금 산정 기준시간 from the roster [PAY-04]. 근로기준법 시행령 제6조 제2항: (1주 소정근로시간
 * + 유급 주휴시간) × 365 ÷ 7 ÷ 12. 소정근로 is at most 8 hours a day and 40 a week (제50조); the rest of a
 * longer shift is 연장. 주휴 is (주 소정 ÷ 40) × 8 hours, none under 15 hours (제18조 제3항).
 */
object StandardHours {
    const val FULL_TIME = 209

    fun weeklyHolidayMinutes(weeklyMinutes: Int): Int =
        if (weeklyMinutes < 15 * 60) 0 else minOf(weeklyMinutes, 40 * 60) / 5

    /**
     * Rounded to the hour like the published 209 (208.57), 226 (225.95) and 243 (243.33).
     * [saturdayPaidMinutes] is a paid 토요일 the 취업규칙 or 단협 grants (4 or 8 hours), which
     * the same formula adds to the week.
     */
    fun monthly(weeklyMinutes: Int, saturdayPaidMinutes: Int = 0): Int {
        val weekly = weeklyMinutes.coerceIn(0, 40 * 60)
        val hours = (weekly + weeklyHolidayMinutes(weekly) + saturdayPaidMinutes.coerceAtLeast(0)) / 60.0 * 365 / 7 / 12
        return Math.round(hours).toInt()
    }

    /** Average weekly 소정근로 minutes over [weeks] weeks of the roster from [from]. */
    fun averageWeekly(from: LocalDate, weeks: Int = 52, resolve: (LocalDate) -> ResolvedDay): Int {
        var total = 0L
        for (w in 0 until weeks) {
            var week = 0
            for (d in 0 until 7) {
                val day = resolve(from.plusDays(w * 7L + d))
                week += (day.scheduledMinutes + day.leaveMinutes).coerceAtMost(8 * 60)
            }
            total += week.coerceAtMost(40 * 60)
        }
        return (total / weeks).toInt()
    }
}
