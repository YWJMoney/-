package com.bamnat.core.work

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.YearMonth

/**
 * [CAL-46] I 리뷰 N9 "이번 달 내 기록": the month in one line once its last shift is done, "11월: 근무 21일 ·
 * 야간 9번 · 특근 2번 · 연차 1일". Shown once per month on 내 달력, with the month's picture to share.
 */
data class MonthRecap(
    val month: YearMonth,
    val workDays: Int,
    val workMinutes: Int,
    val nights: Int,
    /** Extra work by the kind's name, most first: 특근 2, 대근 1. */
    val extras: List<Pair<String, Int>>,
    val leaveDays: Double,
) {
    val line: String get() = buildString {
        append("${month.monthValue}월: 근무 ${workDays}일")
        if (nights > 0) append(" · 야간 ${nights}번")
        for ((name, n) in extras) append(" · $name ${n}번")
        if (leaveDays > 0) append(" · 연차 ${if (leaveDays % 1.0 == 0.0) leaveDays.toInt().toString() else leaveDays.toString()}일")
    }

    companion object {
        fun of(month: YearMonth, days: List<ResolvedDay>): MonthRecap {
            val inMonth = days.filter { YearMonth.from(it.date) == month }
            return MonthRecap(
                month = month,
                workDays = inMonth.count { it.totalMinutes > 0 },
                workMinutes = inMonth.sumOf { it.totalMinutes },
                nights = inMonth.count(ShiftHealth::night),
                extras = inMonth.flatMap { d -> d.extras.map { it.kind.name } }.groupingBy { it }.eachCount()
                    .entries.sortedByDescending { it.value }.map { it.key to it.value },
                leaveDays = inMonth.sumOf { d -> d.leave?.let { it.quarters / 4.0 } ?: 0.0 },
            )
        }

        /**
         * The month whose recap is due on [today], or null: the month just ended for the first three days of the
         * next, else this month once [today] is past its last working day. [seen] (yyyymm) is the last one shown.
         */
        fun due(today: LocalDate, seen: Int, lastWorkDay: (YearMonth) -> LocalDate?): YearMonth? {
            val month = if (today.dayOfMonth <= 3) YearMonth.from(today).minusMonths(1) else YearMonth.from(today)
            if (key(month) <= seen) return null
            val last = lastWorkDay(month) ?: return null
            return month.takeIf { today.isAfter(last) }
        }

        fun key(month: YearMonth): Int = month.year * 100 + month.monthValue
    }
}
