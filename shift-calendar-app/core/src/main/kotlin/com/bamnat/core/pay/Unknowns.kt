package com.bamnat.core.pay

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import java.time.DayOfWeek

/** [PAYS-01] Another answer to a question left as 모르겠어요, as the profile that answer gives. */
data class UnknownAnswer(val label: String, val apply: (PayProfile) -> PayProfile)

/**
 * [PAYS-01] One 확인 필요 key and what it means for the month: [label] names it, [assumed] says what the app
 * calculates with meanwhile, and [answers] are the other answers [PayRange] runs. An amount entered roughly
 * or not known has no answers: it is listed but does not move the range.
 */
data class Unknown(
    val key: String,
    val label: String,
    val assumed: String,
    val answers: List<UnknownAnswer>,
    /** The setup question that settles it; null for a key no question asks (an amount, the old 휴일 급여 방식). */
    val question: SetupQuestion?,
) {
    /** Whether [PayRange] has another answer to run. */
    val movesRange: Boolean get() = answers.isNotEmpty()
}

/**
 * [PAYS-01] The 확인 필요 keys kept in [PayProfile.estimatedFields] ("estimated" in the saved JSON). A question
 * answered with 모르겠어요 is calculated with the value [markUnknown] sets, and its key stays until the
 * question is answered or a payslip settles it. Keys this version does not know are carried along and
 * ignored, so a profile can move between app versions.
 */
object Unknowns {
    /** 직원 5명 이상; assumed yes. */
    const val FIVE_OR_MORE = "fiveOrMore"
    /** 감시·단속 승인; assumed none. */
    const val SUPERVISORY = "supervisory"
    /** Scheduled hours over 8 a day are 연장; assumed so, by law. The 1.11 setup writes it too. */
    const val SCHEDULE_IS_REGULAR = "scheduleIsRegular"
    /** 공휴일: paid holidays at 5인 이상 (no 휴일 대체), ordinary days under 5; assumed as the law has it. */
    const val PUBLIC_HOLIDAYS = "publicHolidays"
    /** Work on a day off; assumed 법대로. */
    const val REST_DAY_WORK = "restDayWork"
    /**
     * [PAYX-23] Which day is the 주휴일, when the 회사 규칙 leave it unset; never stored, listed with [REST_DAY_WORK]
     * whenever that is 확인 필요, since the two together decide how work on a day off is paid.
     */
    const val WEEKLY_HOLIDAY = "weeklyHoliday"
    /** Breaks; assumed unpaid. */
    const val BREAK_PAY = "breakPay"
    /** 월 소정근로시간; assumed 209 or the roster's. */
    const val MONTHLY_HOURS = "monthlyHours"
    /** The base amount was entered as 대략. */
    const val BASE = "base"
    /**
     * [PAYX-23] 연봉 13분할: whether the 13th share is a 퇴직금 reserve (not wage, assumed) or a 정기 상여 paid once a
     * year, which is 통상임금 (대법원 2024.12.19. 2020다247190). Written when 13분할 is picked.
     */
    const val ANNUAL_13 = "annual13"
    /**
     * [PAYX-24] How the month of joining or leaving is paid (일할): no law sets it, so the company's 취업규칙 decides;
     * assumed 달력 일수 until the user picks the company's way.
     */
    const val PRORATION = "proration"
    /** 휴일 급여 방식, written by the setup of 1.11 and earlier when skipped; no longer asked, a payslip settles it. */
    const val HOLIDAY_PAY_MODE = "holidayPayMode"
    private const val ITEM = "item:"
    private const val COVERS = "covers:"

    /** [PAYX-23] The bonus item the [ANNUAL_13] answer 정기 상여 adds. */
    const val ANNUAL_13_ITEM = "annual13Bonus"

    /** An allowance's amount is unknown (금액 모름): with no amount it is left out and listed in [MonthPay.missing]. */
    fun item(id: String): String = ITEM + id

    /** What an allowance stands in for is unknown; [PayItem.defaultCovers] is assumed. */
    fun covers(id: String): String = COVERS + id

