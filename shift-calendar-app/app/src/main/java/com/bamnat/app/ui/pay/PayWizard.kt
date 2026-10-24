package com.bamnat.app.ui.pay

import com.bamnat.app.ui.components.labelWidth
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.core.pay.AnnualPay
import com.bamnat.core.pay.BreakPay
import com.bamnat.core.pay.BulkEditPlanner
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.EditScope
import com.bamnat.core.pay.FlexPeriod
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.OrdinaryGuess
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayPresets
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayRange
import com.bamnat.core.pay.PayslipRead
import com.bamnat.core.pay.SlipRole
import com.bamnat.core.pay.PaySetupQuestion
import com.bamnat.core.pay.PaySetupQuestions
import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.SetupFact
import com.bamnat.core.pay.SetupQuestion
import com.bamnat.core.pay.StandardHours
import com.bamnat.core.pay.UnknownEffect
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.PatternTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import com.bamnat.core.time.Today

/** [PAYS-01] How the pay is stated. 연봉 is worked out to a monthly 기본급 the engine runs on. */
internal enum class Wage(val title: String, val subtitle: String, val type: WageType) {
    MONTHLY("월급", "매달 정해진 기본급을 받아요", WageType.MONTHLY),
    HOURLY("시급", "일한 시간만큼 받아요", WageType.HOURLY),
    DAILY("일당", "하루 일하면 정해진 금액을 받아요", WageType.DAILY),
    ANNUAL("연봉", "계약서에 연봉으로 적혀 있어요. 월 기본급으로 바꿔 계산해요", WageType.MONTHLY);

    companion object {
        fun of(type: WageType): Wage = when (type) {
            WageType.MONTHLY -> MONTHLY
            WageType.HOURLY -> HOURLY
            WageType.DAILY -> DAILY
        }
    }
}

/** One allowance row; [original] is set when it came from the saved profile. */
private class ItemRow(
    val id: String,
    val name: String,
    val cadence: Cadence,
    val ordinary: Boolean,
    val hint: String,
    /** The preset's usual amount, shown only as an example. */
    val suggested: Long,
    checked: Boolean,
    amount: Long?,
    val original: PayItem? = null,
    val fixedOvertimeMinutes: Int = 0,
) {
    var checked by mutableStateOf(checked)
    /** null is 금액 모름: left out of the total and listed with how many times it came up. */
    var amount by mutableStateOf(amount)
    /** An amount the 1.11 setup guessed and the user has not looked at since, still 추정. */
    var guessed by mutableStateOf(false)
}

private const val ANNUAL_OT = "annual-ot"
private const val ANNUAL_BONUS = "annual-bonus"
private const val ANNUAL_OTHER = "annual-other"

private const val WAGE = "wage"
private const val AMOUNT = "amount"
private const val ITEMS = "items"
private const val SUMMARY = "summary"

/** The keys the questions store, for telling them from amounts in the 확인 필요 set. */
private val QUESTION_KEYS = setOf(
    Unknowns.FIVE_OR_MORE, Unknowns.SUPERVISORY, Unknowns.SCHEDULE_IS_REGULAR, Unknowns.PUBLIC_HOLIDAYS,
    Unknowns.REST_DAY_WORK, Unknowns.BREAK_PAY, Unknowns.MONTHLY_HOURS,
)

private fun isQuestionKey(key: String) = key in QUESTION_KEYS || key.startsWith(Unknowns.covers(""))

/**
 * [PAYS-01] Everything the setup has gathered. [raw] builds the pay setup at any step, so the preview
 * follows every answer; [build] adds the 확인 필요 answers on top.
 */
private class PaySetupState(val initial: PayProfile?, val thisMonth: YearMonth, suggested: WageType?) {
    private val initialBase = initial?.base?.on(thisMonth)?.value

    var wage by mutableStateOf(
        when {
            initial?.annual != null -> Wage.ANNUAL
            initial != null -> Wage.of(initial.wageType)
            else -> suggested?.let(Wage::of) ?: Wage.MONTHLY
        },
    )
    var wageChosen by mutableStateOf(initial != null)
    var amount by mutableStateOf(initialBase?.takeIf { it > 0 })
    var rough by mutableStateOf(initial != null && Unknowns.BASE in initial.estimatedFields)
    /** A new setup starts a year back, so last month's payslip can be checked against it. */
    var since by mutableStateOf(if (initial != null) thisMonth else thisMonth.minusMonths(12))
    var dailyHours by mutableIntStateOf(initial?.dailyHours ?: 8 * 60)

    var yearly by mutableStateOf(initial?.annual?.yearly)
    var split13 by mutableStateOf(initial?.annual?.split == 13)
    var annualOt by mutableStateOf(initial?.annual?.fixedOvertimeMonthly?.takeIf { it > 0 })
    var annualOtHours by mutableIntStateOf(initial?.items?.firstOrNull { it.id == ANNUAL_OT }?.fixedOvertimeMinutes?.div(60) ?: 0)
    var annualBonus by mutableStateOf(initial?.annual?.regularBonusYearly?.takeIf { it > 0 })
    var annualBonusMonths by mutableStateOf(initial?.items?.firstOrNull { it.id == ANNUAL_BONUS }?.months?.takeIf { it.isNotEmpty() } ?: setOf(6, 12))
    var annualOther by mutableStateOf(initial?.annual?.otherMonthly?.takeIf { it > 0 })

    var occupation by mutableStateOf(initial?.occupation)
    val rows = mutableStateListOf<ItemRow>()
    val keptBonuses = initial?.items?.filter { it.cadence == Cadence.BONUS && !it.id.startsWith("annual-") }.orEmpty()
    var bonusAnnual by mutableStateOf<Int?>(null)
    var bonusMonths by mutableStateOf(setOf(2, 4, 6, 8, 10, 12))
    var bonusOrdinary by mutableStateOf(true)
    private val bonusId = "bonus-" + System.currentTimeMillis()  // real clock: an id, not a time

    var fiveOrMore by mutableStateOf(initial?.fiveOrMoreEmployees ?: true)
    var supervisory by mutableStateOf(initial?.rules?.supervisoryApproved ?: false)
    var scheduleRegular by mutableStateOf(initial?.scheduleIsRegular ?: false)
    var flexPeriod by mutableStateOf(initial?.flexPeriod)
    var publicHolidaysPaid by mutableStateOf(initial?.rules?.publicHolidaysPaid ?: true)
    var holidaysUnderFive by mutableStateOf(initial?.rules?.holidaysUnderFive ?: false)
    var restDayWork by mutableStateOf(initial?.rules?.restDayWork ?: RestDayWork.LAW)
    var breakPay by mutableStateOf(initial?.rules?.breakPay ?: BreakPay.UNPAID)
    val covers = mutableStateMapOf<String, Set<LineKind>?>().apply { initial?.items?.forEach { put(it.id, it.covers) } }
    var monthlyHours by mutableIntStateOf(initial?.monthlyHours ?: StandardHours.FULL_TIME)
    /** Questions answered 모르겠어요: calculated with the value the app assumes, marked 확인 필요. */
    var unknown by mutableStateOf(initial?.estimatedFields?.filter(::isQuestionKey)?.toSet().orEmpty())
    /** Questions with an answer; a setup run again starts with every earlier answer in. */
    var answered by mutableStateOf(if (initial != null) QUESTION_KEYS + initial.items.map { Unknowns.covers(it.id) } - unknown else emptySet())

