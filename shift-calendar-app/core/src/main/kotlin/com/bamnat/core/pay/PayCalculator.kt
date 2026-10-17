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
) {
    val total: Long get() = lines.sumOf { it.amount }
    val estimated: Boolean get() = lines.any { it.estimated } || minutes.any { it.estimated }
    fun day(date: LocalDate): DayPay? = days.firstOrNull { it.date == date }
}

/**
 * Turns a month of calendar days into pay [PAY-01, PAY-04..06, PAYX-07, PAYX-08, EXTRA-02, EXTRA-03].
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
        /** Paid holidays besides the 주휴일; null uses the built-in 공휴일 when the company counts them. */
        isHoliday: ((LocalDate) -> Boolean)? = null,
    ): MonthPay {
        val rules = profile.rules
        // [PAYX-12] The company's night window replaces 22~06 for every shift, base shifts included.
        // [PAYX-15] The days this month's pay covers, e.g. 9/21~10/20 for October.
        val periodStart = profile.period.start(month)
        val periodEnd = profile.period.end(month)
        fun inPeriod(d: LocalDate) = !d.isBefore(periodStart) && !d.isAfter(periodEnd)
        val rates = rules.applyNight(RateTable.on(periodStart, rateTable))
        val premiums = profile.fiveOrMoreEmployees
        // 공휴일 are paid holidays only at 5인 이상 (근로기준법 제55조 제2항, 시행령 별표1); below that
        // they are ordinary days unless the caller says otherwise.
        // [PAYX-07] 노동절 is a paid holiday at every workplace and cannot be swapped, so it skips both checks.
        val publicHoliday = isHoliday ?: { d: LocalDate ->
            KoreanHolidays.isLaborDay(d) || premiums && rules.publicHolidaysPaid && KoreanHolidays.isHoliday(d)
        }
        // Start at the week containing the 1st so weekly overtime counts the days before it, or at the
        // 탄력적 근로시간제 period's start so a period ending this month is settled whole. End with the
        // week holding the last day: extra work is the week's last hours, so the whole week decides.
        val weekFrom = periodStart.with(TemporalAdjusters.previousOrSame(profile.weekStart))
        val from = profile.flexPeriod?.takeIf { profile.scheduleIsRegular }?.start(periodStart)
            ?.let { minOf(it, weekFrom) } ?: weekFrom
        val to = periodEnd.with(TemporalAdjusters.nextOrSame(profile.weekStart.minus(1)))
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .map(resolve).toList()
        // [EXTRA-02] The 주휴일 is a paid holiday under every company rule (제55조 제1항).
        val weeklyHolidays = weeklyHolidays(rules.weeklyHoliday, days, profile.weekStart)
        val holiday = { d: LocalDate -> d in weeklyHolidays || publicHoliday(d) }
        val classifier = MinuteClassifier(
            rates = { rules.applyNight(RateTable.on(it, rateTable)) },
            options = ClassifyOptions(profile.dailyLimitMinutes, profile.weeklyLimitMinutes, profile.weekStart,
                restDayWork = rules.restDayWork, scheduleIsRegular = profile.scheduleIsRegular,
                flexPeriod = profile.flexPeriod, holidayByClock = !rules.holidayByShiftStart),
        )
        val allMinutes = classifier.classify(days, holiday).map { roundOvertime(it, rules.overtimeRounding) }
        val minutes = allMinutes.filter { inPeriod(it.date) }
        val byDate = minutes.associateBy { it.date }
        val monthDays = days.filter { inPeriod(it.date) }

        val base = profile.base.on(month)
        val ordinaryMonthly = profile.items.filter { it.ordinary && it.cadence == Cadence.MONTHLY && !it.isFixedOvertime }
            .sumOf { it.amount.on(month).value }
        // [PAY-05] Regular bonuses count toward 통상임금 at a year's payments ÷ 12 (시행령 제6조 제2항
        // 제5호), from 2024-12-19 on: the 전원합의체 ruling of that day applies to later pay only.
        val bonusMonthly = if (month.atEndOfMonth().isBefore(BONUS_ORDINARY_FROM)) 0L
        else profile.items.filter { it.ordinary && it.cadence == Cadence.BONUS }
            .sumOf { it.bonusMonthlyShare(base.value, month) }
        val hourly = when (profile.wageType) {
            WageType.MONTHLY -> ((base.value + ordinaryMonthly + bonusMonthly).toDouble() / profile.monthlyHours.coerceAtLeast(1)).roundToLong()
            WageType.HOURLY -> base.value
        }
        val hourlyFormula = when (profile.wageType) {
            WageType.MONTHLY -> "(기본급 ${won(base.value)}" +
                (if (ordinaryMonthly > 0) " + 통상 수당 ${won(ordinaryMonthly)}" else "") +
                (if (bonusMonthly > 0) " + 상여금 월할 ${won(bonusMonthly)}" else "") +
                ") ÷ ${profile.monthlyHours}시간"
            WageType.HOURLY -> "시급"
        }
        // [EXTRA-03] 기본급 for a rule paid as a share of it; hourly pay counts a month of 소정 hours.
        val monthlyBase = when (profile.wageType) {
            WageType.MONTHLY -> base.value
            WageType.HOURLY -> base.value * profile.monthlyHours
        }
        val baseLabel = if (profile.wageType == WageType.MONTHLY) "기본급" else "시급 × ${profile.monthlyHours}시간"

        fun legal(p: Double) = if (premiums) p else 0.0
        val pr = Premiums(
            overtime = rules.overtimePremium ?: legal(rates.overtimePremium),
            night = rules.nightPremium ?: legal(rates.nightPremium),
            holidayWithin8h = rules.holidayPremiumWithin8h ?: legal(rates.holidayPremiumWithin8h),
            holidayOver8h = rules.holidayPremiumOver8h ?: legal(rates.holidayPremiumOver8h),
            // [EXTRA-02] '가산만' is about a paid holiday already in the salary; a day off never is.
            holidayBase = if (profile.wageType == WageType.HOURLY) 1.0 else profile.holidayPayMode.baseMultiplier,
        )
        val law = Premiums(legal(rates.overtimePremium), legal(rates.nightPremium), legal(rates.holidayPremiumWithin8h),
            legal(rates.holidayPremiumOver8h), pr.holidayBase)
        val mOt = 1.0 + pr.overtime
        val mHw = pr.holidayBase + pr.holidayWithin8h
        val mHo = pr.holidayBase + pr.holidayOver8h
        val mRw = 1.0 + pr.holidayWithin8h
        val mRo = 1.0 + pr.holidayOver8h
        fun money(min: Int, rate: Double) = (min / 60.0 * hourly * rate).roundToLong()

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
        // Leave [FEAT-10]: minutes by kind, and paid leave per date for 주휴 weeks.
        var paidLeave = 0; var unpaidLeave = 0
        val paidLeaveByDate = days.filter { it.leave?.kind?.paid == true }.associate { it.date to it.leaveMinutes }
        // [PAY-13] A paid holiday on a scheduled working day: hourly and daily-paid workers get one
        // day's ordinary wage for it, worked or not, and holiday pay on top when they work it
        // (고용노동부 임금근로시간과-653, 2021.3.22.). A 비번 day that falls on a holiday earns nothing.
        // Monthly pay already includes it. The 주휴일 (Sundays when it is not set) is left out: 주휴수당 covers it.
        val weeklyHolidayDay = (rules.weeklyHoliday as? WeeklyHoliday.Fixed)?.day ?: DayOfWeek.SUNDAY.takeIf { rules.weeklyHoliday == WeeklyHoliday.Unknown }
        fun paidHolidayMinutes(day: ResolvedDay): Int =
            if (profile.wageType != WageType.HOURLY || !publicHoliday(day.date) || day.date.dayOfWeek == weeklyHolidayDay || day.date in weeklyHolidays) 0
            else (day.scheduledMinutes + day.leaveMinutes).coerceAtMost(rates.dailyLimitMinutes)
        var paidHoliday = 0
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
                if (!rule.nightPremium) n -= w.night
            }
            fun pay(key: Triple<Long, Long?, Boolean>, name: String, rule: ExtraRule, w: WorkMinutes) {
                val r = applyRule(rule, w, hourly, monthlyBase, baseLabel, pr, law)
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
                    pay(Triple(shiftKind, null, true), "${rules.extraName(shiftKind)} 방식 (공휴일 근무)", shiftRule, w)
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
                pay(Triple(e.kind.id, perShift?.let { covered!!.id }, false), name, rule, em.minutes)
            }
            if (day.isRestDay && day.extras.isNotEmpty()) restDayWorked = true

            regular += reg; extraRegular += xr; overtime += ot; night += n
            hWithin += hw; hOver += ho; rWithin += rw; rOver += ro
            if (profile.wageType == WageType.HOURLY && reg > 0) {
                parts.add(0, DayPart(LineKind.BASE, "기본", money(reg, 1.0), "${hours(reg)} × ${won(hourly)}"))
            }
            if (xr > 0) parts += DayPart(LineKind.EXTRA_HOURS, "소정 외 근로", money(xr, 1.0), "${hours(xr)} × ${won(hourly)} (8·40시간 이내)")
            if (ot > 0) parts += DayPart(LineKind.OVERTIME, "연장", money(ot, mOt), "${hours(ot)} × ${pct(mOt)}")
            if (n > 0 && pr.night > 0) parts += DayPart(LineKind.NIGHT, "야간 가산", money(n, pr.night), "${hours(n)} × ${pct(pr.night)} (${rules.nightLabel(rates)})")
            if (hw > 0) parts += DayPart(LineKind.HOLIDAY, "휴일", money(hw, mHw), "${hours(hw)} × ${pct(mHw)}")
            if (ho > 0) parts += DayPart(LineKind.HOLIDAY, "휴일 8시간 초과", money(ho, mHo), "${hours(ho)} × ${pct(mHo)}")
            if (rw > 0) parts += DayPart(LineKind.HOLIDAY, "쉬는 날 근무", money(rw, mRw), "${hours(rw)} × ${pct(mRw)} (휴일근로로 대우)")
            if (ro > 0) parts += DayPart(LineKind.HOLIDAY, "쉬는 날 8시간 초과", money(ro, mRo), "${hours(ro)} × ${pct(mRo)} (휴일근로로 대우)")

            paidHolidayMinutes(day).takeIf { it > 0 }?.let { mins ->
                paidHoliday += mins
                parts += DayPart(LineKind.ALLOWANCE, "유급휴일", money(mins, 1.0), "${hours(mins)} × ${won(hourly)} (공휴일)")
            }
            day.leave?.let { leave ->
                val mins = day.leaveMinutes
                val (amount, formula) = when {
                    leave.kind.paid && profile.wageType == WageType.HOURLY -> money(mins, 1.0) to "${hours(mins)} × ${won(hourly)} (유급)"
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
        }
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
                estimated = baseEstimated && (rule.basis == ExtraBasis.HOURLY_RATE || rule.basis == ExtraBasis.LEGAL || rule.legalPremiums),
            )
            if (premiums && t.amount < t.legal) {
                warnings += "${t.label}을 회사 방식으로 계산하면 ${won(t.amount)}인데 법정 기준으로는 ${won(t.legal)}이에요. " +
                    "법정보다 적게 받고 있다면 근로기준법 제56조 위반일 수 있어요"
            }
        }

        if (paidLeave > 0 && profile.wageType == WageType.HOURLY) lines += line(
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

        for (item in profile.items) {
            if (item.cadence == Cadence.BONUS) {
                val times = item.timesIn(month)
                if (times == 0) continue
                val once = item.bonusPayment(base.value, month)
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
            if (count == 0 || amount.value == 0L) continue
            lines += PayLine(
                LineKind.ALLOWANCE, item.name, amount.value * count,
                formula = if (count == 1) "${item.cadence.label} ${won(amount.value)}" else "${won(amount.value)} × ${count}회",
                basis = if (item.ordinary) "통상임금에 포함" else "",
                estimated = amount.source == ValueSource.RAISED || item.id in profile.estimatedFields,
            )
        }

        // [PAY-05] 고정 연장수당 already pays overtime up to its amount; only what the hours earn
        // beyond it is owed on top, and a shortfall is never taken back.
        val fixedOt = profile.items.filter { it.isFixedOvertime }
        if (fixedOt.isNotEmpty()) {
            val prepaid = fixedOt.sumOf { it.amount.on(month).value }
            val earned = lines.filter { it.kind == LineKind.OVERTIME }.sumOf { it.amount }
            val covered = minOf(prepaid, earned)
            if (covered > 0) lines += PayLine(
                LineKind.OVERTIME, "고정 연장수당으로 이미 받은 몫", -covered,
                formula = "연장근로 ${won(earned)} 중 고정 연장수당 ${won(prepaid)}만큼",
                multiplier = -1.0,
                basis = "고정 연장수당은 정한 시간까지의 연장수당을 미리 주는 것이라 겹쳐 받지 않아요",
            )
            if (earned > prepaid) warnings += "이번 달 연장수당이 ${won(earned)}으로 고정 연장수당 ${won(prepaid)}보다 " +
                "${won(earned - prepaid)} 많아요. 차액은 따로 받아야 해요"
        }

        if (profile.wageType == WageType.HOURLY && rules.weeklyHolidayPay) {
            weeklyHolidayLine(allMinutes, attendedByDate, weekFrom, periodStart, periodEnd, hourly, baseEstimated)?.let { lines += it }
        }

        if (hourly in 1 until rates.minimumHourlyWage) {
            warnings += "통상시급 ${won(hourly)}이 ${month.year}년 최저임금 ${won(rates.minimumHourlyWage)}보다 낮아요. 입력값을 확인해 주세요"
        }
        if (premiums) {
            listOf(
                Triple("연장 가산", rules.overtimePremium, rates.overtimePremium),
                Triple("야간 가산", rules.nightPremium, rates.nightPremium),
                Triple("휴일 가산(8시간 이내)", rules.holidayPremiumWithin8h, rates.holidayPremiumWithin8h),
                Triple("휴일 가산(8시간 초과)", rules.holidayPremiumOver8h, rates.holidayPremiumOver8h),
            ).forEach { (name, company, law) ->
                if (company != null && company < law) warnings += "회사 $name ${pct(company)}가 법정 ${pct(law)}보다 낮아요"
            }
        } else {
            warnings += "5인 미만 사업장이라 회사 규칙에 넣지 않은 연장·야간·휴일 가산은 계산하지 않았어요"
        }
        val r = rules.overtimeRounding
        if (r.unit > 1 && r.rounding == Rounding.DOWN) {
            warnings += "연장 시간을 ${r.unit}분 단위로 버리고 있어요. 일한 시간보다 적게 계산되면 임금 체불이 될 수 있어요"
        }

        // [PAYX-12] A company window that leaves out part of 22~06 can pay less than the law.
        if (premiums && rules.hasNightWindow) {
            val legalPay = calculate(profile.copy(rules = rules.copy(nightStart = null, nightEnd = null)), month, resolve, isHoliday)
            val legalNight = legalPay.lines.filter { it.kind == LineKind.NIGHT }.sumOf { it.amount }
            val mine = lines.filter { it.kind == LineKind.NIGHT }.sumOf { it.amount }
            if (legalNight > mine) warnings += "회사 야간 시간 ${rules.nightLabel(rates)}로 계산한 야간 가산 ${won(mine)}이 법정 22~06시 기준 " +
                "${won(legalNight)}보다 적어요. 22~06시 근무에는 법정 야간 가산을 받아야 해요(근로기준법 제56조 제3항)"
        }

        // [PAYX-07] Scheduled hours over 8 a day are 연장 by law unless 탄력적 근로시간제 (제51조) is in
        // place, so the difference to the legal count is shown rather than silently dropped.
        if (profile.scheduleIsRegular && profile.flexPeriod == null && premiums) {
            val legalPay = calculate(profile.copy(scheduleIsRegular = false, dailyLimitMinutes = 8 * 60, weeklyLimitMinutes = 40 * 60),
                month, resolve, isHoliday)
            val gap = legalPay.total - lines.sumOf { it.amount }
            val extra = legalPay.minutes.sumOf { it.overtime } - minutes.sumOf { it.overtime }
            if (gap > 0 && extra > 0) warnings += "근무표 시간을 모두 기본 근무로 계산했어요. 탄력적 근로시간제가 아니라면 법으로는 " +
                "하루 8시간·주 40시간을 넘는 ${hours(extra)}이 연장근로라 약 ${won(gap)}을 더 받아야 해요. 취업규칙을 확인해 보세요"
        }

        return MonthPay(month, lines, hourly, rates.version, minutes, warnings, dayPays,
            periodStart.takeUnless { profile.period.isCalendarMonth }, periodEnd.takeUnless { profile.period.isCalendarMonth },
            profile.period.payDate(month)).withHourlyNote(hourlyFormula)
    }

    /** What a company rule pays for one piece of work, how, and what the law would pay for it. */
    private class RuleResult(val amount: Long, val formula: String, val credited: Int, val legal: Long)

    /**
     * [EXTRA-03] One piece of work paid by [rule]: the base part, the hours a per-time payment does not
     * cover at 100%, the legal 연장·휴일 premiums when the company adds them, and the amount on top.
     * Night stays with the night line unless the rule leaves it out.
     */
    private fun applyRule(rule: ExtraRule, w: WorkMinutes, hourly: Long, monthlyBase: Long, baseLabel: String, pr: Premiums, law: Premiums): RuleResult {
        fun money(min: Int, rate: Double) = (min / 60.0 * hourly * rate).roundToLong()
        val worked = w.worked
        // What the law pays these hours, to compare: 100% plus premiums, night too when the rule drops it.
        val legal = money(w.regular, 1.0) + money(w.overtime, 1.0 + law.overtime) +
            money(w.holidayWithin8h, law.holidayBase + law.holidayWithin8h) + money(w.holidayOver8h, law.holidayBase + law.holidayOver8h) +
            money(w.restWithin8h, 1.0 + law.holidayWithin8h) + money(w.restOver8h, 1.0 + law.holidayOver8h) +
            (if (rule.nightPremium) 0 else money(w.night, law.night))
        if (rule.basis == ExtraBasis.LEGAL) {
            // By law with an amount on top: the hours stay in the legal lines.
            return RuleResult(rule.bonus, "법대로 계산하고 1회 ${won(rule.bonus)} 더함", 0, 0)
        }
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
            ExtraBasis.LEGAL -> 0L
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

    companion object {
        fun hours(minutes: Int): String =
            if (minutes % 60 == 0) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"

        /** 대법원 2024.12.19. 2020다247190·2023다302838 (전원합의체): 정기상여금 is 통상임금 from this date. */
        val BONUS_ORDINARY_FROM: LocalDate = LocalDate.of(2024, 12, 19)

        fun pct(m: Double): String = "${(m * 100).roundToLong()}%"
    }
}
