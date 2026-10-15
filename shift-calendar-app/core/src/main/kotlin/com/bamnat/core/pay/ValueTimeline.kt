package com.bamnat.core.pay

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

enum class Rounding { UP, NEAREST, DOWN }

/**
 * A yearly raise: every year in [month], by [percent] or by a fixed [amount], rounded to
 * [unit] won. E.g. "매년 3월 3.5% 인상, 1,000원 단위 올림" [PAYX-02].
 */
data class RaiseRule(
    val month: Int,
    val percent: Double? = null,
    val amount: Long? = null,
    val unit: Long = 10,
    val rounding: Rounding = Rounding.UP,
) {
    init {
        require(month in 1..12) { "month must be 1..12" }
        require((percent == null) != (amount == null)) { "give either percent or amount" }
        require(unit >= 1)
    }

    fun apply(value: Long): Long {
        val raw = if (percent != null) value * (1 + percent / 100.0) else (value + amount!!).toDouble()
        val steps = raw / unit
        val rounded = when (rounding) {
            Rounding.UP -> ceil(steps - 1e-9)
            Rounding.NEAREST -> steps.roundToLong().toDouble()
            Rounding.DOWN -> floor(steps + 1e-9)
        }
        return rounded.toLong() * unit
    }

    fun describe(): String {
        val how = if (percent != null) "${trim(percent)}%" else "${won(amount!!)}"
        val r = when (rounding) { Rounding.UP -> "올림"; Rounding.NEAREST -> "반올림"; Rounding.DOWN -> "버림" }
        return "매년 ${month}월 $how 인상, ${won(unit)} 단위 $r"
    }
}

/** A value the user typed for a date onward, e.g. 2027-03-01부터 기본급 3,224,000원. */
data class DatedValue(val from: LocalDate, val value: Long)

enum class ValueSource { ENTERED, RAISED, NONE }

/** The value in force on a date and how it was reached, for the explanation screens. */
data class ValueAt(
    val value: Long,
    val source: ValueSource,
    val basedOn: DatedValue?,
    /** Yearly raises applied on top of [basedOn]. */
    val raisesApplied: Int,
)

/**
 * A money value that changes over time [PAYX-02]. Values the user entered win; years without
 * one are filled by the [raise] rule from the latest entered value.
 */
data class ValueTimeline(
    val entries: List<DatedValue> = emptyList(),
    val raise: RaiseRule? = null,
) {
    private val sorted = entries.sortedBy { it.from }

    fun on(date: LocalDate): ValueAt {
        val base = sorted.lastOrNull { !it.from.isAfter(date) } ?: return ValueAt(0, ValueSource.NONE, null, 0)
        val rule = raise ?: return ValueAt(base.value, ValueSource.ENTERED, base, 0)
        var value = base.value
        var n = 0
        var raiseDate = firstRaiseAfter(base.from, rule.month)
        while (!raiseDate.isAfter(date)) {
            value = rule.apply(value)
            n++
            raiseDate = raiseDate.plusYears(1)
        }
        return ValueAt(value, if (n == 0) ValueSource.ENTERED else ValueSource.RAISED, base, n)
    }

    fun on(month: YearMonth): ValueAt = on(month.atDay(1))

    fun with(entry: DatedValue): ValueTimeline =
        copy(entries = sorted.filterNot { it.from == entry.from } + entry)

    fun without(from: LocalDate): ValueTimeline = copy(entries = sorted.filterNot { it.from == from })

    private fun firstRaiseAfter(from: LocalDate, month: Int): LocalDate {
        val sameYear = LocalDate.of(from.year, month, 1)
        return if (sameYear.isAfter(from)) sameYear else sameYear.plusYears(1)
    }
}

internal fun won(v: Long): String = "%,d원".format(v)
internal fun trim(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