    /** The 월 소정근로시간 companies use: 209 (주 40시간 + 주휴), 226 and 243 (토요일 4·8시간 유급). */
    val MONTHLY_HOURS_CHOICES: List<Int> = listOf(209, 226, 243)

    /** [PAYX-34] The chips the setup and the company rules show: [MONTHLY_HOURS_CHOICES] and 180 (명세서에 흔한 값). */
    val MONTHLY_HOURS_CHIPS: List<Int> = listOf(180) + MONTHLY_HOURS_CHOICES

    /**
     * Every key in [profile]'s 확인 필요 set this version understands, in question order; [rosterHours] is the
     * roster's 월 소정근로시간 (see [StandardHours]) when known. Allowance amounts the 1.11 setup marked 추정
     * by their bare id stay listed, as amounts.
     */
    fun of(profile: PayProfile, rosterHours: Int? = null): List<Unknown> {
        val ids = profile.items.map { it.id }
        val order = listOf(FIVE_OR_MORE, SUPERVISORY, SCHEDULE_IS_REGULAR, PUBLIC_HOLIDAYS, REST_DAY_WORK, WEEKLY_HOLIDAY, BREAK_PAY) +
            ids.map(::covers) + listOf(MONTHLY_HOURS, PRORATION, ANNUAL_13, BASE) + ids.map(::item) + HOLIDAY_PAY_MODE + ids
        val open = profile.estimatedFields + listOfNotNull(WEEKLY_HOLIDAY.takeIf { REST_DAY_WORK in profile.estimatedFields })
        return order.distinct().filter { it in open }.mapNotNull { describe(it, profile, rosterHours) }
    }

