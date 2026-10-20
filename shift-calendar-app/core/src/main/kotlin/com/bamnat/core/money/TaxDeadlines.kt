package com.bamnat.core.money

import com.bamnat.core.work.WorkStyle
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SHOP-04] Tax deadlines worth a nudge: 부가세 on 1/25 and 7/25 for a shop, and 종합소득세 on 5/31
 * for a shop and for field work (3.3% 환급). Each is told a week ahead and the day before, at nine.
 * The dates are the usual ones; a deadline that falls on a weekend moves, which the 홈택스 notice says.
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
                if (shop) Deadline(LocalDate.of(y, 1, 25), "부가세 신고", "지난해 7~12월 부가세 신고·납부 마감이에요") else null,
                Deadline(LocalDate.of(y, 5, 31), "종합소득세 신고", "지난해 소득 신고 마감이에요. 3.3% 뗀 돈은 이때 돌려받을 수 있어요"),
                if (shop) Deadline(LocalDate.of(y, 7, 25), "부가세 신고", "올해 1~6월 부가세 신고·납부 마감이에요") else null,
            )
        }.filter { !it.date.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }
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
