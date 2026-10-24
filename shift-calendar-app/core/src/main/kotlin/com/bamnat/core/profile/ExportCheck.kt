package com.bamnat.core.profile

/**
 * [EASY-07] Before something that carries money leaves the phone (급여 리포트 PDF·CSV, 노무사 상담 묶음): the
 * money facts still guessed, for one "추정 n개 확인" sheet. None: it goes straight out.
 */
object ExportCheck {
    /** The exports this check stands in front of, by the registry feature that makes each. */
    val EXPORTS = setOf("pay.report", "pay.counsel")

    /** The keys an export reads: the pay facts every amount is worked from, and what that export itself needs. */
    fun keysFor(feature: String): Set<FactKey> = PAY_BASIS + FeatureNeeds.of(feature)

    /** The guessed facts of [facts] that [feature] reads, in the profile's order. */
    fun estimated(facts: Map<FactKey, Fact<*>>, feature: String): List<Fact<*>> {
        val keys = keysFor(feature)
        return FactKey.entries.filter { it in keys }.mapNotNull { facts[it] }.filter { it.estimated }
    }

    private val PAY_BASIS = setOf(
        FactKey.WAGE_TYPE, FactKey.BASE_PAY, FactKey.PAY_ITEMS, FactKey.MONTHLY_HOURS, FactKey.HOLIDAY_PAY,
        FactKey.NIGHT_OT, FactKey.PUBLIC_HOLIDAYS, FactKey.FIVE_OR_MORE, FactKey.TAX_MODE, FactKey.TAX_FAMILY,
    )
}
