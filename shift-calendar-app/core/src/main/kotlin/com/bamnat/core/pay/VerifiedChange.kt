package com.bamnat.core.pay

import java.time.LocalDate
import java.time.YearMonth

/**
 * [PAYX-22] A month the user matched against a payslip ([PayProfile.verified]) is a month they trust. When a
 * change to the pay setup would give that month another total, the app says so, and offers to start the change
 * with the current pay period, keeping the matched month as it was.
 */
object VerifiedChange {
    data class Change(val month: YearMonth, val before: Long, val after: Long) {
        val text: String
            get() = "${month.year}년 ${month.monthValue}월은 명세서와 맞춰 본 달이에요. 이 바꿈으로 그 달이 ${won(before)}에서 ${won(after)}이 돼요."
    }

    /** What happens to the matched month when [old] becomes [new]; null when nothing changes there. */
    fun check(old: PayProfile, new: PayProfile, totalFor: (PayProfile, YearMonth) -> Long): Change? {
        val v = new.verified ?: old.verified ?: return null
        val before = totalFor(old, v.month)
        val after = totalFor(new, v.month)
        return if (before == after) null else Change(v.month, before, after)
    }

    /**
     * [new] with its changed values starting on [start] (the current pay period's first day) and the values
     * before it as in [old]. Only values over time can start later: null when anything else changed (company
     * rules, items added or removed), whose change has no date.
     */
    fun startingOn(old: PayProfile, new: PayProfile, start: LocalDate): PayProfile? {
        if (old.items.map { it.id } != new.items.map { it.id }) return null
        val sameElse = new.copy(base = old.base, items = old.items, estimatedFields = old.estimatedFields) ==
            old.copy(estimatedFields = old.estimatedFields)
        if (!sameElse) return null
        // A new yearly raise reaches back over every year; it has no start date either.
        if (old.base.raise != new.base.raise || old.items.zip(new.items).any { (a, b) -> a.amount.raise != b.amount.raise }) return null
        fun scoped(was: ValueTimeline, now: ValueTimeline): ValueTimeline {
            if (was == now) return was
            val before = was.entries.filter { it.from.isBefore(start) }
            val after = now.entries.filter { !it.from.isBefore(start) }
            // The value in force on [start] under the new setup, so the change begins there.
            val first = DatedValue(start, now.on(start).value)
            return ValueTimeline(before + first + after.filterNot { it.from == start }, now.raise)
        }
        return new.copy(
            base = scoped(old.base, new.base),
            items = new.items.mapIndexed { i, it -> it.copy(amount = scoped(old.items[i].amount, it.amount)) },
        )
    }
}
