package com.bamnat.core.memo

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * [NOTE-05] Memos that come back: rent on the 25th every month, an order every Tuesday, a birthday
 * every year. Kept in [Memo.repeat] as "W1".."W7" (ISO day of week, 1 = Monday), "M1".."M31" (day of
 * the month) or "Y0101" (month and day); empty for a memo that is on its own date only. A repeat
 * starts on the memo's date and never shows before it.
 */
object MemoRepeat {
    /** The ways a memo can repeat, as the editor offers them. */
    enum class Kind(val label: String) { NONE("없음"), WEEK("매주"), MONTH("매달"), YEAR("매년") }

    /** The kind of [repeat], or [Kind.NONE] for empty or text this app does not know. */
    fun kindOf(repeat: String): Kind = when {
        WEEKLY.matches(repeat) -> Kind.WEEK
        MONTHLY.matches(repeat) -> Kind.MONTH
        YEARLY.matches(repeat) -> Kind.YEAR
        else -> Kind.NONE
    }

    /** The stored text for repeating [kind] from [date]: its weekday, its day or its month and day. */
    fun code(kind: Kind, date: LocalDate): String = when (kind) {
        Kind.NONE -> ""
        Kind.WEEK -> "W" + date.dayOfWeek.value
        Kind.MONTH -> "M" + date.dayOfMonth
        Kind.YEAR -> "Y%02d%02d".format(date.monthValue, date.dayOfMonth)
    }

    /** "매주 화요일", "매달 25일", "매년 1월 1일", or empty for none. */
    fun label(repeat: String): String {
        val n = repeat.drop(1)
        return when (kindOf(repeat)) {
            Kind.NONE -> ""
            Kind.WEEK -> "매주 " + "월화수목금토일"[n.toInt() - 1] + "요일"
            Kind.MONTH -> "매달 ${n.toInt()}일"
            Kind.YEAR -> "매년 ${n.take(2).toInt()}월 ${n.drop(2).toInt()}일"
        }
    }

    /**
     * Whether [memo] is on [date]: on its own date, or on a later date its repeat falls on. A monthly
     * repeat on a day the month does not have (31, 30, 29) falls on the month's last day, and 2월 29일
     * every year on 2월 28일 in other years.
     */
    fun occursOn(memo: Memo, date: LocalDate): Boolean {
        val start = memo.date ?: return false
        if (date == start) return true
        if (date.isBefore(start)) return false
        val n = memo.repeat.drop(1)
        return when (kindOf(memo.repeat)) {
            Kind.NONE -> false
            Kind.WEEK -> date.dayOfWeek == DayOfWeek.of(n.toInt())
            Kind.MONTH -> date.dayOfMonth == minOf(n.toInt(), date.lengthOfMonth())
            Kind.YEAR -> {
                val month = n.take(2).toInt()
                val day = n.drop(2).toInt()
                date.monthValue == month && date.dayOfMonth == minOf(day, date.month.length(date.isLeapYear))
            }
        }
    }

    /** The days in [from]..[to] that [memo] is on, in order. */
    fun datesIn(memo: Memo, from: LocalDate, to: LocalDate): List<LocalDate> {
        val start = memo.date ?: return emptyList()
        if (kindOf(memo.repeat) == Kind.NONE) return if (start in from..to) listOf(start) else emptyList()
        return generateSequence(maxOf(from, start)) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .filter { occursOn(memo, it) }.toList()
    }

    /**
     * [memos] with each repeating one put on every day of [from]..[to] it comes back on (a copy with
     * that date and the same id, so opening it opens the memo itself); the rest, and a repeat with no
     * day in the range, as they are.
     */
    fun expand(memos: List<Memo>, from: LocalDate, to: LocalDate): List<Memo> = memos.flatMap { m ->
        if (m.date == null || kindOf(m.repeat) == Kind.NONE) listOf(m)
        else datesIn(m, from, to).map { m.copy(date = it) }.ifEmpty { listOf(m) }
    }

    private val WEEKLY = Regex("W[1-7]")
    private val MONTHLY = Regex("M([1-9]|[12]\\d|3[01])")
    private val YEARLY = Regex("Y(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])")
}
