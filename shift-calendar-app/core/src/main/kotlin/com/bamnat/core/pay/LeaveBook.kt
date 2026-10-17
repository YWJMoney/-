package com.bamnat.core.pay

import com.bamnat.core.model.LeaveKind
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 연차 used in a year against what the company grants [FEAT-10]. */
data class LeaveBalance(
    val year: Int,
    /** Days granted for the year; null when the user has not said. */
    val granted: Double?,
    val used: Double,
    /** Leave recorded on dates still ahead (planned). */
    val planned: Double,
    val byKind: Map<LeaveKind, Double>,
    /** Days carried over from last year (이월) [PAY-12][REL-08]; part of [granted]. */
    val carried: Double = 0.0,
) {
    val remaining: Double? get() = granted?.let { it - used - planned }

    /** The last day this year's 연차 can be used (회계연도 기준). */
    val expiresOn: LocalDate get() = LocalDate.of(year, 12, 31)

    /**
     * [PAY-12] Days that will lapse unless used, from November on (two months' notice, like the
     * 사용촉진 of 근로기준법 제61조); null when none or too early to say.
     */
    fun expiring(today: LocalDate): Double? {
        val left = remaining ?: return null
        if (left <= 0 || today.year != year || today.monthValue < 11) return null
        return left
    }
}

object LeaveBook {
    /** [PAY-12][REL-08] Lapse notices go out on these dates at 09:00: two months and one month ahead of 12/31. */
    val NOTICE_DAYS = listOf(java.time.MonthDay.of(11, 1), java.time.MonthDay.of(12, 1))

    /** The next lapse notice after [now]. */
    fun nextNotice(now: java.time.LocalDateTime): java.time.LocalDateTime =
        (now.year..now.year + 1).asSequence()
            .flatMap { y -> NOTICE_DAYS.asSequence().map { it.atYear(y).atTime(9, 0) } }
            .first { it.isAfter(now) }

    /** Counts leave on working days of [year]; [resolve] is the calendar. */
    fun balance(
        year: Int, granted: Double?, today: LocalDate, resolve: (LocalDate) -> ResolvedDay,
        /** Dates whose leave does not use up 연차, e.g. 공휴일 at some companies. */
        free: (LocalDate) -> Boolean = { false },
        /** Days carried over from last year, added to [granted]. */
        carried: Double = 0.0,
    ): LeaveBalance {
        var used = 0.0
        var planned = 0.0
        val byKind = linkedMapOf<LeaveKind, Double>()
        var d = LocalDate.of(year, 1, 1)
        while (d.year == year) {
            val leave = resolve(d).leave
            if (leave != null) {
                byKind[leave.kind] = (byKind[leave.kind] ?: 0.0) + leave.days
                if (leave.kind.annual && !free(d)) if (d.isAfter(today)) planned += leave.days else used += leave.days
            }
            d = d.plusDays(1)
        }
        return LeaveBalance(year, granted?.let { it + carried }, used, planned, byKind, carried)
    }

    /**
     * 연차 the law grants by length of service on [on], counted from [hireDate] (입사일 기준)
     * [PAY-10]. 근로기준법 제60조: under one year, 1 day per month fully attended, so at most 11
     * (제2항); from one year, 15 days (제1항), plus 1 day for every 2 years beyond the first, at most
     * 25 (제4항). Assumes full attendance (80% 이상 출근) and a workplace of 5 or more (시행령 별표1).
     */
    fun legalDays(hireDate: LocalDate, on: LocalDate): Int {
        if (on.isBefore(hireDate)) return 0
        val years = ChronoUnit.YEARS.between(hireDate, on).toInt()
        if (years < 1) return ChronoUnit.MONTHS.between(hireDate, on).toInt().coerceAtMost(11)
        return (15 + (years - 1) / 2).coerceAtMost(25)
    }

    /** "3.75일", "2일". */
    fun days(value: Double): String =
        if (value == Math.floor(value)) "${value.toLong()}일" else "${String.format(java.util.Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')}일"
}
