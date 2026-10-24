package com.bamnat.core.imports

/**
 * [ROST-08] Filling the rest of a month from what was typed (리뷰 개선 다): D D E E N N O O typed from the 1st, then
 * "이 순서 반복" carries the same order to the month's end. Works on day numbers, for any kind of cell.
 */
object MonthFill {
    /**
     * The order typed so far, carried from [from] to [last]: the days typed one after another, ending the day before
     * [from], are the order (at least two). Days already typed after [from] are kept as they are. Null when there is
     * no order to repeat.
     */
    fun <T> repeat(typed: Map<Int, T>, from: Int, last: Int): Map<Int, T>? {
        if (from !in 2..last) return null
        var start = from - 1
        if (start !in typed) return null
        while (start - 1 in typed) start--
        val cycle = (start until from).map { typed.getValue(it) }
        if (cycle.size < 2) return null
        return (from..last).filter { it !in typed }.associateWith { cycle[(it - start) % cycle.size] }.takeIf { it.isNotEmpty() }
    }
}
