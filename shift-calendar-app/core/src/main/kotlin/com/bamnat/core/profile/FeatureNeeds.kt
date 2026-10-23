package com.bamnat.core.profile

/**
 * [EASY-04] What a feature waits for: until these facts are known it stays on but shows "이걸 알려 주면
 * 시작해요" instead of doing nothing quietly. Kept here and not in [com.bamnat.core.guide.FeatureRegistry]
 * itself, which every bundle edits (결정 N-D1). Each fact a feature needs must list that feature in its
 * [FactKey.usedBy]; `ProfileCatalogTest` checks both ways against the registry.
 */
object FeatureNeeds {
    val all: Map<String, Set<FactKey>> = mapOf(
        "pay.calc" to setOf(FactKey.WAGE_TYPE, FactKey.BASE_PAY, FactKey.PAY_DAY),
        "pay.report" to setOf(FactKey.BASE_PAY, FactKey.PAY_DAY),
        "pay.whatIf" to setOf(FactKey.BASE_PAY),
        "pay.gap" to setOf(FactKey.MONTHLY_HOURS),
        "pay.slipAudit" to setOf(FactKey.PAY_ITEMS),
        "pay.compLeave" to setOf(FactKey.DAILY_HOURS),
        "hourly.takeHome" to setOf(FactKey.WAGE_TYPE, FactKey.TAX_MODE),
        "hourly.weeks" to setOf(FactKey.HOLIDAY_PAY),
        "leave.ledger" to setOf(FactKey.HIRE_DATE, FactKey.FIVE_OR_MORE),
        "leave.promotion" to setOf(FactKey.HIRE_DATE),
        "leave.payout" to setOf(FactKey.HIRE_DATE, FactKey.BASE_PAY),
        "shift.pattern" to setOf(FactKey.SHIFTS, FactKey.ROTATION),
        "shift.premium" to setOf(FactKey.NIGHT_OT),
        "shift.teams" to setOf(FactKey.TEAM),
        "widget.teams" to setOf(FactKey.TEAM),
        "alarm.check" to setOf(FactKey.ALARM_LEAD),
        "today.flow" to setOf(FactKey.SLEEP_TARGET),
        "day.overtimeCap" to setOf(FactKey.OVERTIME_CAP),
        "shop.days" to setOf(FactKey.SHOP_RULES),
        "shop.cardDue" to setOf(FactKey.SHOP_MONEY),
        "field.mode" to setOf(FactKey.FIELD_RULES),
    )

    fun of(featureId: String): Set<FactKey> = all[featureId].orEmpty()
}