    /** What [key] means for [profile]; null for a key this version does not know or an allowance since deleted. */
    fun describe(key: String, profile: PayProfile, rosterHours: Int? = null): Unknown? {
        val rules = profile.rules
        fun answer(label: String, change: (PayProfile) -> PayProfile) = UnknownAnswer(label, change)
        return when {
            key == FIVE_OR_MORE -> {
                fun name(five: Boolean) = if (five) "5인 이상" else "5인 미만"
                val five = profile.fiveOrMoreEmployees
                Unknown(key, "직원 5명 이상", "${name(five)}으로 계산해요",
                    listOf(answer(name(!five)) { it.copy(fiveOrMoreEmployees = !five) }), SetupQuestion.FIVE_OR_MORE)
            }
            key == SUPERVISORY -> {
                fun name(approved: Boolean) = if (approved) "승인 있음" else "승인 없음"
                val approved = rules.supervisoryApproved
                Unknown(key, "감시·단속 승인", "${name(approved)}으로 계산해요",
                    listOf(answer(name(!approved)) { it.withRules { r -> r.copy(supervisoryApproved = !approved) } }), SetupQuestion.SUPERVISORY)
            }
            key == SCHEDULE_IS_REGULAR -> {
                val regular = profile.scheduleIsRegular
                Unknown(key, "8시간 넘는 근무", if (regular) "근무표 시간은 모두 기본 근무로 계산해요" else "8시간 넘는 시간은 법대로 연장으로 계산해요",
                    listOf(answer(if (regular) "법대로 연장" else "모두 기본 근무") { it.copy(scheduleIsRegular = !regular) }),
                    SetupQuestion.SCHEDULE_IS_REGULAR)
            }
            // The answer is fixed by the profile it was asked for, so combining it with 5인 미만 still lowers the month.
            key == PUBLIC_HOLIDAYS -> if (profile.fiveOrMoreEmployees) {
                val paid = rules.publicHolidaysPaid
                Unknown(key, "공휴일 근무", if (paid) "공휴일은 유급휴일로 계산해요" else "공휴일은 다른 날과 바꿔 평일처럼 계산해요",
                    listOf(answer(if (paid) "휴일 대체 (평일처럼)" else "유급휴일") { it.withRules { r -> r.copy(publicHolidaysPaid = !paid) } }),
                    SetupQuestion.PUBLIC_HOLIDAYS)
            } else {
                val granted = rules.holidaysUnderFive
                Unknown(key, "공휴일 근무", if (granted) "회사가 공휴일을 유급휴일로 주는 것으로 계산해요" else "공휴일은 평일처럼 계산해요 (5인 미만)",
                    listOf(answer(if (granted) "평일처럼" else "유급휴일로 줌") { it.withRules { r -> r.copy(holidaysUnderFive = !granted) } }),
                    SetupQuestion.PUBLIC_HOLIDAYS)
            }
            key == REST_DAY_WORK -> Unknown(key, "쉬는 날 근무", "쉬는 날 근무는 ${rules.restDayWork.label} 계산해요",
                RestDayWork.entries.filter { it != rules.restDayWork }.map { m -> answer(m.label) { it.withRules { r -> r.copy(restDayWork = m) } } },
                SetupQuestion.REST_DAY_WORK)
            // [PAYX-23] With no 주휴일 set every day off is a 휴무일 (the classifier and the paid holidays agree); the range
            // also runs the usual 주휴일s. Set in 회사 규칙, so no setup question.
            key == WEEKLY_HOLIDAY -> if (rules.weeklyHoliday != WeeklyHoliday.Unknown) null else
                Unknown(key, "주휴일", "주휴일을 몰라 쉬는 날은 모두 휴무일로 계산해요",
                    listOf(
                        answer("일요일") { it.withRules { r -> r.copy(weeklyHoliday = WeeklyHoliday.Fixed(DayOfWeek.SUNDAY)) } },
                        answer("주마다 첫 쉬는 날") { it.withRules { r -> r.copy(weeklyHoliday = WeeklyHoliday.FirstRestDay) } },
                    ), null)
            key == BREAK_PAY -> Unknown(key, "휴게 시간", when (rules.breakPay) {
                BreakPay.UNPAID -> "휴게는 무급으로 계산해요"
                BreakPay.PAID -> "휴게는 유급으로 계산해요"
                BreakPay.AS_WORK -> "휴게도 근무시간으로 계산해요"
            }, BreakPay.entries.filter { it != rules.breakPay }.map { b -> answer(b.label) { it.withRules { r -> r.copy(breakPay = b) } } },
                SetupQuestion.BREAK_PAY)
            key == MONTHLY_HOURS -> Unknown(key, "월 소정근로시간", "${profile.monthlyHours}시간으로 계산해요",
                if (profile.wageType != WageType.MONTHLY) emptyList()
                else (MONTHLY_HOURS_CHOICES + listOfNotNull(rosterHours)).distinct().filter { it != profile.monthlyHours }
                    .map { h -> answer("${h}시간") { it.copy(monthlyHours = h) } },
                SetupQuestion.MONTHLY_HOURS)
            key == ANNUAL_13 -> profile.annual?.takeIf { it.divisor == 13 }?.let { a ->
                val share = a.breakdown().monthlyShare
                Unknown(key, "연봉 13번째 몫", "퇴직금 몫으로 보고 통상임금에서 뺐어요",
                    listOf(answer("연말 정기 상여 (통상임금)") { p ->
                        if (p.items.any { it.id == ANNUAL_13_ITEM }) p
                        else p.copy(items = p.items + PayItem(ANNUAL_13_ITEM, "13번째 몫 상여", ValueTimeline(listOf(DatedValue(java.time.LocalDate.of(2000, 1, 1), share))), Cadence.BONUS,
                            ordinary = true, months = setOf(12)))
                    }), null)
            }
            key == PRORATION -> Unknown(key, "입사·퇴사 달 일할", "${particle(rules.proration.label, "으로", "로")} 계산해요",
                ProrationBasis.entries.filter { it != rules.proration }.map { b -> answer(b.label) { it.withRules { r -> r.copy(proration = b) } } },
                null)
            key == BASE -> Unknown(key, "${baseName(profile.wageType)} (대략)", "넣은 금액으로 계산해요", emptyList(), null)
            key == HOLIDAY_PAY_MODE -> Unknown(key, "휴일 근무 급여 방식", "${particle(profile.holidayPayMode.label, "으로", "로")} 계산해요",
                if (profile.wageType != WageType.MONTHLY) emptyList()
                else HolidayPayMode.entries.filter { it != profile.holidayPayMode }.map { m -> answer(m.label) { it.copy(holidayPayMode = m) } },
                null)
            key.startsWith(ITEM) -> profile.items.firstOrNull { it.id == key.removePrefix(ITEM) }?.let { item ->
                Unknown(key, "${item.name} 금액", "금액을 몰라 합계에서 뺐어요", emptyList(), null)
            }
            key.startsWith(COVERS) -> profile.items.firstOrNull { it.id == key.removePrefix(COVERS) }?.let { item ->
                val now = item.coveredKinds
                val choices = when {
                    item.isFixedOvertime -> listOf(setOf(LineKind.OVERTIME), setOf(LineKind.OVERTIME, LineKind.NIGHT),
                        setOf(LineKind.OVERTIME, LineKind.NIGHT, LineKind.HOLIDAY))
                    item.isNightAllowance -> listOf(emptySet(), setOf(LineKind.NIGHT))
                    else -> emptyList()
                }
                Unknown(key, "${particle(item.name, "이", "가")} 대신하는 가산",
                    if (now.isEmpty()) "${particle(item.name, "은", "는")} 따로 받는 수당으로 계산해요"
                    else "${particle(item.name, "은", "는")} ${coversText(now)} 가산을 대신하는 것으로 계산해요",
                    choices.filter { it != now }.map { set ->
                        answer(if (set.isEmpty()) "따로 받음" else "${coversText(set)} 대신") { p -> p.withItem(item.id) { it.copy(covers = set) } }
                    },
                    SetupQuestion.COVERS)
            }
            // An allowance amount the 1.11 setup kept as 추정 (its suggested amount left untouched).
            else -> profile.items.firstOrNull { it.id == key }?.let { item ->
                Unknown(key, "${item.name} 금액 (추정)", "넣은 금액으로 계산해요", emptyList(), null)
            }
        }
    }

