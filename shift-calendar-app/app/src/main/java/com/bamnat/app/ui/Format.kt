package com.bamnat.app.ui

import com.bamnat.core.model.WorkTime
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Fmt {
    private val korean = Locale.KOREAN
    private val time = DateTimeFormatter.ofPattern("HH:mm")

    fun dayOfWeekShort(d: DayOfWeek): String = d.getDisplayName(TextStyle.SHORT, korean)

    fun time(t: LocalTime?): String = t?.format(time) ?: "--:--"

    fun timeRange(start: LocalTime, end: LocalTime): String =
        "${time(start)} – ${time(end)}" + if (WorkTime.isOvernight(start, end)) " (다음날)" else ""

    fun month(m: YearMonth): String = "${m.year}년 ${m.monthValue}월"

    fun monthShort(m: YearMonth): String = "${m.monthValue}월"

    /** "10월 14일 (수)" */
    fun dayTitle(d: LocalDate): String = "${d.monthValue}월 ${d.dayOfMonth}일 (${dayOfWeekShort(d.dayOfWeek)})"

    /** "2026. 10. 14. (수)" */
    fun fullDate(d: LocalDate): String =
        "${d.year}. ${d.monthValue}. ${d.dayOfMonth}. (${dayOfWeekShort(d.dayOfWeek)})"

    fun shortFullDate(d: LocalDate): String = "${d.year}.${d.monthValue}.${d.dayOfMonth}"

    fun shortDate(d: LocalDate): String = "${d.monthValue}/${d.dayOfMonth}"

    private val wonFormat = java.text.NumberFormat.getIntegerInstance(korean)

    /** "1,250,000원" */
    fun won(amount: Long): String = wonFormat.format(amount) + "원"

    /** Amounts for a calendar cell: "125만", "8천", "500" [WORK-02]. */
    fun wonShort(amount: Long): String {
        val a = kotlin.math.abs(amount)
        val sign = if (amount < 0) "-" else ""
        return sign + when {
            a >= 100_000_000 -> "%.1f억".format(a / 100_000_000.0).replace(".0억", "억")
            a >= 10_000 -> "${a / 10_000}만"
            a >= 1_000 -> "${a / 1_000}천"
            else -> a.toString()
        }
    }

    fun offset(minutes: Int): String = when {
        minutes == 0 -> "시작 시각"
        minutes % 60 == 0 -> "${minutes / 60}시간 전"
        minutes > 60 -> "${minutes / 60}시간 ${minutes % 60}분 전"
        else -> "${minutes}분 전"
    }
}
