package com.bamnat.core.profile

import com.bamnat.core.work.WorkStyle

/** [EASY-03][EASY-08] The groups of the profile summary in settings, in the order they are shown. */
enum class FactGroup(val label: String) {
    WORK("근무"), ALARM("알람"), PAY("급여"), LEAVE("연차"), TAX("세금"), SLEEP("잠"), COMPANY("회사"), SHOP("가게·현장"),
}

/**
 * [EASY-03] Every fact the app asks about once and every feature reads from there. [id] is stable (kept in
 * the meta file and the question log), [store] names the store that holds the value (the 정본, see
 * research/1.23-N-설계.md 1장), [usedBy] the [com.bamnat.core.guide.FeatureRegistry] ids that read it,
 * [money] whether a guess of it changes an amount the app shows or sends out (those get the '추정' badge and
 * the check before 내보내기), [styles] the kinds of work it is for (empty for every kind).
 *
 * To add one: add it here, give it an adapter in the app's `ProfileStore`, and list it in the features that
 * need it in [FeatureNeeds]. `ProfileCatalogTest` fails until all three agree.
 */
enum class FactKey(
    val id: String,
    val label: String,
    val group: FactGroup,
    val store: String,
    val usedBy: List<String>,
    val money: Boolean = false,
    val styles: Set<WorkStyle> = emptySet(),
) {
    WORK_STYLE("work.style", "일하는 방식", FactGroup.WORK, "AppSettings.workStyle / PersonalCalendar.style",
        listOf("guide.checklist", "calendar.edit")),
    SHIFTS("work.shifts", "근무 종류와 시간", FactGroup.WORK, "ScheduleRepository 근무 유형",
        listOf("shift.pattern", "pay.calc", "shift.restRules", "today.flow", "alarm.check"), money = true, styles = PAID),
    ROTATION("work.rotation", "근무 순서와 시작일", FactGroup.WORK, "ScheduleRepository WorkPattern",
        listOf("shift.pattern", "shift.teams", "share.link", "widget.teams"), styles = setOf(WorkStyle.SHIFT, WorkStyle.DAY)),
    EXTRA_KINDS("work.extraKinds", "회사의 특근·잔업 종류", FactGroup.WORK, "AppSettings.extraKinds",
        listOf("extra.kinds", "pay.calc"), money = true, styles = PAID),
    REST_RULES("work.restRules", "근무표 점검 기준", FactGroup.WORK, "AppSettings.restRules",
        listOf("shift.restRules"), styles = setOf(WorkStyle.SHIFT)),
    TEAM("work.team", "내 조", FactGroup.WORK, "TeamStore",
        listOf("shift.teams", "widget.teams"), styles = setOf(WorkStyle.SHIFT)),
    OVERTIME_CAP("work.overtimeCap", "시간외 상한", FactGroup.WORK, "WorkTypePrefs.overtimeCapHours",
        listOf("day.overtimeCap"), styles = setOf(WorkStyle.DAY)),
    ALARM_LEAD("alarm.lead", "근무 몇 분 전 알람", FactGroup.ALARM, "ShiftType.alarms",
        listOf("alarm.check", "alarm.moveAll"), styles = PAID),
    WAGE_TYPE("pay.wageType", "월급·시급·일당", FactGroup.PAY, "PayProfile.wageType",
        listOf("pay.calc", "pay.report", "hourly.takeHome"), money = true, styles = PAID),
    BASE_PAY("pay.base", "기본급", FactGroup.PAY, "PayProfile.base",
        listOf("pay.calc", "leave.payout", "pay.whatIf", "pay.report"), money = true, styles = PAID),
    PAY_ITEMS("pay.items", "수당", FactGroup.PAY, "PayProfile.items",
        listOf("pay.calc", "pay.slipAudit"), money = true, styles = PAID),
    PAY_DAY("pay.day", "급여일", FactGroup.PAY, "PayProfile.period",
        listOf("pay.calc", "pay.report"), money = true, styles = PAID),
    MONTHLY_HOURS("pay.monthlyHours", "월 소정근로시간", FactGroup.PAY, "PayProfile.monthlyHours",
        listOf("pay.calc", "pay.gap"), money = true, styles = PAID),
    DAILY_HOURS("pay.dailyHours", "하루 소정근로시간", FactGroup.PAY, "PayProfile.dailyHours",
        listOf("pay.compLeave", "leave.ledger"), money = true, styles = PAID),
    HOLIDAY_PAY("pay.holidayPay", "주휴수당 받는 방식", FactGroup.PAY, "PayProfile.holidayPayMode",
        listOf("hourly.weeks", "pay.calc"), money = true, styles = PAID),
    NIGHT_OT("pay.nightOt", "야간·연장 수당과 회사 규칙", FactGroup.PAY, "PayProfile.rules",
        listOf("pay.calc", "shift.premium"), money = true, styles = PAID),
    PUBLIC_HOLIDAYS("pay.publicHolidays", "공휴일 쉬는 날·수당", FactGroup.PAY, "PayProfile.rules",
        listOf("pay.calc", "leave.ledger"), money = true, styles = PAID),
    FIVE_OR_MORE("pay.fiveOrMore", "직원 5명 이상 회사", FactGroup.COMPANY, "PayProfile.fiveOrMoreEmployees",
        listOf("pay.calc", "leave.ledger"), money = true, styles = PAID),
    HIRE_DATE("pay.hireDate", "입사일", FactGroup.LEAVE, "PayProfile.hireDate",
        listOf("leave.ledger", "leave.promotion", "leave.payout", "pay.calc"), money = true, styles = PAID),
    TAX_MODE("pay.taxMode", "세금 떼는 방식", FactGroup.TAX, "PayProfile.taxMode (없으면 WorkTypePrefs.taxMode)",
        listOf("hourly.takeHome", "pay.calc"), money = true, styles = PAID),
    TAX_FAMILY("pay.taxFamily", "공제대상 가족·자녀", FactGroup.TAX, "PayProfile.taxFamily·taxChildren",
        listOf("hourly.takeHome", "pay.calc"), money = true, styles = PAID),
    PREVIOUS_YEAR_PAY("pay.previousYearPay", "작년 총급여", FactGroup.TAX, "PayProfile.previousYearPay",
        listOf("pay.calc"), money = true, styles = PAID),
    SLEEP_TARGET("sleep.target", "목표 수면", FactGroup.SLEEP, "AppSettings.sleepTargetMinutes",
        listOf("today.flow", "sleep.log"), styles = PAID),
    COMMUTE("sleep.commute", "퇴근길 시간", FactGroup.SLEEP, "AppSettings.commuteMinutes",
        listOf("today.flow"), styles = PAID),
    COMPANY("work.company", "회사", FactGroup.COMPANY, "CompanyStore",
        listOf("plaza.news"), styles = PAID),
    SHOP_RULES("shop.rules", "영업시간·쉬는 날", FactGroup.SHOP, "PersonalCalendar.shop",
        listOf("shop.days", "shop.autoClose"), styles = setOf(WorkStyle.BUSINESS)),
    SHOP_MONEY("shop.money", "카드 수수료·정산일", FactGroup.SHOP, "PersonalCalendar.shopMoney",
        listOf("shop.cardDue", "shop.target", "shop.vat"), money = true, styles = setOf(WorkStyle.BUSINESS)),
    FIELD_RULES("field.rules", "일당·건별과 단가", FactGroup.SHOP, "PersonalCalendar.field",
        listOf("field.mode", "field.units"), money = true, styles = setOf(WorkStyle.FIELD)),
    ;

    fun fits(style: WorkStyle?): Boolean = styles.isEmpty() || (style ?: WorkStyle.SHIFT) in styles

    companion object {
        fun byId(id: String): FactKey? = entries.firstOrNull { it.id == id }
        fun of(style: WorkStyle?): List<FactKey> = entries.filter { it.fits(style) }
    }
}

private val PAID = setOf(WorkStyle.SHIFT, WorkStyle.DAY, WorkStyle.HOURLY)
