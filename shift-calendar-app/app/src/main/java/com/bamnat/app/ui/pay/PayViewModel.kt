package com.bamnat.app.ui.pay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.tier.Tier
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.pay.BulkEditPlanner
import com.bamnat.core.pay.Cadence
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** Which value a change is about: the base pay or one allowance. */
sealed interface ValueTarget {
    data object Base : ValueTarget
    data class Item(val id: String) : ValueTarget
}

/** Another way the company might calculate, and what the month would come to [PAYX-05 명세서 대조]. */
data class Alternative(val label: String, val profile: PayProfile, val total: Long)

class PayViewModel(private val container: AppContainer) : ViewModel() {
    val profile: StateFlow<PayProfile?> = container.payStore.profile
    val undoAvailable: StateFlow<PayProfile?> = container.payStore.canUndo
    val tier: StateFlow<Tier> = container.featureGate.tier
    val snapshot: StateFlow<ScheduleSnapshot?> =
        container.scheduleRepository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()
    fun moveMonth(by: Long) { _month.value = _month.value.plusMonths(by) }

    private val calculator = PayCalculator()

    init { container.telemetry.event(DiagEvents.SCREEN, "pay") }

    fun canUse(feature: Feature, tier: Tier) = FeatureGate.allows(tier, feature)

    fun calculate(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): MonthPay =
        calculator.calculate(profile, month, snapshot.resolver::resolve)

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
        container.payStore.save(profile, keepUndo = false)
        container.telemetry.event("pay_setup_done", profile.occupation.name)
    }

    fun reset() = viewModelScope.launch { container.payStore.save(null) }

    fun undo() = viewModelScope.launch { container.payStore.undo() }

    fun update(transform: (PayProfile) -> PayProfile) = viewModelScope.launch {
        val p = profile.value ?: return@launch
        container.payStore.save(transform(p))
    }

    // ---- values over time ----

    fun timelineOf(p: PayProfile, target: ValueTarget): ValueTimeline = when (target) {
        ValueTarget.Base -> p.base
        is ValueTarget.Item -> p.items.first { it.id == target.id }.amount
    }

    private fun PayProfile.withTimeline(target: ValueTarget, t: ValueTimeline): PayProfile = when (target) {
        ValueTarget.Base -> copy(base = t, estimatedFields = estimatedFields - "base")
        is ValueTarget.Item -> copy(
            items = items.map { if (it.id == target.id) it.copy(amount = t) else it },
            estimatedFields = estimatedFields - target.id,
        )
    }

    /** What the change does to the next months, before it is applied [PAYX-03]. */
    fun planEdit(p: PayProfile, snapshot: ScheduleSnapshot, target: ValueTarget, value: Long, scope: EditScope, from: YearMonth): EditPlan =
        BulkEditPlanner.plan(
            timelineOf(p, target), value, scope,
            preview = (0L until 12L).map { from.plusMonths(it) },
            totalFor = { t, m -> calculate(p.withTimeline(target, t), snapshot, m).total },
        )

    fun applyEdit(target: ValueTarget, plan: EditPlan) {
        update { it.withTimeline(target, plan.after) }
        container.telemetry.event("pay_bulk_edit", if (target is ValueTarget.Base) "base" else "item")
    }

    fun setRaise(target: ValueTarget, rule: RaiseRule?) =
        update { it.withTimeline(target, timelineOf(it, target).copy(raise = rule)) }

    fun addItem(name: String, amount: Long, cadence: Cadence, ordinary: Boolean, from: YearMonth) = update { p ->
        val id = "item-" + System.currentTimeMillis()
        p.copy(items = p.items + PayItem(id, name.trim().ifEmpty { "수당" }, ValueTimeline(listOf(DatedValue(from.atDay(1), amount))), cadence, ordinary = ordinary))
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
                // Legal premiums come from the rate table; company multipliers are shown, not applied.
                CandidateKind.MULTIPLIER -> Unit
            }
        }
        p
    }

    // ---- matching the pay slip ----

    /** Company choices that might explain a different total, closest to [received] first. */
    fun alternatives(p: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth, received: Long): List<Alternative> {
        val options = buildList {
            if (p.wageType == WageType.MONTHLY) {
                val other = if (p.holidayPayMode == HolidayPayMode.FULL) HolidayPayMode.PREMIUM_ONLY else HolidayPayMode.FULL
                add("휴일 근무를 ‘${other.label}’으로 계산" to p.copy(holidayPayMode = other))
            }
            add((if (p.dailyLimitMinutes == null) "하루 8시간 넘으면 연장으로 계산" else "하루 8시간 기준 끄기 (탄력적 근로시간제)") to
                p.copy(dailyLimitMinutes = if (p.dailyLimitMinutes == null) 480 else null))
            add((if (p.fiveOrMoreEmployees) "5인 미만 사업장으로 계산 (가산 없음)" else "5인 이상 사업장으로 계산") to
                p.copy(fiveOrMoreEmployees = !p.fiveOrMoreEmployees))
            p.items.filter { !it.ordinary && it.cadence == Cadence.MONTHLY }.forEach { item ->
                add("‘${item.name}’을 통상임금에 포함" to p.copy(items = p.items.map { if (it.id == item.id) it.copy(ordinary = true) else it }))
            }
        }
        container.telemetry.event("pay_reconcile")
        return options.map { (label, alt) -> Alternative(label, alt, calculate(alt, snapshot, month).total) }
            .sortedBy { abs(it.total - received) }
    }
}
