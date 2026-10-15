package com.bamnat.core.pay

import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.model.ExtraWork
import com.bamnat.core.schedule.ResolvedDay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/** What a line is, so screens can group and colour it. */
enum class LineKind { BASE, OVERTIME, NIGHT, HOLIDAY, EXTRA, ALLOWANCE, LEAVE }

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
    /** The shift name on the calendar, or 휴무. */
    val label: String,
    val holidayName: String?,
    val extraWork: ExtraWork?,
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
) {
    val total: Long get() = lines.sumOf { it.amount }
    val estimated: Boolean get() = lines.any { it.estimated } || minutes.any { it.estimated }
    fun day(date: LocalDate): DayPay? = days.firstOrNull { it.date == date }
}

/**
 * Turns a month of calendar days into pay [PAY-01, PAY-04..06, PAYX-07, PAYX-08]. Pure: everything
 * comes in through the arguments, so the same inputs always give the same result (see [CalcSnapshot]).
 * The calendar is the input: each shift, 잔업·특근·대근 mark and holiday on it becomes minutes, then
 * money by the law and the company's [CompanyRules].
 */
class PayCalculator(
    private val rateTable: List<Rates> = RateTable.builtIn,
) {
    private class ExtraTotal(var amount: Long = 0, var minutes: Int = 0, var times: Int = 0, var legal: Long = 0)

    fun calculate(
        profile: PayProfile,
        month: YearMonth,
        resolve: (LocalDate) -> ResolvedDay,
        /** Paid holidays; null uses the built-in 공휴일 when the company counts them. */
        isHoliday: ((LocalDate) -> Boolean)? = null,
    ): MonthPay {
        val rules = profile.rules
        val rates = RateTable.on(month.atDay(1), rateTable)
        val premiums = profile.fiveOrMoreEmployees
        // 공휴일 are paid holidays only at 5인 이상 (근로기준법 제55조 제2항, 시행령 별표1); below that
        // they are ordinary days unless the caller says otherwise.
        val holiday = isHoliday ?: { d: LocalDate -> premiums && rules.publicHolidaysPaid && KoreanHolidays.isHoliday(d) }
        val classifier = MinuteClassifier(
            rates = { RateTable.on(it, rateTable) },
            options = ClassifyOptions(profile.dailyLimitMinutes, profile.weeklyLimitMinutes, profile.weekStart,
                offDayWorkIsHoliday = rules.offDayWork == OffDayWork.HOLIDAY),
        )
        // Start at the week containing the 1st so weekly overtime counts the days before it.
        val from = month.atDay(1).with(TemporalAdjusters.previousOrSame(profile.weekStart))
        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(month.atEndOfMonth()) }
            .map(resolve).toList()
        val allMinutes = classifier.classify(days, holiday).map { m ->
            val rounded = rules.overtimeRounding.apply(m.overtime)
            if (rounded == m.overtime) m else m.copy(overtime = rounded)
        }
        val minutes = allMinutes.filter { YearMonth.from(it.date) == month }
        val byDate = minutes.associateBy { it.date }
        val monthDays = days.filter { YearMonth.from(it.date) == month }

        val base = profile.base.on(month)
        val ordinaryMonthly = profile.items.filter { it.ordinary && it.cadence == Cadence.MONTHLY && !it.isFixedOvertime }
            .sumOf { it.amount.on(month).value }
        // [PAY-05] Regular bonuses count toward 통상임금 at a year's payments ÷ 12 (시행령 제6조 제2항
        // 제5호), from 2024-12-19 on: the 전원합의체 ruling of that day applies to later pay only.
        val bonusMonthly = if (month.atEndOfMonth().isBefore(BONUS_ORDINARY_FROM)) 0L
        else profile.items.filter { it.ordinary && it.cadence == Cadence.BONUS }
            .sumOf { it.bonusMonthlyShare(base.value, month) }
        val hourly = when (profile.wageType) {
            WageType.MONTHLY -> ((base.value + ordinaryMonthly + bonusMonthly).toDouble() / profile.monthlyHours).roundToLong()
            WageType.HOURLY -> base.value
        }
        val hourlyFormula = when (profile.wageType) {
            WageType.MONTHLY -> "(기본급 ${won(base.value)}" +
                (if (ordinaryMonthly > 0) " + 통상 수당 ${won(ordinaryMonthly)}" else "") +
                (if (bonusMonthly > 0) " + 상여금 월할 ${won(bonusMonthly)}" else "") +
                ") ÷ ${profile.monthlyHours}시간"
            WageType.HOURLY -> "시급"
        }

        // Premiums on top of 100%: the company's when it set one, else the law's (none under 5 employees).
        fun legal(p: Double) = if (premiums) p else 0.0
        val otP = rules.overtimePremium ?: legal(rates.overtimePremium)
        val nightP = rules.nightPremium ?: legal(rates.nightPremium)
        val hwP = rules.holidayPremiumWithin8h ?: legal(rates.holidayPremiumWithin8h)
        val hoP = rules.holidayPremiumOver8h ?: legal(rates.holidayPremiumOver8h)
        val holidayBase = if (profile.wageType == WageType.HOURLY) 1.0 else profile.holidayPayMode.baseMultiplier
        val mOt = 1.0 + otP
        val mHw = holidayBase + hwP
        val mHo = holidayBase + hoP
        fun money(min: Int, rate: Double) = (min / 60.0 * hourly * rate).roundToLong()

        val lines = mutableListOf<PayLine>()
        val warnings = mutableListOf<String>()
        val baseEstimated = "base" in profile.estimatedFields || base.source == ValueSource.RAISED
        val extraTotals = linkedMapOf<ExtraWork, ExtraTotal>()
        var regular = 0; var overtime = 0; var night = 0; var hWithin = 0; var hOver = 0
        val itemCounts = HashMap<String, Int>()
        val dayPays = mutableListOf<DayPay>()
        // Leave [FEAT-10]: minutes by kind, and paid leave per date for 주휴 weeks.
        var paidLeave = 0; var unpaidLeave = 0
        val paidLeaveByDate = days.filter { it.leave?.kind?.paid == true }.associate { it.date to it.leaveMinutes }
        // [PAY-13] A paid holiday on a scheduled working day: hourly and daily-paid workers get one
        // day's ordinary wage for it, worked or not, and holiday pay on top when they work it
        // (고용노동부 임금근로시간과-653, 2021.3.22.). A 비번 day that falls on a holiday earns nothing.
        // Monthly pay already includes it. Sundays are left out: the 주휴일 covers them.
        fun paidHolidayMinutes(day: ResolvedDay): Int =
            if (profile.wageType != WageType.HOURLY || !holiday(day.date) || day.date.dayOfWeek == DayOfWeek.SUNDAY) 0
            else (day.scheduledMinutes + day.leaveMinutes).coerceAtMost(rates.dailyLimitMinutes)
        var paidHoliday = 0
        // The week's 소정근로 for 주휴 counts paid leave and paid holidays as attended.
        val attendedByDate = days.associate { it.date to (paidLeaveByDate[it.date] ?: 0) + paidHolidayMinutes(it) }
            .filterValues { it > 0 }

        for (day in monthDays) {
            val m = byDate[day.date] ?: DayMinutes(day.date)
            var ot = m.overtime; var hw = m.holidayWithin8h; var ho = m.holidayOver8h; var n = m.night
            val parts = mutableListOf<DayPart>()
            val rule = rules.extraRule(day.extraWork)
            if (rule != null) {
                val kind = day.extraWork!!
                val extraMin = ot + hw + ho
                if (extraMin > 0 || rule.basis == ExtraBasis.FIXED_PER_TIME) {
                    val credited = maxOf(extraMin, rule.minMinutes)
                    val amount = when (rule.basis) {
                        ExtraBasis.HOURLY_RATE -> money(credited, rule.multiplier)
                        ExtraBasis.FIXED_PER_TIME -> rule.amount
                        ExtraBasis.FIXED_PER_HOUR -> (credited / 60.0 * rule.amount).roundToLong()
                        ExtraBasis.LEGAL -> 0
                    }
                    val legalAmount = money(ot, 1.0 + legal(rates.overtimePremium)) +
                        money(hw, holidayBase + legal(rates.holidayPremiumWithin8h)) +
                        money(ho, holidayBase + legal(rates.holidayPremiumOver8h))
                    if (!rule.nightPremium && m.worked > 0) n -= (m.night * extraMin.toDouble() / m.worked).roundToLong().toInt()
                    ot = 0; hw = 0; ho = 0
                    extraTotals.getOrPut(kind) { ExtraTotal() }.apply {
                        this.amount += amount; this.minutes += credited; times++; this.legal += legalAmount
                    }
                    parts += DayPart(LineKind.EXTRA, kind.label, amount, "${rule.describe()}" +
                        if (credited > 0) " · ${hours(credited)}" else "")
                }
            }
            regular += m.regular; overtime += ot; night += n; hWithin += hw; hOver += ho
            if (profile.wageType == WageType.HOURLY && m.regular > 0) {
                parts.add(0, DayPart(LineKind.BASE, "기본", money(m.regular, 1.0), "${hours(m.regular)} × ${won(hourly)}"))
            }
            if (ot > 0) parts += DayPart(LineKind.OVERTIME, "연장", money(ot, mOt), "${hours(ot)} × ${pct(mOt)}")
            if (n > 0 && nightP > 0) parts += DayPart(LineKind.NIGHT, "야간 가산", money(n, nightP), "${hours(n)} × ${pct(nightP)}")
            if (hw > 0) parts += DayPart(LineKind.HOLIDAY, "휴일", money(hw, mHw), "${hours(hw)} × ${pct(mHw)}")
            if (ho > 0) parts += DayPart(LineKind.HOLIDAY, "휴일 8시간 초과", money(ho, mHo), "${hours(ho)} × ${pct(mHo)}")

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
            for (item in profile.items) {
                val counts = when (item.cadence) {
                    Cadence.MONTHLY, Cadence.BONUS -> false
                    Cadence.PER_WORK_DAY -> day.isWorkDay || day.holidayWorkMinutes > 0
                    Cadence.PER_NIGHT_SHIFT -> day.isWorkDay && isNightShift(day.date, day.start, day.end, rates)
                    Cadence.PER_SHIFT_TYPE -> day.isWorkDay && day.shiftType?.id in item.shiftTypeIds
                }
                // [FEAT-02] A second shift earns per-shift allowances again (a 나이트 대근 is a night shift).
                val second = day.second
                val secondCounts = second != null && when (item.cadence) {
                    Cadence.PER_NIGHT_SHIFT -> isNightShift(day.date, second.start, second.end, rates)
                    Cadence.PER_SHIFT_TYPE -> second.type.id in item.shiftTypeIds
                    else -> false
                }
                val times = (if (counts) 1 else 0) + (if (secondCounts) 1 else 0)
                if (times == 0) continue
                itemCounts[item.id] = (itemCounts[item.id] ?: 0) + times
                val v = item.amount.on(day.date).value
                if (v > 0) parts += DayPart(LineKind.ALLOWANCE, item.name, v * times, if (times > 1) "${item.cadence.label} × $times" else item.cadence.label)
            }
            dayPays += DayPay(
                date = day.date,
                label = day.leave?.takeIf { it.isFullDay }?.let { "${day.shiftType?.name ?: "근무"} · ${it.label}" }
                    ?: (day.shiftType?.takeIf { day.isWorkDay }?.name?.let { n -> day.second?.let { "$n + ${it.type.name}" } ?: n })
                    ?: day.second?.let { "쉬는 날 ${it.type.name}" }
                    ?: if (day.holidayWorkMinutes > 0) "쉬는 날 근무" else "휴무",
                holidayName = KoreanHolidays.nameOf(day.date),
                extraWork = day.extraWork,
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
        if (overtime > 0) lines += line(
            LineKind.OVERTIME, "연장근로", overtime, hourly, mOt,
            "하루 8시간·주 40시간 초과와 달력에 넣은 연장" + companyNote(rules.overtimePremium != null) +
                if (rules.offDayWork == OffDayWork.OVERTIME) ", 쉬는 날 근무 포함" else "",
            "근로기준법 제56조 제1항", baseEstimated,
        )
        if (night > 0 && nightP > 0) lines += line(
            LineKind.NIGHT, "야간근로 가산", night, hourly, nightP,
            "22시~06시 근무에 더해 주는 가산분" + companyNote(rules.nightPremium != null), "근로기준법 제56조 제3항", baseEstimated,
        )
        if (hWithin > 0) lines += line(
            LineKind.HOLIDAY, "휴일근로 (8시간 이내)", hWithin, hourly, mHw,
            "공휴일·쉬는 날 근무 8시간까지" + (if (profile.wageType == WageType.MONTHLY) " (${profile.holidayPayMode.label})" else "") +
                companyNote(rules.holidayPremiumWithin8h != null),
            "근로기준법 제56조 제2항 제1호", baseEstimated || "holidayPayMode" in profile.estimatedFields,
        )
        if (hOver > 0) lines += line(
            LineKind.HOLIDAY, "휴일근로 (8시간 초과)", hOver, hourly, mHo,
            "휴일 근무 8시간 넘는 부분" + companyNote(rules.holidayPremiumOver8h != null), "근로기준법 제56조 제2항 제2호", baseEstimated,
        )
        for ((kind, t) in extraTotals) {
            val rule = rules.extraRule(kind)!!
            lines += PayLine(
                LineKind.EXTRA, "${kind.label} (회사 규칙)", t.amount,
                formula = "${rule.describe()}: ${t.times}회" + (if (t.minutes > 0) ", ${hours(t.minutes)}" else ""),
                minutes = t.minutes,
                hourly = if (rule.basis == ExtraBasis.HOURLY_RATE) hourly else 0,
                multiplier = if (rule.basis == ExtraBasis.HOURLY_RATE) rule.multiplier else 0.0,
                basis = "달력에 ‘${kind.label}’로 표시한 날",
                estimated = baseEstimated && rule.basis == ExtraBasis.HOURLY_RATE,
            )
            if (premiums && t.amount < t.legal) {
                warnings += "${kind.label}을 회사 방식으로 계산하면 ${won(t.amount)}인데 법정 기준으로는 ${won(t.legal)}이에요. " +
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
                if (month.monthValue !in item.months) continue
                val paid = item.bonusPayment(base.value, month)
                if (paid > 0) lines += PayLine(
                    LineKind.ALLOWANCE, item.name, paid,
                    formula = item.percentOfBase?.let { "기본급 ${won(base.value)} × ${pct(it)}" } ?: "정액 ${won(paid)}",
                    basis = (if (item.ordinary) "통상임금에 포함 (연 ${item.months.size}회 ÷ 12)" else "통상임금 제외") +
                        " · ${item.months.sorted().joinToString(",")}월 지급",
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
            weeklyHolidayLine(allMinutes, attendedByDate, from, month, hourly, baseEstimated)?.let { lines += it }
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

        return MonthPay(month, lines, hourly, rates.version, minutes, warnings, dayPays).withHourlyNote(hourlyFormula)
    }

    /**
     * 주휴수당 [PAY-12 선행, 근로기준법 제55조·시행령 제30조]: each week ending in the month with at least
     * 15 scheduled hours earns (weekly hours ÷ 40) × 8 hours, at most 8. The calendar is taken as
     * worked in full (개근).
     */
    private fun weeklyHolidayLine(
        all: List<DayMinutes>, attended: Map<LocalDate, Int>, from: LocalDate, month: YearMonth, hourly: Long, estimated: Boolean,
    ): PayLine? {
        var weekStart = from
        var minutes = 0
        val weeks = mutableListOf<Int>()
        while (!weekStart.plusDays(6).isAfter(month.atEndOfMonth())) {
            val end = weekStart.plusDays(6)
            if (YearMonth.from(end) == month) {
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
    private fun isNightShift(date: LocalDate, start: java.time.LocalTime?, end: java.time.LocalTime?, rates: Rates): Boolean {
        val s = start ?: return false
        val e = end ?: return false
        val from = date.atTime(s)
        val to = if (e.isAfter(s)) date.atTime(e) else date.plusDays(1).atTime(e)
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
