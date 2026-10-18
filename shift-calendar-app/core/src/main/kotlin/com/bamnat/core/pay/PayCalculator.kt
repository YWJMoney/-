package com.bamnat.core.pay

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.schedule.ResolvedDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/** What a line is, so screens can group and colour it. */
enum class LineKind {
    BASE, OVERTIME, NIGHT, HOLIDAY, EXTRA, ALLOWANCE, LEAVE,
    /** [EXTRA-02] Extra work within 8 hours a day and 40 a week: 100%, not 연장. */
    EXTRA_HOURS,
}

/**
 * One line of the result, explaining itself [PAY-09]: the formula, the minutes, the hourly rate
 * and multiplier that went in, the legal basis and the rate version.
 */
data class PayLine(
    val kind: LineKind,
    val label: String,
    val amount: Long,
    val formula: String,
    val minutes: Int = 0,
    val hourly: Long = 0,
    val multiplier: Double = 0.0,
    val basis: String = "",
    val estimated: Boolean = false,
)

/** What one calendar day added to the month, so a day on the calendar and its pay line up [PAYX-08]. */
data class DayPart(val kind: LineKind, val label: String, val amount: Long, val formula: String)

data class DayPay(
    val date: LocalDate,
    /** The shift name on the calendar, or 휴무, with the day's extra work. */
    val label: String,
    val holidayName: String?,
    /** [EXTRA-01] Names of the day's extra work, e.g. "야간 대근". */
    val extras: List<String> = emptyList(),
    val minutes: DayMinutes,
    /** Amounts beyond the monthly base salary (for hourly pay, the day's base too). */
    val parts: List<DayPart>,
) {
    val amount: Long get() = parts.sumOf { it.amount }
}

/**
 * [PAYS-01] An allowance whose amount the user does not know (금액 모름, [Unknowns.item]): left out of the
 * total and listed instead of being dropped without a word, e.g. "야간근무수당 · 금액 모름 · 6회".
 */
data class MissingItem(
    val id: String,
    val name: String,
    /** How many times it would have been paid this month; null for a monthly allowance. */
    val times: Int?,
)

data class MonthPay(
    val month: YearMonth,
    val lines: List<PayLine>,
    val ordinaryHourly: Long,
    val ratesVersion: String,
    val minutes: List<DayMinutes>,
    val warnings: List<String>,
    val days: List<DayPay> = emptyList(),
    /** [PAYX-15] The days this pay covers and when it is paid; null for the calendar month. */
    val periodStart: LocalDate? = null,
    val periodEnd: LocalDate? = null,
    val payDate: LocalDate? = null,
    /** [PAYS-01] Allowances paid this month whose amount is unknown, not in [total]. */
    val missing: List<MissingItem> = emptyList(),
    /** [PAYS-02] How many times each allowance paid per shift came up this month, by [PayItem.id]. */
    val itemTimes: Map<String, Int> = emptyMap(),
) {
    val total: Long get() = lines.sumOf { it.amount }
    val estimated: Boolean get() = lines.any { it.estimated } || minutes.any { it.estimated }
    fun day(date: LocalDate): DayPay? = days.firstOrNull { it.date == date }
}

/**
 * Turns a month of calendar days into pay [PAY-01, PAY-04..06, PAYX-07, PAYX-08, EXTRA-02, EXTRA-03, PAYS-03].
 * Pure: everything comes in through the arguments, so the same inputs always give the same result
 * (see [CalcSnapshot]). The calendar is the input: each shift, piece of extra work and holiday on it
 * becomes minutes, then money by the law and the company's [CompanyRules].
 */
