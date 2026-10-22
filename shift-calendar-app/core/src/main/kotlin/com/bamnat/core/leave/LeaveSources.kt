package com.bamnat.core.leave

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate

/**
 * [LEAVE-01] Where a calendar's ledger comes from: the law's lots ([LeaveAccrual]) for the hire date in the pay
 * setup, the leave taken on the calendar, the 보상휴가 its extra work earned [PAYX-18], and the stored rows.
 */
object LeaveSources {
    /** Why the app makes no leave by itself, or null when it does. */
    fun noAuto(profile: PayProfile?): String? = when {
        profile == null -> "급여 설정을 하면 연차를 법대로 세어 드려요"
        !profile.rules.leave.auto -> "연차를 직접 넣기로 했어요"
        profile.hireDate == null -> "입사일을 넣으면 연차를 법대로 세어 드려요"
        // 근로기준법 시행령 별표1: 제60조 is not for workplaces under 5.
        !profile.fiveOrMoreEmployees -> "5인 미만 사업장은 법으로 정한 연차가 없어요. 회사가 주는 날을 직접 넣어요"
        else -> null
    }

    /** The law's lots through [until], or none (see [noAuto]). */
    fun autoLots(profile: PayProfile?, until: LocalDate): List<LeaveLot> {
        if (profile == null || noAuto(profile) != null) return emptyList()
        return LeaveAccrual.lots(profile.hireDate!!, profile.rules.leave, until)
    }

    /**
     * Leave taken on the calendar: 연차 (not on a 공휴일 the company does not count, [com.bamnat.core.pay.CompanyRules.holidayLeaveFree])
     * and 보상휴가. Leave only counts on a working day, as the calendar shows it.
     */
    fun uses(entries: List<DayEntry>, resolve: (LocalDate) -> ResolvedDay, profile: PayProfile?): List<LeaveUse> =
        entries.filter { it.leave != null }.mapNotNull { e ->
            val leave = resolve(e.date).leave ?: return@mapNotNull null
            when {
                leave.kind == LeaveKind.COMP -> LeaveUse(e.date, leave.days, LeavePool.COMP)
                !leave.kind.annual -> null
                profile?.rules?.holidayLeaveFree == true && KoreanHolidays.isHoliday(e.date) -> null
                else -> LeaveUse(e.date, leave.days)
            }
        }.sortedBy { it.date }

    /**
     * [PAYX-18] 보상휴가 earned by extra work marked as paid in time off: the pay months holding such work are
     * worked out, so the hours carry their premiums as the pay screen shows them. A day of 보상휴가 is the day's
     * 소정근로시간 (8 hours, or the 일당's).
     */
    fun compLots(profile: PayProfile?, entries: List<DayEntry>, resolve: (LocalDate) -> ResolvedDay, calculator: PayCalculator = PayCalculator()): List<LeaveLot> {
        if (profile == null) return emptyList()
        val months = entries.filter { e -> e.extras.any { it.compLeave } }.map { profile.period.monthOf(it.date) }.distinct()
        val dayMinutes = profile.dailyHours.coerceIn(60, 8 * 60).toDouble()
        return months.flatMap { m -> calculator.calculate(profile, m, resolve, checks = false).compLeave.entries }
            .sortedBy { it.key }
            .map { (date, minutes) ->
                LeaveLot(LotKind.COMP, Math.round(minutes / dayMinutes * 100) / 100.0, date, null,
                    note = "${PayCalculator.hours(minutes)} (가산 포함)")
            }
    }

    /** [LEAVE-01] The calendar's ledger on [today]. */
    fun summary(
        today: LocalDate,
        profile: PayProfile?,
        entries: List<DayEntry>,
        resolve: (LocalDate) -> ResolvedDay,
        stored: List<LedgerEntry>,
        until: LocalDate = today.plusYears(1),
    ): LeaveSummary = LeaveLedger.build(
        today = today,
        auto = autoLots(profile, until),
        entries = stored,
        uses = uses(entries, resolve, profile),
        compLots = compLots(profile, entries, resolve),
        until = until,
    )
}
