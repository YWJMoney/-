package com.bamnat.app.tier

import com.bamnat.app.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Subscription tiers [TIER-01]. Higher tiers include everything below them. */
enum class Tier(val label: String) {
    FREE("무료"),
    PLUS("플러스"),
    PRO("프로"),
    LEADER("리더"),
}

/** Paid features and the lowest tier that unlocks each [TIER-02]. */
enum class Feature(val label: String, val minTier: Tier) {
    NO_ADS("광고 없음", Tier.PLUS),
    PAY_ENGINE("급여 계산", Tier.PRO),
    PAY_RULE_IMPORT("임금 규칙 자동 인식", Tier.PRO),
    PAY_BULK_EDIT("값 일괄 적용", Tier.PRO),
    TEAM_ROSTER("팀 근무표 만들기", Tier.LEADER),
    /** [TIER-05] This month's records and totals stay free; comparisons, trends and breakdowns are pro. */
    MONEY_STATS("돈 통계", Tier.PRO),
    /** [MCAL-07] The main calendar and one added calendar are free; a second added calendar on is pro. */
    MORE_CALENDARS("달력 여러 개", Tier.PRO),
}

/**
 * The one place that answers "can this user use X" [TIER-02]. Until Play billing is connected
 * the tier comes from settings (testers switch it in the alarm diagnostics screen); later the
 * purchase state and Remote Config feed the same flow, and screens do not change.
 */
class FeatureGate(settings: SettingsRepository, scope: CoroutineScope) {
    val tier: StateFlow<Tier> = settings.settings.map { it.tier }.stateIn(scope, SharingStarted.Eagerly, settings.current.tier)

    fun allows(feature: Feature): Boolean = allows(tier.value, feature)

    companion object {
        /**
         * [TIER-02] No Play billing yet, so nobody can buy a tier: every feature stays open until
         * billing ships, otherwise the 1.0 test build shows the pay screen locked with no way in.
         */
        const val BILLING_LIVE = false

        fun allows(tier: Tier, feature: Feature): Boolean = !BILLING_LIVE || tier >= feature.minTier
    }
}
