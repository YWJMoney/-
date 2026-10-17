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

    /** "10월 17일" */
    fun monthDay(d: LocalDate): String = "${d.monthValue}월 ${d.dayOfMonth}일"

    /** [DAY-03] "을" or "를" after [word]: 대근을, 잔업 2시간을, 휴가를. */
    fun obj(word: String): String = if (batchim(word) != 0) "을" else "를"

    /** [DAY-03] "으로" or "로" after [word]: 야간으로, 데이로, 10월 17일로. */
    fun ro(word: String): String = batchim(word).let { if (it == 0 || it == RIEUL) "로" else "으로" }

    private const val RIEUL = 8

    /** The final consonant index of [word]'s last sound (0 for none), reading digits and a few letters as Korean says them. */
    private fun batchim(word: String): Int {
        val c = word.trimEnd().lastOrNull() ?: return 0
        return when {
            c in '가'..'힣' -> (c - '가') % 28
            // 영 일 삼 육 칠 팔 end in a consonant; 이 사 오 구 do not.
            c.isDigit() -> when (c) { '0' -> 21; '1', '7', '8' -> RIEUL; '3' -> 16; '6' -> 1; else -> 0 }
            c in "LlRr" -> RIEUL
            c in "MmNn" -> 4
            else -> 0
        }
    }

    fun offset(minutes: Int): String = when {
        minutes == 0 -> "시작 시각"
        minutes % 60 == 0 -> "${minutes / 60}시간 전"
        minutes > 60 -> "${minutes / 60}시간 ${minutes % 60}분 전"
        else -> "${minutes}분 전"
    }
}
