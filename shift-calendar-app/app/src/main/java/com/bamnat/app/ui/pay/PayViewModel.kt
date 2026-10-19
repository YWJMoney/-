package com.bamnat.app.ui.pay

import com.bamnat.app.pay.named

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.tier.Tier
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.pay.BulkEditPlanner
import com.bamnat.core.pay.CalcSnapshot
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CompanyRules
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.PremiumTarget
import com.bamnat.core.pay.CandidateKind
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.EditPlan
import com.bamnat.core.pay.EditScope
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayPresets
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.RuleCandidate
import com.bamnat.core.pay.RuleImport
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.PayRange
import com.bamnat.core.pay.Payslip
import com.bamnat.core.pay.PayslipMatch
import com.bamnat.core.pay.PayslipMatcher
import com.bamnat.core.pay.StandardHours
import com.bamnat.core.pay.Unknowns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** Which value a change is about: the base pay or one allowance. */
sealed interface ValueTarget {
    data object Base : ValueTarget
    data class Item(val id: String) : ValueTarget
}

/**
 * [PAYS-01] One month for the pay screen, worked out on a background thread so the screen never waits
 * on it: the result, the range over the 확인 필요 answers and the figures the cards around it compare.
 */
data class PayView(
    val result: MonthPay,
    /** The month over the 확인 필요 answers; null when nothing is left 확인 필요. */
    val range: PayRange?,
    /** This and the next 11 months, closed ones as they were closed. */
    val year: List<Pair<YearMonth, Long>>,
    /** [EXTRA-05] The month with 쉬는 날 근무 by law, while 1.8's way is kept and not yet compared. */
    val restDayLaw: Long?,
    /** [PAY-06] The month with each 휴일 급여 방식, for 월급제 with holiday work. */
    val holidayModes: List<Pair<HolidayPayMode, Long>>,
)

