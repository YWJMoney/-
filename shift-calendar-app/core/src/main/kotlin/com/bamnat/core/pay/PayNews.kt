package com.bamnat.core.pay

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max

/**
 * [PAYX-43] 근무 교환 급여 영향: the month with some days changed (a swap with a colleague), worked out twice by the
 * same pure calculator, and the longest week's hours before and after, so the 교환 도우미 (I 묶음) can say
 * "이번 달 −48,000원, 가장 긴 주 48→56시간" before anything is sent.
 */
object SwapImpact {
    data class Result(val before: Long, val after: Long, val longestWeekBefore: Int, val longestWeekAfter: Int) {
        val diff: Long get() = after - before
        /** Over 52 hours a week after the swap and not before (근로기준법 제53조). */
        val overLimit: Boolean get() = longestWeekAfter > 52 * 60 && longestWeekBefore <= 52 * 60
    }

    fun of(
        profile: PayProfile, month: YearMonth, resolve: (LocalDate) -> ResolvedDay, changed: Map<LocalDate, ResolvedDay>,
        calculator: PayCalculator = PayCalculator(),
    ): Result {
        val after = { d: LocalDate -> changed[d] ?: resolve(d) }
        val a = calculator.calculate(profile, month, resolve, checks = false)
        val b = calculator.calculate(profile, month, after, checks = false)
        return Result(a.total, b.total, longestWeek(a), longestWeek(b))
    }

    private fun longestWeek(pay: MonthPay): Int {
        if (pay.minutes.isEmpty()) return 0
        return pay.minutes.groupBy { it.date.minusDays(((it.date.dayOfWeek.value + 6) % 7).toLong()) }.values.maxOf { w -> w.sumOf { it.worked } }
    }
}

/**
 * [PAYX-44] 최저임금·법 개정 알림: once, when a newer rates year starts (or a newer rates file brings one), what
 * changed and how the user's own 시급 stands against the new 최저임금 (최저임금법 제6조).
 */
object RateNews {
    data class News(val from: LocalDate, val version: String, val lines: List<String>, val belowMinimum: Boolean) {
        /** The key the app keeps so the card shows once. */
        val key: String get() = "rates-$version"
    }

    fun of(today: LocalDate, hourly: Long?, table: List<Rates> = RateTable.current): News? {
        val sorted = table.sortedBy { it.effectiveFrom }
        // The year that starts within 45 days ahead or started within the last 60.
        val now = sorted.lastOrNull { !it.effectiveFrom.isAfter(today.plusDays(45)) } ?: return null
        if (now.effectiveFrom.isBefore(today.minusDays(60))) return null
        val before = sorted.lastOrNull { it.effectiveFrom.isBefore(now.effectiveFrom) } ?: return null
        val lines = buildList {
            if (now.minimumHourlyWage != before.minimumHourlyWage) {
                val up = now.minimumHourlyWage - before.minimumHourlyWage
                add("최저임금 시급 ${"%,d".format(now.minimumHourlyWage)}원 (${if (up > 0) "+" else ""}${"%,d".format(up)}원), 월 209시간 ${"%,d".format(now.minimumHourlyWage * 209L)}원")
            }
            fun pct(v: Double?) = v?.let { "%.3f".format(it * 100).trimEnd('0').trimEnd('.') + "%" } ?: "발표 전"
            if (now.healthRate != before.healthRate) add("건강보험료율 ${pct(before.healthRate)} → ${pct(now.healthRate)}")
            if (now.pensionRate != before.pensionRate) add("국민연금 보험료율 ${pct(before.pensionRate)} → ${pct(now.pensionRate)}")
            if (now.longTermCareRate != before.longTermCareRate) add("장기요양보험료율 ${pct(before.longTermCareRate)} → ${pct(now.longTermCareRate)}")
            if (now.unemploymentRate != before.unemploymentRate) add("고용보험료율 ${pct(before.unemploymentRate)} → ${pct(now.unemploymentRate)}")
        }
        if (lines.isEmpty()) return null
        val below = hourly != null && hourly > 0 && hourly < now.minimumHourlyWage
        return News(now.effectiveFrom, now.version, lines + listOfNotNull(
            hourly?.takeIf { it > 0 }?.let { h ->
                if (below) "내 시급 ${"%,d".format(h)}원은 새 최저임금보다 ${"%,d".format(now.minimumHourlyWage - h)}원 적어요. 회사에 확인해 보세요."
                else "내 시급 ${"%,d".format(h)}원은 새 최저임금보다 ${"%,d".format(max(0, h - now.minimumHourlyWage))}원 많아요."
            },
        ), below)
    }
}
