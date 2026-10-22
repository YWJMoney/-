package com.bamnat.core.pay

import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.YearMonth

/**
 * [PAYS-06] One pay rule read from a sentence, as a change to the pay setup. Each one says in words what it
 * does ([summary]); nothing is applied until the user agrees, with [RuleText.apply].
 */
sealed interface RuleChange {
    val summary: String

    data class Base(val type: WageType, val amount: Long) : RuleChange {
        override val summary get() = "${type.label()} ${won(amount)}"
    }
    data class Annual(val yearly: Long) : RuleChange {
        override val summary get() = "연봉 ${won(yearly)} (월 기본급 ${won(AnnualPay(yearly).breakdown().monthlyBase)})"
    }
    data class Allowance(val name: String, val amount: Long, val cadence: Cadence, val shiftTypeIds: Set<Long> = emptySet(), val ordinary: Boolean? = null) : RuleChange {
        override val summary get() = "$name ${cadence.label} ${won(amount)}" + when (ordinary) { true -> " · 통상임금"; false -> " · 통상임금 제외"; null -> "" }
    }
    data class Bonus(val name: String, val yearlyPercent: Double?, val amount: Long?, val months: Set<Int>, val festivals: Set<Festival> = emptySet()) : RuleChange {
        override val summary get() = "$name " + (yearlyPercent?.let { "연 ${ExtraRule.percent(it / 100)}" } ?: "1회 ${won(amount ?: 0)}") +
            " · " + (months.sorted().joinToString(",").takeIf { it.isNotEmpty() }?.let { "${it}월" } ?: "") +
            festivals.joinToString("") { (if (months.isEmpty()) "" else "·") + it.label }
    }
    data class Premium(val target: PremiumTarget, val premium: Double, val over8h: Boolean = false) : RuleChange {
        override val summary get() = "${target.label}${if (over8h) " 8시간 초과" else ""} 가산 ${ExtraRule.percent(premium)}"
    }
    data class NightWindow(val start: LocalTime, val end: LocalTime) : RuleChange {
        override val summary get() = "야간 시간 ${RuleWhen.clock(start)}~${RuleWhen.clock(end)}시"
    }
    data class MonthlyHours(val hours: Int) : RuleChange {
        override val summary get() = "월 소정근로시간 ${hours}시간"
    }
    data class FiveOrMore(val value: Boolean) : RuleChange {
        override val summary get() = if (value) "직원 5명 이상" else "직원 5명 미만"
    }
    data class Breaks(val mode: BreakPay) : RuleChange {
        override val summary get() = "휴게 ${mode.label}"
    }
    data class PublicHolidayAs(val kind: Long, val kindName: String) : RuleChange {
        override val summary get() = "공휴일 근무는 $kindName 방식으로"
    }
    data class PublicHolidaysPaid(val value: Boolean) : RuleChange {
        override val summary get() = if (value) "공휴일 근무는 휴일근로" else "공휴일은 다른 날로 바꿔 쉼(휴일 대체)"
    }
    data class RestDay(val mode: RestDayWork) : RuleChange {
        override val summary get() = "쉬는 날 근무는 ${mode.label}"
    }
    data class ScheduleRegular(val value: Boolean) : RuleChange {
        override val summary get() = if (value) "근무표 시간은 모두 기본 근무" else "8시간 넘는 근무표 시간은 연장"
    }
    data class WeeklyHolidayDay(val day: DayOfWeek) : RuleChange {
        override val summary get() = "주휴일 ${RuleWhen.DAY_NAMES.getValue(day)}요일"
    }
    data class Raise(val month: Int, val percent: Double) : RuleChange {
        override val summary get() = "매년 ${month}월 ${"%.1f".format(percent).removeSuffix(".0")}% 인상"
    }
    data class Extra(val kind: Long, val kindName: String, val rule: ExtraRule) : RuleChange {
        override val summary get() = "$kindName: ${rule.describe()}"
    }
    data class Custom(val rule: CustomRule, val shiftNames: Map<Long, String> = emptyMap()) : RuleChange {
        override val summary get() = rule.describe(shiftNames)
    }
}

private fun WageType.label() = when (this) {
    WageType.MONTHLY -> "기본급"
    WageType.HOURLY -> "시급"
    WageType.DAILY -> "일당"
}

/** [PAYS-06] A change read from [sourceLine]; low [confidence] ones start unticked. */
data class RuleFinding(val sourceLine: String, val change: RuleChange, val confidence: Double)

/**
 * [PAYS-06] Reads pay rules written in plain words and turns each into a [RuleChange]: the pay itself, allowances
 * and bonuses, the premiums and night hours, how the company counts days off, 공휴일 and breaks, how each kind of
 * extra work is paid ("대근은 8시간에 기본급의 10%, 8시간 넘으면 50% 가산, 야간 50% 가산"), and any rule of the
 * company's own as a [CustomRule] ("토요일 근무는 시간당 50% 더", "19~23시 근무 25% 가산"). Sentences can be
 * pasted from a 취업규칙, a contract or a message from a colleague. Lines that are payslip amounts
 * (연장근로수당 523,000원) are left to [PayslipReader], so they are never counted twice. Plain pattern
 * matching, no network.
 */
