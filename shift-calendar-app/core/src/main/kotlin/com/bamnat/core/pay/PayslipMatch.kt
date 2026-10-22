package com.bamnat.core.pay

import com.bamnat.core.model.WorkTime
import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToLong

/** [PAYS-02] A month checked against its payslip, kept on the phone only as [PayProfile.verified]. */
data class PayslipCheck(
    val month: YearMonth,
    /** 지급 총액 on the payslip. */
    val gross: Long,
    /** What the app came to for the same month with the settings kept. */
    val appTotal: Long,
    val atMillis: Long,
)

/**
 * [PAYS-02] What a payslip says about one month; only [gross] is needed. Payslips list the hours behind
 * 연장·야간·휴일 pay (근로기준법 제48조 제2항). The figures never leave the phone.
 */
data class Payslip(
    /** 지급 총액: everything paid before deductions. */
    val gross: Long,
    /** 기본급; for hourly and daily pay, the amount for the 소정 hours. */
    val base: Long? = null,
    val overtimePay: Long? = null,
    val overtimeMinutes: Int? = null,
    val nightPay: Long? = null,
    val nightMinutes: Int? = null,
    val holidayPay: Long? = null,
    val holidayMinutes: Int? = null,
    /** Other allowances by the name on the payslip, matched to the profile's allowances by name. */
    val allowances: Map<String, Long> = emptyMap(),
)

/** [PAYS-02] One setting the match changed: [key] (an [Unknowns] key where there is one), [label], [from] and [to] in words. */
data class MatchChange(val key: String, val label: String, val from: String, val to: String)

/**
 * [PAYS-02] Something only the user can confirm that would explain most of the gap: a legal fact (5인 이상,
 * 공휴일 휴일 대체, 감시·단속 승인, 탄력적 근로시간제) or an allowance the profile does not have. It is never
 * applied; [total] is the month if it were so.
 */
data class MatchHint(val key: String, val text: String, val total: Long)

/** [PAYS-02] The settings that come closest to a payslip and how close they come. */
data class PayslipMatch(
    val month: YearMonth,
    /**
     * The closest settings, with the payslip's amounts in; keys the payslip settled leave the 확인 필요 set
     * (an amount it states always, a calculation choice it confirms when [matched]).
     */
    val profile: PayProfile,
    /** The month with [profile]. */
    val total: Long,
    val gross: Long,
    /** What [profile] changed against the profile matched, amounts first. */
    val changes: List<MatchChange>,
    val hints: List<MatchHint>,
    /** How much less than the law [profile] pays this month, against the legal way for the choices it made; null when not less. */
    val belowLaw: Long?,
    /** 통상시급 from the payslip's 연장 or 야간 pay and hours; null when it gave neither with hours. */
    val payslipHourly: Long?,
    /** Payslip allowances the profile has no allowance for, by name; part of the gap until added. */
    val unmatched: Map<String, Long>,
    /** How many times the month was calculated. */
    val runs: Int,
) {
    /** What the payslip pays beyond [total]; negative when the app comes to more. */
    val gap: Long get() = gross - total
    /** [gap] as a share of [gross], in percent. */
    val gapPercent: Double get() = if (gross == 0L) 0.0 else gap * 100.0 / gross
    /** Within 0.5% or 1,000원 of the payslip, whichever is more. */
    val matched: Boolean get() = abs(gap) <= tolerance(gross)

    /** [profile] with this check stored, for the app to save once the user applies the match. */
    fun verifiedProfile(atMillis: Long): PayProfile = profile.copy(verified = PayslipCheck(month, gross, total, atMillis))

    companion object {
        /** The least gap that counts as matched, in won. */
        const val TOLERANCE_WON = 1_000L
        /** The gap that counts as matched, as a share of the payslip's 지급 총액. */
        const val TOLERANCE_SHARE = 0.005

        /** How far from [gross] a month may be and still match the payslip. */
        fun tolerance(gross: Long): Long = maxOf(TOLERANCE_WON, (abs(gross) * TOLERANCE_SHARE).roundToLong())
    }
}

