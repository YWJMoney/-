package com.bamnat.core.pay

import java.time.LocalDate

/** A suggested allowance in a job preset; the wizard shows these as tick boxes [PAYX-01]. */
data class PresetItem(
    val id: String,
    val name: String,
    val cadence: Cadence,
    val suggestedAmount: Long,
    val ordinary: Boolean = false,
    val checked: Boolean = true,
    val hint: String = "",
)

data class OccupationPreset(
    val occupation: Occupation,
    val wageType: WageType,
    /** Typical starting value, only a placeholder for the input field. */
    val baseHint: Long,
    val items: List<PresetItem>,
    val dailyLimitMinutes: Int? = 8 * 60,
    val note: String = "",
)

/**
 * Starting points per job. Amounts are placeholders the user is asked to replace with their
 * own pay slip; anything left untouched is marked "추정".
 */
object PayPresets {
    val all: List<OccupationPreset> = listOf(
        OccupationPreset(
            Occupation.NURSE, WageType.MONTHLY, 3_000_000,
            listOf(
                PresetItem("night", "나이트 수당", Cadence.PER_NIGHT_SHIFT, 30_000, hint = "야간근무 1회당 정액"),
                PresetItem("shift", "교대근무 수당", Cadence.MONTHLY, 100_000, ordinary = true),
                PresetItem("license", "면허·자격 수당", Cadence.MONTHLY, 50_000, ordinary = true, checked = false),
                PresetItem("meal", "식대", Cadence.MONTHLY, 200_000, checked = false, hint = "월 20만원까지 비과세"),
            ),
            note = "병원마다 수당 이름과 금액이 달라요. 급여명세서를 보고 맞춰 주세요",
        ),
        OccupationPreset(
            Occupation.PART_TIME, WageType.HOURLY, 10_320,
            listOf(PresetItem("meal", "식대", Cadence.PER_WORK_DAY, 0, checked = false)),
            note = "주휴수당은 원문 확인 뒤 다음 업데이트에서 넣어요",
        ),
        OccupationPreset(
            Occupation.MANUFACTURING, WageType.MONTHLY, 2_700_000,
            listOf(
                PresetItem("shift", "교대 수당", Cadence.MONTHLY, 150_000, ordinary = true),
                PresetItem("night", "야간 근무 수당", Cadence.PER_NIGHT_SHIFT, 20_000),
                PresetItem("meal", "식대", Cadence.MONTHLY, 200_000, checked = false),
            ),
        ),
        OccupationPreset(
            Occupation.CARE, WageType.MONTHLY, 2_400_000,
            listOf(PresetItem("night", "야간 수당", Cadence.PER_NIGHT_SHIFT, 20_000)),
        ),
        OccupationPreset(
            Occupation.SECURITY, WageType.MONTHLY, 2_300_000,
            listOf(PresetItem("duty", "당직 수당", Cadence.PER_WORK_DAY, 0, checked = false)),
            note = "감시·단속 업무로 승인받은 경우 연장·휴일 가산이 빠질 수 있어요. 해당되면 회사 규칙에서 꺼 주세요",
        ),
        // [JOB-01] 봉급 placeholder: 일반직 9급 10호봉 2026 (research/1.21-직군-조사.md). Each person's own 봉급 replaces it.
        OccupationPreset(
            Occupation.PUBLIC, WageType.MONTHLY, 2_542_700,
            listOf(
                PresetItem("meal", "정액급식비", Cadence.MONTHLY, 160_000, hint = "명세서 금액으로 고쳐 주세요"),
                PresetItem("duty", "직급보조비", Cadence.MONTHLY, 0, checked = false),
            ),
            note = PublicServantPay.NOTE,
        ),
        OccupationPreset(
            Occupation.POLICE, WageType.MONTHLY, 2_542_700,
            listOf(
                PresetItem("meal", "정액급식비", Cadence.MONTHLY, 160_000, hint = "명세서 금액으로 고쳐 주세요"),
                PresetItem("risk", "위험근무수당", Cadence.MONTHLY, 0, hint = "명세서 금액"),
                PresetItem("field", "현장 근무 수당", Cadence.MONTHLY, 0, checked = false, hint = "지구대·파출소 근무자"),
            ),
            note = PublicServantPay.NOTE,
        ),
        OccupationPreset(
            Occupation.FIRE, WageType.MONTHLY, 2_542_700,
            listOf(
                PresetItem("meal", "정액급식비", Cadence.MONTHLY, 160_000, hint = "명세서 금액으로 고쳐 주세요"),
                PresetItem("risk", "위험근무수당", Cadence.MONTHLY, 0, hint = "명세서 금액"),
                PresetItem("fire", "화재진화수당", Cadence.MONTHLY, 0, checked = false, hint = "명세서 금액"),
            ),
            note = PublicServantPay.NOTE,
        ),
        OccupationPreset(Occupation.CUSTOM, WageType.MONTHLY, 0, emptyList()),
    )

    fun of(occupation: Occupation): OccupationPreset = all.first { it.occupation == occupation }

    /**
     * [JOB-01] A first setup for a public servant: no 근로기준법 premiums (공무원 are paid by 공무원수당 등에
     * 관한 규정), and its 야간·휴일·시간외 수당 as the user's own rules worked out from the 봉급 entered.
     * Anything else, and every later setup, is left as it is.
     */
    fun firstSetup(p: PayProfile): PayProfile =
        if (!PublicServantPay.isPublic(p.occupation) || p.customRules.isNotEmpty()) p
        else p.copy(
            rules = p.rules.copy(overtimePremium = 0.0, nightPremium = 0.0, holidayPremiumWithin8h = 0.0, holidayPremiumOver8h = 0.0),
            customRules = PublicServantPay.rules(PublicServantPay.standardBase(p.base.entries.maxByOrNull { it.from }?.value ?: 0)),
            estimatedFields = p.estimatedFields + PublicServantPay.ESTIMATED,
        )

    /** A profile from the wizard's answers. */
    fun profile(
        preset: OccupationPreset,
        wageType: WageType,
        base: Long,
        since: LocalDate,
        chosen: List<PresetItem>,
        fiveOrMore: Boolean,
        holidayMode: HolidayPayMode,
        estimated: Set<String>,
    ) = PayProfile(
        occupation = preset.occupation,
        wageType = wageType,
        base = ValueTimeline(listOf(DatedValue(since, base))),
        items = chosen.map {
            PayItem(it.id, it.name, ValueTimeline(listOf(DatedValue(since, it.suggestedAmount))), it.cadence, ordinary = it.ordinary)
        },
        fiveOrMoreEmployees = fiveOrMore,
        holidayPayMode = holidayMode,
        dailyLimitMinutes = preset.dailyLimitMinutes,
        estimatedFields = estimated,
    )
}