object RuleText {
    private val percentRe = Regex("""(\d+(?:\.\d+)?)\s*(%|퍼센트|프로)""")
    private val timesRe = Regex("""(\d+(?:\.\d+)?)\s*배""")
    private val hoursRe = Regex("""(\d{1,2}(?:\.\d+)?)\s*시간""")
    private val clockRangeRe = Regex(
        """(?:(오전|오후|밤|새벽|저녁)\s*)?(\d{1,2})(?::(\d{2})|\s*시(?:\s*(\d{1,2})\s*분)?)?\s*(?:~|-|–|부터|에서)\s*(?:(오전|오후|밤|새벽|저녁)\s*)?(\d{1,2})(?::(\d{2})|\s*시(?:\s*(\d{1,2})\s*분)?)\s*(?:까지)?""",
    )
    private val monthListRe = Regex("""(\d{1,2})\s*월""")

    fun read(text: String, kinds: List<ExtraKind> = emptyList(), shiftTypes: List<ShiftType> = emptyList(), fiveOrMore: Boolean = true): List<RuleFinding> =
        sentences(text).flatMap { readSentence(it, kinds, shiftTypes, fiveOrMore) }

    /** Lines, and sentences within a line; a dot between digits (3.5%) does not end one. */
    internal fun sentences(text: String): List<String> =
        text.lines().flatMap { it.split(Regex("""(?<!\d)\.(?!\d)|;|。""")) }
            .map { it.trim().trimStart('-', '•', '·', '*', '○', '●', '▶', ' ').trim() }.filter { it.length >= 2 }

    private fun readSentence(line: String, kinds: List<ExtraKind>, shiftTypes: List<ShiftType>, fiveOrMore: Boolean): List<RuleFinding> {
        val c = line.replace(" ", "")
        if (isPayslipAmount(line, c)) return emptyList()
        // [PAYS-07] Money taken off is never a way of paying extra work: "식대 근무일마다 3,000원 공제" holds "대근".
        val takenOff = Regex("차감|공제|감액").containsMatchIn(c)
        if (!takenOff) extraRule(line, c, kinds, fiveOrMore)?.let { return listOf(it) }
        if (!takenOff) publicHoliday(line, c, kinds)?.let { return listOf(it) }
        // Several rules on a line, split at commas: "연장 150%, 야간 50%".
        return line.split(Regex("""(?<!\d)[,，]|[,，](?!\d{3})|그리고""")).map { it.trim() }.filter { it.isNotEmpty() }
            .flatMap { clause -> readClause(line, clause, clause.replace(" ", ""), shiftTypes) }
    }

    /** A payslip line: an amount for a premium or a total with no rule words, e.g. "연장근로수당 523,000원". */
    private fun isPayslipAmount(line: String, c: String): Boolean {
        val role = PayslipReader.roleOf(Regex("""^[가-힣A-Za-z]+""").find(c)?.value ?: return false) ?: return false
        if (role !in setOf(SlipRole.OVERTIME, SlipRole.NIGHT, SlipRole.HOLIDAY, SlipRole.WEEKLY_HOLIDAY, SlipRole.GROSS,
                SlipRole.NET, SlipRole.DEDUCTION, SlipRole.DEDUCTION_TOTAL, SlipRole.TOTAL, SlipRole.EXTRA_WORK)) return false
        val hasRuleWords = percentRe.containsMatchIn(line) || timesRe.containsMatchIn(line) ||
            listOf("시간당", "1회", "회당", "가산", "기본급의", "통상시급", "인정", "까지", "부터").any { it in c } ||
            // [PAYS-07] "기숙사비 매달 5만원 차감" is a rule; "기숙사비 50,000" is a payslip line.
            role == SlipRole.DEDUCTION && Regex("매달|매월|마다|차감").containsMatchIn(c) ||
            Regex("차감|공제").containsMatchIn(c) && Regex("매달|매월|마다").containsMatchIn(c)
        return !hasRuleWords && RuleImport.parseAmount(line) != null || Regex("""^[가-힣]+\d{1,3}(,\d{3})+$""").matches(c)
    }

    // ---- extra work ----

    private fun kindOf(c: String, kinds: List<ExtraKind>): Pair<Long, String>? {
        val own = kinds.filter { it.name.replace(" ", "").length >= 2 && it.name.replace(" ", "") in c }
            .maxByOrNull { it.name.replace(" ", "").length }
        if (own != null) return own.id to own.name
        return when {
            "대근" in c || "대체근무" in c -> ExtraKind.COVER_ID to "대근"
            "특근" in c || "특별근무" in c -> ExtraKind.SPECIAL_ID to "특근"
            "잔업" in c -> ExtraKind.OVERTIME_ID to "잔업"
            else -> null
        }
    }

