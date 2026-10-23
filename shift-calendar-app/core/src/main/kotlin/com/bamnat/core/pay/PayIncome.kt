package com.bamnat.core.pay

import java.time.YearMonth

/**
 * [LEDG-29] Pay as the bank account sees it: the 실수령 of each pay month whose pay day ([PayPeriod.payDate]) falls in
 * the calendar month, not the 세전 of the work done in it (research/1.23-리뷰 K M1). Without a pay day it is the month's
 * own pay, paid in the month.
 */
object PayIncome {
    data class Paid(val payMonth: YearMonth, val gross: Long, val net: Long)

    /** The pay months paid in [month]: none, one, or (when the pay day moves) two. */
    fun payMonthsIn(profile: PayProfile, month: YearMonth): List<YearMonth> {
        if (profile.period.payDay == null) return listOf(month)
        return (-2..1).map { month.plusMonths(it.toLong()) }.filter { m -> profile.period.payDate(m)?.let { YearMonth.from(it) } == month }
    }

    /** The take-home of [gross], the pay of [payMonth], with the profile's tax setting and 비과세. */
    fun net(profile: PayProfile, payMonth: YearMonth, gross: Long): Long =
        TakeHome.estimate(gross, profile.taxMode, profile.taxFamily, payMonth.atDay(1), profile.taxChildren,
            TakeHome.nonTaxable(profile, payMonth)).net

    /** What arrives in [month], [grossOf] giving a pay month's 세전 total (null when it cannot be worked out). */
    fun paidIn(profile: PayProfile, month: YearMonth, grossOf: (YearMonth) -> Long?): List<Paid> =
        payMonthsIn(profile, month).mapNotNull { m -> grossOf(m)?.let { g -> Paid(m, g, net(profile, m, g)) } }
}