    /**
     * [profile] with [key] left as 모르겠어요: the value the app assumes set and the key added. An unknown
     * allowance amount is cleared so it is left out of the total; [rosterHours] is the roster's 월 소정근로시간.
     */
    fun markUnknown(profile: PayProfile, key: String, rosterHours: Int? = null): PayProfile {
        val p = when {
            key == FIVE_OR_MORE -> profile.copy(fiveOrMoreEmployees = true)
            key == SUPERVISORY -> profile.withRules { it.copy(supervisoryApproved = false) }
            key == SCHEDULE_IS_REGULAR -> profile.copy(scheduleIsRegular = false)
            key == PUBLIC_HOLIDAYS -> profile.withRules { it.copy(publicHolidaysPaid = true, holidaysUnderFive = false) }
            key == REST_DAY_WORK -> profile.withRules { it.copy(restDayWork = RestDayWork.LAW) }
            key == BREAK_PAY -> profile.withRules { it.copy(breakPay = BreakPay.UNPAID) }
            key == MONTHLY_HOURS -> profile.copy(monthlyHours = rosterHours ?: StandardHours.FULL_TIME)
            key == HOLIDAY_PAY_MODE -> profile.copy(holidayPayMode = HolidayPayMode.FULL)
            // A bonus set as a share of 기본급 is a known amount, so the share goes too.
            key.startsWith(ITEM) -> profile.withItem(key.removePrefix(ITEM)) { it.copy(amount = ValueTimeline(), percentOfBase = null) }
            key.startsWith(COVERS) -> profile.withItem(key.removePrefix(COVERS)) { it.copy(covers = null) }
            else -> profile
        }
        return p.copy(estimatedFields = p.estimatedFields + key)
    }

    /** [profile] with [key] answered: the key leaves the 확인 필요 set, the values stay as they are. */
    fun answered(profile: PayProfile, key: String): PayProfile = profile.copy(estimatedFields = profile.estimatedFields - key)