/**
 * [PAYS-02] Finds the settings that reproduce a payslip. Amounts the payslip states (기본급, allowances by
 * name) go in first; the 통상시급 behind its 연장 or 야간 pay gives the 월 소정근로시간 when that lands within 1%
 * of a usual value. Then a greedy search over calculation choices only: 휴일 급여 방식, 근무표 시간 as 기본
 * 근무, 쉬는 날 근무, 월 소정근로시간, each allowance's 통상임금, the night window and the rounding of 연장.
 * Legal facts are never changed: when flipping one alone would close most of the gap, it becomes a hint
 * to check with the company. About 40 runs, none with the checks that run the month again.
 */
object PayslipMatcher {
    /** 월 소정근로시간 the derived value snaps to (with the roster's), within 1%. */
    val SNAP_HOURS: List<Int> = listOf(180, 209, 226, 243)
    private const val SNAP_SHARE = 0.01
    private const val MAX_RUNS = 80
    private const val MAX_PASSES = 3

    /** A calculation choice: its [Unknowns] key when it has one, and every value it can take. */
    private class Choice(val key: String?, val options: List<(PayProfile) -> PayProfile>)

    /**
     * The settings closest to [payslip] for [month], starting from [profile] (the user's setup) and the roster
     * in [resolve]. [isHoliday] and [calculator] go to every run, as for [PayCalculator.calculate].
     */
    fun match(
        profile: PayProfile,
        month: YearMonth,
        resolve: (LocalDate) -> ResolvedDay,
        payslip: Payslip,
        isHoliday: ((LocalDate) -> Boolean)? = null,
        calculator: PayCalculator = PayCalculator(),
        /** The roster's 월 소정근로시간; worked out from [resolve] when null. */
        rosterHours: Int? = null,
    ): PayslipMatch {
        var runs = 0
        fun run(p: PayProfile): MonthPay = calculator.calculate(p, month, resolve, checks = false, isHoliday = isHoliday).also { runs++ }
        val from = profile.period.start(month)
        val monthly = profile.wageType == WageType.MONTHLY
        val settled = mutableSetOf<String>()
        var p = profile

        // 1. Amounts the payslip states.
        if (payslip.base != null && monthly) {
            if (p.base.on(month).value != payslip.base) p = p.copy(base = p.base.with(DatedValue(from, payslip.base)))
            settled += Unknowns.BASE
        }
        val first = run(p)
        val unmatched = linkedMapOf<String, Long>()
        for ((name, amount) in payslip.allowances) {
            val item = p.items.firstOrNull { norm(it.name) == norm(name) }
            // [PAYS-07] An item the user made for the company's own way is in the total already, as it is.
            if (item == null && p.customRules.any { it.on && norm(it.name) == norm(name) }) continue
            if (item == null) {
                unmatched[name] = amount
                continue
            }
            if (item.cadence == Cadence.BONUS && item.percentOfBase != null) continue // follows the 기본급
            val times = when (item.cadence) {
                Cadence.MONTHLY -> 1
                Cadence.BONUS -> item.timesIn(month)
                else -> first.itemTimes[item.id] ?: 0
            }
            if (times <= 0) {
                unmatched[name] = amount
                continue
            }
            val each = (amount.toDouble() / times).roundToLong()
            if (item.amount.on(month).value != each) p = p.withItem(item.id) { it.copy(amount = it.amount.with(DatedValue(from, each))) }
            settled += Unknowns.item(item.id)
            settled += item.id
        }

        // 2. The 통상시급 behind the payslip's premiums, and the 월 소정근로시간 it gives.
        val premiumsOn = p.fiveOrMoreEmployees && !p.rules.supervisoryApproved
        val otRate = 1.0 + (p.rules.overtimePremium ?: if (premiumsOn) 0.5 else 0.0)
        val nightRate = p.rules.nightPremium ?: if (p.fiveOrMoreEmployees) 0.5 else 0.0
        val hourly = perHour(payslip.overtimePay, payslip.overtimeMinutes, otRate) ?: perHour(payslip.nightPay, payslip.nightMinutes, nightRate)
        val roster = rosterHours ?: PayRange.rosterMonthlyHours(p, month, resolve)
        val hourChoices = (SNAP_HOURS + roster + p.monthlyHours).distinct()
        if (monthly && hourly != null) {
            val raw = PayCalculator.ordinaryWage(p, month).total.toDouble() / hourly
            hourChoices.filter { abs(raw - it) <= it * SNAP_SHARE }.minByOrNull { abs(raw - it) }?.let { p = p.copy(monthlyHours = it) }
        }

        // 3. A greedy search over the calculation choices, scored on the gross and the lines the payslip gives.
        val target = payslip.gross - unmatched.values.sum()
        val tolerance = PayslipMatch.tolerance(payslip.gross)
        fun gaps(pay: MonthPay): List<Long> = listOfNotNull(
            target - pay.total,
            payslip.overtimePay?.let { it - pay.sum(LineKind.OVERTIME) },
            payslip.nightPay?.let { it - pay.sum(LineKind.NIGHT) },
            payslip.holidayPay?.let { it - pay.sum(LineKind.HOLIDAY) },
            payslip.base?.takeIf { !monthly }?.let { it - pay.sum(LineKind.BASE) },
        )
        fun score(pay: MonthPay): Long = gaps(pay).sumOf { abs(it) }
        fun good(pay: MonthPay): Boolean = gaps(pay).all { abs(it) <= tolerance }

        val days = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(p.period.end(month)) }.map(resolve).toList()
        val worksRestDays = days.any { it.isRestDay && it.extras.isNotEmpty() }
        // 감시·단속 is suggested only where setup asks about it: a shift of 20 hours or more, or 경비·시설 work.
        val guardLike = profile.occupation == Occupation.SECURITY || profile.rules.supervisoryApproved ||
            days.any { it.isWorkDay && it.start != null && it.end != null && WorkTime.spanMinutes(it.start, it.end) >= 20 * 60 }
        val choices = buildList {
            if (monthly && first.minutes.any { it.holidayWithin8h + it.holidayOver8h > 0 }) {
                add(Choice(Unknowns.HOLIDAY_PAY_MODE, HolidayPayMode.entries.map { m -> { q: PayProfile -> q.copy(holidayPayMode = m) } }))
            }
            if (p.flexPeriod == null) add(Choice(Unknowns.SCHEDULE_IS_REGULAR, listOf(false, true).map { v -> { q: PayProfile -> q.copy(scheduleIsRegular = v) } }))
            if (worksRestDays) add(Choice(Unknowns.REST_DAY_WORK, RestDayWork.entries.map { m -> { q: PayProfile -> q.withRules { it.copy(restDayWork = m) } } }))
            if (monthly) {
                add(Choice(Unknowns.MONTHLY_HOURS, hourChoices.map { h -> { q: PayProfile -> q.copy(monthlyHours = h) } }))
                p.items.filter { it.cadence == Cadence.BONUS || it.cadence == Cadence.MONTHLY && !it.isFixedOvertime && it.coveredKinds.isEmpty() }.forEach { item ->
                    add(Choice(null, listOf(true, false).map { v -> { q: PayProfile -> q.withItem(item.id) { it.copy(ordinary = v) } } }))
                }
            }
            add(Choice(null, listOf<Pair<LocalTime?, LocalTime?>>(null to null, LATE_NIGHT).map { (s, e) ->
                { q: PayProfile -> q.withRules { it.copy(nightStart = s, nightEnd = e) } }
            }))
            if (first.minutes.any { it.overtime > 0 }) {
                add(Choice(null, listOf(1, 10, 15, 30).map { u -> { q: PayProfile -> q.withRules { it.copy(overtimeRounding = MinuteRounding(u, Rounding.DOWN)) } } }))
            }
        }
        var best = p
        var bestPay = run(best)
        var bestScore = score(bestPay)
        // Choices whose other values move the month past the tolerance: a matched payslip settles them.
        var sensitive = mutableSetOf<String>()
        for (pass in 1..MAX_PASSES) {
            sensitive = mutableSetOf()
            var improved = false
            for (choice in choices) {
                var pick: Pair<PayProfile, MonthPay>? = null
                var pickScore = bestScore
                for (option in choice.options) {
                    if (runs >= MAX_RUNS) break
                    val candidate = option(best)
                    if (candidate == best) continue
                    val pay = run(candidate)
                    if (choice.key != null && abs(pay.total - bestPay.total) > tolerance) sensitive += choice.key
                    val s = score(pay)
                    if (s < pickScore) {
                        pick = candidate to pay
                        pickScore = s
                    }
                }
                // Settings that already match the payslip stay as the user has them.
                if (pick != null && !good(bestPay)) {
                    best = pick.first
                    bestPay = pick.second
                    bestScore = pickScore
                    improved = true
                }
            }
            if (!improved || good(bestPay) || runs >= MAX_RUNS) break
        }

