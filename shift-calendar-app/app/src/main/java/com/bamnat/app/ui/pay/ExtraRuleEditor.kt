package com.bamnat.app.ui.pay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.bamnat.app.ui.components.CountStepper
import com.bamnat.app.ui.components.StepperRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.calendar.TimeBox
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayUnit
import com.bamnat.core.pay.Premium
import com.bamnat.core.pay.PremiumScope
import java.time.LocalTime
import kotlin.math.abs

/** [EXTRA-08] 통상시급 and 기본급 this month, so the editor can say what an hour of the work comes to. */
data class UnitMoney(val hourly: Long, val monthlyBase: Long, val baseLabel: String)

/** [EXTRA-08] The hours a premium is paid on, in words: "이 일 8시간 넘는 시간", "19~23시". */
internal fun scopeText(s: PremiumScope, nightLabel: String): String = when (s) {
    PremiumScope.All -> "이 일 모든 시간"
    is PremiumScope.Over -> "이 일 ${PayCalculator.hours(s.minutes)} 넘는 시간"
    PremiumScope.LegalOvertime -> "하루 8시간·주 40시간 넘는 시간"
    PremiumScope.Night -> "야간 $nightLabel"
    is PremiumScope.Window -> "${hm(s.from)}~${hm(s.to)}시"
    PremiumScope.Holiday -> "공휴일·주휴일에 일한 시간"
    PremiumScope.RestDay -> "쉬는 날 일한 시간"
}

private fun hm(t: LocalTime) = if (t.minute == 0) "%02d".format(t.hour) else "%02d:%02d".format(t.hour, t.minute)

/** What a premium row says under its name. */
private fun premiumText(p: Premium, nightLabel: String): String = when (p.key) {
    Premium.NIGHT -> "$nightLabel 일한 시간 · 시간대는 회사 규칙에서 바꿔요"
    Premium.HOLIDAY -> "공휴일·주휴일에 일한 시간"
    Premium.REST -> "쉬는 날 일한 시간 · 법으로는 가산이 없어요"
    else -> scopeText(p.scope, nightLabel) + if (p.offDaysOnly) " · 쉬는 날·휴일만" else ""
}

/** Rates people are told in: 0.5 가산 = 50%. */
private val RATES = listOf(0.25, 0.5, 0.75, 1.0, 1.5, 2.0)


/** [rule] with [p] in place of the premium of its key, or added: the four always listed first, in order. */
private fun ExtraRule.with(p: Premium): ExtraRule {
    val i = premiums.indexOfFirst { it.key == p.key }
    val list = if (i >= 0) premiums.toMutableList().also { it[i] = p } else premiums + p
    return copy(premiums = Premium.BUILT_IN.mapNotNull { k -> list.firstOrNull { it.key == k } } + list.filter { it.custom })
}

private fun ExtraRule.without(key: String): ExtraRule = copy(premiums = premiums.filterNot { it.key == key })

private fun ExtraRule.nextCustomKey(): String = (1..99).map { "c$it" }.first { k -> premiums.none { it.key == k } }

/**
 * What decides the pay of a rule paid by the hour and its premiums, so two rules that pay the same
 * compare equal: numbers of an unused way and the rate of an unticked box do not count.
 */
private fun ExtraRule.payKey(): List<Any?> = listOf(
    basis,
    unit,
    percentOfBase.takeIf { unit == PayUnit.BASE_SHARE },
    unitMinutes.takeIf { unit == PayUnit.BASE_SHARE },
    amount.takeIf { unit == PayUnit.PER_HOUR || unit == PayUnit.PER_TIME },
    coverMinutes.takeIf { unit == PayUnit.PER_TIME },
    minMinutes,
    Premium.BUILT_IN.map { k -> premium(k)?.takeIf { it.on } ?: Premium.builtIn(k, 0.0) },
    premiums.filter { it.custom },
)

