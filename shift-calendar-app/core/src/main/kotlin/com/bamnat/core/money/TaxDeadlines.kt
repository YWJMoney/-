package com.bamnat.core.money

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.work.WorkStyle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SHOP-04] Tax deadlines worth a nudge: 부가세 on 1/25 and 7/25 for a shop, and 종합소득세 on 5/31
 * for a shop and for field work (3.3% 환급). Each is told a week ahead and the day before, at nine.
 * A deadline on a weekend or public holiday moves to the next working day (국세기본법 제5조).
 */
object TaxDeadlines {
    data class Deadline(val date: LocalDate, val name: String, val text: String)
    data class Alert(val at: LocalDateTime, val key: String, val title: String, val text: String)

    val AT: LocalTime = LocalTime.of(9, 0)

    /** Deadlines for [styles] from [from] to [to]. */
    fun deadlines(from: LocalDate, to: LocalDate, styles: Set<WorkStyle>): List<Deadline> {
        val shop = WorkStyle.BUSINESS in styles
        val income = shop || WorkStyle.FIELD in styles
        if (!income) return emptyList()
        return (from.year..to.year).flatMap { y ->
            listOfNotNull(
                if (shop) Deadline(workday(LocalDate.of(y, 1, 25)), "부가세 신고", "지난해 7~12월 부가세 신고·납부 마감이에요") else null,
                Deadline(workday(LocalDate.of(y, 5, 31)), "종합소득세 신고", "지난해 소득 신고 마감이에요. 3.3% 뗀 돈은 이때 돌려받을 수 있어요"),
                if (shop) Deadline(workday(LocalDate.of(y, 7, 25)), "부가세 신고", "올해 1~6월 부가세 신고·납부 마감이에요") else null,
            )
        }.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }
    }

    /** [date], or the next day that is not a weekend or a public holiday. */
    fun workday(date: LocalDate): LocalDate {
        var d = date
        while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || KoreanHolidays.isHoliday(d)) d = d.plusDays(1)
        return d
    }

    /** Reminders a week before and the day before each deadline from [today] on (about 13 months ahead). */
    fun alerts(today: LocalDate, styles: Set<WorkStyle>): List<Alert> =
        deadlines(today, today.plusMonths(13), styles).flatMap { d ->
            listOf(7L to "일주일 뒤", 1L to "내일").mapNotNull { (before, word) ->
                val day = d.date.minusDays(before)
                if (day.isBefore(today)) null
                else Alert(day.atTime(AT), "tax-${d.date}-$before", "$word ${d.name}", "${d.date.monthValue}월 ${d.date.dayOfMonth}일 · ${d.text}")
            }
        }
}