    init {
        initial?.let { p ->
            p.items.filter { it.cadence != Cadence.BONUS && !it.id.startsWith("annual-") }.forEach {
                val unknownAmount = Unknowns.item(it.id) in p.estimatedFields
                rows += ItemRow(it.id, it.name, it.cadence, it.ordinary, "", 0, true,
                    if (unknownAmount) null else it.amount.on(thisMonth).value, original = it, fixedOvertimeMinutes = it.fixedOvertimeMinutes)
                    .also { r -> r.guessed = it.id in p.estimatedFields }
            }
        }
    }

    /** Fills the usual allowances of [o]; the ones already entered stay as they are. */
    fun choose(o: Occupation) {
        occupation = o
        val preset = PayPresets.of(o)
        rows.removeAll { it.original == null && !it.id.startsWith("custom-") && it.amount == null }
        preset.items.filter { p -> rows.none { it.id == p.id } }.forEach {
            rows += ItemRow(it.id, it.name, it.cadence, it.ordinary, it.hint, it.suggestedAmount, checked = it.checked, amount = null)
        }
    }

    fun addRow(name: String, amount: Long?, cadence: Cadence, ordinary: Boolean, fixedOvertimeMinutes: Int) {
        rows += ItemRow("custom-" + System.currentTimeMillis(), name.trim().ifEmpty { "수당" }, cadence, ordinary, "", 0, true, amount,  // real clock: an id, not a time
            fixedOvertimeMinutes = fixedOvertimeMinutes)
    }

    /**
     * [PAYS-04][PAYS-05] Fills the pay and allowances from a payslip read from a photo or text, and returns what it
     * filled in words. 연장·야간·휴일 and 잔업·특근·대근 pay are left out: the app works them out from the calendar.
     */
    fun fillFrom(read: PayslipRead): String {
        val hourly = read.amount(SlipRole.HOURLY)
        val base = read.amount(SlipRole.BASE)
        when {
            hourly != null && hourly < 100_000 -> { wage = Wage.HOURLY; amount = hourly }
            base != null -> { wage = Wage.MONTHLY; amount = base }
        }
        if (hourly != null || base != null) { wageChosen = true; rough = false }
        read.month?.let { since = it }
        fun norm(n: String) = n.filterNot { it.isWhitespace() }.lowercase()
        val added = mutableListOf<String>()
        read.lines.filter { it.role == SlipRole.ALLOWANCE && it.amount != null }.forEach { line ->
            val existing = rows.firstOrNull { norm(it.name) == norm(line.name) }
            if (existing != null) {
                existing.checked = true
                existing.amount = line.amount
                existing.guessed = false
            } else {
                val fixedOt = Regex("고정.*(연장|시간외|OT)", RegexOption.IGNORE_CASE).containsMatchIn(line.name)
                val welfare = Regex("식대|식비|교통|차량|복지|통신|자녀|가족|학자").containsMatchIn(line.name)
                val id = "custom-" + System.nanoTime()
                rows += ItemRow(id, line.name.take(20), Cadence.MONTHLY, ordinary = !fixedOt && (OrdinaryGuess.of(line.name, Cadence.MONTHLY) ?: !welfare), hint = "명세서에서", suggested = 0,
                    checked = true, amount = line.amount)
                if (fixedOt) covers[id] = setOf(LineKind.OVERTIME)
            }
            added += line.name
        }
        // [PAYX-46] An allowance whose amount could not be read is still a row, left 금액 모름 and unticked, for the
        // user to confirm rather than for the app to guess.
        val unread = read.lines.filter { it.role == SlipRole.ALLOWANCE && it.amount == null && rows.none { r -> norm(r.name) == norm(it.name) } }
        unread.forEach { line ->
            rows += ItemRow("custom-" + System.nanoTime(), line.name.take(20), Cadence.MONTHLY, ordinary = OrdinaryGuess.of(line.name, Cadence.MONTHLY) ?: true,
                hint = "명세서에서 · 금액을 못 읽었어요", suggested = 0, checked = false, amount = null)
        }
        val skipped = read.lines.filter { it.role in setOf(SlipRole.OVERTIME, SlipRole.NIGHT, SlipRole.HOLIDAY, SlipRole.WEEKLY_HOLIDAY, SlipRole.EXTRA_WORK) }
        val bonuses = read.lines.filter { it.role == SlipRole.BONUS }
        return buildList {
            when {
                hourly != null && hourly < 100_000 -> add("시급 ${won(hourly)}")
                base != null -> add("기본급 ${won(base)}")
            }
            if (added.isNotEmpty()) add("수당 ${added.size}개(${added.joinToString(", ")})")
        }.joinToString(", ").ifEmpty { "읽은 금액이 없어요" }.let { "명세서에서 $it 채웠어요." } +
            (if (skipped.isNotEmpty()) " ${skipped.joinToString("·") { it.name }}은 달력 근무로 계산해요." else "") +
            (if (bonuses.isNotEmpty()) " 상여는 수당 단계에서 받는 달과 함께 넣어 주세요." else "") +
            (if (unread.isNotEmpty()) " ${unread.joinToString("·") { it.name }}은 금액을 못 읽어 꺼 두었어요. 맞으면 켜고 금액을 넣어 주세요." else "") +
            " 끝나면 ‘명세서로 맞춰 보기’로 한 번에 확인할 수 있어요."
    }

    fun annualPay(): AnnualPay? = yearly?.takeIf { it > 0 }?.let {
        AnnualPay(it, if (split13) 13 else 12, annualOt ?: 0, annualBonus ?: 0, annualOther ?: 0)
    }

    /** The base the setup has so far, ready to go on. */
    val amountReady: Boolean get() = if (wage == Wage.ANNUAL) (yearly ?: 0) > 0 else (amount ?: 0) > 0

    fun answer(key: String, change: () -> Unit) {
        change()
        unknown = unknown - key
        answered = answered + key
    }

    fun dontKnow(key: String) {
        unknown = unknown + key
        answered = answered - key
    }

