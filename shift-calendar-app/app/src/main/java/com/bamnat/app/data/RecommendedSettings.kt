package com.bamnat.app.data

import com.bamnat.core.consent.RecommendedFeature

/** [ONBOARD-03] The recommended features as the app's own switches, so the one screen reads and writes the same values as 설정 › 알림과 잠. */
object RecommendedSettings {
    fun isOn(s: AppSettings, f: RecommendedFeature): Boolean = when (f) {
        RecommendedFeature.BRIEFING -> s.briefing
        RecommendedFeature.BEDTIME -> s.bedtimeNotice
        RecommendedFeature.COMMUTE -> s.commuteNotice
        RecommendedFeature.TODAY_SHIFT -> s.todayShiftNotice
    }

    fun on(s: AppSettings): Set<RecommendedFeature> = RecommendedFeature.entries.filterTo(LinkedHashSet()) { isOn(s, it) }

    /** [s] with exactly [on] of the recommended features on; every other setting as it was. */
    fun apply(s: AppSettings, on: Set<RecommendedFeature>): AppSettings = s.copy(
        briefing = RecommendedFeature.BRIEFING in on,
        bedtimeNotice = RecommendedFeature.BEDTIME in on,
        commuteNotice = RecommendedFeature.COMMUTE in on,
        todayShiftNotice = RecommendedFeature.TODAY_SHIFT in on,
    )
}