class PayViewModel(
    private val container: AppContainer,
    /** [MCAL-06] The calendar whose pay this is. */
    val calendarId: String = com.bamnat.app.data.PersonalCalendar.MAIN,
) : ViewModel() {
    private val scope = container.scopeOf(calendarId)
    private val payStore = scope.pay
    val profile: StateFlow<PayProfile?> = payStore.profile
    val undoAvailable: StateFlow<PayProfile?> = payStore.canUndo
    val tier: StateFlow<Tier> = container.featureGate.tier
    val snapshot: StateFlow<ScheduleSnapshot?> =
        scope.schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // [PAYX-15] Opens on the pay month today belongs to: after a 20일 close, 10/25 is November's pay.
    // [NAV-01] Opened while the calendar or 근무·급여 shows another month, pay opens on that month;
    // on this month, on the pay period running now.
    private val _month = MutableStateFlow(container.shownMonth.month.value.takeIf { it != YearMonth.now() }
        ?: payStore.profile.value?.period?.monthOf(java.time.LocalDate.now()) ?: YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()
    fun moveMonth(by: Long) { _month.value = _month.value.plusMonths(by) }

    private val calculator = PayCalculator()
    val settings: StateFlow<com.bamnat.app.data.AppSettings> =
        scope.settings.stateIn(viewModelScope, SharingStarted.Eagerly, scope.currentSettings())

    init { container.telemetry.event(DiagEvents.SCREEN, "pay") }

    fun canUse(feature: Feature, tier: Tier) = FeatureGate.allows(tier, feature)

    val closed: StateFlow<Map<YearMonth, CalcSnapshot>> = payStore.closed

    /** Keeps this month's result as it is now, whatever changes later [PAYX-03]. */
    fun closeMonth(result: MonthPay) = viewModelScope.launch {
        val existing = closed.value[result.month]
        val snap = existing?.recalculated(result, System.currentTimeMillis()) ?: CalcSnapshot.close(result, System.currentTimeMillis())
        payStore.saveClosed(snap, result.month)
        container.telemetry.event("pay_month_close", if (existing == null) "close" else "recalc")
    }

    fun reopenMonth(month: YearMonth) = viewModelScope.launch { payStore.saveClosed(null, month) }

    /** What a month comes to: its closed result when closed, else a fresh calculation. */
    fun totalFor(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): Long =
        closed.value[month]?.total ?: calculate(profile, snapshot, month).total

    fun calculate(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): MonthPay =
        calculator.calculate(profile.named(settings.value.extraKinds), month, snapshot.resolver::resolve)

    /** A sample nurse profile on the user's own schedule, for the free preview and the first-time walkthrough. */
    fun sampleProfile(): PayProfile {
        val preset = PayPresets.of(Occupation.NURSE)
        return PayPresets.profile(
            preset, preset.wageType, preset.baseHint, LocalDate.of(2020, 1, 1),
            preset.items.filter { it.checked }, fiveOrMore = true, holidayMode = HolidayPayMode.FULL,
            estimated = setOf("base"),
        )
    }

    fun saveSetup(profile: PayProfile) = viewModelScope.launch {
        payStore.save(profile, keepUndo = false)
        container.telemetry.event("pay_setup_done", profile.occupation.name)
    }

    fun reset() = viewModelScope.launch { payStore.save(null) }

    fun undo() = viewModelScope.launch { payStore.undo() }

    fun update(transform: (PayProfile) -> PayProfile) = viewModelScope.launch {
        payStore.update(transform)
    }

    // ---- values over time ----

    fun timelineOf(p: PayProfile, target: ValueTarget): ValueTimeline = when (target) {
        ValueTarget.Base -> p.base
        is ValueTarget.Item -> p.items.first { it.id == target.id }.amount
    }

    private fun PayProfile.withTimeline(target: ValueTarget, t: ValueTimeline): PayProfile = when (target) {
        ValueTarget.Base -> copy(base = t, estimatedFields = estimatedFields - Unknowns.BASE)
        is ValueTarget.Item -> copy(
            items = items.map { if (it.id == target.id) it.copy(amount = t) else it },
            estimatedFields = estimatedFields - target.id - Unknowns.item(target.id),
        )
    }

    /** What the change does to the next months, before it is applied [PAYX-03]. */
    fun planEdit(p: PayProfile, snapshot: ScheduleSnapshot, target: ValueTarget, value: Long, scope: EditScope, from: YearMonth): EditPlan =
        BulkEditPlanner.plan(
            timelineOf(p, target), value, scope,
            preview = (0L until 12L).map { from.plusMonths(it) },
            totalFor = { t, m -> calculate(p.withTimeline(target, t), snapshot, m).total },
            isLocked = { it in closed.value },
        )

    fun applyEdit(target: ValueTarget, plan: EditPlan) {
        update { it.withTimeline(target, plan.after) }
        container.telemetry.event("pay_bulk_edit", if (target is ValueTarget.Base) "base" else "item")
    }

    fun setRaise(target: ValueTarget, rule: RaiseRule?) =
        update { it.withTimeline(target, timelineOf(it, target).copy(raise = rule)) }

    /** [PAYS-01] With no [amount] the allowance is 금액 모름: left out of the total and listed until it is known. */
    fun addItem(name: String, amount: Long?, cadence: Cadence, ordinary: Boolean, from: YearMonth, fixedOvertimeMinutes: Int = 0) = update { p ->
        val id = "item-" + System.currentTimeMillis()
        val value = if (amount == null) ValueTimeline() else ValueTimeline(listOf(DatedValue(p.period.start(from), amount)))
        p.copy(items = p.items + PayItem(id, name.trim().ifEmpty { "수당" }, value, cadence,
            ordinary = ordinary, fixedOvertimeMinutes = fixedOvertimeMinutes),
            estimatedFields = if (amount == null) p.estimatedFields + Unknowns.item(id) else p.estimatedFields)
    }

    /** [PAYS-01] The amount of an allowance that was 금액 모름, from [month]'s pay period on. */
    fun setItemAmount(id: String, amount: Long, month: YearMonth) = update { p ->
        val from = p.period.start(month)
        Unknowns.answered(p, Unknowns.item(id)).let { q ->
            q.copy(items = q.items.map { if (it.id == id) it.copy(amount = it.amount.with(DatedValue(from, amount))) else it })
        }
    }

    /** [PAYS-01] A 확인 필요 answer settled: [change] gives the answer (none keeps the assumed one). */
    fun answer(key: String, change: (PayProfile) -> PayProfile = { it }) = update { p -> Unknowns.answered(change(p), key) }

    /** [EXTRA-05] The 1.8 way of paying a day off was shown next to the law's once; it is not shown again. */
    fun restDayCompared() = container.settingsRepository.update { it.copy(restDayCompared = true) }

    /** Changes the company's rules [PAYX-07]; every change can be undone like a value edit. */
    fun updateRules(transform: (CompanyRules) -> CompanyRules) {
        update { it.copy(rules = transform(it.rules)) }
        container.telemetry.event("pay_rules_edit")
    }

    /** [PAY-05] A bonus paid in [months], a percent of 기본급 or a fixed amount. */
    fun addBonus(
        name: String, months: Set<Int>, percentOfBase: Double?, amount: Long, ordinary: Boolean, from: YearMonth,
        festivals: Set<com.bamnat.core.pay.Festival> = emptySet(), festivalMonthBefore: Boolean = false,
    ) = update { p ->
        val id = "bonus-" + System.currentTimeMillis()
        p.copy(items = p.items + PayItem(id, name.trim().ifEmpty { if (festivals.isNotEmpty()) "명절상여" else "상여금" },
            ValueTimeline(listOf(DatedValue(from.atDay(1), amount))),
            Cadence.BONUS, ordinary = ordinary, months = months, percentOfBase = percentOfBase,
            festivals = festivals, festivalMonthBefore = festivalMonthBefore))
    }

    fun removeItem(id: String) = update { p -> p.copy(items = p.items.filterNot { it.id == id }) }

    fun updateItem(id: String, transform: (PayItem) -> PayItem) =
        update { p -> p.copy(items = p.items.map { if (it.id == id) transform(it) else it }) }

    // ---- reading rules from text [PAYX-04] ----

    fun readRules(text: String): List<RuleCandidate> = RuleImport.read(text).also {
        container.telemetry.event("pay_rule_import", "lines=${text.lines().size} found=${it.size}")
    }

    /** Applies the candidates the user ticked, from [from] on. */
    fun applyCandidates(chosen: List<RuleCandidate>, from: YearMonth) = update { start ->
        var p = start
        val since = from.atDay(1)
        for (c in chosen) {
            when (c.kind) {
                CandidateKind.BASE_SALARY, CandidateKind.HOURLY_WAGE -> {
                    val amount = c.amount ?: continue
                    p = p.copy(
                        wageType = if (c.kind == CandidateKind.HOURLY_WAGE) WageType.HOURLY else WageType.MONTHLY,
                        base = BulkEditPlanner.apply(p.base, amount, EditScope.FromMonth(from)),
                        estimatedFields = p.estimatedFields - "base",
                    )
                }
                CandidateKind.ALLOWANCE -> {
                    val amount = c.amount ?: continue
                    val existing = p.items.firstOrNull { it.name.replace(" ", "") == c.name.replace(" ", "") }
                    p = if (existing != null) {
                        p.copy(items = p.items.map {
                            if (it.id == existing.id) it.copy(amount = BulkEditPlanner.apply(it.amount, amount, EditScope.FromMonth(from)),
                                cadence = c.cadence ?: it.cadence) else it
                        }, estimatedFields = p.estimatedFields - existing.id)
                    } else {
                        p.copy(items = p.items + PayItem("item-${System.nanoTime()}", c.name,
                            ValueTimeline(listOf(DatedValue(since, amount))), c.cadence ?: Cadence.MONTHLY))
                    }
                }
                CandidateKind.RAISE -> {
                    val pct = c.percent ?: continue
                    val month = Regex("""(\d{1,2})\s*월""").find(c.sourceLine)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..12 }
                        ?: from.monthValue
                    p = p.copy(base = p.base.copy(raise = RaiseRule(month = month, percent = pct, unit = 1_000)))
                }
                CandidateKind.MULTIPLIER -> {
                    val premium = c.premium ?: continue
                    p = p.copy(rules = when (c.premiumTarget) {
                        PremiumTarget.NIGHT -> p.rules.copy(nightPremium = premium)
                        PremiumTarget.HOLIDAY -> p.rules.copy(holidayPremiumWithin8h = premium)
                        else -> p.rules.copy(overtimePremium = premium)
                    })
                }
                CandidateKind.EXTRA_RULE -> {
                    val kind = c.extraKind ?: continue
                    val rule = c.extraRule ?: continue
                    p = p.copy(rules = p.rules.copy(extra = p.rules.extra + (kind to rule)))
                }
            }
        }
        p
    }

    /** [PAYS-06] Changes read from rules in words, from [from] on; one tap on 되돌리기 brings the old setup back. */
    fun applyRuleChanges(changes: List<com.bamnat.core.pay.RuleChange>, from: YearMonth) {
        update { com.bamnat.core.pay.RuleText.apply(it, changes, from) }
        container.telemetry.event("pay_rule_text", "changes=${changes.size}")
    }

    // ---- off the main thread [PAYS-01][PAYS-02] ----

    /** The month for the pay screen, with the range and the figures the cards compare. */
    suspend fun view(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): PayView = withContext(Dispatchers.Default) {
        val p = profile.named(settings.value.extraKinds)
        val resolve = snapshot.resolver::resolve
        val result = calculator.calculate(p, month, resolve)
        val range = if (Unknowns.of(p).isEmpty()) null else PayRange.of(p, month, resolve, calculator = calculator, rosterHours = rosterHours(p, snapshot, month))
        val closedNow = closed.value
        val year = (0L until 12L).map { month.plusMonths(it) }.map { m ->
            m to (closedNow[m]?.total ?: if (m == month) result.total else calculator.calculate(p, m, resolve, checks = false).total)
        }
        val restDayLaw = if (p.rules.restDayWork == RestDayWork.HOLIDAY && !settings.value.restDayCompared) {
            calculator.calculate(p.copy(rules = p.rules.copy(restDayWork = RestDayWork.LAW)), month, resolve, checks = false).total
        } else null
        val holidayModes = if (p.wageType == WageType.MONTHLY && result.lines.any { it.kind == LineKind.HOLIDAY }) {
            HolidayPayMode.entries.map { m ->
                m to if (m == p.holidayPayMode) result.total else calculator.calculate(p.copy(holidayPayMode = m), month, resolve, checks = false).total
            }
        } else emptyList()
        PayView(result, range, year, restDayLaw, holidayModes)
    }

    /** One month on a background thread, for editors that show the total as it changes. */
    suspend fun calculateOff(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): MonthPay =
        withContext(Dispatchers.Default) { calculate(profile, snapshot, month) }

    /** [PAYS-01] The month a setup being answered comes to, as a range over its 모르겠어요 answers. */
    suspend fun preview(draft: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): PayRange = withContext(Dispatchers.Default) {
        val p = draft.named(settings.value.extraKinds)
        PayRange.of(p, month, snapshot.resolver::resolve, calculator = calculator, rosterHours = rosterHours(p, snapshot, month))
    }

    /** The roster's 월 소정근로시간, for a 월 소정근로시간 left 모르겠어요; null when the calendar has no work. */
    private fun rosterHours(p: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): Int? =
        if (Unknowns.MONTHLY_HOURS !in p.estimatedFields) null
        else StandardHours.averageWeekly(p.period.start(month), resolve = snapshot.resolver::resolve).takeIf { it > 0 }?.let(StandardHours::monthly)

    /** [PAYS-02] The settings closest to [payslip]; nothing is saved until [applyMatch]. */
    suspend fun match(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth, payslip: Payslip): PayslipMatch {
        val match = withContext(Dispatchers.Default) {
            PayslipMatcher.match(profile.named(settings.value.extraKinds), month, snapshot.resolver::resolve, payslip, calculator = calculator)
        }
        container.telemetry.event("pay_payslip_match", if (match.matched) "matched" else "gap")
        return match
    }

    // ---- [PAYS-07] the company's own items ----

    /** What [made] does to [month] on top of [profile]: the amount (negative when it takes off) and how often it came up. */
    internal suspend fun effectOf(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth, made: MadeItem): ItemEffect =
        withContext(Dispatchers.Default) {
            val p = profile.named(settings.value.extraKinds)
            val resolve = snapshot.resolver::resolve
            val with = when (made) {
                is MadeItem.Item -> p.copy(items = p.items.filterNot { it.id == made.item.id } + made.item)
                is MadeItem.Rule -> p.copy(customRules = p.customRules.filterNot { it.id == made.rule.id } + made.rule)
            }
            val before = calculator.calculate(p, month, resolve, checks = false)
            val after = calculator.calculate(with, month, resolve, checks = false)
            val times = when (made) {
                is MadeItem.Item -> if (made.item.cadence == Cadence.MONTHLY) 1 else after.itemTimes[made.item.id] ?: 0
                is MadeItem.Rule -> if (made.rule.pay.monthly) 1 else after.days.count { d -> d.parts.any { it.label == made.rule.name } }
            }
            ItemEffect(after.total - before.total, times)
        }

    /** [PAYS-07] Keeps an item the user made; a rule made before keeps its place in the list. */
    internal fun saveMade(made: MadeItem) = update { p ->
        when (made) {
            is MadeItem.Item -> p.copy(items = p.items.filterNot { it.id == made.item.id } + made.item)
            is MadeItem.Rule -> {
                val i = p.customRules.indexOfFirst { it.id == made.rule.id }
                p.copy(customRules = if (i >= 0) p.customRules.toMutableList().also { it[i] = made.rule }
                else (p.customRules + made.rule).take(com.bamnat.core.pay.CustomRule.MAX))
            }
        }
    }

    fun setCustomRuleOn(id: String, on: Boolean) = update { p -> p.copy(customRules = p.customRules.map { if (it.id == id) it.copy(on = on) else it }) }

    fun removeCustomRule(id: String) = update { p -> p.copy(customRules = p.customRules.filterNot { it.id == id }) }

    /** [PAYS-02] Keeps the matched settings and the check; one tap on 되돌리기 brings the old ones back. */
    fun applyMatch(match: PayslipMatch) = update { p ->
        val v = match.verifiedProfile(System.currentTimeMillis())
        v.copy(rules = v.rules.copy(extraNames = p.rules.extraNames))
    }
}