    /** The pay setup from the answers so far, without the 확인 필요 marks of the questions. */
    fun raw(): PayProfile {
        val type = wage.type
        val annual = if (wage == Wage.ANNUAL) annualPay() else null
        val baseValue = if (wage == Wage.ANNUAL) annual?.breakdown()?.monthlyBase ?: 0 else amount ?: 0
        val from = since.atDay(1)
        val base = when {
            initial == null || initial.wageType != type -> ValueTimeline(listOf(DatedValue(from, baseValue)))
            baseValue == initialBase -> initial.base
            else -> BulkEditPlanner.apply(initial.base, baseValue, EditScope.FromMonth(since))
        }
        val items = rows.filter { it.checked }.map { r ->
            val o = r.original
            val value = r.amount
            val timeline = when {
                value == null -> ValueTimeline()
                o == null -> ValueTimeline(listOf(DatedValue(from, value)))
                o.amount.on(thisMonth).value == value -> o.amount
                else -> BulkEditPlanner.apply(o.amount, value, EditScope.FromMonth(thisMonth))
            }
            (o ?: PayItem(r.id, r.name, timeline, r.cadence, ordinary = r.ordinary, fixedOvertimeMinutes = r.fixedOvertimeMinutes))
                .copy(amount = timeline, covers = if (r.id in covers) covers[r.id] else o?.covers)
        } + keptBonuses + listOfNotNull(
            bonusAnnual?.takeIf { type == WageType.MONTHLY && bonusMonths.isNotEmpty() }?.let { a ->
                PayItem(bonusId, "정기상여", ValueTimeline(listOf(DatedValue(from, 0))), Cadence.BONUS,
                    ordinary = bonusOrdinary, months = bonusMonths, percentOfBase = a / 100.0 / bonusMonths.size)
            },
        ) + annualItems(annual, from)
        val ids = items.map { it.id }.toSet()
        // What the setup decides leaves the 확인 필요 set; anything else in it (휴일 급여 방식, keys of a newer version) stays.
        val kept = initial?.estimatedFields.orEmpty().filterNot { key ->
            isQuestionKey(key) || key == Unknowns.BASE || key.startsWith(Unknowns.item("")) || initial?.items?.any { it.id == key } == true
        }
        val marks = buildSet {
            addAll(kept)
            if (rough && wage != Wage.ANNUAL) add(Unknowns.BASE)
            rows.filter { it.checked && it.amount == null }.forEach { add(Unknowns.item(it.id)) }
            rows.filter { it.checked && it.amount != null && it.guessed && it.original?.amount?.on(thisMonth)?.value == it.amount }.forEach { add(it.id) }
            // [PAYX-23] 13분할: whether the 13th share is 퇴직금 or a 정기 상여 stays 확인 필요 until answered.
            if (annual?.divisor == 13 && items.none { it.id == Unknowns.ANNUAL_13_ITEM }) add(Unknowns.ANNUAL_13)
        }.filter { k -> !k.startsWith(Unknowns.item("")) || k.removePrefix(Unknowns.item("")) in ids }
            .filter { k -> k != Unknowns.ANNUAL_13 || annual?.divisor == 13 }.toSet()
        val occupationNow = occupation ?: initial?.occupation ?: Occupation.CUSTOM
        return (initial ?: PayProfile(occupationNow, type, base)).copy(
            occupation = occupationNow,
            wageType = type,
            base = base,
            items = items,
            fiveOrMoreEmployees = fiveOrMore,
            scheduleIsRegular = scheduleRegular,
            flexPeriod = flexPeriod.takeIf { scheduleRegular },
            monthlyHours = monthlyHours,
            dailyHours = dailyHours,
            annual = annual,
            dailyLimitMinutes = if (initial != null) initial.dailyLimitMinutes else PayPresets.of(occupationNow).dailyLimitMinutes,
            rules = (initial?.rules ?: CompanyRules()).copy(
                supervisoryApproved = supervisory, publicHolidaysPaid = publicHolidaysPaid, holidaysUnderFive = holidaysUnderFive,
                restDayWork = restDayWork, breakPay = breakPay,
            ),
            estimatedFields = marks,
        ).let { if (initial == null) PayPresets.firstSetup(it) else it } // [JOB-01]
    }

    /** [raw] with each question answered 모르겠어요 set to what the app assumes and marked 확인 필요. */
    fun build(raw: PayProfile, questions: List<PaySetupQuestion>, rosterHours: Int?): PayProfile =
        questions.map { it.key }.filter { it in unknown }.fold(raw) { p, key -> Unknowns.markUnknown(p, key, rosterHours) }

    /** The allowances a 연봉 states inside it, kept as their own lines. */
    private fun annualItems(annual: AnnualPay?, from: LocalDate): List<PayItem> {
        annual ?: return emptyList()
        fun money(v: Long) = ValueTimeline(listOf(DatedValue(from, v)))
        return listOfNotNull(
            annual.fixedOvertimeMonthly.takeIf { it > 0 }?.let { v ->
                // Hours not known still stand in for 연장 only, the way a 고정 연장수당 does.
                PayItem(ANNUAL_OT, "고정 연장수당", money(v), Cadence.MONTHLY, fixedOvertimeMinutes = annualOtHours * 60,
                    covers = if (annualOtHours > 0) covers[ANNUAL_OT] else setOf(LineKind.OVERTIME))
            },
            annual.regularBonusYearly.takeIf { it > 0 && annualBonusMonths.isNotEmpty() }?.let { v ->
                PayItem(ANNUAL_BONUS, "정기상여", money(Math.round(v.toDouble() / annualBonusMonths.size)), Cadence.BONUS,
                    ordinary = true, months = annualBonusMonths)
            },
            annual.otherMonthly.takeIf { it > 0 }?.let { v -> PayItem(ANNUAL_OTHER, "기타 고정 수당", money(v), Cadence.MONTHLY, ordinary = true) },
        )
    }
}