/**
 * [EXTRA-03, EXTRA-08] How the company pays one kind of work, as boxes to tick: what an hour is paid
 * at, then each premium with its rate (연장, 야간, 휴일, 휴무일, and the company's own), then a
 * minimum and an amount each time. It opens on what the law pays ([start]); any change makes it the
 * company's way, and changing it back, or one tap, returns to 법대로.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtraRuleEditor(
    rule: ExtraRule,
    /** The way shown while [rule] is 법대로 and started from: [ExtraRule.lawUnit] for this work. */
    start: ExtraRule,
    /** What 법대로 comes to for this kind, in a line. */
    legalText: String,
    nightLabel: String,
    money: UnitMoney?,
    fiveOrMore: Boolean,
    ownTitle: String = "회사 방식으로 계산해요",
    resetLabel: String = "법대로 되돌리기",
    /**
     * True for a kind: 법대로 with an amount each time is still 법대로. False for one shift of a kind
     * [PAYX-11], where anything but [start] itself is a way of its own.
     */
    keepsBonus: Boolean = true,
    onChange: (ExtraRule) -> Unit,
) {
    var rateFor by rememberSaveable { mutableStateOf<String?>(null) }
    var customFor by rememberSaveable { mutableStateOf<String?>(null) }
    val legacy = rule.basis != ExtraBasis.LEGAL && rule.basis != ExtraBasis.UNIT
    val atLaw = rule.basis == ExtraBasis.LEGAL
    val shown = if (rule.basis == ExtraBasis.UNIT) rule else start.copy(bonus = if (keepsBonus) rule.bonus else start.bonus)
    val reset: () -> Unit = { onChange(if (keepsBonus) ExtraRule(bonus = rule.bonus) else ExtraRule()) }
    fun set(next: ExtraRule) = onChange(
        when {
            next.payKey() != start.payKey() -> next
            keepsBonus -> ExtraRule(bonus = next.bonus)
            next.bonus == start.bonus -> ExtraRule()
            else -> next
        },
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (atLaw) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            legacy -> "예전 방식으로 계산해요"
                            atLaw -> "법대로 계산해요"
                            else -> ownTitle
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(if (atLaw) legalText else rule.describe(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!atLaw) TextButton(onClick = reset) { Text(resetLabel) }
            }
        }
        if (legacy) {
            // [PAYX-21] Hour blocks counted from this piece's start, or from the day's first work (the shift included).
            if (rule.basis == ExtraBasis.TIERED) {
                Toggle("구간을 그날 첫 근무부터 세기", "끄면 이 근무를 시작한 때부터 8시간·4시간 구간을 세요. 켜면 그날 앞선 근무 시간까지 이어서 세요",
                    rule.tiersFromDayStart) { onChange(rule.copy(tiersFromDayStart = it)) }
            }
            Hint("이 방식대로 계속 계산해요. 연장·야간 같은 가산을 하나씩 고르려면 새로 정해 주세요.")
            OutlinedButton(onClick = { onChange(start.copy(bonus = rule.bonus, minMinutes = rule.minMinutes)) }, modifier = Modifier.padding(top = 4.dp)) {
                Text("가산 하나씩 고르기")
            }
            return@Column
        }

        Label("1시간에 얼마를 받나요?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PayUnit.entries.forEach { u ->
                FilterChip(
                    selected = shown.unit == u,
                    // A payment each time has its own hours (한 번이 덮는 시간), not a minimum.
                    onClick = { if (shown.unit != u) set(shown.copy(unit = u, minMinutes = if (u == PayUnit.PER_TIME) 0 else shown.minMinutes)) },
                    label = { Text(u.label) },
                )
            }
        }
        UnitNumbers(shown, money, ::set)

        Label("가산 · 체크한 것만 더해요")
        for (key in Premium.BUILT_IN) {
            val p = shown.premium(key) ?: Premium.builtIn(key)
            PremiumRow(p, premiumText(p, nightLabel), onToggle = { set(shown.with(p.copy(on = it))) }, onRate = { rateFor = key })
            if (key == Premium.OVERTIME && p.on) OvertimeScope(p.scope) { set(shown.with(p.copy(scope = it))) }
        }
        val customs = shown.premiums.filter { it.custom }
        for (p in customs) {
            PremiumRow(p, premiumText(p, nightLabel), onToggle = { set(shown.with(p.copy(on = it))) }, onRate = { rateFor = p.key }) {
                IconButton(onClick = { customFor = p.key }) { Icon(Icons.Outlined.Edit, "${p.name} 고치기") }
            }
        }
        if (customs.size < Premium.MAX_CUSTOM) {
            TextButton(onClick = { customFor = NEW }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("가산 직접 추가")
            }
        } else {
            Hint("직접 추가한 가산은 ${Premium.MAX_CUSTOM}개까지예요.")
        }
        Hint(
            if (fiveOrMore) "예: 대근 19~23시 25% 더, 쉬는 날 근무 전부 50% 더처럼 회사만의 가산을 이름과 시간으로 넣어요."
            else "5인 미만 사업장은 법정 가산이 없어요. 회사가 주는 가산만 체크해 주세요.",
        )

        if (shown.unit != PayUnit.PER_TIME) {
            Label("최소 인정 시간")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 4 * 60, 8 * 60).forEach { m ->
                    FilterChip(selected = shown.minMinutes == m, onClick = { set(shown.copy(minMinutes = m)) },
                        label = { Text(if (m == 0) "일한 만큼" else "최소 ${PayCalculator.hours(m)}") })
                }
            }
        }
        Spacer(Modifier.padding(top = 4.dp))
        MoneyField("1회 수당 (따로 더 받는 돈)", shown.bonus.takeIf { it > 0 }, { set(shown.copy(bonus = it ?: 0)) }, placeholder = "없으면 비워 두세요")
        Hint("예: 대근 수당 30,000원. 위 계산에 더해 한 번마다 받아요.")
    }

    // A premium taken away meanwhile (an undo) has no rate to change.
    rateFor?.let { key -> shown.premium(key) ?: Premium.builtIn(key).takeIf { key in Premium.BUILT_IN } }?.let { p ->
        RateDialog(p.name, p.rate, onDismiss = { rateFor = null }) { v ->
            rateFor = null
            // Choosing a rate means it is paid.
            set(shown.with(p.copy(rate = v, on = true)))
        }
    }
    customFor?.let { key ->
        val existing = shown.premiums.firstOrNull { it.key == key && it.custom }
        CustomPremiumDialog(
            initial = existing,
            nightLabel = nightLabel,
            onDismiss = { customFor = null },
            onDelete = existing?.let { e -> { customFor = null; set(shown.without(e.key)) } },
        ) { p ->
            customFor = null
            set(shown.with(p.copy(key = existing?.key ?: shown.nextCustomKey())))
        }
    }
}

