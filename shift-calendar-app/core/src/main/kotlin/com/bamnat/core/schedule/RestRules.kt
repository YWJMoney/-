package com.bamnat.core.schedule

/**
 * [REST-01][REST-02] One set of limits for every roster check: the calendar's 휴식 점검 ([RestCheck]) and the
 * month typed at once ([DutyCheck]) read the same values, so a hospital's own rules are set once. A limit of 0
 * is not checked. None of these stop anything from being saved; they are signs to look at.
 *
 * Where the defaults come from (계획/1.22-C-직군-근무표-휴식-개발계획.md 2장, checked 2026-10-22):
 * - 11 hours between shifts: 근로기준법 제59조 제2항 (특례업종 연속 11시간 휴식), used as a common floor.
 * - 2 days off after nights, at most 3 nights in a row, 15 nights a month: 보건복지부 간호인력 야간근무 가이드라인
 *   (2019, 권고; 월 14일 → 15일 2023. 10.), 고시 원문 확인 필요.
 * - 6 work days in a row: 근로기준법 제55조 (1주 1회 이상 유급휴일).
 */
data class RestRules(
    /** false turns every roster check off. */
    val on: Boolean = true,
    val minRestHours: Int = 11,
    /** Days off after the last night of a run. */
    val offsAfterNights: Int = 2,
    val maxNightsInRow: Int = 3,
    /** More work days in a row than this has no weekly day off. */
    val maxWorkDaysInRow: Int = 6,
    val maxNightsPerMonth: Int = 15,
) {
    /** One line for the settings file, e.g. "on=1;rest=11;offs=2;nights=3;run=6;month=15". */
    fun encode(): String = "on=${if (on) 1 else 0};rest=$minRestHours;offs=$offsAfterNights;nights=$maxNightsInRow;run=$maxWorkDaysInRow;month=$maxNightsPerMonth"

    /** Which preset these values are, or null when the user changed them one by one. */
    val preset: Preset? get() = Preset.entries.firstOrNull { it.rules == copy(on = true) }

    enum class Preset(val label: String, val note: String, val rules: RestRules) {
        BASIC("기본", "휴식 11시간 · 나이트 뒤 쉬는 날 2일 · 연속 나이트 3일 · 7일 연속 근무 · 한 달 나이트 15일", RestRules()),
        STRICT("더 엄격하게", "연속 나이트 2일부터 알려요 (교대근무 건강 연구의 권고)", RestRules(maxNightsInRow = 2)),
        LIGHT("느슨하게", "휴식 11시간과 7일 연속 근무만 봐요", RestRules(offsAfterNights = 0, maxNightsInRow = 0, maxNightsPerMonth = 0)),
    }

    /** Where each sign comes from, shown under it [REST-02]. */
    object Sources {
        const val REST = "근로기준법 제59조 제2항(연속 11시간 휴식)을 기준으로 삼은 값"
        const val NIGHTS = "보건복지부 간호인력 야간근무 가이드라인(권고): 연속 야간 3일 이하, 야간 뒤 48시간 휴식, 월 15일"
        const val ORDER = "교대 순서는 앞으로 도는 것(주→오후→야간)이 몸에 덜 부담된다는 교대근무 연구 권고"
        const val WEEK = "근로기준법 제55조: 1주에 1일 이상 유급휴일"
    }

    companion object {
        /**
         * Reads [encode]'s line; a missing or broken value keeps its default. Before 1.22 there was only
         * '더 엄격하게' ([legacyStrict]): an empty line becomes [Preset.STRICT] for those who had it on.
         */
        fun decode(s: String?, legacyStrict: Boolean = false): RestRules {
            if (s.isNullOrBlank()) return if (legacyStrict) Preset.STRICT.rules else RestRules()
            val m = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            fun int(k: String, d: Int, range: IntRange) = m[k]?.toIntOrNull()?.takeIf { it in range } ?: d
            val d = RestRules()
            return RestRules(
                on = m["on"]?.let { it != "0" } ?: true,
                minRestHours = int("rest", d.minRestHours, 0..24),
                offsAfterNights = int("offs", d.offsAfterNights, 0..7),
                maxNightsInRow = int("nights", d.maxNightsInRow, 0..14),
                maxWorkDaysInRow = int("run", d.maxWorkDaysInRow, 0..31),
                maxNightsPerMonth = int("month", d.maxNightsPerMonth, 0..31),
            )
        }

        /** The calendar's rules before 1.22, kept for [RestCheck.check] callers that pass only strict. */
        internal fun legacy(strict: Boolean) = RestRules(
            offsAfterNights = if (strict) 2 else 0,
            maxNightsInRow = if (strict) 2 else 3,
            maxWorkDaysInRow = 0,
            maxNightsPerMonth = 0,
        )
    }
}