    /**
     * "대근은 8시간에 기본급의 10%, 8시간 넘으면 50% 가산, 야간 50% 가산", "특근 시간당 2만원", "대근 1회 8만원 +
     * 연장 가산". A premium the sentence names makes it a rule of an hour's pay and ticked premiums; the ones it does
     * not name stay as the law has them, so nothing is paid below the law by leaving a word out.
     */
    private fun extraRule(line: String, c: String, kinds: List<ExtraKind>, fiveOrMore: Boolean): RuleFinding? {
        // "공휴일 근무는 대근으로" is about holidays, not how 대근 is paid.
        if (Regex("공휴일|명절|휴일근무는|빨간날").containsMatchIn(c) && Regex("(으로|로|처럼|방식|같이)").containsMatchIn(c) &&
            percentRe.find(c) == null && RuleImport.parseAmount(line) == null) return null
        val (kind, name) = kindOf(c, kinds) ?: return null
        if (c.startsWith("공휴일") || c.startsWith("명절")) return null
        val clauses = line.split(Regex("""(?<!\d)[,，]|[,，](?!\d{3})|\+|그리고""")).map { it.replace(" ", "") }
        val minHours = Regex("""최소(\d{1,2})시간""").find(c)?.groupValues?.get(1)?.toInt()
        val noNight = Regex("야간(가산)?(없|제외|미|안)").containsMatchIn(c)
        val legal = if (fiveOrMore) 0.5 else 0.0
        var rule = ExtraRule.lawUnit(overtime = legal, night = legal, holiday = legal, restAsHoliday = false)
        var unitSet = false
        var named = false
        var bonus = 0L
        val custom = mutableListOf<Premium>()
        for ((i, cl) in clauses.withIndex()) {
            val pct = percentRe.find(cl)?.groupValues?.get(1)?.toDouble()
            val times = timesRe.find(cl)?.groupValues?.get(1)?.toDouble()
            val amount = RuleImport.parseAmount(cl)
            val hrs = hoursRe.find(cl)?.groupValues?.get(1)?.toDouble()?.let { (it * 60).toInt() }
            val window = clockRange(cl)
            when {
                // The hour's pay.
                (pct != null) && ("기본급" in cl || "월급" in cl) && !unitSet -> {
                    rule = if ("1회" in cl || "회당" in cl) ExtraRule(ExtraBasis.PERCENT_OF_BASE, percentOfBase = pct / 100, coverMinutes = hrs ?: 480)
                    else rule.copy(unit = PayUnit.BASE_SHARE, percentOfBase = pct / 100, unitMinutes = hrs ?: 480)
                    unitSet = true
                }
                "통상" in cl && (pct != null || times != null) && !unitSet && window == null && !Regex("넘|초과|이후").containsMatchIn(cl) -> {
                    val m = times ?: (pct!! / 100)
                    // "통상시급의 200%" is the whole rate: the hour at 100% and the rest as a premium on every minute.
                    rule = rule.copy(unit = PayUnit.ORDINARY)
                    if (m > 1.0) custom += Premium("c${custom.size + 1}", "$name 가산", m - 1.0, scope = PremiumScope.All)
                    unitSet = true
                }
                amount != null && Regex("시간당|1시간|매시간").containsMatchIn(cl) && !unitSet -> {
                    rule = rule.copy(unit = PayUnit.PER_HOUR, amount = amount); unitSet = true
                }
                amount != null && !unitSet && (i == 0 || Regex("1회|회당|매회").containsMatchIn(cl)) -> {
                    rule = rule.copy(unit = PayUnit.PER_TIME, amount = amount, coverMinutes = hrs?.takeIf { "까지" in cl || "기준" in cl })
                    unitSet = true
                }
                amount != null && unitSet -> bonus += amount
                pct != null || times != null -> {
                    val p = when {
                        times != null -> times - 1.0
                        pct!! >= 100 && "가산" !in cl -> pct / 100 - 1.0
                        else -> pct / 100
                    }
                    when {
                        window != null -> custom += Premium("c${custom.size + 1}", "${RuleWhen.clock(window.first)}~${RuleWhen.clock(window.second)}시 가산", p,
                            scope = PremiumScope.Window(window.first, window.second))
                        hrs != null && Regex("넘|초과|이후|부터").containsMatchIn(cl) ->
                            rule = rule.tick(Premium.OVERTIME, p) { it.copy(scope = PremiumScope.Over(hrs), name = "${PayCalculator.hours(hrs)} 넘는 시간 가산") }
                        "야간" in cl || "심야" in cl -> rule = rule.tick(Premium.NIGHT, p)
                        "휴무일" in cl || "쉬는날" in cl -> rule = rule.tick(Premium.REST, p)
                        "휴일" in cl -> rule = rule.tick(Premium.HOLIDAY, p)
                        "연장" in cl -> rule = rule.tick(Premium.OVERTIME, p)
                        else -> continue
                    }
                    named = true
                }
            }
        }
        if (!unitSet && !named && bonus == 0L) return null
        if (noNight) rule = rule.copy(premiums = rule.premiums.map { if (it.key == Premium.NIGHT) it.copy(on = false) else it })
        rule = if (rule.basis == ExtraBasis.UNIT) rule.copy(premiums = rule.premiums + custom.take(Premium.MAX_CUSTOM))
        // A payment each time with premiums named: the law's 연장·휴일 premiums on top, the night one stays in the month's line.
        else rule.copy(legalPremiums = named, nightPremium = !noNight)
        rule = rule.copy(minMinutes = (minHours ?: 0) * 60, bonus = bonus)
        return RuleFinding(line, RuleChange.Extra(kind, name, rule), if (unitSet) 0.85 else 0.6)
    }