private const val NEW = "new"

/** The numbers of the chosen hour's pay, under the choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnitNumbers(r: ExtraRule, money: UnitMoney?, set: (ExtraRule) -> Unit) {
    when (r.unit) {
        PayUnit.ORDINARY -> Hint("기본급과 통상임금에 드는 수당으로 정한 시급이에요" + (money?.let { ". 지금 ${Fmt.won(it.hourly)}" } ?: "") + ".")
        PayUnit.BASE_SHARE -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0.05, 0.1, 0.15, 0.2).forEach { p ->
                    FilterChip(selected = abs(r.percentOfBase - p) < 1e-9, onClick = { set(r.copy(percentOfBase = p)) },
                        label = { Text("기본급 ${ExtraRule.percent(p)}") })
                }
            }
            // In steps of 0.5%, kept on the step so 10% stays exactly 10%.
            StepperRow("직접") {
                CountStepper(Math.round(r.percentOfBase * 200).toInt(), { set(r.copy(percentOfBase = it / 200.0)) }, name = "기본급 비율",
                    range = 1..200, format = { ExtraRule.percent(it / 200.0) })
            }
            StepperRow("기준 시간", Modifier.padding(top = 4.dp)) {
                CountStepper((r.unitMinutes / 60).coerceIn(1, 24), { set(r.copy(unitMinutes = it * 60)) }, name = "기준 시간",
                    range = 1..24, format = { PayCalculator.hours(it * 60) })
            }
            val perHour = money?.let { Math.round(it.monthlyBase * r.percentOfBase * 60.0 / r.unitMinutes.coerceAtLeast(1)) }
            Hint("${PayCalculator.hours(r.unitMinutes)} 일하면 기본급의 ${ExtraRule.percent(r.percentOfBase)}예요" +
                (perHour?.let { ". 1시간에 ${Fmt.won(it)}" } ?: "") + ". 가산은 이 단가에 붙어요.")
        }
        PayUnit.PER_HOUR -> {
            MoneyField("1시간에 받는 금액", r.amount.takeIf { it > 0 }, { set(r.copy(amount = it ?: 0)) })
            Hint("가산은 이 금액에 붙어요." + (money?.let { " 통상시급은 ${Fmt.won(it.hourly)}이에요." } ?: ""))
        }
        PayUnit.PER_TIME -> {
            MoneyField("한 번에 받는 금액", r.amount.takeIf { it > 0 }, { set(r.copy(amount = it ?: 0)) })
            Label("한 번이 덮는 시간")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = r.coverMinutes == null, onClick = { set(r.copy(coverMinutes = null)) }, label = { Text("시간 상관없이") })
                listOf(4 * 60, 8 * 60, 12 * 60).forEach { m ->
                    FilterChip(selected = r.coverMinutes == m, onClick = { set(r.copy(coverMinutes = m)) },
                        label = { Text("${PayCalculator.hours(m)}까지") })
                }
            }
            Hint((r.coverMinutes?.let { "${PayCalculator.hours(it)} 넘게 일하면 넘는 시간은 통상시급으로 더 받아요." } ?: "몇 시간을 일해도 한 번 금액만 받아요.") +
                " 가산은 통상시급에 붙어요.")
        }
    }
}

/** One premium: the box ticks it, the chip shows and changes its rate. */
@Composable
private fun PremiumRow(p: Premium, subtitle: String, onToggle: (Boolean) -> Unit, onRate: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .toggleable(value = p.on, role = Role.Checkbox, onValueChange = onToggle)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = p.on, onCheckedChange = null, modifier = Modifier.padding(12.dp).size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilterChip(
            selected = p.on,
            onClick = onRate,
            label = { Text(ExtraRule.percent(p.rate)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, "${p.name} 비율 바꾸기", Modifier.size(18.dp)) },
            modifier = Modifier.padding(start = 6.dp),
        )
        trailing?.invoke()
    }
}

