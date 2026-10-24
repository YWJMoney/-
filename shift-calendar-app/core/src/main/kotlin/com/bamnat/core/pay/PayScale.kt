package com.bamnat.core.pay

import java.time.LocalDate

/**
 * [PAYX-20] 호봉표: the 기본급 of each step (호봉) and the month a step goes up every year. The 인상 규칙's second
 * step [PAYX-02]: instead of "매년 3.5%", the company's own table, e.g. 1호봉 2,400,000원, 2호봉 2,480,000원 …
 * Turned into the dated values of a [ValueTimeline], so the calculator and every screen read it as before and a
 * value the user enters later still wins.
 */
data class PayScale(
    /** 기본급 by step, from step 1 (or the lowest the company uses) up. */
    val steps: Map<Int, Long>,
    /** The month a step goes up each year, 1..12 (승급 월, e.g. 1월 or the hire month). */
    val stepMonth: Int,
    /** The step on [since]. */
    val step: Int,
    val since: LocalDate,
) {
    init {
        require(stepMonth in 1..12) { "stepMonth must be 1..12" }
        require(steps.isNotEmpty()) { "a pay scale needs steps" }
    }

    val top: Int get() = steps.keys.max()

    /** The step on [date]: one more on each [stepMonth] 1일 after [since], never above the table's top. */
    fun stepOn(date: LocalDate): Int {
        if (date.isBefore(since)) return step
        var d = LocalDate.of(since.year, stepMonth, 1).let { if (it.isAfter(since)) it else it.plusYears(1) }
        var s = step
        while (!d.isAfter(date) && s < top) { s++; d = d.plusYears(1) }
        return s
    }

    /** The table as dated values from [since] through [until], one per step change. */
    fun values(until: LocalDate): List<DatedValue> {
        val out = mutableListOf<DatedValue>()
        steps[step]?.let { out += DatedValue(since, it) }
        var d = LocalDate.of(since.year, stepMonth, 1).let { if (it.isAfter(since)) it else it.plusYears(1) }
        var s = step
        while (!d.isAfter(until) && s < top) {
            s++
            steps[s]?.let { out += DatedValue(d, it) }
            d = d.plusYears(1)
        }
        return out
    }

    /** [base] with the table's values put in from [since] on, and no yearly raise (the table is the raise). */
    fun applyTo(base: ValueTimeline, until: LocalDate): ValueTimeline =
        ValueTimeline(base.entries.filter { it.from.isBefore(since) } + values(until), raise = null)

    fun describe(): String = "호봉표 ${steps.size}단계, 매년 ${stepMonth}월 승급, ${since.year}년 ${step}호봉부터"

    companion object {
        /** [PAYX-47] What a row added under [amounts] starts at: the last step plus the last step's raise (never less). */
        fun nextAmount(amounts: List<Long>): Long = when (amounts.size) {
            0 -> 2_400_000
            1 -> amounts[0]
            else -> amounts.last() + (amounts.last() - amounts[amounts.size - 2]).coerceAtLeast(0)
        }

        /**
         * Reads a table pasted as text: one step per line, "1호봉 2,400,000" or "1 2400000" (the 호봉 and the
         * amount, tabs or spaces between).
         */
        fun parse(text: String): Map<Int, Long> = text.lines().mapNotNull { line ->
            val m = Regex("""^\s*(\d{1,2})\s*(?:호봉|호|단계)?[\s:,\t=]+([\d,]{4,})\s*원?\s*$""").find(line) ?: return@mapNotNull null
            val step = m.groupValues[1].toInt()
            val amount = m.groupValues[2].replace(",", "").toLongOrNull() ?: return@mapNotNull null
            step to amount
        }.toMap()
    }
}