    private fun baseName(type: WageType): String = when (type) {
        WageType.MONTHLY -> "기본급"
        WageType.HOURLY -> "시급"
        WageType.DAILY -> "일당"
    }

    /** "연장", "야간", "연장·야간·휴일". */
    internal fun coversText(kinds: Set<LineKind>): String = PayItem.COVERABLE.filter { it in kinds }.joinToString("·") {
        when (it) {
            LineKind.OVERTIME -> "연장"
            LineKind.NIGHT -> "야간"
            else -> "휴일"
        }
    }
}

internal fun PayProfile.withRules(change: (CompanyRules) -> CompanyRules): PayProfile = copy(rules = change(rules))

internal fun PayProfile.withItem(id: String, change: (PayItem) -> PayItem): PayProfile =
    copy(items = items.map { if (it.id == id) change(it) else it })

/** [PAYS-01] The optional pay questions, each answerable with 모르겠어요; [key] is the 확인 필요 key it stores. */
enum class SetupQuestion(val key: String, val label: String) {
    FIVE_OR_MORE(Unknowns.FIVE_OR_MORE, "직원 5명 이상"),
    SUPERVISORY(Unknowns.SUPERVISORY, "감시·단속 승인"),
    SCHEDULE_IS_REGULAR(Unknowns.SCHEDULE_IS_REGULAR, "8시간 넘는 근무"),
    PUBLIC_HOLIDAYS(Unknowns.PUBLIC_HOLIDAYS, "공휴일 근무"),
    REST_DAY_WORK(Unknowns.REST_DAY_WORK, "쉬는 날 근무"),
    BREAK_PAY(Unknowns.BREAK_PAY, "휴게 시간"),
    /** Asked once per allowance; the key is [Unknowns.covers] of that allowance. */
    COVERS("covers:", "가산을 대신하는 수당"),
    MONTHLY_HOURS(Unknowns.MONTHLY_HOURS, "월 소정근로시간"),
}

/** [PAYS-01] What in the profile or the schedule makes a question apply. */
enum class SetupFact(val label: String) {
    EVERY_WORKPLACE("모든 사업장"),
    SHIFT_20H("20시간 넘는 근무"),
    SECURITY_WORK("경비·시설 직군"),
    SHIFT_OVER_8H("8시간 넘는 근무"),
    WEEK_OVER_40H("주 40시간 넘는 주"),
    SHIFT_WORK("교대·주말 근무"),
    UNDER_FIVE("5인 미만"),
    DAYS_OFF("쉬는 날"),
    LONG_BREAK("휴게 1시간 이상"),
    NIGHT_ALLOWANCE("야간수당"),
    FIXED_OVERTIME("고정 연장수당"),
    MONTHLY_PAY("월급제"),
    ANNUAL_PAY("연봉"),
}

/** [PAYS-01] A question that applies, the facts that made it apply and, for [SetupQuestion.COVERS], the allowance. */
data class PaySetupQuestion(
    val question: SetupQuestion,
    /** The 확인 필요 key a 모르겠어요 answer stores. */
    val key: String,
    val facts: List<SetupFact>,
    val itemId: String? = null,
)

/**
 * [PAYS-01] Which optional pay questions apply to a profile being set up and its schedule, so setup only asks
 * what can change this person's pay. Data only: the app words the questions.
 */
object PaySetupQuestions {
    private const val GUARD_SHIFT = 20 * 60