    private fun ExtraRule.tick(key: String, rate: Double, change: (Premium) -> Premium = { it }): ExtraRule =
        copy(premiums = premiums.map { if (it.key == key) change(it.copy(rate = rate, on = rate > 0)) else it })

    /** "공휴일 근무는 대근처럼", "공휴일은 휴일 대체". */
    private fun publicHoliday(line: String, c: String, kinds: List<ExtraKind>): RuleFinding? {
        if (!Regex("공휴일|빨간날|법정휴일").containsMatchIn(c)) return null
        if ("대체" in c && ("휴일대체" in c || "바꿔" in c || "대체휴" in c)) return RuleFinding(line, RuleChange.PublicHolidaysPaid(false), 0.8)
        val kind = kindOf(c.replace("휴일근로", ""), kinds)
        if (kind != null && Regex("(으로|로|처럼|방식|같이|대입)").containsMatchIn(c)) return RuleFinding(line, RuleChange.PublicHolidayAs(kind.first, kind.second), 0.85)
        if (Regex("휴일근로로|휴일근무로|휴일수당").containsMatchIn(c) && percentRe.find(c) == null) return RuleFinding(line, RuleChange.PublicHolidaysPaid(true), 0.75)
        return null
    }

    // ---- everything else, one clause at a time ----

    private fun readClause(line: String, clause: String, c: String, shiftTypes: List<ShiftType>): List<RuleFinding> {
        val amount = RuleImport.parseAmount(clause)
        val pct = percentRe.find(clause)?.groupValues?.get(1)?.toDouble()
        val times = timesRe.find(clause)?.groupValues?.get(1)?.toDouble()
        fun one(change: RuleChange, confidence: Double) = listOf(RuleFinding(line, change, confidence))

        // The pay itself.
        if ("연봉" in c && amount != null) return one(RuleChange.Annual(amount), 0.85)
        if (Regex("^(기본급|기본월급|월급|기본임금)").containsMatchIn(c) && amount != null && pct == null) return one(RuleChange.Base(WageType.MONTHLY, amount), 0.9)
        if (Regex("^(시급|기본시급)").containsMatchIn(c) && amount != null) return one(RuleChange.Base(WageType.HOURLY, amount), 0.9)
        if (Regex("^(일당|일급)").containsMatchIn(c) && amount != null) return one(RuleChange.Base(WageType.DAILY, amount), 0.85)
        if (("인상" in c || "상승" in c) && pct != null) {
            val month = monthListRe.find(c)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1..12 } ?: 1
            return one(RuleChange.Raise(month, pct), 0.8)
        }

        // How the company counts.
        if (Regex("5인미만|5명미만|다섯명미만").containsMatchIn(c)) return one(RuleChange.FiveOrMore(false), 0.9)
        if (Regex("5인이상|5명이상").containsMatchIn(c)) return one(RuleChange.FiveOrMore(true), 0.9)
        if ("휴게" in c) {
            when {
                Regex("근무시간|근로시간|일한시간|대기").containsMatchIn(c) && !Regex("아니|않|제외").containsMatchIn(c) -> return one(RuleChange.Breaks(BreakPay.AS_WORK), 0.75)
                "무급" in c || Regex("안나|빼").containsMatchIn(c) -> return one(RuleChange.Breaks(BreakPay.UNPAID), 0.8)
                "유급" in c || Regex("돈이나|급여가나|임금지급").containsMatchIn(c) -> return one(RuleChange.Breaks(BreakPay.PAID), 0.8)
            }
        }
        Regex("""(소정근로|근로시수|소정시간|월소정|기준시간|근무시수)[^\d]{0,6}(\d{2,3})\s*시간?""").find(c)?.let {
            val h = it.groupValues[2].toInt()
            if (h in 100..320) return one(RuleChange.MonthlyHours(h), 0.85)
        }
        Regex("""^(월)?(\d{3})시간(이에요|입니다|기준)?$""").find(c)?.let {
            val h = it.groupValues[2].toInt()
            if (h in 100..320) return one(RuleChange.MonthlyHours(h), 0.6)
        }
        if (("주휴일" in c || "주휴" in c && "일" in c) && amount == null && pct == null) {
            RuleWhen.DAY_NAMES.entries.firstOrNull { (_, n) -> "${n}요일" in c }?.let { return one(RuleChange.WeeklyHolidayDay(it.key), 0.8) }
        }
        if (Regex("쉬는날|휴무일|비번").containsMatchIn(c) && Regex("근무|일하|나오").containsMatchIn(c) && amount == null && pct == null) {
            when {
                Regex("휴일근로|휴일근무|휴일로").containsMatchIn(c) -> return one(RuleChange.RestDay(RestDayWork.HOLIDAY), 0.8)
                Regex("연장근로로|연장으로|전부연장|모두연장").containsMatchIn(c) -> return one(RuleChange.RestDay(RestDayWork.OVERTIME), 0.8)
                "법대로" in c -> return one(RuleChange.RestDay(RestDayWork.LAW), 0.7)
            }
        }
        if (Regex("근무표|정해진근무|교대근무|12시간근무").containsMatchIn(c) && Regex("기본근무|소정근로|기본급에포함|연장아님|연장없").containsMatchIn(c)) {
            return one(RuleChange.ScheduleRegular(true), 0.75)
        }