/** What 연장 counts: the law's 8 and 40 hours, or the hours of this work beyond a number. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OvertimeScope(scope: PremiumScope, onScope: (PremiumScope) -> Unit) {
    val over = (scope as? PremiumScope.Over)?.minutes
    val shown = listOf(4 * 60, 8 * 60, 12 * 60).let { if (over != null && over !in it) (it + over).sorted() else it }
    FlowRow(
        Modifier.padding(start = 48.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Short words: the line above says in full what the choice counts.
        FilterChip(selected = scope == PremiumScope.LegalOvertime, onClick = { onScope(PremiumScope.LegalOvertime) }, label = { Text("법대로") })
        shown.forEach { m ->
            FilterChip(selected = over == m, onClick = { onScope(PremiumScope.Over(m)) }, label = { Text("${PayCalculator.hours(m)} 넘게") })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RateChips(rate: Double, onRate: (Double) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RATES.forEach { p -> FilterChip(selected = abs(rate - p) < 1e-9, onClick = { onRate(p) }, label = { Text(ExtraRule.percent(p)) }) }
    }
    // In steps of 5%, kept on the step.
    StepperRow("직접") {
        CountStepper(Math.round(rate * 20).toInt(), { onRate(it / 20.0) }, name = "가산율",
            range = 1..Math.round(Premium.MAX_RATE * 20).toInt(), format = { ExtraRule.percent(it / 20.0) })
    }
}

@Composable
private fun RateDialog(name: String, rate: Double, onDismiss: () -> Unit, onPick: (Double) -> Unit) {
    var value by rememberSaveable { mutableDoubleStateOf(rate.coerceIn(0.05, Premium.MAX_RATE)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name 얼마인가요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("1시간 단가에 이만큼 더 받아요. 0.5 가산이면 50%, 1.5배예요.")
                RateChips(value) { value = it }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(value) }) { Text("확인") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * [EXTRA-08] A premium of the company's own: its name, rate and hours (모든 시간, 몇 시간 넘는
 * 시간, 정한 시각 사이, 야간), and whether only a day off gets it. Kept through a fold.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomPremiumDialog(
    initial: Premium?,
    nightLabel: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (Premium) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var rate by rememberSaveable { mutableDoubleStateOf(initial?.rate?.takeIf { it > 0 } ?: 0.5) }
    var kind by rememberSaveable {
        mutableStateOf(
            when (initial?.scope) {
                is PremiumScope.Over -> "over"
                is PremiumScope.Window -> "window"
                PremiumScope.Night -> "night"
                PremiumScope.LegalOvertime -> "law"
                PremiumScope.Holiday -> "holiday"
                PremiumScope.RestDay -> "rest"
                else -> "all"
            },
        )
    }
    var over by rememberSaveable { mutableIntStateOf((initial?.scope as? PremiumScope.Over)?.minutes ?: 8 * 60) }
    var from by rememberSaveable { mutableIntStateOf((initial?.scope as? PremiumScope.Window)?.from?.let { it.hour * 60 + it.minute } ?: 19 * 60) }
    var to by rememberSaveable { mutableIntStateOf((initial?.scope as? PremiumScope.Window)?.to?.let { it.hour * 60 + it.minute } ?: 23 * 60) }
    var offDays by rememberSaveable { mutableStateOf(initial?.offDaysOnly ?: false) }
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    fun time(m: Int): LocalTime = LocalTime.of(m / 60, m % 60)
    val scope: PremiumScope = when (kind) {
        "over" -> PremiumScope.Over(over)
        "window" -> PremiumScope.Window(time(from), time(to))
        "night" -> PremiumScope.Night
        "law" -> PremiumScope.LegalOvertime
        "holiday" -> PremiumScope.Holiday
        "rest" -> PremiumScope.RestDay
        else -> PremiumScope.All
    }
    val valid = name.isNotBlank() && !(kind == "window" && from == to)
    val choices = buildList {
        add("all" to "이 일 모든 시간")
        add("over" to "몇 시간 넘는 시간")
        add("window" to "정한 시각 사이")
        add("night" to "야간 $nightLabel")
        // One from a shared file keeps hours the dialog does not offer.
        if (kind in listOf("law", "holiday", "rest")) add(kind to scopeText(scope, nightLabel))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "가산 직접 추가" else "가산 고치기") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(Premium.MAX_NAME) },
                    label = { Text("이름 (예: 심야 가산)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Label("얼마나 더 받나요?")
                RateChips(rate) { rate = it }
                Label("어느 시간에 받나요?")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    choices.forEach { (k, label) -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(label) }) }
                }
                when (kind) {
                    "over" -> StepperRow("이 일을 넘는 시간") {
                        CountStepper((over / 60).coerceIn(1, 23), { over = it * 60 }, name = "넘는 시간",
                            range = 1..23, format = { PayCalculator.hours(it * 60) })
                    }
                    "window" -> {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TimeBox(label = "부터", time = time(from), onClick = { picking = "from" }, modifier = Modifier.weight(1f))
                            TimeBox(label = "까지", time = time(to), onClick = { picking = "to" }, modifier = Modifier.weight(1f))
                        }
                        Hint(if (from == to) "시작과 끝이 같아요" else "휴게 시간은 빼고 이 사이에 일한 시간에만 붙어요." + if (to < from) " 끝은 다음 날이에요." else "")
                    }
                    "night" -> Hint("회사 규칙의 야간 시간대에 일한 시간이에요. 야간 가산과 따로 하나 더 받을 때 써요.")
                    else -> Unit
                }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                        .toggleable(value = offDays, role = Role.Switch, onValueChange = { offDays = it }).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("쉬는 날·휴일에 할 때만", style = MaterialTheme.typography.bodyLarge)
                        Text("근무하는 날 잔업·조출에는 붙지 않아요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = offDays, onCheckedChange = null)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(Premium(initial?.key ?: NEW, name.trim(), rate, true, scope, offDays)) }, enabled = valid) {
                Text(if (initial == null) "추가" else "저장")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
    picking?.let { which ->
        TimePickerDialog(
            title = if (which == "from") "시작 시각" else "끝 시각",
            initial = time(if (which == "from") from else to),
            onDismiss = { picking = null },
        ) { t ->
            picking = null
            val m = t.hour * 60 + t.minute
            if (which == "from") from = m else to = m
        }
    }
}

@Composable
internal fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun Label(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))

@Composable
internal fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
