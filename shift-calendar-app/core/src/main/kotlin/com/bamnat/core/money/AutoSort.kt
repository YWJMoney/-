package com.bamnat.core.money

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import com.bamnat.core.time.Today

/**
 * [LEDG-22] What the user left out of 결제 알림으로 적기: apps whose notifications are not read,
 * words that drop a text, and amounts too small to write down. Applied on the phone before
 * anything is written or kept.
 */
data class CaptureFilter(
    val blockedApps: Set<String> = emptySet(),
    val words: List<String> = emptyList(),
    val minAmount: Long = 0,
) {
    fun drops(app: String, text: String, now: java.time.LocalDateTime = Today.dateTime()): Boolean {
        if (app in blockedApps) return true
        if (words.any { w -> w.isNotBlank() && text.contains(w.trim(), ignoreCase = true) }) return true
        if (minAmount > 0) {
            val amount = (PaymentParser.parse(text, now) as? PaymentParser.Result.Ok)?.parsed?.amount
            if (amount != null && amount < minAmount) return true
        }
        return false
    }

    companion object {
        const val MAX_WORDS = 20
        const val MAX_WORD = 20

        /** A word the user may add: trimmed, short, one line. */
        fun clean(word: String): String = word.replace(Regex("\\s+"), " ").trim().take(MAX_WORD)
    }
}

/**
 * [LEDG-23] Spending still in 기타, one group per place (its branches together), so each place is
 * sorted with one tap and learned for next time.
 */
object OtherPlaces {
    data class Group(val place: String, val entries: List<MoneyEntry>) {
        val total: Long get() = entries.sumOf { it.amount }
    }

    const val DAYS = 90L

    fun groups(entries: List<MoneyEntry>, today: LocalDate): List<Group> =
        entries.asSequence()
            .filter {
                it.kind == MoneyKind.EXPENSE && it.category == MoneyCategories.OTHER && it.parts.isEmpty() && it.amount > 0 &&
                    it.merchant.isNotBlank() && !it.date.isAfter(today) && ChronoUnit.DAYS.between(it.date, today) <= DAYS
            }
            .groupBy { PaymentParser.placeKey(it.merchant).ifEmpty { it.merchant } }
            .values
            .map { list -> Group(list.maxBy { it.date }.merchant, list.sortedByDescending { it.date }) }
            .sortedWith(compareByDescending<Group> { it.entries.size }.thenByDescending { it.total })
}

/**
 * [LEDG-24] Spending that looks like it comes back every month (a subscription, rent, a phone bill
 * paid by card): the same place in at least three of the last four months, close in amount and day,
 * no more than about once a month, and not already a 반복 지출. Offered, never added by itself.
 */
object RepeatFinder {
    data class Suggestion(val place: String, val amount: Long, val day: Int, val category: String, val months: Int) {
        /** What 아니요 remembers, so the same place is not offered again. */
        val key: String get() = PaymentParser.placeKey(place)

        fun toFixedCost(): FixedCost = FixedCost(name = place.take(30), amount = amount, day = day, category = category)
    }

    fun find(entries: List<MoneyEntry>, costs: List<FixedCost>, today: LocalDate, dismissed: Set<String> = emptySet(), limit: Int = 3): List<Suggestion> {
        val thisMonth = YearMonth.from(today)
        val from = thisMonth.minusMonths(3)
        val lines = entries.filter {
            it.kind == MoneyKind.EXPENSE && it.amount > 0 && it.merchant.isNotBlank() && it.fixedId == null &&
                !YearMonth.from(it.date).isBefore(from) && !it.date.isAfter(today)
        }
        return lines.groupBy { PaymentParser.placeKey(it.merchant) }
            .filterKeys { it.length >= 2 && it !in dismissed }
            .mapNotNull { (_, list) -> suggestion(list, thisMonth) }
            .filter { s -> costs.none { PaymentParser.samePlace(it.name, s.place) } }
            .sortedByDescending { it.amount }
            .take(limit)
    }

    private fun suggestion(list: List<MoneyEntry>, thisMonth: YearMonth): Suggestion? {
        val byMonth = list.groupBy { YearMonth.from(it.date) }
        if (byMonth.size < 3) return null
        // Still going: last month or this one has it.
        if (thisMonth !in byMonth && thisMonth.minusMonths(1) !in byMonth) return null
        // A place used every week is a habit, not a bill.
        if (list.size > byMonth.size + 1) return null
        val amounts = list.map { it.amount }.sorted()
        val middle = amounts[amounts.size / 2]
        val each = byMonth.values.map { m -> m.minBy { kotlin.math.abs(it.amount - middle) } }
        if (each.any { kotlin.math.abs(it.amount - middle) * 10 > middle }) return null
        val days = each.map { it.date.dayOfMonth }
        if (days.max() - days.min() > 6) return null
        val last = each.maxBy { it.date }
        return Suggestion(last.merchant, last.amount, last.date.dayOfMonth, last.category, byMonth.size)
    }
}