        // The night window: "야간은 23시~07시", "야간 시간 22:00-06:00".
        val window = clockRange(clause)
        if (window != null && ("야간" in c || "심야" in c) && pct == null && times == null && amount == null) {
            return one(RuleChange.NightWindow(window.first, window.second), 0.85)
        }

        // Bonuses: "상여금 800% 짝수달", "명절상여 50만원 설·추석".
        if (Regex("상여|보너스|명절").containsMatchIn(c) && (pct != null || amount != null)) {
            val festivals = buildSet {
                if (Regex("설|명절").containsMatchIn(c)) add(Festival.SEOLLAL)
                if (Regex("추석|명절").containsMatchIn(c)) add(Festival.CHUSEOK)
            }
            val months = months(c)
            val name = if (festivals.isNotEmpty() && months.isEmpty()) "명절상여" else "상여금"
            return if (pct != null) one(RuleChange.Bonus(name, pct, null, months.ifEmpty { if (festivals.isEmpty()) setOf(2, 4, 6, 8, 10, 12) else emptySet() }, festivals), 0.75)
            else one(RuleChange.Bonus(name, null, amount, months, festivals.takeIf { it.isNotEmpty() || months.isNotEmpty() } ?: setOf(Festival.SEOLLAL, Festival.CHUSEOK)), 0.7)
        }

        // [PAYS-07] Money the company takes off: "지각 1회 1만원 차감", "기숙사비 매달 5만원 공제".
        deduction(line, clause, c, amount, pct, shiftTypes)?.let { return listOf(it) }
        // Money taken off that the calendar cannot count (지각 1회 1만원 차감) is never read as money paid.
        if (amount != null && Regex("차감|공제|감액").containsMatchIn(c)) return emptyList()

        // A rule of the company's own: a condition (a day, a clock window, hours beyond some) and a way to pay.
        custom(line, clause, c, amount, pct, times, window, shiftTypes)?.let { return listOf(it) }

        // The month's premiums: "연장 150%", "야간 가산 50%", "휴일 8시간 넘으면 200%".
        if ((pct != null || times != null) && Regex("연장|야간|심야|휴일").containsMatchIn(c) && amount == null) {
            val target = when {
                "야간" in c || "심야" in c -> PremiumTarget.NIGHT
                "휴일" in c -> PremiumTarget.HOLIDAY
                else -> PremiumTarget.OVERTIME
            }
            val premium = when {
                times != null -> times - 1.0
                "가산" in c || target == PremiumTarget.NIGHT || pct!! < 100 -> pct!! / 100
                else -> pct / 100 - 1.0
            }
            val over8h = target == PremiumTarget.HOLIDAY && Regex("8시간(넘|초과|이상)").containsMatchIn(c)
            return one(RuleChange.Premium(target, premium.coerceIn(0.0, Premium.MAX_RATE), over8h), 0.75)
        }