class PayCalculator(
    private val rateTable: List<Rates> = RateTable.builtIn,
) {
    private class ExtraTotal(val rule: ExtraRule, val label: String, val kind: Long, var amount: Long = 0, var minutes: Int = 0, var times: Int = 0, var legal: Long = 0)

    /** The premiums in force: the company's when it set one, else the law's (none under 5 employees). */
    private class Premiums(val overtime: Double, val night: Double, val holidayWithin8h: Double, val holidayOver8h: Double, val holidayBase: Double)

    fun calculate(
        profile: PayProfile,
        month: YearMonth,
        resolve: (LocalDate) -> ResolvedDay,
        /**
         * [PAYS-01] false leaves out the checks that calculate the month a second time to compare (the
         * company's night window, 근무표 시간 as 기본 근무, 감시·단속 승인) and their warnings, for
         * [PayRange] and [PayslipMatcher], which run a month many times. It comes before [isHoliday] so
         * calls passing [isHoliday] as a trailing lambda keep working.
         */
        checks: Boolean = true,
        /** Paid holidays besides the 주휴일; null uses the built-in 공휴일 when the company counts them. */
        isHoliday: ((LocalDate) -> Boolean)? = null,
    ): MonthPay {
        val rules = profile.rules
        // [PAYS-03] 감시·단속 승인 (제63조 제3호): the rules on hours, breaks and holidays (chapter 4) do not apply.
        val supervisory = rules.supervisoryApproved
        // [PAYS-03] 시급 and 일당 are both paid for the hours worked; only a monthly salary already holds the month.
        val byHour = profile.wageType != WageType.MONTHLY
        // [PAYX-12] The company's night window replaces 22~06 for every shift, base shifts included.
        // [PAYX-15] The days this month's pay covers, e.g. 9/21~10/20 for October.
        val periodStart = profile.period.start(month)
        val periodEnd = profile.period.end(month)
        fun inPeriod(d: LocalDate) = !d.isBefore(periodStart) && !d.isAfter(periodEnd)
        val rates = rules.applyNight(RateTable.on(periodStart, rateTable))
        val premiums = profile.fiveOrMoreEmployees
        // 공휴일 are paid holidays only at 5인 이상 (근로기준법 제55조 제2항, 시행령 별표1); below that
        // they are ordinary days unless the company grants them [PAYS-03] or the caller says otherwise.
        // [PAYX-07] 노동절 is a paid holiday at every workplace and cannot be swapped, so it skips both checks.
        // [PAYS-03] An approved 감시·단속 worker has no paid 공휴일 (제55조 is in chapter 4).
        // verify: 노동절 for an approved 감시·단속 worker; kept as a paid day under its own law, with no premium.
        val publicHoliday = isHoliday ?: { d: LocalDate ->
            KoreanHolidays.isLaborDay(d) ||
                !supervisory && (if (premiums) rules.publicHolidaysPaid else rules.holidaysUnderFive) && KoreanHolidays.isHoliday(d)
        }
        // [PAYX-07] 탄력적 근로시간제 settles scheduled hours over its period; an approved 감시·단속 worker has no limits to settle.
        val flex = profile.flexPeriod?.takeIf { profile.scheduleIsRegular && !supervisory }
        // Start at the week containing the 1st so weekly overtime counts the days before it, or at the
        // 탄력적 근로시간제 period's start so a period ending this month is settled whole. End with the
        // week holding the last day: extra work is the week's last hours, so the whole week decides.
        val weekFrom = periodStart.with(TemporalAdjusters.previousOrSame(profile.weekStart))
        val from = flex?.start(periodStart)?.let { minOf(it, weekFrom) } ?: weekFrom
        val to = periodEnd.with(TemporalAdjusters.nextOrSame(profile.weekStart.minus(1)))
        // [PAYS-03] A break counted as work is work from the start: it can make 연장, 야간 and 주휴 hours.
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map(resolve).map { if (rules.breakPay == BreakPay.AS_WORK) it.breaksAsWork() else it }.toList()
        // [EXTRA-02] The 주휴일 is a paid holiday under every company rule (제55조 제1항), except for an
        // approved 감시·단속 worker, who has none [PAYS-03].
        val weeklyHolidays = if (supervisory) emptySet() else weeklyHolidays(rules.weeklyHoliday, days, profile.weekStart)
        val holiday = { d: LocalDate -> d in weeklyHolidays || publicHoliday(d) }
        val classifier = MinuteClassifier(
            rates = { rules.applyNight(RateTable.on(it, rateTable)) },
            // [PAYS-03] No 8시간·40시간 for an approved 감시·단속 worker: every hour is paid at 100%.
            options = ClassifyOptions(profile.dailyLimitMinutes.takeUnless { supervisory }, profile.weeklyLimitMinutes.takeUnless { supervisory },
                profile.weekStart, restDayWork = rules.restDayWork, scheduleIsRegular = profile.scheduleIsRegular,
                flexPeriod = flex, holidayByClock = !rules.holidayByShiftStart),
        )
        val allMinutes = classifier.classify(days, holiday).map { roundOvertime(it, rules.overtimeRounding) }
        val minutes = allMinutes.filter { inPeriod(it.date) }
        val byDate = minutes.associateBy { it.date }
        val monthDays = days.filter { inPeriod(it.date) }

        val base = profile.base.on(month)
        val ordinary = ordinaryWage(profile, month)
        val ordinaryMonthly = ordinary.allowances
        val bonusMonthly = ordinary.bonuses
        // [PAYS-03] The minutes of 소정근로 one 일당 pays for.
        val dailyMinutes = profile.dailyHours.coerceIn(1, 8 * 60)
        val hourly = when (profile.wageType) {
            WageType.MONTHLY -> (ordinary.total.toDouble() / profile.monthlyHours.coerceAtLeast(1)).roundToLong()
            WageType.HOURLY -> base.value
            // [PAYS-03] 일급 금액 ÷ 1일 소정근로시간 (시행령 제6조 제2항 제2호).
            WageType.DAILY -> (base.value * 60.0 / dailyMinutes).roundToLong()
        }
        val hourlyFormula = when (profile.wageType) {
            WageType.MONTHLY -> "(기본급 ${won(base.value)}" +
                (if (ordinaryMonthly > 0) " + 통상 수당 ${won(ordinaryMonthly)}" else "") +
                (if (bonusMonthly > 0) " + 상여금 월할 ${won(bonusMonthly)}" else "") +
                ") ÷ ${profile.monthlyHours}시간"
            WageType.HOURLY -> "시급"
            WageType.DAILY -> "일당 ${won(base.value)} ÷ ${hours(dailyMinutes)}"
        }
        // [EXTRA-03] 기본급 for a rule paid as a share of it; hourly and daily pay count a month of 소정 hours.
        val monthlyBase = when (profile.wageType) {
            WageType.MONTHLY -> base.value
            WageType.HOURLY, WageType.DAILY -> hourly * profile.monthlyHours
        }
        val baseLabel = when (profile.wageType) {
            WageType.MONTHLY -> "기본급"
            WageType.HOURLY, WageType.DAILY -> "시급 × ${profile.monthlyHours}시간"
        }

        fun legal(p: Double) = if (premiums) p else 0.0
        // [PAYS-03] An approved 감시·단속 worker has no legal 연장·휴일 premium (제63조); the night premium stays.
        fun legalHours(p: Double) = if (supervisory) 0.0 else legal(p)
        val pr = Premiums(
            overtime = rules.overtimePremium ?: legalHours(rates.overtimePremium),
            night = rules.nightPremium ?: legal(rates.nightPremium),
            holidayWithin8h = rules.holidayPremiumWithin8h ?: legalHours(rates.holidayPremiumWithin8h),
            holidayOver8h = rules.holidayPremiumOver8h ?: legalHours(rates.holidayPremiumOver8h),
            // [EXTRA-02] '가산만' is about a paid holiday already in the salary; a day off never is.
            holidayBase = if (byHour) 1.0 else profile.holidayPayMode.baseMultiplier,
        )
        val law = Premiums(legalHours(rates.overtimePremium), legal(rates.nightPremium), legalHours(rates.holidayPremiumWithin8h),
            legalHours(rates.holidayPremiumOver8h), pr.holidayBase)
        val mOt = 1.0 + pr.overtime
        val mHw = pr.holidayBase + pr.holidayWithin8h
        val mHo = pr.holidayBase + pr.holidayOver8h
        val mRw = 1.0 + pr.holidayWithin8h
        val mRo = 1.0 + pr.holidayOver8h
        fun money(min: Int, rate: Double) = (min / 60.0 * hourly * rate).roundToLong()
        // [PAYS-03] 100% of 소정 minutes: a day of [dailyMinutes] pays exactly the 일당, whatever the
        // rounding of the hourly rate.
        fun basePay(min: Int): Long =
            if (profile.wageType == WageType.DAILY) (min.toDouble() * base.value / dailyMinutes).roundToLong() else money(min, 1.0)

        val lines = mutableListOf<PayLine>()
        val warnings = mutableListOf<String>()
        val baseEstimated = "base" in profile.estimatedFields || base.source == ValueSource.RAISED
        // Keyed by kind, the covered shift type for a per-shift rule [PAYX-11], and whether it is a
        // 공휴일 shift paid that kind's way.
        val extraTotals = linkedMapOf<Triple<Long, Long?, Boolean>, ExtraTotal>()
        var regular = 0; var extraRegular = 0; var overtime = 0; var night = 0
        var hWithin = 0; var hOver = 0; var rWithin = 0; var rOver = 0
        var restDayWorked = false
        val itemCounts = HashMap<String, Int>()
        val dayPays = mutableListOf<DayPay>()
        // [PAYS-06] What each of the user's own rules paid this month, by [CustomRule.id].
        val customTotals = linkedMapOf<String, CustomTotal>()
        val shiftNames = HashMap<Long, String>()
        val extraKindNames = HashMap<Long, String>(rules.extraNames)
        // Leave [FEAT-10]: minutes by kind, and paid leave per date for 주휴 weeks.
        var paidLeave = 0; var unpaidLeave = 0
        val paidLeaveByDate = days.filter { it.leave?.kind?.paid == true }.associate { it.date to it.leaveMinutes }
        // [PAY-13] A paid holiday on a scheduled working day: hourly and daily-paid workers get one
        // day's ordinary wage for it, worked or not, and holiday pay on top when they work it
        // (고용노동부 임금근로시간과-653, 2021.3.22.). A 비번 day that falls on a holiday earns nothing.
        // Monthly pay already includes it. The 주휴일 (Sundays when it is not set) is left out: 주휴수당 covers it.
        val weeklyHolidayDay = (rules.weeklyHoliday as? WeeklyHoliday.Fixed)?.day ?: DayOfWeek.SUNDAY.takeIf { rules.weeklyHoliday == WeeklyHoliday.Unknown }
        fun paidHolidayMinutes(day: ResolvedDay): Int =
            if (!byHour || !publicHoliday(day.date) || day.date.dayOfWeek == weeklyHolidayDay || day.date in weeklyHolidays) 0
            else (day.scheduledMinutes + day.leaveMinutes).coerceAtMost(rates.dailyLimitMinutes)
        var paidHoliday = 0
        // [PAYS-03] Paid breaks of hourly and daily pay; a monthly salary already holds them.
        var paidBreak = 0
        // The week's 소정근로 for 주휴 counts paid leave and paid holidays as attended.
        val attendedByDate = days.associate { it.date to (paidLeaveByDate[it.date] ?: 0) + paidHolidayMinutes(it) }
            .filterValues { it > 0 }

        for (day in monthDays) {
            val m = byDate[day.date] ?: DayMinutes(day.date)
            val parts = mutableListOf<DayPart>()
            // What the law pays this day, by category; a company rule takes its own piece out.
            var reg = m.regular; var xr = m.extraRegular; var ot = m.overtime; var n = m.night
            var hw = m.holidayWithin8h; var ho = m.holidayOver8h; var rw = m.restWithin8h; var ro = m.restOver8h
            fun takeOut(w: WorkMinutes, rule: ExtraRule, shiftPart: Boolean) {
                if (shiftPart) reg -= w.regular else xr -= w.regular
                ot -= w.overtime; hw -= w.holidayWithin8h; ho -= w.holidayOver8h; rw -= w.restWithin8h; ro -= w.restOver8h
                if (rule.ownsNight) n -= w.night
            }
            fun pay(key: Triple<Long, Long?, Boolean>, name: String, rule: ExtraRule, w: WorkMinutes, piece: PieceAt) {
                val r = applyRule(rule, w, piece, hourly, monthlyBase, baseLabel, pr, law, rates)
                extraTotals.getOrPut(key) { ExtraTotal(rule, name, key.first) }.apply {
                    amount += r.amount; this.minutes += r.credited; times++; legal += r.legal
                }
                parts += DayPart(LineKind.EXTRA, name, r.amount, r.formula)
            }

            // [PAYX-07] A shift on a 공휴일 can be paid the company's way for some kind of extra work: its
            // holiday minutes only, the rest of the day stays as the law says.
            val shiftKind = rules.publicHolidayAs?.takeIf { publicHoliday(day.date) && day.isWorkDay }
            val shiftRule = shiftKind?.let { rules.extraRule(it, day.shiftType?.id) }
            if (shiftRule != null) {
                val s = m.shift
                val w = WorkMinutes(holidayWithin8h = s.holidayWithin8h, holidayOver8h = s.holidayOver8h,
                    night = if (s.worked > 0) (s.night * (s.holidayWithin8h + s.holidayOver8h).toDouble() / s.worked).roundToLong().toInt() else 0)
                if (w.worked > 0) {
                    if (shiftRule.basis != ExtraBasis.LEGAL) takeOut(w, shiftRule, shiftPart = true)
                    // Clock-time premiums count the shift's span, in the share of it that is holiday work.
                    val at = PieceAt(false, day.shiftFrom, day.shiftTo, s.worked, day.breakMinutes, day.breakStart,
                        share = if (s.worked > 0) w.worked.toDouble() / s.worked else 0.0)
                    pay(Triple(shiftKind, null, true), "${rules.extraName(shiftKind)} 방식 (공휴일 근무)", shiftRule, w, at)
                }
            }

            // [EXTRA-03] Each piece of extra work by its own kind's rule, on its own minutes.
            for ((i, e) in day.extras.withIndex()) {
                val em = m.extra(i) ?: continue
                // [PAYX-11] The shift the work covered: its own, else the day's.
                val covered = e.covered ?: day.shiftType?.takeIf { day.isWorkDay }
                val perShift = covered?.id?.let { rules.extraByShift[e.kind.id]?.get(it) }?.takeUnless { it.isLegal }
                val rule = rules.extraRule(e.kind.id, covered?.id) ?: continue
                val name = rules.extraName(e.kind.id) + if (perShift != null) " · ${covered!!.name}" else ""
                if (rule.basis != ExtraBasis.LEGAL) takeOut(em.minutes, rule, shiftPart = false)
                pay(Triple(e.kind.id, perShift?.let { covered!!.id }, false), name, rule, em.minutes,
                    PieceAt(day.isRestDay, e.from, e.to, e.minutes, e.breakMinutes, e.breakStart))
            }
            if (day.isRestDay && day.extras.isNotEmpty()) restDayWorked = true

            regular += reg; extraRegular += xr; overtime += ot; night += n
            hWithin += hw; hOver += ho; rWithin += rw; rOver += ro
            if (byHour && reg > 0) parts.add(0, when (profile.wageType) {
                WageType.DAILY -> DayPart(LineKind.BASE, "일당", basePay(reg),
                    if (reg == dailyMinutes) "일당 ${won(base.value)}" else "일당 ${won(base.value)} × ${hours(reg)} ÷ ${hours(dailyMinutes)}")
                else -> DayPart(LineKind.BASE, "기본", money(reg, 1.0), "${hours(reg)} × ${won(hourly)}")
            })
            if (xr > 0) parts += DayPart(LineKind.EXTRA_HOURS, "소정 외 근로", money(xr, 1.0), "${hours(xr)} × ${won(hourly)} (8·40시간 이내)")
            if (ot > 0) parts += DayPart(LineKind.OVERTIME, "연장", money(ot, mOt), "${hours(ot)} × ${pct(mOt)}")
            if (n > 0 && pr.night > 0) parts += DayPart(LineKind.NIGHT, "야간 가산", money(n, pr.night), "${hours(n)} × ${pct(pr.night)} (${rules.nightLabel(rates)})")
            if (hw > 0) parts += DayPart(LineKind.HOLIDAY, "휴일", money(hw, mHw), "${hours(hw)} × ${pct(mHw)}")
            if (ho > 0) parts += DayPart(LineKind.HOLIDAY, "휴일 8시간 초과", money(ho, mHo), "${hours(ho)} × ${pct(mHo)}")
            if (rw > 0) parts += DayPart(LineKind.HOLIDAY, "쉬는 날 근무", money(rw, mRw), "${hours(rw)} × ${pct(mRw)} (휴일근로로 대우)")
            if (ro > 0) parts += DayPart(LineKind.HOLIDAY, "쉬는 날 8시간 초과", money(ro, mRo), "${hours(ro)} × ${pct(mRo)} (휴일근로로 대우)")
            // [PAYS-03] A paid break is free time paid at 100%, never 연장 or 주휴 hours.
            if (byHour && rules.breakPay == BreakPay.PAID) {
                val brk = (if (day.isWorkDay) day.breakMinutes else 0) + day.extras.sumOf { it.breakMinutes }
                if (brk > 0) {
                    paidBreak += brk
                    parts += DayPart(LineKind.BASE, "유급 휴게", money(brk, 1.0), "${hours(brk)} × ${won(hourly)}")
                }
            }

            paidHolidayMinutes(day).takeIf { it > 0 }?.let { mins ->
                paidHoliday += mins
                parts += DayPart(LineKind.ALLOWANCE, "유급휴일", money(mins, 1.0), "${hours(mins)} × ${won(hourly)} (공휴일)")
            }
            day.leave?.let { leave ->
                val mins = day.leaveMinutes
                val (amount, formula) = when {
                    leave.kind.paid && byHour -> money(mins, 1.0) to "${hours(mins)} × ${won(hourly)} (유급)"
                    leave.kind.paid -> 0L to "기본급에 포함 (유급, ${hours(mins)})"
                    profile.wageType == WageType.MONTHLY -> -money(mins, 1.0) to "${hours(mins)} × ${won(hourly)} 공제 (무급)"
                    else -> 0L to "무급 ${hours(mins)}"
                }
                if (leave.kind.paid) paidLeave += mins else unpaidLeave += mins
                parts += DayPart(LineKind.LEAVE, leave.label, amount, formula)
            }
            // [EXTRA-03] Allowances counted the same way however the work was entered: the day's shift
            // and each piece of extra work that counts as a shift.
            val shifts = day.extras.filter { it.kind.countsAsShift }
            for (item in profile.items) {
                val times = when (item.cadence) {
                    Cadence.MONTHLY, Cadence.BONUS -> 0
                    Cadence.PER_WORK_DAY -> if (day.isWorkDay || shifts.isNotEmpty()) 1 else 0
                    Cadence.PER_NIGHT_SHIFT -> (if (day.isWorkDay && isNightShift(day.shiftFrom, day.shiftTo, rates)) 1 else 0) +
                        shifts.count { isNightShift(it.from, it.to, rates) }
                    Cadence.PER_SHIFT_TYPE -> (if (day.isWorkDay && day.shiftType?.id in item.shiftTypeIds) 1 else 0) +
                        shifts.count { it.covered?.id in item.shiftTypeIds }
                }
                if (times == 0) continue
                itemCounts[item.id] = (itemCounts[item.id] ?: 0) + times
                val v = item.amount.on(day.date).value
                if (v > 0) parts += DayPart(LineKind.ALLOWANCE, item.name, v * times, if (times > 1) "${item.cadence.label} × $times" else item.cadence.label)
            }
            // [PAYS-06] The user's own rules, on the minutes each one names.
            day.shiftType?.let { shiftNames[it.id] = it.name }
            day.extras.forEach { e -> e.covered?.let { shiftNames[it.id] = it.name }; extraKindNames.getOrPut(e.kind.id) { rules.extraName(e.kind.id) } }
            for (rule in profile.customRules) {
                if (!rule.on) continue
                val r = customRule(rule, day, m, holiday(day.date), hourly, monthlyBase, baseLabel, rates) ?: continue
                customTotals.getOrPut(rule.id) { CustomTotal(rule) }.apply { amount += r.amount; this.minutes += r.credited; times++ }
                parts += DayPart(LineKind.ALLOWANCE, rule.name, r.amount, r.formula)
            }
            // "야간 대근": the covered shift goes first unless the kind's own name already says it.
            val extraNames = day.extras.map { e ->
                val name = rules.extraName(e.kind.id)
                e.covered?.name?.takeUnless { it in name }?.let { "$it $name" } ?: name
            }
            val dayLabel = day.leave?.takeIf { it.isFullDay }?.let { "${day.shiftType?.name ?: "근무"} · ${it.label}" }
                ?: day.shiftType?.takeIf { day.isWorkDay }?.name
                ?: if (extraNames.isNotEmpty()) "쉬는 날" else "휴무"
            dayPays += DayPay(
                date = day.date,
                label = (listOf(dayLabel) + extraNames).joinToString(" + "),
                holidayName = KoreanHolidays.nameOf(day.date) ?: if (day.date in weeklyHolidays && day.extras.isNotEmpty()) "주휴일" else null,
                extras = extraNames,
                minutes = m,
                parts = parts,
            )
        }

        when (profile.wageType) {
            WageType.MONTHLY -> lines += PayLine(
                LineKind.BASE, "기본급", base.value,
                formula = when (base.source) {
                    ValueSource.RAISED -> "${won(base.basedOn!!.value)}에 ${profile.base.raise!!.describe()} ${base.raisesApplied}회 적용"
                    else -> "입력한 월 기본급"
                },
                estimated = baseEstimated,
            )
            WageType.HOURLY -> lines += line(LineKind.BASE, "기본 근무", regular, hourly, 1.0, "소정근로 시간 × 시급", "", baseEstimated)
            // [PAYS-03] A day of 소정근로 is one 일당; a shorter day is its share.
            WageType.DAILY -> lines += PayLine(
                LineKind.BASE, "일당", basePay(regular),
                formula = "일당 ${won(base.value)} × " +
                    if (regular % dailyMinutes == 0) "${regular / dailyMinutes}일" else "${hours(regular)} ÷ ${hours(dailyMinutes)}",
                minutes = regular, hourly = hourly, multiplier = 1.0,
                basis = "하루 소정근로 ${hours(dailyMinutes)}, 일당 ${won(base.value)} ÷ ${hours(dailyMinutes)} = 시급 ${won(hourly)}" +
                    " (근로기준법 시행령 제6조 제2항 제2호)",
                estimated = baseEstimated,
            )
        }
        if (paidBreak > 0) lines += line(
            LineKind.BASE, "유급 휴게", paidBreak, hourly, 1.0,
            "회사가 임금을 주는 휴게시간 (연장·주휴 시간에는 넣지 않아요)",
            // verify: whether a paid break inside 22~06 also earns the night premium; taken as no, since it is not work.
            "취업규칙·근로계약 (휴게시간은 근로시간이 아님, 근로기준법 제54조)", baseEstimated,
        )
        val companyNote = { custom: Boolean -> if (custom) " (회사 규칙)" else "" }
        // [EXTRA-02] Without a 주휴일 every day off is a 휴무일; say so where its hours are paid.
        val restNote = if (restDayWorked && rules.weeklyHoliday == WeeklyHoliday.Unknown && rules.restDayWork != RestDayWork.HOLIDAY)
            " · 주휴일을 정하지 않아 쉬는 날은 모두 휴무일로 계산" else ""
        if (extraRegular > 0) lines += line(
            LineKind.EXTRA_HOURS, "소정 외 근로 (8·40시간 이내)", extraRegular, hourly, 1.0,
            "근무표 밖에서 더 일했지만 하루 8시간·주 40시간 안인 시간",
            "근로기준법 제50조 (연장은 8시간·40시간을 넘는 부분만)$restNote", baseEstimated,
        )
        if (overtime > 0) lines += line(
            LineKind.OVERTIME, "연장근로", overtime, hourly, mOt,
            "하루 8시간·주 40시간을 넘는 근무" + companyNote(rules.overtimePremium != null) +
                if (rules.restDayWork == RestDayWork.OVERTIME) ", 쉬는 날 근무 포함" else "",
            "근로기준법 제56조 제1항$restNote", baseEstimated,
        )
        if (night > 0 && pr.night > 0) lines += line(
            LineKind.NIGHT, "야간근로 가산", night, hourly, pr.night,
            "${rules.nightLabel(rates)} 근무에 더해 주는 가산분" + companyNote(rules.nightPremium != null || rules.hasNightWindow), "근로기준법 제56조 제3항", baseEstimated,
        )
        if (hWithin > 0) lines += line(
            LineKind.HOLIDAY, "휴일근로 (8시간 이내)", hWithin, hourly, mHw,
            "주휴일·공휴일 근무 8시간까지" + (if (profile.wageType == WageType.MONTHLY) " (${profile.holidayPayMode.label})" else "") +
                companyNote(rules.holidayPremiumWithin8h != null),
            "근로기준법 제56조 제2항 제1호", baseEstimated || "holidayPayMode" in profile.estimatedFields,
        )
        if (hOver > 0) lines += line(
            LineKind.HOLIDAY, "휴일근로 (8시간 초과)", hOver, hourly, mHo,
            "휴일 근무 8시간 넘는 부분" + companyNote(rules.holidayPremiumOver8h != null), "근로기준법 제56조 제2항 제2호", baseEstimated,
        )
        if (rWithin > 0) lines += line(
            LineKind.HOLIDAY, "쉬는 날 근무 (휴일근로로 대우)", rWithin, hourly, mRw,
            "휴무일 근무를 회사가 휴일근로로 쳐 주는 8시간까지" + companyNote(rules.holidayPremiumWithin8h != null),
            "취업규칙·단체협약 (법으로는 휴무일 근무는 휴일근로가 아님)", baseEstimated,
        )
        if (rOver > 0) lines += line(
            LineKind.HOLIDAY, "쉬는 날 근무 8시간 초과 (휴일근로로 대우)", rOver, hourly, mRo,
            "휴일근로로 쳐 주는 휴무일 근무의 8시간 넘는 부분" + companyNote(rules.holidayPremiumOver8h != null),
            "취업규칙·단체협약", baseEstimated,
        )
        for ((_, t) in extraTotals) {
            val rule = t.rule
            lines += PayLine(
                LineKind.EXTRA, "${t.label} (회사 규칙)", t.amount,
                formula = "${rule.describe()}: ${t.times}회" + (if (t.minutes > 0) ", ${hours(t.minutes)}" else ""),
                minutes = t.minutes,
                hourly = if (rule.basis == ExtraBasis.HOURLY_RATE) hourly else 0,
                multiplier = if (rule.basis == ExtraBasis.HOURLY_RATE) rule.multiplier else 0.0,
                basis = "달력에 ‘${rules.extraName(t.kind)}’로 넣은 근무",
                estimated = baseEstimated && (rule.basis == ExtraBasis.HOURLY_RATE || rule.basis == ExtraBasis.LEGAL || rule.legalPremiums ||
                    rule.basis == ExtraBasis.UNIT && (rule.unit != PayUnit.PER_HOUR && rule.unit != PayUnit.PER_TIME || rule.ticked.isNotEmpty())),
            )
            if (premiums && t.amount < t.legal) {
                warnings += "${t.label}을 회사 방식으로 계산하면 ${won(t.amount)}인데 법정 기준으로는 ${won(t.legal)}이에요. " +
                    "법정보다 적게 받고 있다면 근로기준법 제56조 위반일 수 있어요"
            }
        }

        for ((_, t) in customTotals) {
            val rule = t.rule
            val pay = rule.pay
            lines += PayLine(
                LineKind.ALLOWANCE, "${rule.name} (회사 규칙)", t.amount,
                formula = "${rule.describe(shiftNames, extraKindNames)}: ${t.times}일" +
                    (if (pay !is RulePay.PerTime && t.minutes > 0) ", ${hours(t.minutes)}" else ""),
                minutes = t.minutes,
                hourly = if (pay is RulePay.OfHourly) hourly else 0,
                multiplier = if (pay is RulePay.OfHourly) pay.rate else 0.0,
                basis = "직접 넣은 회사 규칙" + (rule.source.takeIf { it.isNotBlank() }?.let { " “$it”" } ?: "") + " · 통상임금 제외",
                estimated = baseEstimated && pay.followsWage,
            )
        }

        if (paidLeave > 0 && byHour) lines += line(
            LineKind.LEAVE, "유급 휴가", paidLeave, hourly, 1.0,
            "연차·유급 휴가로 쉰 근무시간", "근로기준법 제60조 (연차 유급휴가)", baseEstimated,
        )
        if (paidHoliday > 0) lines += line(
            LineKind.ALLOWANCE, "유급휴일수당 (공휴일)", paidHoliday, hourly, 1.0,
            "근무일과 겹친 공휴일의 1일분 통상임금 (일하면 휴일근로 수당을 더해서)",
            "근로기준법 제55조 제2항, 고용노동부 임금근로시간과-653 (2021.3.22.)", baseEstimated,
        )
        if (unpaidLeave > 0 && profile.wageType == WageType.MONTHLY) lines += line(
            LineKind.LEAVE, "무급 휴가 공제", unpaidLeave, hourly, -1.0,
            "무급으로 쉰 근무시간", "결근·무급 휴가는 일한 만큼만 지급", baseEstimated,
        )

        // [PAYS-01] 금액 모름: an allowance the user marked unknown and left at 0 is listed, not added.
        val missing = mutableListOf<MissingItem>()
        fun unknownAmount(item: PayItem) = Unknowns.item(item.id) in profile.estimatedFields
        // What each allowance paid this month, for the premiums it stands in for [PAYS-03].
        val allowancePaid = HashMap<String, Long>()
        for (item in profile.items) {
            if (item.cadence == Cadence.BONUS) {
                val times = item.timesIn(month)
                if (times == 0) continue
                val once = item.bonusPayment(base.value, month)
                if (once == 0L && unknownAmount(item)) {
                    missing += MissingItem(item.id, item.name, times)
                    continue
                }
                // [PAYX-10] Name the 명절 that falls this year so the moving month is explained.
                val festivals = item.festivals.mapNotNull { f ->
                    listOf(month.year, month.year + 1).firstNotNullOfOrNull { y ->
                        f.date(y)?.takeIf { f.payMonth(y, item.festivalMonthBefore) == month }
                    }?.let { "${f.label} ${it.monthValue}/${it.dayOfMonth}" }
                }
                val paidIn = listOfNotNull(
                    item.months.sorted().takeIf { it.isNotEmpty() }?.joinToString(",")?.let { "${it}월" },
                    festivals.takeIf { it.isNotEmpty() }?.joinToString("·")?.let { "$it ${if (item.festivalMonthBefore) "전 달" else "있는 달"}" },
                ).joinToString(" · ")
                if (once > 0) lines += PayLine(
                    LineKind.ALLOWANCE, item.name, once * times,
                    formula = (item.percentOfBase?.let { "기본급 ${won(base.value)} × ${pct(it)}" } ?: "정액 ${won(once)}") +
                        if (times > 1) " × ${times}회" else "",
                    basis = (if (item.ordinary) "통상임금에 포함 (연 ${item.timesInYear(month.year)}회 ÷ 12)" else "통상임금 제외") +
                        " · $paidIn 지급",
                )
                continue
            }
            val amount = item.amount.on(month)
            val count = if (item.cadence == Cadence.MONTHLY) 1 else itemCounts[item.id] ?: 0
            if (count > 0 && amount.value == 0L && unknownAmount(item)) {
                missing += MissingItem(item.id, item.name, count.takeUnless { item.cadence == Cadence.MONTHLY })
            }
            if (count == 0 || amount.value == 0L) continue
            allowancePaid[item.id] = amount.value * count
            lines += PayLine(
                LineKind.ALLOWANCE, item.name, amount.value * count,
                formula = if (count == 1) "${item.cadence.label} ${won(amount.value)}" else "${won(amount.value)} × ${count}회",
                basis = if (item.ordinary) "통상임금에 포함" else "",
                estimated = amount.source == ValueSource.RAISED || item.id in profile.estimatedFields || unknownAmount(item),
            )
        }

        coverPremiums(profile, lines, allowancePaid, warnings)

        // [PAYS-03] An approved 감시·단속 worker has no 주휴 (제55조 is in chapter 4).
        if (byHour && rules.weeklyHolidayPay && !supervisory) {
            weeklyHolidayLine(allMinutes, attendedByDate, weekFrom, periodStart, periodEnd, hourly, baseEstimated)?.let { lines += it }
        }

        if (hourly in 1 until rates.minimumHourlyWage) {
            warnings += "통상시급 ${won(hourly)}이 ${month.year}년 최저임금 ${won(rates.minimumHourlyWage)}보다 낮아요. 입력값을 확인해 주세요"
        }
        if (premiums) {
            // Compared with what the law gives here: no 연장·휴일 premium for an approved 감시·단속 worker [PAYS-03].
            listOf(
                Triple("연장 가산", rules.overtimePremium, law.overtime),
                Triple("야간 가산", rules.nightPremium, law.night),
                Triple("휴일 가산(8시간 이내)", rules.holidayPremiumWithin8h, law.holidayWithin8h),
                Triple("휴일 가산(8시간 초과)", rules.holidayPremiumOver8h, law.holidayOver8h),
            ).forEach { (name, company, legalRate) ->
                if (company != null && company < legalRate) warnings += "회사 $name ${pct(company)}가 법정 ${pct(legalRate)}보다 낮아요"
            }
        } else {
            warnings += "5인 미만 사업장이라 회사 규칙에 넣지 않은 연장·야간·휴일 가산은 계산하지 않았어요"
        }
        val r = rules.overtimeRounding
        if (r.unit > 1 && r.rounding == Rounding.DOWN) {
            warnings += "연장 시간을 ${r.unit}분 단위로 버리고 있어요. 일한 시간보다 적게 계산되면 임금 체불이 될 수 있어요"
        }

        // [PAYX-12] A company window that leaves out part of 22~06 can pay less than the law.
        if (checks && premiums && rules.hasNightWindow) {
            val legalPay = calculate(profile.copy(rules = rules.copy(nightStart = null, nightEnd = null)), month, resolve, checks = false, isHoliday = isHoliday)
            val legalNight = legalPay.lines.filter { it.kind == LineKind.NIGHT }.sumOf { it.amount }
            val mine = lines.filter { it.kind == LineKind.NIGHT }.sumOf { it.amount }
            if (legalNight > mine) warnings += "회사 야간 시간 ${rules.nightLabel(rates)}로 계산한 야간 가산 ${won(mine)}이 법정 22~06시 기준 " +
                "${won(legalNight)}보다 적어요. 22~06시 근무에는 법정 야간 가산을 받아야 해요(근로기준법 제56조 제3항)"
        }

        // [PAYX-07] Scheduled hours over 8 a day are 연장 by law unless 탄력적 근로시간제 (제51조) is in
        // place, so the difference to the legal count is shown rather than silently dropped.
        if (checks && profile.scheduleIsRegular && profile.flexPeriod == null && premiums && !supervisory) {
            val legalPay = calculate(profile.copy(scheduleIsRegular = false, dailyLimitMinutes = 8 * 60, weeklyLimitMinutes = 40 * 60),
                month, resolve, checks = false, isHoliday = isHoliday)
            val gap = legalPay.total - lines.sumOf { it.amount }
            val extra = legalPay.minutes.sumOf { it.overtime } - minutes.sumOf { it.overtime }
            if (gap > 0 && extra > 0) warnings += "근무표 시간을 모두 기본 근무로 계산했어요. 탄력적 근로시간제가 아니라면 법으로는 " +
                "하루 8시간·주 40시간을 넘는 ${hours(extra)}이 연장근로라 약 ${won(gap)}을 더 받아야 해요. 취업규칙을 확인해 보세요"
        }

        // [PAYS-03] The approval is the employer's to get from 고용노동부; without it the month pays what the law
        // gives everyone, so that figure is shown next to this one.
        if (checks && supervisory) {
            val without = calculate(profile.copy(rules = rules.copy(supervisoryApproved = false)), month, resolve, checks = false, isHoliday = isHoliday)
            if (without.total != lines.sumOf { it.amount }) warnings += "감시·단속 승인이 없으면 이 달은 ${won(without.total)}이에요. " +
                "승인은 고용노동부 지방관서에서 받아야 해요"
        }

        return MonthPay(month, lines, hourly, rates.version, minutes, warnings, dayPays,
            periodStart.takeUnless { profile.period.isCalendarMonth }, periodEnd.takeUnless { profile.period.isCalendarMonth },
            profile.period.payDate(month), missing, itemCounts.toMap()).withHourlyNote(hourlyFormula)
    }

    /**
     * [PAY-05][PAYS-03] An allowance that stands in for 연장·야간·휴일 premiums (a 고정 연장수당, or a 야간수당
     * paid instead of the night premium) already pays them up to what it paid this month: only what the law
     * gives beyond that is owed on top, and a shortfall is never taken back. So the month holds the larger of
     * the two. Allowances standing in for the same premiums share one line; company rules for extra work
     * (EXTRA lines) are never covered.
     */
    private fun coverPremiums(profile: PayProfile, lines: MutableList<PayLine>, paid: Map<String, Long>, warnings: MutableList<String>) {
        val covering = profile.items.filter { it.cadence != Cadence.BONUS && it.coveredKinds.isNotEmpty() }
        if (covering.isEmpty()) return
        // The legal premium lines each group can still take from; a premium is never covered twice.
        val left = PayItem.COVERABLE.associateWith { k -> lines.filter { it.kind == k && it.amount > 0 }.sumOf { it.amount } }.toMutableMap()
        covering.groupBy { it.coveredKinds }.forEach { (kinds, group) ->
            val prepaid = group.sumOf { paid[it.id] ?: 0L }
            val earned = kinds.sumOf { left.getValue(it) }
            val covered = minOf(prepaid, earned)
            var take = covered
            for (k in kinds) {
                val t = minOf(take, left.getValue(k))
                left[k] = left.getValue(k) - t
                take -= t
            }
            // A 고정 연장수당 that covers 연장 only reads as it always has.
            val fixedOt = kinds == setOf(LineKind.OVERTIME) && group.all { it.isFixedOvertime }
            val names = if (fixedOt) "고정 연장수당" else group.joinToString("·") { it.name }
            val what = kinds.joinToString("·") { coverName(it) } + "수당"
            if (covered > 0) lines += PayLine(
                kinds.first(), "${particle(names, "으로", "로")} 이미 받은 몫", -covered,
                formula = if (fixedOt) "연장근로 ${won(earned)} 중 고정 연장수당 ${won(prepaid)}만큼" else "$what ${won(earned)} 중 $names ${won(prepaid)}만큼",
                multiplier = -1.0,
                basis = if (fixedOt) "고정 연장수당은 정한 시간까지의 연장수당을 미리 주는 것이라 겹쳐 받지 않아요"
                else "${particle(names, "은", "는")} 법정 ${what}을 대신해 주는 것이라 겹쳐 받지 않아요",
            )
            if (prepaid > 0 && earned > prepaid) warnings += "이번 달 ${particle(what, "이", "가")} ${won(earned)}으로 $names ${won(prepaid)}보다 " +
                "${won(earned - prepaid)} 많아요. 차액은 따로 받아야 해요"
        }
    }

    private fun coverName(kind: LineKind): String = when (kind) {
        LineKind.OVERTIME -> "연장"
        LineKind.NIGHT -> "야간"
        else -> "휴일"
    }

    /** What a company rule pays for one piece of work, how, and what the law would pay for it. */
    private class RuleResult(val amount: Long, val formula: String, val credited: Int, val legal: Long)

    /** [EXTRA-08] Where a piece of work sits: on a day off or not, and on the clock (null when unknown). */
    private class PieceAt(
        val restDay: Boolean,
        val from: LocalDateTime?,
        val to: LocalDateTime?,
        val paid: Int,
        val breakMinutes: Int,
        val breakStart: java.time.LocalTime?,
        /** The part of the span the rule pays, for a 공휴일 shift paid only for its holiday minutes. */
        val share: Double = 1.0,
    )

    /**
     * [EXTRA-03] One piece of work paid by [rule]: the base part, the hours a per-time payment does not
     * cover at 100%, the legal 연장·휴일 premiums when the company adds them, and the amount on top.
     * Night stays with the night line unless the rule leaves it out.
     */
    private fun applyRule(
        rule: ExtraRule, w: WorkMinutes, piece: PieceAt, hourly: Long, monthlyBase: Long, baseLabel: String,
        pr: Premiums, law: Premiums, rates: Rates,
    ): RuleResult {
        fun money(min: Int, rate: Double) = (min / 60.0 * hourly * rate).roundToLong()
        val worked = w.worked
        // What the law pays these hours, to compare: 100% plus premiums, night too when the rule pays it itself.
        val legal = money(w.regular, 1.0) + money(w.overtime, 1.0 + law.overtime) +
            money(w.holidayWithin8h, law.holidayBase + law.holidayWithin8h) + money(w.holidayOver8h, law.holidayBase + law.holidayOver8h) +
            money(w.restWithin8h, 1.0 + law.holidayWithin8h) + money(w.restOver8h, 1.0 + law.holidayOver8h) +
            (if (rule.ownsNight) money(w.night, law.night) else 0)
        if (rule.basis == ExtraBasis.LEGAL) {
            // By law with an amount on top: the hours stay in the legal lines.
            return RuleResult(rule.bonus, "법대로 계산하고 1회 ${won(rule.bonus)} 더함", 0, 0)
        }
        if (rule.basis == ExtraBasis.UNIT) return applyUnit(rule, w, piece, hourly, monthlyBase, baseLabel, rates, legal)
        val credited = maxOf(worked, rule.minMinutes)
        val night = worked > 0 && w.night * 2 >= worked
        val parts = mutableListOf<String>()
        val basePart = when (rule.basis) {
            ExtraBasis.HOURLY_RATE -> money(credited, rule.multiplier).also { parts += "${hours(credited)} × ${won(hourly)} × ${pct(rule.multiplier)}" }
            ExtraBasis.FIXED_PER_TIME -> rule.amount.also { parts += "1회 ${won(it)}" }
            ExtraBasis.PERCENT_OF_BASE -> Math.round(monthlyBase * rule.percentOfBase)
                .also { parts += "$baseLabel ${won(monthlyBase)} × ${ExtraRule.percent(rule.percentOfBase)} = ${won(it)}" }
            ExtraBasis.FIXED_PER_HOUR -> (credited / 60.0 * rule.amount).roundToLong().also { parts += "${hours(credited)} × ${won(rule.amount)}" }
            ExtraBasis.TIERED -> rule.tieredAmount(credited, hourly, night)
                .also { parts += "${if (night) "야간" else "주간"} ${rule.describeTiers(night)} · ${hours(credited)}" }
            ExtraBasis.LEGAL, ExtraBasis.UNIT -> 0L
        }
        // Hours beyond what one payment covers are paid like any other hour.
        val uncovered = rule.coverMinutes?.takeIf { rule.perTime }?.let { (credited - it).coerceAtLeast(0) } ?: 0
        val uncoveredPay = money(uncovered, 1.0)
        if (uncovered > 0) parts += "${hours(rule.coverMinutes!!)} 넘는 ${hours(uncovered)} × 100% ${won(uncoveredPay)}"
        var premiumPay = 0L
        if (rule.legalPremiums) {
            listOf(
                Triple("연장 가산", w.overtime, pr.overtime),
                Triple("휴일 가산", w.holidayWithin8h + w.restWithin8h, pr.holidayWithin8h),
                Triple("휴일 8시간 초과 가산", w.holidayOver8h + w.restOver8h, pr.holidayOver8h),
            ).forEach { (label, min, p) ->
                if (min > 0 && p > 0) {
                    val v = money(min, p)
                    premiumPay += v
                    parts += "$label ${hours(min)} × ${pct(p)} ${won(v)}"
                }
            }
        }
        if (rule.bonus > 0) parts += "1회 ${won(rule.bonus)}"
        val formula = parts.joinToString(" + ") + if (credited > worked) " (최소 ${hours(rule.minMinutes)} 인정)" else ""
        return RuleResult(basePart + uncoveredPay + premiumPay + rule.bonus, formula, credited, legal)
    }

    /**
     * [EXTRA-08] One piece paid as an hour's pay ([ExtraRule.unit]) plus each ticked premium on the
     * minutes it names. Premiums are on that hour, or on 통상시급 for a payment each time; nothing
     * the rule does not tick is added, and the month's warnings say when that is below the law.
     */
    private fun applyUnit(rule: ExtraRule, w: WorkMinutes, piece: PieceAt, hourly: Long, monthlyBase: Long, baseLabel: String, rates: Rates, legal: Long): RuleResult {
        val worked = w.worked
        val credited = maxOf(worked, rule.minMinutes)
        val unitHourly = when (rule.unit) {
            PayUnit.ORDINARY, PayUnit.PER_TIME -> hourly.toDouble()
            PayUnit.BASE_SHARE -> monthlyBase * rule.percentOfBase * 60.0 / rule.unitMinutes.coerceAtLeast(1)
            PayUnit.PER_HOUR -> rule.amount.toDouble()
        }
        fun money(min: Int, rate: Double) = (min / 60.0 * unitHourly * rate).roundToLong()
        val unitText = when (rule.unit) {
            PayUnit.ORDINARY, PayUnit.PER_TIME -> won(hourly)
            PayUnit.BASE_SHARE -> "${won(unitHourly.roundToLong())}($baseLabel ${won(monthlyBase)} × ${ExtraRule.percent(rule.percentOfBase)} ÷ ${hours(rule.unitMinutes)})"
            PayUnit.PER_HOUR -> won(rule.amount)
        }
        val parts = mutableListOf<String>()
        val basePart = if (rule.unit == PayUnit.PER_TIME) {
            val uncovered = rule.coverMinutes?.let { (credited - it).coerceAtLeast(0) } ?: 0
            val beyond = money(uncovered, 1.0)
            parts += "1회 ${won(rule.amount)}"
            if (uncovered > 0) parts += "${hours(rule.coverMinutes!!)} 넘는 ${hours(uncovered)} × $unitText = ${won(beyond)}"
            rule.amount + beyond
        } else {
            money(credited, 1.0).also { parts += "${hours(credited)} × $unitText = ${won(it)}" }
        }
        val holiday = w.holidayWithin8h + w.holidayOver8h
        val holidayTicked = rule.ticked.any { it.key == Premium.HOLIDAY }
        val offDay = piece.restDay || holiday > 0
        var premiumPay = 0L
        for (p in rule.ticked) {
            if (p.offDaysOnly && !offDay) continue
            val m = when (val sc = p.scope) {
                PremiumScope.All -> worked
                is PremiumScope.Over -> (worked - sc.minutes).coerceAtLeast(0)
                PremiumScope.LegalOvertime -> w.overtime + w.holidayOver8h + w.restOver8h
                PremiumScope.Night -> w.night
                is PremiumScope.Window -> windowMinutes(piece, sc, rates)
                PremiumScope.Holiday -> holiday
                // A day off that is also a paid holiday takes the 휴일 premium when that is ticked, not both.
                PremiumScope.RestDay -> if (piece.restDay) worked - (if (holidayTicked) holiday else 0) else 0
            }
            if (m <= 0) continue
            val v = money(m, p.rate)
            premiumPay += v
            parts += "${p.name} ${hours(m)} × ${pct(p.rate)} = ${won(v)}"
        }
        if (rule.bonus > 0) parts += "1회 ${won(rule.bonus)}"
        val formula = parts.joinToString(" + ") + if (credited > worked) " (최소 ${hours(rule.minMinutes)} 인정)" else ""
        return RuleResult(basePart + premiumPay + rule.bonus, formula, credited, legal)
    }

    private class CustomTotal(val rule: CustomRule, var amount: Long = 0, var minutes: Int = 0, var times: Int = 0)

    /**
     * [PAYS-06] What one of the user's own rules pays for [day], or null when nothing on it matches. The day's
     * shift and each piece of extra work are checked one by one; minutes in a clock window are counted on the
     * clock, the break taken off as for night minutes, and a piece with no clock times has none.
     */
    private fun customRule(
        rule: CustomRule, day: ResolvedDay, m: DayMinutes, holiday: Boolean, hourly: Long, monthlyBase: Long, baseLabel: String, rates: Rates,
    ): RuleResult? {
        val w = rule.whenever
        val dayFits = when (w.dayKind) {
            RuleDayKind.ANY -> true
            RuleDayKind.WORK_DAY -> day.isWorkDay
            RuleDayKind.OFF_DAY -> day.isRestDay
            RuleDayKind.HOLIDAY -> holiday
        }
        if (!dayFits || w.daysOfWeek.isNotEmpty() && day.date.dayOfWeek !in w.daysOfWeek) return null
        val classifier = MinuteClassifier({ rates })
        fun minutesOf(from: LocalDateTime?, to: LocalDateTime?, paid: Int, breakMinutes: Int, breakStart: java.time.LocalTime?): Int = when {
            paid <= 0 -> 0
            !w.hasWindow -> paid
            from == null || to == null -> 0
            else -> classifier.windowMinutes(from, to, paid, breakMinutes, breakStart, w.from!!, w.to!!, rates)
        }
        var matched = 0
        if (w.pieces != RulePieces.EXTRA && day.isWorkDay && (w.shiftTypeIds.isEmpty() || day.shiftType?.id in w.shiftTypeIds)) {
            matched += minutesOf(day.shiftFrom, day.shiftTo, m.shift.worked, day.breakMinutes, day.breakStart)
        }
        if (w.pieces != RulePieces.SHIFT) for ((i, e) in day.extras.withIndex()) {
            if (w.extraKinds.isNotEmpty() && e.kind.id !in w.extraKinds) continue
            val covered = e.covered?.id ?: day.shiftType?.takeIf { day.isWorkDay }?.id
            if (w.shiftTypeIds.isNotEmpty() && covered !in w.shiftTypeIds) continue
            matched += minutesOf(e.from, e.to, m.extra(i)?.minutes?.worked ?: 0, e.breakMinutes, e.breakStart)
        }
        val minutes = (matched - w.overMinutes).coerceAtLeast(0)
        if (minutes <= 0 || matched < w.minMinutes) return null
        val (amount, formula) = when (val pay = rule.pay) {
            is RulePay.PerHour -> (minutes / 60.0 * pay.amount).roundToLong() to "${hours(minutes)} × ${won(pay.amount)}"
            is RulePay.OfHourly -> (minutes / 60.0 * hourly * pay.rate).roundToLong() to "${hours(minutes)} × ${won(hourly)} × ${pct(pay.rate)}"
            is RulePay.OfBase -> (monthlyBase * pay.share * minutes / pay.perMinutes.coerceAtLeast(1)).roundToLong() to
                "$baseLabel ${won(monthlyBase)} × ${ExtraRule.percent(pay.share)} × ${hours(minutes)} ÷ ${hours(pay.perMinutes)}"
            is RulePay.PerTime -> pay.amount to "1회 ${won(pay.amount)}"
        }
        return RuleResult(amount, formula, if (rule.pay is RulePay.PerTime) 0 else minutes, 0)
    }

    /** [EXTRA-08] Minutes of [piece] between the scope's clock times; none when it has no clock times. */
    private fun windowMinutes(piece: PieceAt, w: PremiumScope.Window, rates: Rates): Int {
        val from = piece.from ?: return 0
        val to = piece.to ?: return 0
        val m = MinuteClassifier({ rates }).windowMinutes(from, to, piece.paid, piece.breakMinutes, piece.breakStart, w.from, w.to, rates)
        return (m * piece.share).roundToLong().toInt()
    }

    /** [EXTRA-02] Dates that are the 주휴일 among [days]. */
    private fun weeklyHolidays(rule: WeeklyHoliday, days: List<ResolvedDay>, weekStart: DayOfWeek): Set<LocalDate> = when (rule) {
        WeeklyHoliday.Unknown -> emptySet()
        is WeeklyHoliday.Fixed -> days.map { it.date }.filter { it.dayOfWeek == rule.day }.toSet()
        WeeklyHoliday.FirstRestDay -> days.filter { it.isRestDay }
            .groupBy { it.date.with(TemporalAdjusters.previousOrSame(weekStart)) }
            .values.map { week -> week.minOf { it.date } }.toSet()
    }

    /** Overtime rounded per day [PAY-07]: the difference comes off the day's last hours of overtime. */
    private fun roundOvertime(m: DayMinutes, rounding: MinuteRounding): DayMinutes {
        val total = m.overtime
        val rounded = rounding.apply(total)
        if (rounded == total) return m
        var shift = m.shift
        val extras = m.extras.toMutableList()
        if (rounded > total) {
            // Rounded up: the extra minutes go to the last piece that has overtime.
            val i = extras.indexOfLast { it.minutes.overtime > 0 }
            if (i >= 0) extras[i] = extras[i].copy(minutes = extras[i].minutes.copy(overtime = extras[i].minutes.overtime + rounded - total))
            else shift = shift.copy(overtime = shift.overtime + rounded - total)
        } else {
            var cut = total - rounded
            for (i in extras.indices.reversed()) {
                val take = minOf(cut, extras[i].minutes.overtime)
                if (take > 0) extras[i] = extras[i].copy(minutes = extras[i].minutes.copy(overtime = extras[i].minutes.overtime - take))
                cut -= take
            }
            if (cut > 0) shift = shift.copy(overtime = shift.overtime - cut)
        }
        return m.copy(shift = shift, extras = extras)
    }

    /**
     * 주휴수당 [PAY-12 선행, 근로기준법 제55조·시행령 제30조]: each week ending in the pay period with at least
     * 15 scheduled hours earns (weekly hours ÷ 40) × 8 hours, at most 8. The calendar is taken as
     * worked in full (개근). Extra work is not 소정근로 and does not count.
     */
    private fun weeklyHolidayLine(
        all: List<DayMinutes>, attended: Map<LocalDate, Int>, from: LocalDate, periodStart: LocalDate, periodEnd: LocalDate,
        hourly: Long, estimated: Boolean,
    ): PayLine? {
        var weekStart = from
        var minutes = 0
        val weeks = mutableListOf<Int>()
        while (!weekStart.plusDays(6).isAfter(periodEnd)) {
            val end = weekStart.plusDays(6)
            if (!end.isBefore(periodStart)) {
                // Paid leave and paid holidays count as attended hours of the week.
                val worked = all.filter { !it.date.isBefore(weekStart) && !it.date.isAfter(end) }
                    .sumOf { it.regular + (attended[it.date] ?: 0) }
                if (worked >= 15 * 60) {
                    val credit = minOf(worked, 40 * 60) / 5
                    minutes += credit
                    weeks += credit
                }
            }
            weekStart = weekStart.plusDays(7)
        }
        if (weeks.isEmpty()) return null
        return line(
            LineKind.ALLOWANCE, "주휴수당", minutes, hourly, 1.0,
            "${weeks.size}주 (주마다 근무시간 ÷ 40 × 8시간, 최대 8시간)", "근로기준법 제55조, 시행령 제30조 (달력대로 개근 가정)", estimated,
        )
    }

    private fun MonthPay.withHourlyNote(formula: String) =
        copy(lines = lines.map { if (it.hourly > 0) it.copy(formula = "${it.formula} · 통상시급 = $formula") else it })

    private fun line(
        kind: LineKind, label: String, minutes: Int, hourly: Long, multiplier: Double,
        what: String, basis: String, estimated: Boolean,
    ) = PayLine(
        kind, label,
        amount = (minutes / 60.0 * hourly * multiplier).roundToLong(),
        formula = "$what: ${hours(minutes)} × ${won(hourly)} × ${pct(multiplier)}",
        minutes = minutes, hourly = hourly, multiplier = multiplier, basis = basis, estimated = estimated,
    )

    /** A shift with at least half its time in the night window counts as a night shift. */
    private fun isNightShift(from: LocalDateTime?, to: LocalDateTime?, rates: Rates): Boolean {
        if (from == null || to == null) return false
        val total = java.time.Duration.between(from, to).toMinutes()
        val night = MinuteClassifier({ rates }).nightMinutes(from, to, rates)
        return total > 0 && night * 2 >= total
    }

    /** [PAYS-03] The day with its breaks counted as work (대기시간, 근로기준법 제50조 제3항), the day's shift and extra work alike. */
    private fun ResolvedDay.breaksAsWork(): ResolvedDay = copy(
        scheduledMinutes = scheduledMinutes + breakMinutes,
        breakMinutes = 0,
        breakStart = null,
        extras = extras.map { it.copy(minutes = it.minutes + it.breakMinutes, breakMinutes = 0, breakStart = null) },
    )

    /** [PAY-05] A month's 통상임금 before it is divided by 월 소정근로시간 (monthly pay). */
    class OrdinaryWage(
        val base: Long,
        /** Monthly allowances in 통상임금; one standing in for premiums never is. */
        val allowances: Long,
        /** Regular bonuses in 통상임금: a year's payments ÷ 12. */
        val bonuses: Long,
    ) {
        val total: Long get() = base + allowances + bonuses
    }

    companion object {
        /**
         * [PAY-05][PAYS-02] The 통상임금 of [month] for monthly pay: 기본급, the monthly allowances that count and
         * the monthly share of regular bonuses. Bonuses count at a year's payments ÷ 12 (시행령 제6조 제2항 제5호)
         * from 2024-12-19 on: the 전원합의체 ruling of that day applies to later pay only.
         */
        fun ordinaryWage(profile: PayProfile, month: YearMonth): OrdinaryWage {
            val base = profile.base.on(month).value
            val allowances = profile.items.filter { it.ordinary && it.cadence == Cadence.MONTHLY && !it.isFixedOvertime && it.coveredKinds.isEmpty() }
                .sumOf { it.amount.on(month).value }
            val bonuses = if (month.atEndOfMonth().isBefore(BONUS_ORDINARY_FROM)) 0L
            else profile.items.filter { it.ordinary && it.cadence == Cadence.BONUS }.sumOf { it.bonusMonthlyShare(base, month) }
            return OrdinaryWage(base, allowances, bonuses)
        }

        fun hours(minutes: Int): String =
            if (minutes % 60 == 0) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"

        /** 대법원 2024.12.19. 2020다247190·2023다302838 (전원합의체): 정기상여금 is 통상임금 from this date. */
        val BONUS_ORDINARY_FROM: LocalDate = LocalDate.of(2024, 12, 19)

        fun pct(m: Double): String = "${(m * 100).roundToLong()}%"
    }
}