/**
 * [PAYS-01][PAYX-01] The pay setup as questions: how the pay comes, how much and since when, the
 * allowances, then only the questions that can change this person's month, each answerable with
 * 모르겠어요. The month so far is shown under every step. Running it again keeps the history of values
 * that did not change (raises, earlier months).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayWizard(
    initial: PayProfile?,
    onCancel: (() -> Unit)?,
    onDone: (PayProfile) -> Unit,
    startStep: Int = 0,
    /** [EASY-06] Told when the setup is done whether a payslip filled it, so the profile marks what it read as 추정. */
    filledFromSlip: (Boolean) -> Unit = {},
    /** Screenshots only: open the step scrolled to the bottom. */
    scrollToEnd: Boolean = false,
    /** [PAYS-01] The user's shifts and order, for the questions that apply; null asks the usual ones. */
    snapshot: ScheduleSnapshot? = null,
    /** [PAYS-01] The month so far, worked out off the main thread; null shows none. */
    preview: (suspend (PayProfile) -> PayRange)? = null,
    /** How work like the user's is usually paid, chosen to start with (시급 for 아르바이트). */
    suggestedWage: WageType? = null,
    /** [SETUP-14] The job the user's way of working points to (아르바이트 → PART_TIME), its items ticked to start with; a new setup only. */
    suggestedOccupation: Occupation? = null,
    /** Screenshots only: the step to open by its key ("items", a question's key, "summary"), when it is asked. */
    startAt: String? = null,
) {
    val today = remember { Today.date() }
    val s = remember {
        PaySetupState(initial, YearMonth.from(today), suggestedWage).also { st -> if (initial == null) suggestedOccupation?.let(st::choose) }
    }
    val scope = rememberCoroutineScope()
    val rosterHours = remember(snapshot) {
        snapshot?.let { snap ->
            StandardHours.averageWeekly(today.withDayOfMonth(1), resolve = snap.resolver::resolve).takeIf { it > 0 }?.let(StandardHours::monthly)
        }
    }
    val pattern = remember(snapshot) { snapshot?.let { PatternTimeline.current(it.patterns, today) } }
    val raw = s.raw()
    val questions = remember(raw, snapshot) { PaySetupQuestions.applicable(raw, snapshot?.shiftTypes.orEmpty(), pattern) }
    val draft = remember(raw, questions, s.unknown, rosterHours) { s.build(raw, questions, rosterHours) }
    val steps = listOf(WAGE, AMOUNT, ITEMS) + questions.map { it.key } + SUMMARY
    var step by remember { mutableStateOf(startAt?.takeIf { it in steps } ?: steps[startStep.coerceIn(0, steps.lastIndex)]) }
    val index = steps.indexOf(step).takeIf { it >= 0 } ?: steps.lastIndex
    var advancing by remember { mutableStateOf(false) }
    val latestSteps by rememberUpdatedState(steps)

    fun go(to: String) { step = to }
    fun next() { latestSteps.let { list -> list.getOrNull(list.indexOf(step) + 1)?.let(::go) } }
    // A single tap answers and moves on, after a beat so the choice is seen.
    fun answerAndGo(change: () -> Unit) {
        change()
        if (advancing) return
        advancing = true
        val from = step
        scope.launch {
            delay(260)
            advancing = false
            if (step == from) next()
        }
    }

    // [PAYS-01] The month so far follows each answer; it is worked out on a background thread.
    var range by remember { mutableStateOf<PayRange?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(draft) {
        val p = preview ?: return@LaunchedEffect
        if (!s.amountReady) return@LaunchedEffect
        busy = true
        delay(150)
        try {
            range = p(draft)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A preview that fails leaves the last one; the setup itself goes on.
        }
        busy = false
    }

    // [SETUP-05] What the current question can do to the month: the draft with only this answer left open,
    // so "이 답에 따라 최대 4만 원" shows before the user picks, and 모르겠어요 is safe when it moves nothing.
    var stake by remember { mutableStateOf<UnknownEffect?>(null) }
    LaunchedEffect(step, draft) {
        val p = preview ?: return@LaunchedEffect
        val q = questions.firstOrNull { it.key == step } ?: return@LaunchedEffect
        if (!s.amountReady) return@LaunchedEffect
        if (stake?.key != q.key) stake = null
        delay(200)
        val probe = Unknowns.markUnknown(draft.copy(estimatedFields = draft.estimatedFields.filterNot(::isQuestionKey).toSet()), q.key, rosterHours)
        try {
            stake = p(probe).effects.firstOrNull { it.key == q.key }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No figure is better than a wrong one; the question still works.
        }
    }

    // [PAYS-04][PAYS-05] 명세서로 한 번에 채우기, and what it filled.
    var slipFill by remember { mutableStateOf(false) }
    var slipNote by remember { mutableStateOf<String?>(null) }
    if (slipFill) PayslipFillDialog("명세서로 한 번에 채우기", onDismiss = { slipFill = false }) { read ->
        slipNote = s.fillFrom(read)
        slipFill = false
        go(AMOUNT)
    }

    val question = questions.firstOrNull { it.key == step }
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(progress = { (index + 1) / steps.size.toFloat() }, modifier = Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState(if (scrollToEnd) Int.MAX_VALUE else 0))
                .padding(horizontal = 20.dp, vertical = 16.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WizardProgress(
                part = when (step) { WAGE, AMOUNT, ITEMS -> 0; SUMMARY -> 2; else -> 1 },
                left = (steps.lastIndex - index - 1).coerceAtLeast(0),
                last = step == SUMMARY,
            )
            AnimatedContent(
                step,
                transitionSpec = {
                    val forward = steps.indexOf(targetState) >= steps.indexOf(initialState)
                    (slideInHorizontally { w -> if (forward) w / 6 else -w / 6 } + fadeIn()) togetherWith fadeOut()
                },
                label = "pay-setup",
            ) { key ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when {
                        key == WAGE -> WageStep(s, onSlip = { slipFill = true }) { w -> answerAndGo { s.wage = w; s.wageChosen = true } }
                        key == AMOUNT -> AmountStep(s, today, slipNote)
                        key == ITEMS -> ItemsStep(s)
                        key == SUMMARY -> SummaryStep(s, draft, questions, range, snapshot) { go(it) }
                        else -> questions.firstOrNull { it.key == key }?.let { q ->
                            QuestionStep(q, s, snapshot, rosterHours, stake = stake?.takeIf { it.key == q.key }, showStake = preview != null && s.amountReady,
                                onAnswer = ::answerAndGo, onUnknown = { answerAndGo { s.dontKnow(q.key) } })
                        }
                    }
                }
            }
        }
        if (preview != null && step != WAGE && step != SUMMARY && s.amountReady) {
            PreviewBar(range, busy, draft)
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    index > 0 -> OutlinedButton(onClick = { go(steps[index - 1]) }) { Text("이전") }
                    onCancel != null -> TextButton(onClick = onCancel) { Text("취소") }
                }
                Spacer(Modifier.weight(1f))
                val last = step == SUMMARY
                val ready = when {
                    step == WAGE -> s.wageChosen
                    step == AMOUNT -> s.amountReady
                    question != null -> question.key in s.answered || question.key in s.unknown
                    else -> true
                }
                Button(onClick = { if (last) { filledFromSlip(slipNote != null); onDone(draft) } else next() }, enabled = ready) {
                    Text(if (last) "계산 시작" else "다음")
                }
            }
        }
    }
}