        // Allowances: "자격수당 월 5만원", "나이트 수당 1회 3만원", "야간 근무마다 2만원".
        if (amount != null) {
            val name = nameOf(clause)
            val shifts = shiftTypes.filter { it.name.length >= 1 && Regex("${Regex.escape(it.name.replace(" ", ""))}(근무|조|번)?(마다|1회|당|할때)").containsMatchIn(c) }
            val cadence = when {
                shifts.isNotEmpty() -> Cadence.PER_SHIFT_TYPE
                Regex("나이트|야간근무|밤근무").containsMatchIn(c) && Regex("1회|회당|근무당|마다|매회").containsMatchIn(c) -> Cadence.PER_NIGHT_SHIFT
                Regex("1회|회당|근무당|1일|근무일|출근|매회|마다").containsMatchIn(c) -> Cadence.PER_WORK_DAY
                else -> Cadence.MONTHLY
            }
            val ordinary = when {
                Regex("통상임금(제외|아님|미포함|안)").containsMatchIn(c) -> false
                "통상임금" in c -> true
                else -> null
            }
            if (name.isNotEmpty() || Regex("수당|식대|보조|지원").containsMatchIn(c)) {
                return one(RuleChange.Allowance(name.ifEmpty { "수당" }, amount, cadence, shifts.map { it.id }.toSet(), ordinary),
                    if (name.isNotEmpty() && Regex("수당|식대|식비|보조|지원|비$").containsMatchIn(name)) 0.8 else 0.5)
            }
        }
        return emptyList()
    }

    /** "토요일 근무는 시간당 50% 더", "19~23시 근무 25% 가산", "일요일 근무 1회 2만원", "12시간 넘는 시간은 시간당 3,000원". */
    private fun custom(
        line: String, clause: String, c: String, amount: Long?, pct: Double?, times: Double?,
        window: Pair<LocalTime, LocalTime>?, shiftTypes: List<ShiftType>,
    ): RuleFinding? {
        val days = buildSet {
            RuleWhen.DAY_NAMES.forEach { (d, n) -> if ("${n}요일" in c) add(d) }
            if ("주말" in c) { add(DayOfWeek.SATURDAY); add(DayOfWeek.SUNDAY) }
            if ("평일" in c) addAll(DayOfWeek.entries.filter { it.value <= 5 })
        }
        val dayKind = when {
            Regex("공휴일|빨간날").containsMatchIn(c) -> RuleDayKind.HOLIDAY
            Regex("쉬는날|휴무일|비번날").containsMatchIn(c) -> RuleDayKind.OFF_DAY
            else -> RuleDayKind.ANY
        }
        val shifts = shiftTypes.filter { s ->
            val n = s.name.replace(" ", "")
            n.isNotEmpty() && Regex("${Regex.escape(n)}(근무|조|번)(때|일|에|는|시|한날|날)").containsMatchIn(c)
        }
        val over = Regex("""(\d{1,2})시간(넘|초과|이후)""").find(c)?.groupValues?.get(1)?.toInt()?.times(60)
        val atLeast = Regex("""(\d{1,2})시간이상""").find(c)?.groupValues?.get(1)?.toInt()?.times(60)
        // A night window on its own is the night premium; a window with "근무" or a named day is the company's own.
        val windowRule = window != null && !Regex("^(야간|심야)").containsMatchIn(c)
        val hasCondition = days.isNotEmpty() || shifts.isNotEmpty() || over != null || atLeast != null || windowRule ||
            dayKind != RuleDayKind.ANY && (amount != null || "시간당" in c)
        if (!hasCondition) return null
        val pay: RulePay = when {
            pct != null && ("기본급" in c) -> RulePay.OfBase(pct / 100, hoursRe.findAll(c).map { (it.groupValues[1].toDouble() * 60).toInt() }
                .firstOrNull { it != over && it != atLeast } ?: 480)
            amount != null && Regex("시간당|1시간|매시간").containsMatchIn(c) -> RulePay.PerHour(amount)
            amount != null -> RulePay.PerTime(amount)
            times != null -> RulePay.OfHourly(times - 1.0)
            pct != null -> RulePay.OfHourly(if (pct >= 100 && "가산" !in c && "더" !in c && "추가" !in c) pct / 100 - 1.0 else pct / 100)
            else -> return null
        }
        if (pay is RulePay.OfHourly && pay.rate <= 0) return null
        val whenever = RuleWhen(
            dayKind = dayKind,
            daysOfWeek = days,
            shiftTypeIds = shifts.map { it.id }.toSet(),
            from = window?.first?.takeIf { windowRule }, to = window?.second?.takeIf { windowRule },
            overMinutes = over ?: 0,
            minMinutes = if (pay is RulePay.PerTime) atLeast ?: 0 else 0,
        )
        val what = when {
            days.isNotEmpty() -> when (days) {
                setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "주말"
                DayOfWeek.entries.filter { it.value <= 5 }.toSet() -> "평일"
                else -> days.sorted().joinToString("·") { RuleWhen.DAY_NAMES.getValue(it) } + "요일"
            }
            shifts.isNotEmpty() -> shifts.joinToString("·") { it.name }
            windowRule -> "${RuleWhen.clock(window!!.first)}~${RuleWhen.clock(window.second)}시"
            over != null -> "${PayCalculator.hours(over)} 초과"
            dayKind != RuleDayKind.ANY -> dayKind.label
            else -> "${PayCalculator.hours(atLeast ?: 0)} 이상"
        }
        val name = (what + if (pay is RulePay.PerTime) " 수당" else " 가산").take(CustomRule.MAX_NAME)
        val rule = CustomRule("rule-" + (line + clause).hashCode().toUInt().toString(16), name, whenever, pay, source = line.trim())
        return RuleFinding(line, RuleChange.Custom(rule, shiftTypes.associate { it.id to it.name }), 0.75)
    }

    /**
     * [PAYS-07] "기숙사비 매달 5만원 차감", "식대 근무일마다 3,000원 공제", "조합비 기본급의 1% 공제": money the company
     * takes off what it pays. Taxes and 4대보험 come off after the total the app works out, so they are left out,
     * and so is anything the calendar cannot count (지각, 조퇴).
     */
    private fun deduction(line: String, clause: String, c: String, amount: Long?, pct: Double?, shiftTypes: List<ShiftType>): RuleFinding? {
        if (!Regex("차감|공제|빼고|빼서|뺀다|뺌|감액").containsMatchIn(c)) return null
        if (Regex("국민연금|건강보험|고용보험|장기요양|소득세|주민세|세금|4대보험|공제합계|공제총액|공제계|지각|조퇴|외출").containsMatchIn(c)) return null
        val days = buildSet { RuleWhen.DAY_NAMES.forEach { (d, n) -> if ("${n}요일" in c) add(d) } }
        val shifts = shiftTypes.filter { s ->
            val n = s.name.replace(" ", "")
            n.isNotEmpty() && Regex("${Regex.escape(n)}(근무|조|번)").containsMatchIn(c)
        }
        val perDay = days.isNotEmpty() || shifts.isNotEmpty() || Regex("1회|회당|번당|건당|마다|근무일|출근").containsMatchIn(c)
        val pay: RulePay = when {
            pct != null && "기본급" in c -> RulePay.OfBaseMonthly(pct / 100)
            amount == null || amount <= 0 -> return null
            perDay -> RulePay.PerTime(amount)
            else -> RulePay.Monthly(amount)
        }
        val whenever = if (pay is RulePay.PerTime) RuleWhen(RuleDayKind.WORK_DAY, days, shifts.map { it.id }.toSet()) else RuleWhen()
        val name = nameOf(clause).replace(Regex("""\s*(근무일|출근|근무|매일|차감|공제|기본급의|기본급)\s*$"""), "").trim()
            .ifEmpty { "차감" }.take(CustomRule.MAX_NAME)
        val rule = CustomRule("rule-" + (line + clause).hashCode().toUInt().toString(16), name, whenever, pay, source = line.trim(), deduct = true)
        return RuleFinding(line, RuleChange.Custom(rule, shiftTypes.associate { it.id to it.name }), 0.7)
    }

    /** Clock range in a clause, e.g. 19~23시, 22:00-06:00, 밤 10시부터 새벽 6시까지. */
    internal fun clockRange(text: String): Pair<LocalTime, LocalTime>? {
        val m = clockRangeRe.find(text) ?: return null
        // A bare "8-12" with no 시 or : is not a clock range.
        if (!Regex("시|:").containsMatchIn(m.value)) return null
        fun hour(prefix: String, h: Int): Int = when (prefix) {
            "오후", "저녁", "밤" -> if (h in 1..11) h + 12 else h
            "새벽", "오전" -> if (h == 12) 0 else h
            else -> h
        }
        val sh = hour(m.groupValues[1], m.groupValues[2].toInt())
        val sm = (m.groupValues[3].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: 0
        val eh = hour(m.groupValues[5], m.groupValues[6].toInt())
        val em = (m.groupValues[7].ifEmpty { m.groupValues[8] }).toIntOrNull() ?: 0
        if (sh !in 0..24 || eh !in 0..24 || sm !in 0..59 || em !in 0..59) return null
        return LocalTime.of(sh % 24, sm) to LocalTime.of(eh % 24, em)
    }

    private fun months(c: String): Set<Int> = when {
        "짝수" in c -> setOf(2, 4, 6, 8, 10, 12)
        "홀수" in c -> setOf(1, 3, 5, 7, 9, 11)
        "분기" in c -> setOf(3, 6, 9, 12)
        "반기" in c -> setOf(6, 12)
        "매달" in c || "매월" in c -> (1..12).toSet()
        else -> Regex("""(\d{1,2})(?=[·,/\s]*\d{0,2}월)""").findAll(c.substringBefore("%")).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..12 }.toSet().takeIf { "월" in c }.orEmpty()
    }

    /** The words before the first number, without bullets and joining words. */
    private fun nameOf(clause: String): String =
        clause.split(Regex("""[\d:：]"""), limit = 2).first()
            .replace(Regex("""^[\s\-•·*○●▶\[\(]+"""), "")
            .replace(Regex("""(은|는|이|가)?\s*(월|1회|회당|근무당|매달|매월|마다|당)?\s*$"""), "")
            .trim()

    /**
     * The setup with [changes] made, from [from] on for amounts. Each answer it settles leaves the 확인 필요 set.
     * [kinds] names extra work in the changes' summaries only.
     */
    fun apply(profile: PayProfile, changes: List<RuleChange>, from: YearMonth): PayProfile = changes.fold(profile) { p, change ->
        val since = p.period.start(from)
        fun money(v: Long) = ValueTimeline(listOf(DatedValue(since, v)))
        when (change) {
            is RuleChange.Base -> p.copy(
                wageType = change.type,
                base = if (p.wageType == change.type) BulkEditPlanner.apply(p.base, change.amount, EditScope.FromMonth(from)) else money(change.amount),
                annual = null,
                estimatedFields = p.estimatedFields - Unknowns.BASE,
            )
            is RuleChange.Annual -> {
                val a = AnnualPay(change.yearly)
                p.copy(wageType = WageType.MONTHLY, annual = a, estimatedFields = p.estimatedFields - Unknowns.BASE,
                    base = if (p.wageType == WageType.MONTHLY) BulkEditPlanner.apply(p.base, a.breakdown().monthlyBase, EditScope.FromMonth(from))
                    else money(a.breakdown().monthlyBase))
            }
            is RuleChange.Allowance -> {
                val existing = p.items.firstOrNull { it.cadence != Cadence.BONUS && it.name.replace(" ", "") == change.name.replace(" ", "") }
                if (existing != null) p.copy(
                    items = p.items.map {
                        if (it.id != existing.id) it else it.copy(
                            amount = BulkEditPlanner.apply(it.amount, change.amount, EditScope.FromMonth(from)), cadence = change.cadence,
                            shiftTypeIds = change.shiftTypeIds.ifEmpty { it.shiftTypeIds }, ordinary = change.ordinary ?: it.ordinary,
                        )
                    },
                    estimatedFields = p.estimatedFields - existing.id - Unknowns.item(existing.id),
                ) else p.copy(items = p.items + PayItem(newId("item", change.name, p), change.name, money(change.amount), change.cadence,
                    shiftTypeIds = change.shiftTypeIds, ordinary = change.ordinary ?: (change.cadence == Cadence.MONTHLY && !welfare(change.name))))
            }
            is RuleChange.Bonus -> {
                val count = (change.months.size + change.festivals.size).coerceAtLeast(1)
                p.copy(items = p.items + PayItem(newId("bonus", change.name, p), change.name, money(change.amount ?: 0), Cadence.BONUS,
                    ordinary = true, months = change.months, festivals = change.festivals,
                    percentOfBase = change.yearlyPercent?.let { it / 100 / count }))
            }
            is RuleChange.Premium -> p.withRules {
                when (change.target) {
                    PremiumTarget.OVERTIME -> it.copy(overtimePremium = change.premium)
                    PremiumTarget.NIGHT -> it.copy(nightPremium = change.premium)
                    PremiumTarget.HOLIDAY -> if (change.over8h) it.copy(holidayPremiumOver8h = change.premium) else it.copy(holidayPremiumWithin8h = change.premium)
                }
            }
            is RuleChange.NightWindow -> p.withRules { it.copy(nightStart = change.start, nightEnd = change.end) }
            is RuleChange.MonthlyHours -> p.copy(monthlyHours = change.hours, estimatedFields = p.estimatedFields - Unknowns.MONTHLY_HOURS)
            is RuleChange.FiveOrMore -> p.copy(fiveOrMoreEmployees = change.value, estimatedFields = p.estimatedFields - Unknowns.FIVE_OR_MORE)
            is RuleChange.Breaks -> p.withRules { it.copy(breakPay = change.mode) }.let { Unknowns.answered(it, Unknowns.BREAK_PAY) }
            is RuleChange.PublicHolidayAs -> p.withRules { it.copy(publicHolidayAs = change.kind, publicHolidaysPaid = true) }
                .let { Unknowns.answered(it, Unknowns.PUBLIC_HOLIDAYS) }
            is RuleChange.PublicHolidaysPaid -> p.withRules { it.copy(publicHolidaysPaid = change.value) }.let { Unknowns.answered(it, Unknowns.PUBLIC_HOLIDAYS) }
            is RuleChange.RestDay -> p.withRules { it.copy(restDayWork = change.mode) }.let { Unknowns.answered(it, Unknowns.REST_DAY_WORK) }
            is RuleChange.ScheduleRegular -> p.copy(scheduleIsRegular = change.value).let { Unknowns.answered(it, Unknowns.SCHEDULE_IS_REGULAR) }
            is RuleChange.WeeklyHolidayDay -> p.withRules { it.copy(weeklyHoliday = WeeklyHoliday.Fixed(change.day)) }
            is RuleChange.Raise -> p.copy(base = p.base.copy(raise = RaiseRule(month = change.month, percent = change.percent, unit = 1_000)))
            is RuleChange.Extra -> p.withRules { it.copy(extra = it.extra + (change.kind to change.rule)) }
            is RuleChange.Custom -> p.copy(customRules = (p.customRules.filterNot { it.id == change.rule.id || it.source.isNotEmpty() && it.source == change.rule.source && it.name == change.rule.name } +
                change.rule).takeLast(CustomRule.MAX))
        }
    }

    /** 식대·교통비 and the like: welfare paid to everyone, usually left out of 통상임금 by companies. */
    private fun welfare(name: String) = Regex("식대|식비|교통|차량|복지|통신|자녀|가족|학자").containsMatchIn(name)

    private fun newId(prefix: String, name: String, p: PayProfile): String {
        var i = 0
        while (true) {
            val id = "$prefix-text-${name.hashCode().toUInt().toString(16)}" + if (i == 0) "" else "-$i"
            if (p.items.none { it.id == id }) return id
            i++
        }
    }
}