    /**
     * The questions for [draft] with [shiftTypes] and the repeating [pattern] (null when none is set yet), in
     * the order to ask them. A shift type counts when the pattern uses it, or every type with times when there
     * is no pattern.
     */
    fun applicable(draft: PayProfile, shiftTypes: Collection<ShiftType>, pattern: WorkPattern?): List<PaySetupQuestion> {
        val byId = shiftTypes.associateBy { it.id }
        val steps: List<Long?>? = when (pattern) {
            is WorkPattern.Rotation -> pattern.steps
            is WorkPattern.Weekly -> pattern.steps
            null -> null
        }
        fun worked(id: Long?): ShiftType? = id?.let(byId::get)?.takeIf { it.hasTimes }
        val used = (steps?.mapNotNull(::worked) ?: shiftTypes.filter { it.hasTimes }).distinct()
        val week = steps?.map { worked(it)?.workMinutes ?: 0 }
        // Any 7 days in a row of the cycle can be a week, as a rotation slides across the calendar.
        val weekOver40 = week != null && week.indices.any { i -> (0 until 7).sumOf { week[(i + it) % week.size] } > 40 * 60 }
        val daysOff = steps?.any { worked(it) == null } ?: true
        val shiftWork = when (pattern) {
            is WorkPattern.Rotation -> pattern.steps.indices.any { i ->
                worked(pattern.steps[i]) != null && (pattern.steps.size % 7 != 0 || pattern.anchorDate.plusDays(i.toLong()).dayOfWeek.weekend)
            }
            is WorkPattern.Weekly -> listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY).any { worked(pattern.byDay[it]) != null }
            null -> true
        }
        return buildList {
            add(PaySetupQuestion(SetupQuestion.FIVE_OR_MORE, Unknowns.FIVE_OR_MORE, listOf(SetupFact.EVERY_WORKPLACE)))
            listOfNotNull(
                SetupFact.SHIFT_20H.takeIf { used.any { it.durationMinutes >= GUARD_SHIFT } },
                SetupFact.SECURITY_WORK.takeIf { draft.occupation == Occupation.SECURITY },
            ).ifNotEmpty { add(PaySetupQuestion(SetupQuestion.SUPERVISORY, Unknowns.SUPERVISORY, it)) }
            listOfNotNull(
                SetupFact.SHIFT_OVER_8H.takeIf { used.any { it.workMinutes > 8 * 60 } },
                SetupFact.WEEK_OVER_40H.takeIf { weekOver40 },
            ).ifNotEmpty { add(PaySetupQuestion(SetupQuestion.SCHEDULE_IS_REGULAR, Unknowns.SCHEDULE_IS_REGULAR, it)) }
            when {
                // Under 5 the question is whether the company gives 공휴일 at all (holidaysUnderFive).
                !draft.fiveOrMoreEmployees -> add(PaySetupQuestion(SetupQuestion.PUBLIC_HOLIDAYS, Unknowns.PUBLIC_HOLIDAYS, listOf(SetupFact.UNDER_FIVE)))
                shiftWork -> add(PaySetupQuestion(SetupQuestion.PUBLIC_HOLIDAYS, Unknowns.PUBLIC_HOLIDAYS, listOf(SetupFact.SHIFT_WORK)))
            }
            if (daysOff) add(PaySetupQuestion(SetupQuestion.REST_DAY_WORK, Unknowns.REST_DAY_WORK, listOf(SetupFact.DAYS_OFF)))
            if (used.any { it.breakMinutes >= 60 }) add(PaySetupQuestion(SetupQuestion.BREAK_PAY, Unknowns.BREAK_PAY, listOf(SetupFact.LONG_BREAK)))
            for (item in draft.items) {
                val fact = when {
                    item.isFixedOvertime -> SetupFact.FIXED_OVERTIME
                    item.isNightAllowance -> SetupFact.NIGHT_ALLOWANCE
                    else -> continue
                }
                add(PaySetupQuestion(SetupQuestion.COVERS, Unknowns.covers(item.id), listOf(fact), item.id))
            }
            if (draft.wageType == WageType.MONTHLY) add(PaySetupQuestion(SetupQuestion.MONTHLY_HOURS, Unknowns.MONTHLY_HOURS,
                listOfNotNull(SetupFact.MONTHLY_PAY, SetupFact.ANNUAL_PAY.takeIf { draft.annual != null })))
        }
    }

    private val DayOfWeek.weekend: Boolean get() = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

    private inline fun <T> List<T>.ifNotEmpty(block: (List<T>) -> Unit) {
        if (isNotEmpty()) block(this)
    }
}