        // 4. Legal facts are the user's to confirm: a hint when one alone would close most of the gap. Each is
        // tried on the settings before the search too, since the search may have bent a choice towards it.
        val hints = mutableListOf<MatchHint>()
        val left = abs(target - bestPay.total)
        if (left > tolerance) {
            // The search changes no legal fact, so both lists name the same flips in the same order.
            val before = if (p == best) emptyList() else legalFlips(p, guardLike)
            for ((i, flip) in legalFlips(best, guardLike).withIndex()) {
                val (key, text, other) = flip
                val start = before.getOrNull(i)?.takeIf { it.first == key && it.second == text }?.third
                val total = listOfNotNull(other, start).distinct().map { run(it).total }.minBy { abs(target - it) }
                // "맞아요" only when the flip lands on the payslip; one that only comes most of the way is "더 가까워요".
                val lands = if (abs(target - total) <= tolerance) "명세서와 맞아요" else "명세서에 더 가까워요"
                if (abs(target - total) * 2 <= left) hints += MatchHint(key, "$text $lands. 회사에 확인해 보세요", total)
            }
            hints.sortBy { abs(target - it.total) }
        }
        unmatched.forEach { (name, amount) ->
            hints += MatchHint("unmatched:$name", "명세서의 ‘$name’ ${won(amount)}은 앱에 없는 수당이에요. 수당으로 넣어 주세요", bestPay.total + amount)
        }