@Composable
internal fun StepTitle(title: String, body: String? = null) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * [SETUP-03] Where the setup is, as three parts that stay put while answers add or drop questions
 * (급여 › 회사 방식 › 확인), and how many steps are left, instead of a "3 / 10" whose total kept changing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WizardProgress(part: Int, left: Int, last: Boolean, parts: List<String> = listOf("급여", "회사 방식", "확인")) {
    val leftText = when {
        last -> "마지막 확인이에요"
        left == 0 -> "다음은 확인이에요"
        else -> "${left}단계 남았어요"
    }
    FlowRow(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${parts.size}부분 중 ${part + 1}번째, ${parts[part]}. $leftText"
        },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            parts.forEachIndexed { i, name ->
                if (i > 0) Text("  ›  ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.outline)
                val color = when {
                    i == part -> MaterialTheme.colorScheme.primary
                    i < part -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                if (i < part) {
                    Icon(Icons.Filled.Check, null, tint = color, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                }
                Text(name, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (i == part) FontWeight.Bold else FontWeight.Normal), color = color)
            }
        }
        Text(leftText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * [SETUP-05] How much one question moves this month, worked out from the user's own roster, so a question
 * that changes nothing can be skipped without worry and one that matters says so.
 */
@Composable
private fun StakeNote(effect: UnknownEffect?) {
    val text = when {
        effect == null -> "이 답이 이번 달에 얼마나 영향을 주는지 계산하고 있어요"
        !effect.moves -> "이번 달 근무표로는 어떤 답이든 금액이 같아요. 잘 모르면 ‘모르겠어요’로 넘어가도 돼요."
        effect.down < 0 && effect.up > 0 -> "답에 따라 이번 달이 −${manwon(-effect.down)}~+${manwon(effect.up)} 원 달라져요. 모르면 ‘모르겠어요’로 두고 명세서로 맞춰 볼 때 정해도 돼요."
        else -> "답에 따라 이번 달이 최대 ${manwon(maxOf(-effect.down, effect.up))} 원 달라져요. 모르면 ‘모르겠어요’로 두고 명세서로 맞춰 볼 때 정해도 돼요."
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
            if (effect == null) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(if (effect.moves) Icons.Filled.Payments else Icons.Filled.CheckCircle, null,
                tint = if (effect.moves) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun WageStep(s: PaySetupState, onSlip: () -> Unit, onPick: (Wage) -> Unit) {
    // [PAYS-04] The easiest way first: a payslip photo fills the pay and the allowances in one go.
    Surface(
        onClick = onSlip,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhotoCamera, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("명세서로 한 번에 채우기", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("가장 쉬워요. 명세서 사진이나 캡처 한 장으로 기본급과 수당을 채워요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
    StepTitle("급여를 어떻게 받나요?", "계약서나 급여명세서에 적힌 대로 골라 주세요. 나중에 언제든 바꿀 수 있어요.")
    Wage.entries.forEach { w -> ChoiceCard(w.title, w.subtitle, s.wageChosen && s.wage == w) { onPick(w) } }
    // Out of scope, said plainly [PAY-07].
    Text("공무원·소방·경찰 수당은 아직 계산하지 않아요.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AmountStep(s: PaySetupState, today: LocalDate, slipNote: String?) {
    val rates = remember(today) { RateTable.on(today) }
    slipNote?.let { note ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(14.dp))
        }
    }
    when (s.wage) {
        Wage.MONTHLY -> StepTitle("기본급이 얼마예요?", "급여명세서의 ‘기본급’만 넣어 주세요. 수당은 다음에 따로 넣어요.")
        Wage.HOURLY -> StepTitle("시급이 얼마예요?", "계약서의 시급을 넣어 주세요. ${today.year}년 최저시급은 ${won(rates.minimumHourlyWage)}이에요.")
        Wage.DAILY -> StepTitle("일당이 얼마예요?", "하루 일하고 받는 금액을 넣어 주세요.")
        Wage.ANNUAL -> StepTitle("연봉이 얼마예요?", "계약서의 1년 총액을 넣어 주세요. 월 기본급으로 바꿔서 계산해요.")
    }
    if (s.wage == Wage.ANNUAL) {
        AnnualFields(s)
    } else {
        MoneyField(label = s.wage.type.baseName(), value = s.amount, onChange = { s.amount = it }, modifier = Modifier.fillMaxWidth())
        FilterChip(selected = s.rough, onClick = { s.rough = !s.rough }, label = { Text("대략이에요") })
        if (s.rough) Hint("괜찮아요. ‘확인 필요’로 두고, 명세서로 맞춰 볼 때 정확한 금액으로 고쳐요.")
    }
    if (s.wage == Wage.DAILY) {
        Label("하루 몇 시간 일한 값이에요?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(4, 5, 6, 7, 8).forEach { h ->
                FilterChip(selected = s.dailyHours == h * 60, onClick = { s.dailyHours = h * 60 }, label = { Text("${h}시간") })
            }
        }
        val hourly = s.amount?.let { Math.round(it * 60.0 / s.dailyHours) }
        Hint("일당 ÷ ${PayCalculator.hours(s.dailyHours)}이 시급이에요" + (hourly?.let { " (${won(it)})" } ?: "") +
            ". 이보다 오래 일한 시간과 연장·야간 가산은 이 시급으로 더해요.")
        if (hourly != null && hourly < rates.minimumHourlyWage) {
            Text("시급으로 치면 최저시급(${won(rates.minimumHourlyWage)})보다 적어요. 금액과 시간을 확인해 주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    if (s.wage == Wage.HOURLY) s.amount?.takeIf { it in 1 until rates.minimumHourlyWage }?.let {
        Text("최저시급보다 적어요. 계약서를 다시 확인해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    // [SETUP-04] The question people paid by the hour ask most, answered before they go looking.
    if (s.wage == Wage.HOURLY || s.wage == Wage.DAILY) {
        Hint("주휴수당은 따로 넣지 않아도 돼요. 근무표를 보고 앱이 더해요.")
        TermLink("주휴수당")
    }
    Label("언제부터 이 금액이에요?")
    MonthStepper(s.since, onChange = { s.since = it })
    Hint("이 달부터 계산해요. 지난달 명세서와 맞춰 보려면 그 전 달로 두세요. 그다음은 인상 규칙(나중에 설정)으로 계산해요.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnualFields(s: PaySetupState) {
    MoneyField("연봉 (1년 총액)", s.yearly, { s.yearly = it }, Modifier.fillMaxWidth())
    RuleToggle("13으로 나눠 받아요", "13번째 몫을 퇴직금으로 떼어 두는 계약이에요. 연말에 상여로 주는 몫이면 통상임금이라 결과 화면 ‘확인 필요’에서 골라요. 모르면 끈 채로 두세요", s.split13) { s.split13 = it }
    Label("연봉 안에 따로 나오는 돈이 있나요? (있으면)")
    MoneyField("매달 고정 연장수당", s.annualOt, { s.annualOt = it }, Modifier.fillMaxWidth(), placeholder = "없으면 비워 두세요")
    if ((s.annualOt ?: 0) > 0) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0, 10, 20, 30, 40, 52).forEach { h ->
                FilterChip(selected = s.annualOtHours == h, onClick = { s.annualOtHours = h },
                    label = { Text(if (h == 0) "몇 시간분인지 몰라요" else "${h}시간분") })
            }
        }
    }
    MoneyField("1년 정기 상여 합계", s.annualBonus, { s.annualBonus = it }, Modifier.fillMaxWidth(), placeholder = "없으면 비워 두세요")
    if ((s.annualBonus ?: 0) > 0) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("짝수 달" to setOf(2, 4, 6, 8, 10, 12), "분기" to setOf(1, 4, 7, 10), "6·12월" to setOf(6, 12), "매달" to (1..12).toSet())
                .forEach { (label, set) -> FilterChip(selected = s.annualBonusMonths == set, onClick = { s.annualBonusMonths = set }, label = { Text(label) }) }
        }
    }
    MoneyField("매달 다른 고정 수당 (식대 등)", s.annualOther, { s.annualOther = it }, Modifier.fillMaxWidth(), placeholder = "없으면 비워 두세요")
    s.annualPay()?.breakdown()?.let { b ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("월 기본급 ${won(b.monthlyBase)}", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                b.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                b.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemsStep(s: PaySetupState) {
    var adding by remember { mutableStateOf(false) }
    StepTitle("받는 수당이 있나요?", "직군을 고르면 자주 받는 수당을 채워 드려요. 금액을 모르면 ‘금액 모름’으로 두세요. 합계에서 빼고 따로 알려 드려요.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PayPresets.all.filter { it.occupation != Occupation.CUSTOM }.forEach { preset ->
            FilterChip(selected = s.occupation == preset.occupation, onClick = { s.choose(preset.occupation) },
                label = { Text(preset.occupation.label) })
        }
    }
    // The presets file's notes for 알바 (주휴수당 is calculated now) and 경비 (감시·단속 is asked as its own question)
    // are out of date and the file is signed, so they are left out here.
    PayPresets.of(s.occupation ?: Occupation.CUSTOM).note
        .takeIf { it.isNotEmpty() && s.occupation != Occupation.PART_TIME && s.occupation != Occupation.SECURITY }?.let { Hint(it) }
    s.rows.forEach { row -> ItemRowEditor(row) }
    com.bamnat.app.ui.components.AddButton("다른 수당 넣기", onClick = { adding = true })
    // [SETUP-03][PAYS-07] Nothing here has to be complete: the rest of the company's way has its own place later.
    Hint("빠진 수당이 있어도 괜찮아요. 기숙사비 같은 차감이나 회사만의 수당은 설정을 마친 뒤 ‘우리 회사 항목 만들기’로 언제든 넣어요.")
    if (s.wage == Wage.ANNUAL) {
        s.annualPay()?.let { a ->
            listOfNotNull(
                a.fixedOvertimeMonthly.takeIf { it > 0 }?.let { "고정 연장수당 월 ${won(it)}" },
                a.regularBonusYearly.takeIf { it > 0 }?.let { "정기상여 연 ${won(it)}" },
                a.otherMonthly.takeIf { it > 0 }?.let { "기타 고정 수당 월 ${won(it)}" },
            ).takeIf { it.isNotEmpty() }?.let { Hint("연봉에서 나온 수당도 함께 계산해요: ${it.joinToString(" · ")}") }
        }
    } else if (s.wage == Wage.MONTHLY) {
        BonusPicker(
            kept = s.keptBonuses.size, annual = s.bonusAnnual, months = s.bonusMonths, ordinary = s.bonusOrdinary,
            onAnnual = { s.bonusAnnual = it }, onMonths = { s.bonusMonths = it }, onOrdinary = { s.bonusOrdinary = it },
        )
    }
    if (adding) AddItemDialog(onDismiss = { adding = false }, allowUnknownAmount = true) { name, amount, cadence, ordinary, fixedOt ->
        s.addRow(name, amount, cadence, ordinary, fixedOt)
        adding = false
    }
}

/** One answer card; [detail] says what it means for the month. */
@Composable
internal fun AnswerCard(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/** A question's answers as [AnswerCard]s and the line 모르겠어요 adds. */
private class Answer(val title: String, val detail: String?, val selected: Boolean, val pick: () -> Unit)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionStep(
    q: PaySetupQuestion,
    s: PaySetupState,
    snapshot: ScheduleSnapshot?,
    rosterHours: Int?,
    stake: UnknownEffect?,
    showStake: Boolean,
    onAnswer: (() -> Unit) -> Unit,
    onUnknown: () -> Unit,
) {
    val key = q.key
    val known = key in s.answered
    fun <T> pick(value: T, current: T, set: (T) -> Unit) = Answer("", null, known && value == current) { onAnswer { s.answer(key) { set(value) } } }
    val types = snapshot?.shiftTypes.orEmpty().filter { it.hasTimes }
    val item = q.itemId?.let { id -> s.rows.firstOrNull { it.id == id } }
    var unknownText = ""
    var title = ""
    var body = ""
    val answers = mutableListOf<Answer>()
    fun add(t: String, d: String?, a: Answer) { answers += Answer(t, d, a.selected, a.pick) }
    when (q.question) {
        SetupQuestion.FIVE_OR_MORE -> {
            title = "회사 직원이 5명 이상인가요?"
            body = "함께 일하는 사람 수예요. 5명 미만이면 연장·야간·휴일 가산이 법으로 정해져 있지 않아요(근로기준법 시행령 제7조 별표 1)."
            add("네, 5명 이상이에요", null, pick(true, s.fiveOrMore) { s.fiveOrMore = it })
            add("아니요, 5명 미만이에요", "회사가 따로 주는 가산은 나중에 회사 규칙에서 넣어요", pick(false, s.fiveOrMore) { s.fiveOrMore = it })
            unknownText = "모르면 5명 이상으로 계산해요"
        }
        SetupQuestion.SUPERVISORY -> {
            title = "감시·단속 근로 승인을 받은 회사인가요?"
            body = "경비·시설처럼 대기가 많은 일은 회사가 고용노동부 승인을 받으면 연장·휴일 가산과 주휴가 없어요. 야간 가산은 그대로 받아요(근로기준법 제63조)."
            add("네, 승인을 받았어요", null, pick(true, s.supervisory) { s.supervisory = it })
            add("아니요, 승인이 없어요", null, pick(false, s.supervisory) { s.supervisory = it })
            unknownText = "모르면 승인이 없는 것으로 계산해요"
        }
        SetupQuestion.SCHEDULE_IS_REGULAR -> {
            val longest = types.maxOfOrNull { it.workMinutes } ?: 0
            title = if (SetupFact.SHIFT_OVER_8H in q.facts && longest > 8 * 60) {
                "근무표대로 ${PayCalculator.hours(longest)} 일하는 날, 8시간 넘는 시간은 연장수당으로 따로 받나요?"
            } else "주 40시간 넘게 일하는 주, 넘는 시간은 연장수당으로 따로 받나요?"
            body = "긴 근무가 원래 근무표인 회사는 그 시간이 기본급에 들어 있다고 보기도 해요. 급여명세서에 연장수당이 매달 찍히는지 보면 알 수 있어요."
            add("네, 연장수당으로 따로 받아요", "법정 기준이에요. 하루 8시간·주 40시간을 넘는 시간은 연장이에요", pick(false, s.scheduleRegular) { s.scheduleRegular = it })
            answers += Answer("아니요, 기본 근무예요", "근무표 시간은 모두 기본 근무로 보고, 잔업으로 적은 시간만 연장이에요. 야간 가산은 그대로예요",
                known && s.scheduleRegular) { s.answer(key) { s.scheduleRegular = true } }
            unknownText = "모르면 법대로 연장으로 계산해요"
        }
        SetupQuestion.PUBLIC_HOLIDAYS -> if (s.fiveOrMore) {
            title = "공휴일에 근무하면 휴일근로로 받나요?"
            body = "5명 이상 회사는 공휴일이 유급휴일이라 그날 일하면 휴일 가산을 받아요. 근무표에서 다른 날과 바꿔 쉬는 회사(휴일 대체)는 평일처럼 계산해요."
            add("네, 휴일근로로 받아요", null, pick(true, s.publicHolidaysPaid) { s.publicHolidaysPaid = it })
            add("아니요, 다른 날로 바꿔 쉬어요", "휴일 대체예요. 공휴일 근무를 평일처럼 계산해요", pick(false, s.publicHolidaysPaid) { s.publicHolidaysPaid = it })
            unknownText = "모르면 휴일근로로 계산해요"
        } else {
            title = "회사가 공휴일을 유급휴일로 주나요?"
            body = "5명 미만 회사는 공휴일이 법으로 정해져 있지 않아 회사마다 달라요."
            add("네, 쉬어도 그날 급여가 나와요", null, pick(true, s.holidaysUnderFive) { s.holidaysUnderFive = it })
            add("아니요, 평일과 같아요", null, pick(false, s.holidaysUnderFive) { s.holidaysUnderFive = it })
            unknownText = "모르면 평일처럼 계산해요"
        }
        SetupQuestion.REST_DAY_WORK -> {
            title = "쉬는 날 나가서 일하면 어떻게 받나요?"
            body = "근무표상 쉬는 날에 일한 경우예요. 법으로는 하루 8시간·주 40시간을 넘는 시간만 연장이에요(근로기준법 제50조·제56조). " +
                "취업규칙에 휴일근로로 준다고 되어 있으면 그쪽을 골라 주세요."
            RestDayWork.entries.forEach { m ->
                add(when (m) {
                    RestDayWork.LAW -> "법대로 받아요"
                    RestDayWork.HOLIDAY -> "휴일근로로 받아요"
                    RestDayWork.OVERTIME -> "연장근로로 받아요"
                }, m.detail, pick(m, s.restDayWork) { s.restDayWork = it })
            }
            unknownText = "모르면 법대로 계산해요"
        }
        SetupQuestion.BREAK_PAY -> {
            val longestBreak = types.maxOfOrNull { it.breakMinutes } ?: 60
            title = "휴게 시간에도 돈이 나오나요?"
            body = "근무표에 휴게 ${PayCalculator.hours(longestBreak)}이 있어요. 법으로 휴게는 일한 시간이 아니라 무급이에요(근로기준법 제54조). " +
                "쉬는 동안에도 자리를 지키며 대기하면 일한 시간으로 봐요."
            add("아니요, 휴게는 빼고 받아요", null, pick(BreakPay.UNPAID, s.breakPay) { s.breakPay = it })
            add("휴게도 돈은 나와요", "일한 시간으로는 치지 않아 연장·야간은 그대로예요", pick(BreakPay.PAID, s.breakPay) { s.breakPay = it })
            add("휴게도 일한 시간으로 쳐요", "대기하는 휴게라면 이쪽이에요. 연장·야간도 그만큼 늘어요", pick(BreakPay.AS_WORK, s.breakPay) { s.breakPay = it })
            unknownText = "모르면 무급으로 계산해요"
        }
        SetupQuestion.COVERS -> {
            val name = item?.name ?: "이 수당"
            val id = q.itemId.orEmpty()
            val now = s.covers[id] ?: if ((item?.fixedOvertimeMinutes ?: 0) > 0 && item?.cadence == Cadence.MONTHLY) setOf(LineKind.OVERTIME) else emptySet()
            fun cover(set: Set<LineKind>) = pick(set, now) { s.covers[id] = it }
            if (SetupFact.FIXED_OVERTIME in q.facts) {
                title = "‘$name’${Fmt.eun(name)} 어떤 가산을 미리 주는 돈인가요?"
                body = "고정 연장수당은 정한 만큼의 가산을 미리 주는 거예요. 실제 가산이 더 많으면 차액을 알려 드려요."
                add("연장만", null, cover(setOf(LineKind.OVERTIME)))
                add("연장·야간", null, cover(setOf(LineKind.OVERTIME, LineKind.NIGHT)))
                add("연장·야간·휴일", null, cover(setOf(LineKind.OVERTIME, LineKind.NIGHT, LineKind.HOLIDAY)))
                unknownText = "모르면 연장만 대신하는 것으로 계산해요"
            } else {
                title = "‘$name’${Fmt.eun(name)} 야간 가산 대신 주는 돈인가요?"
                body = "야간 가산(통상시급의 50%)과 따로 받으면 둘 다 더해요. 대신 주는 돈이면 겹치지 않게 빼요. 명세서에 야간 가산이 따로 찍히는지 보면 알 수 있어요."
                add("아니요, 따로 받아요", null, cover(emptySet()))
                add("네, 야간 가산 대신이에요", null, cover(setOf(LineKind.NIGHT)))
                unknownText = "모르면 따로 받는 것으로 계산해요"
            }
        }
        SetupQuestion.MONTHLY_HOURS -> {
            title = "한 달 소정근로시간(근로 시수)은 몇 시간이에요?"
            body = "통상시급을 구할 때 기본급을 나누는 시간이에요. 근로계약서나 명세서에 적혀 있어요. 주 40시간이면 보통 209시간이에요."
            unknownText = "모르면 " + (rosterHours?.let { "근무표로 계산한 ${it}시간" } ?: "209시간") + "으로 계산해요"
        }
    }
    StepTitle(title, body)
    // [SETUP-04] The one word the question leans on, explained where it is asked.
    val term = when (q.question) {
        SetupQuestion.FIVE_OR_MORE -> "5인 이상"
        SetupQuestion.SUPERVISORY, SetupQuestion.SCHEDULE_IS_REGULAR, SetupQuestion.COVERS -> "가산"
        SetupQuestion.PUBLIC_HOLIDAYS, SetupQuestion.REST_DAY_WORK -> "휴일과 휴무일"
        SetupQuestion.MONTHLY_HOURS -> "소정근로시간"
        SetupQuestion.BREAK_PAY -> null
    }
    q.facts.filter { it != SetupFact.EVERY_WORKPLACE }.takeIf { it.isNotEmpty() }?.let { facts ->
        Text("물어보는 까닭: ${facts.joinToString(" · ") { it.label }}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
    }
    if (term != null) TermLink(term)
    if (showStake) StakeNote(stake)
    if (q.question == SetupQuestion.MONTHLY_HOURS) {
        val chosen = known
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (com.bamnat.core.pay.Unknowns.MONTHLY_HOURS_CHIPS + listOfNotNull(rosterHours)).distinct().sorted().forEach { h ->
                FilterChip(selected = chosen && s.monthlyHours == h, onClick = { onAnswer { s.answer(key) { s.monthlyHours = h } } },
                    label = { Text("${h}시간" + if (h == rosterHours) " (근무표)" else "") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { s.answer(key) { s.monthlyHours = (s.monthlyHours - 1).coerceAtLeast(100) } }) { Text("−") }
            Text("${s.monthlyHours}시간", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
            OutlinedButton(onClick = { s.answer(key) { s.monthlyHours = (s.monthlyHours + 1).coerceAtMost(320) } }) { Text("+") }
        }
    }
    answers.forEach { a -> AnswerCard(a.title, a.detail, a.selected, a.pick) }
    if (q.question == SetupQuestion.SCHEDULE_IS_REGULAR && known && s.scheduleRegular) {
        FlexPicker(s.flexPeriod, onFlex = { s.flexPeriod = it })
    }
    AnswerCard("모르겠어요", "$unknownText. ‘확인 필요’로 표시하고 범위로 보여 드려요", key in s.unknown, onUnknown)
}

@Composable
private fun SummaryStep(
    s: PaySetupState,
    draft: PayProfile,
    questions: List<PaySetupQuestion>,
    range: PayRange?,
    snapshot: ScheduleSnapshot?,
    onEdit: (String) -> Unit,
) {
    StepTitle("이렇게 계산할게요", "고칠 곳이 있으면 눌러 주세요. 계산을 시작한 뒤에도 언제든 바꿀 수 있어요.")
    val base = draft.base.on(s.thisMonth).value
    SummaryRow("급여", "${s.wage.title} · " + if (s.wage == Wage.ANNUAL) "연 ${won(s.yearly ?: 0)} → 기본급 ${won(base)}" else won(s.amount ?: 0),
        check = Unknowns.BASE in draft.estimatedFields) { onEdit(AMOUNT) }
    SummaryRow("시작", "${Fmt.month(s.since)}부터") { onEdit(AMOUNT) }
    val items = draft.items.filter { it.cadence != Cadence.BONUS }
    SummaryRow("수당", items.joinToString(" · ") { i ->
        if (Unknowns.item(i.id) in draft.estimatedFields) "${i.name} 금액 모름" else "${i.name} ${won(i.amount.on(s.thisMonth).value)}"
    }.ifEmpty { "없음" }, check = items.any { Unknowns.item(it.id) in draft.estimatedFields }) { onEdit(ITEMS) }
    draft.items.filter { it.cadence == Cadence.BONUS }.takeIf { it.isNotEmpty() }?.let { bonuses ->
        SummaryRow("상여", bonuses.joinToString(" · ") { b ->
            b.name + (b.percentOfBase?.let { " 연 ${PayCalculator.pct(it * b.yearlyTimes)}" } ?: " ${b.yearlyTimes}번")
        }) { onEdit(ITEMS) }
    }
    questions.forEach { q ->
        SummaryRow(q.label(s), if (q.key in s.unknown) "모르겠어요" else q.answerText(s, draft), check = q.key in s.unknown) { onEdit(q.key) }
    }
    if (range != null) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        PayslipPreview(range, expandedByDefault = true)
    } else if (snapshot != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  이번 달을 계산하고 있어요", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun PaySetupQuestion.label(s: PaySetupState): String =
    if (question == SetupQuestion.COVERS) "‘${s.rows.firstOrNull { it.id == itemId }?.name ?: "수당"}’ 가산 대신" else question.label

private fun PaySetupQuestion.answerText(s: PaySetupState, p: PayProfile): String = when (question) {
    SetupQuestion.FIVE_OR_MORE -> if (p.fiveOrMoreEmployees) "5명 이상" else "5명 미만"
    SetupQuestion.SUPERVISORY -> if (p.rules.supervisoryApproved) "승인 있음" else "승인 없음"
    SetupQuestion.SCHEDULE_IS_REGULAR -> if (p.scheduleIsRegular) "기본 근무" + (p.flexPeriod?.let { " (탄력제 ${it.unit})" } ?: "") else "연장으로 따로"
    SetupQuestion.PUBLIC_HOLIDAYS -> if (p.fiveOrMoreEmployees) (if (p.rules.publicHolidaysPaid) "휴일근로" else "휴일 대체")
        else if (p.rules.holidaysUnderFive) "유급휴일로 줌" else "평일처럼"
    SetupQuestion.REST_DAY_WORK -> p.rules.restDayWork.label
    SetupQuestion.BREAK_PAY -> p.rules.breakPay.label
    SetupQuestion.COVERS -> p.items.firstOrNull { it.id == itemId }?.coveredKinds?.let { kinds ->
        if (kinds.isEmpty()) "따로 받음" else kinds.joinToString("·") { when (it) { LineKind.OVERTIME -> "연장"; LineKind.NIGHT -> "야간"; else -> "휴일" } } + " 대신"
    } ?: ""
    SetupQuestion.MONTHLY_HOURS -> "${p.monthlyHours}시간"
}

@Composable
private fun SummaryRow(label: String, value: String, check: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.labelWidth(112.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (check) { Spacer(Modifier.width(6.dp)); EstimatedChip("확인 필요") }
    }
}

/** [PAYS-01] The month so far as a small payslip: the range, then its lines. */
@Composable
internal fun PayslipPreview(range: PayRange, expandedByDefault: Boolean = false) {
    var open by remember { mutableStateOf(expandedByDefault) }
    val pay = range.base
    val checks = range.effects.size
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${pay.month.monthValue}월 예상 (세전)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(rangeText(range.low, range.high), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            }
            if (checks > 0) EstimatedChip("확인 필요 $checks")
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "접기" else "펼치기")
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                pay.lines.forEach { line ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(line.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(won(line.amount), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                pay.missing.forEach { m ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(m.name + (m.times?.let { " · ${it}회" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("금액 모름", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                if (!range.exact) {
                    Text("지금 답으로는 ${won(range.total)}이에요. ‘모르겠어요’로 둔 답에 따라 ${won(range.low)}~${won(range.high)}까지 달라져요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun PreviewBar(range: PayRange?, busy: Boolean, draft: PayProfile) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            if (range == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text("  이번 달을 계산하고 있어요", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                PayslipPreview(range)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                else if (draft.items.any { Unknowns.item(it.id) in draft.estimatedFields }) {
                    Text("금액 모르는 수당은 합계에서 뺐어요", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    AnswerCard(title, subtitle, selected, onClick)
}

@Composable
private fun ItemRowEditor(row: ItemRow) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).animateContentSize()) {
            Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { row.checked = !row.checked }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge)
                        if (row.checked && row.guessed) { Spacer(Modifier.width(6.dp)); EstimatedChip("추정") }
                    }
                    Text(row.cadence.label + if (row.hint.isNotEmpty()) " · ${row.hint}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.checked) {
                Column(Modifier.padding(start = 48.dp, end = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MoneyField(
                        label = if (row.cadence == Cadence.MONTHLY) "한 달 금액" else "1회 금액",
                        value = row.amount,
                        onChange = { row.amount = it; row.guessed = false },
                        placeholder = if (row.suggested > 0) "예: ${won(row.suggested)}" else "",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = row.amount == null, onClick = { row.amount = null; row.guessed = false }, label = { Text("금액 모름") })
                        if (row.amount == null) Text("  합계에서 빼고 따로 알려 드려요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/**
 * [PAYX-07] 탄력적 근로시간제 단위기간. Only with it may long scheduled days go without 연장, and even
 * then hours beyond 40 a week on average over the period are 연장 (근로기준법 제51조).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlexPicker(
    flex: FlexPeriod?,
    onFlex: (FlexPeriod?) -> Unit,
    /** [PAYX-17] The day a 2주 period starts, as the company set it; null counts from a Monday. */
    start: java.time.LocalDate? = null,
    onStart: ((java.time.LocalDate?) -> Unit)? = null,
) {
    Text("회사에 탄력적 근로시간제가 있나요?", style = MaterialTheme.typography.titleSmall)
    Text("법으로는 이 제도를 둔 회사만 긴 근무를 연장 없이 계산할 수 있어요. 있으면 기간 평균 주 40시간을 넘는 시간만 연장이 되고, " +
        "없거나 모르면 법정 기준과의 차액을 결과 화면에 따로 알려 드려요. 취업규칙이나 노사 합의서에 적혀 있어요.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = flex == null, onClick = { onFlex(null) }, label = { Text("없거나 몰라요") })
        FlexPeriod.entries.forEach { p -> FilterChip(selected = flex == p, onClick = { onFlex(p) }, label = { Text(p.label) }) }
    }
    if (flex == FlexPeriod.TWO_WEEKS) {
        if (onStart != null) com.bamnat.app.ui.work.DateField("2주가 시작하는 날", start, onStart)
        Text("2주 단위는 취업규칙으로 정해요(근로기준법 제51조 제1항). 한 주는 48시간까지예요. " +
            if (onStart == null) "시작하는 날은 회사 규칙에서 넣을 수 있어요." else "정하지 않으면 월요일부터 2주씩 세요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RuleToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** ‹ 2026년 10월 › for choosing a starting month. */
@Composable
internal fun MonthStepper(month: YearMonth, onChange: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { onChange(month.minusMonths(1)) }) { Text("‹") }
        Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp))
        OutlinedButton(onClick = { onChange(month.plusMonths(1)) }) { Text("›") }
    }
}

/** Regular bonus as a yearly total of the base pay [PAY-05], e.g. 상여 800% paid in even months. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BonusPicker(
    kept: Int, annual: Int?, months: Set<Int>, ordinary: Boolean,
    onAnnual: (Int?) -> Unit, onMonths: (Set<Int>) -> Unit, onOrdinary: (Boolean) -> Unit,
) {
    Text("상여금", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    if (kept > 0) {
        Text("이미 넣은 상여금 ${kept}개는 그대로 둬요. 결과 화면에서 고칠 수 있어요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text("1년에 기본급의 몇 %를 상여로 받나요?", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = annual == null, onClick = { onAnnual(null) }, label = { Text("없음") })
        listOf(400, 600, 800, 1000).forEach { a -> FilterChip(selected = annual == a, onClick = { onAnnual(a) }, label = { Text("연 $a%") }) }
    }
    if (annual == null) return
    Text("받는 달", style = MaterialTheme.typography.bodyMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("짝수 달" to setOf(2, 4, 6, 8, 10, 12), "분기 (1·4·7·10월)" to setOf(1, 4, 7, 10), "반기 (6·12월)" to setOf(6, 12),
            "매달" to (1..12).toSet()).forEach { (label, set) ->
            FilterChip(selected = months == set, onClick = { onMonths(set) }, label = { Text(label) })
        }
    }
    Text("한 번에 기본급의 ${PayCalculator.pct(annual / 100.0 / months.size)}씩 ${months.size}번 받아요.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    RuleToggle("통상임금에 포함", "정기적으로 정해진 상여는 재직 조건이 있어도 통상임금이에요(대법원 2024.12.19. 판결). 성과에 따라 달라지면 꺼 주세요.",
        ordinary, onOrdinary)
}
