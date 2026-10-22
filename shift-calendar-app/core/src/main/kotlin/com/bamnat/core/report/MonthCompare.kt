package com.bamnat.core.report

import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.MonthSummary
import com.bamnat.core.pay.MonthPay
import java.time.YearMonth
import kotlin.math.abs

/**
 * [PROV-02] This month beside last month and the same month a year ago, and a year month by month
 * (프로). Hours come from the calendar as the 통계 tiles count them; night work, work on days off and the
 * pay come from the pay calculation when there is one.
 */
object MonthCompare {
    data class Side(
        val month: YearMonth,
        val worked: Int,
        val workDays: Int,
        val extra: Int,
        /** 휴일·쉬는 날 근무. */
        val holiday: Int,
        /** Only with a pay calculation, which sorts the night minutes. */
        val night: Int?,
        val pay: Long?,
    ) {
        /** Nothing on the calendar that month. */
        val empty: Boolean get() = worked == 0 && (pay ?: 0L) == 0L
    }

    fun side(summary: MonthSummary, pay: MonthPay?): Side {
        val f = pay?.let(PayReport::figures)
        return Side(
            month = summary.month,
            worked = summary.totalMinutes,
            workDays = summary.workDays,
            extra = summary.extraMinutes,
            holiday = f?.holiday ?: summary.restDayExtraMinutes,
            night = f?.night,
            pay = pay?.total,
        )
    }

    data class Row(val label: String, val now: String, val before: String, val change: String, val more: Boolean?)

    /** The rows both months can fill. */
    fun rows(now: Side, before: Side): List<Row> = buildList {
        fun minutes(label: String, a: Int, b: Int) =
            add(Row(label, HoursFormat.korean(a), HoursFormat.korean(b), change((a - b).toLong()) { HoursFormat.korean(it.toInt()) }, sign((a - b).toLong())))
        minutes("근무시간", now.worked, before.worked)
        val days = (now.workDays - before.workDays).toLong()
        add(Row("근무일", "${now.workDays}일", "${before.workDays}일", change(days) { "${it}일" }, sign(days)))
        if (now.night != null && before.night != null) minutes("야간", now.night, before.night)
        minutes("휴일·쉬는 날 근무", now.holiday, before.holiday)
        minutes("추가 근무", now.extra, before.extra)
        if (now.pay != null && before.pay != null) {
            val d = now.pay - before.pay
            add(Row("급여(세전 예상)", PayReport.won(now.pay), PayReport.won(before.pay), change(d) { PayReport.won(it) }, sign(d)))
        }
    }

    /** "+3시간 30분", "−2일", "같아요". */
    private inline fun change(d: Long, text: (Long) -> String): String = when {
        d == 0L -> "같아요"
        d > 0 -> "+" + text(d)
        else -> "−" + text(abs(d))
    }

    /** null when nothing changed. */
    private fun sign(d: Long): Boolean? = if (d == 0L) null else d > 0

    /** [PROV-02] The months of [year] for the year card, with their totals. */
    data class Year(val year: Int, val months: List<Side>) {
        val worked: Int get() = months.sumOf { it.worked }
        val night: Int? get() = if (months.any { it.night == null }) null else months.sumOf { it.night ?: 0 }
        val holiday: Int get() = months.sumOf { it.holiday }
        val extra: Int get() = months.sumOf { it.extra }
        val pay: Long? get() = if (months.any { it.pay == null }) null else months.sumOf { it.pay ?: 0L }
        val workDays: Int get() = months.sumOf { it.workDays }
    }
}