        // 5. Less than the law: the same month with the legal way for each choice that can pay below it.
        val lawful = best.copy(scheduleIsRegular = best.scheduleIsRegular && best.flexPeriod != null, holidayPayMode = HolidayPayMode.FULL)
            .withRules { it.copy(overtimeRounding = MinuteRounding()) }
        val lawTotal = listOf(lawful, lawful.withRules { it.copy(nightStart = null, nightEnd = null) }).distinct()
            .maxOf { if (it == best) bestPay.total else run(it).total }
        val belowLaw = (lawTotal - bestPay.total).takeIf { it > 0 }

        val matched = abs(payslip.gross - bestPay.total) <= tolerance
        val keep = profile.estimatedFields - settled - (if (matched) sensitive else emptySet())
        val result = best.copy(estimatedFields = keep)
        return PayslipMatch(month, result, bestPay.total, payslip.gross, changes(profile, result, month), hints, belowLaw,
            hourly, unmatched, runs)
    }

    /** The company night window the search tries besides the law's 22~06. */
    private val LATE_NIGHT: Pair<LocalTime?, LocalTime?> = LocalTime.of(23, 0) to LocalTime.of(7, 0)

    private fun perHour(pay: Long?, minutes: Int?, rate: Double): Long? =
        if (pay == null || minutes == null || minutes <= 0 || rate <= 0) null else (pay / (minutes / 60.0 * rate)).roundToLong()

    /** Each legal fact flipped alone, with the condition to tell the user; 감시·단속 only when [guardLike]. */
    private fun legalFlips(p: PayProfile, guardLike: Boolean): List<Triple<String, String, PayProfile>> {
        val r = p.rules
        return buildList {
            add(Triple(Unknowns.FIVE_OR_MORE,
                "직원이 5명 ${if (p.fiveOrMoreEmployees) "미만" else "이상"}이면", p.copy(fiveOrMoreEmployees = !p.fiveOrMoreEmployees)))
            if (p.fiveOrMoreEmployees) add(Triple(Unknowns.PUBLIC_HOLIDAYS,
                if (r.publicHolidaysPaid) "공휴일을 다른 날과 바꿔 쉬는 회사(휴일 대체)라면" else "공휴일이 유급휴일이라면",
                p.withRules { it.copy(publicHolidaysPaid = !r.publicHolidaysPaid) }))
            else add(Triple(Unknowns.PUBLIC_HOLIDAYS,
                "회사가 공휴일을 유급휴일로 ${if (r.holidaysUnderFive) "주지 않는다면" else "준다면"}",
                p.withRules { it.copy(holidaysUnderFive = !r.holidaysUnderFive) }))
            if (guardLike) add(Triple(Unknowns.SUPERVISORY,
                if (r.supervisoryApproved) "감시·단속 승인이 없다면" else "감시·단속 승인을 받은 회사라면",
                p.withRules { it.copy(supervisoryApproved = !r.supervisoryApproved) }))
            // [PAYX-17] 2주 needs the company's start date, so it is not guessed from a payslip.
            if (p.flexPeriod == null) FlexPeriod.entries.filter { it != FlexPeriod.TWO_WEEKS }.forEach { f ->
                add(Triple("flexPeriod", "탄력적 근로시간제(${f.unit} 단위)를 쓰는 회사라면",
                    p.copy(scheduleIsRegular = true, flexPeriod = f)))
            } else add(Triple("flexPeriod", "탄력적 근로시간제가 아니라면", p.copy(scheduleIsRegular = false, flexPeriod = null)))
        }
    }

    /** What [after] changed against [before], in words. */
    private fun changes(before: PayProfile, after: PayProfile, month: YearMonth): List<MatchChange> = buildList {
        val b = before.base.on(month).value
        val a = after.base.on(month).value
        if (a != b) add(MatchChange(Unknowns.BASE, "기본급", won(b), won(a)))
        for (item in after.items) {
            val old = before.items.firstOrNull { it.id == item.id } ?: continue
            val was = old.amount.on(month).value
            val now = item.amount.on(month).value
            val wasText = if (was == 0L && Unknowns.item(item.id) in before.estimatedFields) "금액 모름" else amountText(old, was)
            if (was != now) add(MatchChange(Unknowns.item(item.id), item.name, wasText, amountText(item, now)))
        }
        if (before.monthlyHours != after.monthlyHours) {
            add(MatchChange(Unknowns.MONTHLY_HOURS, "월 소정근로시간", "${before.monthlyHours}시간", "${after.monthlyHours}시간"))
        }
        if (before.holidayPayMode != after.holidayPayMode) {
            add(MatchChange(Unknowns.HOLIDAY_PAY_MODE, "휴일 근무 급여 방식", before.holidayPayMode.label, after.holidayPayMode.label))
        }
        if (before.scheduleIsRegular != after.scheduleIsRegular) {
            fun text(regular: Boolean) = if (regular) "근무표 시간은 모두 기본 근무" else "8시간 넘으면 연장"
            add(MatchChange(Unknowns.SCHEDULE_IS_REGULAR, "8시간 넘는 근무", text(before.scheduleIsRegular), text(after.scheduleIsRegular)))
        }
        if (before.rules.restDayWork != after.rules.restDayWork) {
            add(MatchChange(Unknowns.REST_DAY_WORK, "쉬는 날 근무", before.rules.restDayWork.label, after.rules.restDayWork.label))
        }
        for (item in after.items) {
            val old = before.items.firstOrNull { it.id == item.id } ?: continue
            fun text(ordinary: Boolean) = if (ordinary) "포함" else "제외"
            if (old.ordinary != item.ordinary) add(MatchChange("ordinary:${item.id}", "‘${item.name}’ 통상임금", text(old.ordinary), text(item.ordinary)))
        }
        if (nightText(before.rules) != nightText(after.rules)) add(MatchChange("nightWindow", "야간 시간", nightText(before.rules), nightText(after.rules)))
        if (before.rules.overtimeRounding != after.rules.overtimeRounding) {
            add(MatchChange("overtimeRounding", "연장 시간 끝처리", roundText(before.rules.overtimeRounding), roundText(after.rules.overtimeRounding)))
        }
    }

    private fun amountText(item: PayItem, v: Long): String = if (item.cadence == Cadence.MONTHLY) won(v) else "1회 ${won(v)}"

    private fun nightText(r: CompanyRules): String =
        if (r.hasNightWindow) "${r.nightStart!!.hour.pad()}~${r.nightEnd!!.hour.pad()}시" else "22~06시"

    private fun Int.pad(): String = "%02d".format(this)

    private fun roundText(r: MinuteRounding): String = if (r.unit <= 1) "1분 단위" else "${r.unit}분 단위 " + when (r.rounding) {
        Rounding.DOWN -> "버림"
        Rounding.UP -> "올림"
        Rounding.NEAREST -> "반올림"
    }

    private fun norm(name: String): String = name.filterNot { it.isWhitespace() }.lowercase()

    private fun MonthPay.sum(kind: LineKind): Long = lines.filter { it.kind == kind }.sumOf { it.amount }
}
