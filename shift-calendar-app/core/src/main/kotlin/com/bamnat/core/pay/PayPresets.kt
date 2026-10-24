package com.bamnat.core.pay

import com.bamnat.core.remote.PresetBook
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
    /**
     * [REMOTE-03] From the presets file (`remote/v1/presets.json`), so a 직군's starting amounts can be fixed
     * without an app update. 직접 설정 is always there.
     */
    val all: List<OccupationPreset>
        get() = PresetBook.current.pay.let { l -> if (l.any { it.occupation == Occupation.CUSTOM }) l else l + CUSTOM }

    private val CUSTOM = OccupationPreset(Occupation.CUSTOM, WageType.MONTHLY, 0, emptyList())

    fun of(occupation: Occupation): OccupationPreset = all.firstOrNull { it.occupation == occupation } ?: CUSTOM.copy(occupation = occupation)

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
