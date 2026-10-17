package com.bamnat.app.ui.pay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.core.pay.ExtraBasis
import com.bamnat.core.pay.ExtraRule
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.Tier

/** The ways to pay extra work, in the order people meet them: the law, then a payment each time, then by the hour. */
private val BASIS_ORDER = listOf(
    ExtraBasis.LEGAL, ExtraBasis.PERCENT_OF_BASE, ExtraBasis.FIXED_PER_TIME,
    ExtraBasis.HOURLY_RATE, ExtraBasis.FIXED_PER_HOUR, ExtraBasis.TIERED,
)

private fun example(b: ExtraBasis): String = when (b) {
    ExtraBasis.LEGAL -> ""
    ExtraBasis.PERCENT_OF_BASE -> "예: 한 번에 기본급의 10%"
    ExtraBasis.FIXED_PER_TIME -> "예: 한 번에 80,000원"
    ExtraBasis.HOURLY_RATE -> "예: 일한 시간마다 통상시급의 200%"
    ExtraBasis.FIXED_PER_HOUR -> "예: 시간당 20,000원"
    ExtraBasis.TIERED -> "예: 8시간까지 100%, 다음 4시간 150%"
}

/**
 * [EXTRA-03] How the company pays one kind of extra work, asked as a question: the base part as a
 * choice with an example and its numbers right under it, then what comes on top of it (한 번이 덮는
 * 시간, 가산, 1회 수당). [legalText] says what 법대로 comes to for this kind.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExtraRuleEditor(
    rule: ExtraRule,
    legalText: String,
    nightLabel: String,
    /** For one shift of a kind [PAYX-11]: the first choice keeps the kind's own way. */
    sameAsKind: Boolean = false,
    onChange: (ExtraRule) -> Unit,
) {
    Column {
        for (b in BASIS_ORDER) {
            val selected = rule.basis == b
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(selected = selected, role = Role.RadioButton) {
                        // Tiers usually price the night hours already, so the separate night premium starts off there.
                        if (!selected) onChange(rule.copy(basis = b, nightPremium = b != ExtraBasis.TIERED && (rule.basis == ExtraBasis.TIERED || rule.nightPremium)))
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (b == ExtraBasis.LEGAL && sameAsKind) "위 방식과 같게" else b.label, style = MaterialTheme.typography.bodyLarge)
                    Text(if (b == ExtraBasis.LEGAL) legalText else example(b),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selected && b != ExtraBasis.LEGAL) {
                Column(Modifier.padding(start = 36.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasisNumbers(rule, onChange)
                }
            }
        }
        if (rule.basis != ExtraBasis.LEGAL) {
            Label("이것도 받나요?")
            if (rule.perTime) {
                Text("한 번이 덮는 시간", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = rule.coverMinutes == null, onClick = { onChange(rule.copy(coverMinutes = null)) },
                        label = { Text("시간 상관없이") })
                    listOf(4 * 60, 8 * 60, 12 * 60).forEach { m ->
                        FilterChip(selected = rule.coverMinutes == m, onClick = { onChange(rule.copy(coverMinutes = m)) },
                            label = { Text("${PayCalculator.hours(m)}까지") })
                    }
                }
                Hint(rule.coverMinutes?.let { "${PayCalculator.hours(it)} 넘게 일하면 넘는 시간은 통상시급 100%로 더 받아요." }
                    ?: "몇 시간을 일해도 한 번 금액만 받아요.")
            } else {
                Text("최소 인정 시간", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 4 * 60, 8 * 60).forEach { m ->
                        FilterChip(selected = rule.minMinutes == m, onClick = { onChange(rule.copy(minMinutes = m)) },
                            label = { Text(if (m == 0) "일한 만큼" else "최소 ${PayCalculator.hours(m)}") })
                    }
                }
            }
            if (rule.basis != ExtraBasis.HOURLY_RATE && rule.basis != ExtraBasis.TIERED) {
                Toggle("연장·휴일 가산도 받기", "하루 8시간·주 40시간을 넘는 시간에 연장 가산을 법대로 더해요. 예: 대근 1회 + 연장 0.5",
                    rule.legalPremiums) { onChange(rule.copy(legalPremiums = it)) }
            }
            Toggle("야간 가산 따로 받기", "$nightLabel 시간에 야간 가산을 더해요. 예: 야간 0.5", rule.nightPremium) {
                onChange(rule.copy(nightPremium = it))
            }
        }
        if (sameAsKind && rule.basis == ExtraBasis.LEGAL) return@Column
        Spacer(Modifier.padding(top = 4.dp))
        MoneyField("1회 수당 (따로 더 받는 돈)", rule.bonus.takeIf { it > 0 }, { onChange(rule.copy(bonus = it ?: 0)) }, placeholder = "없으면 비워 두세요")
        Hint("예: 대근 수당 30,000원. 위 방식에 더해 한 번마다 받아요.")
    }
}

/** The numbers of the chosen way to pay, under the choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BasisNumbers(rule: ExtraRule, onChange: (ExtraRule) -> Unit) {
    when (rule.basis) {
        ExtraBasis.LEGAL -> Unit
        ExtraBasis.PERCENT_OF_BASE -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                listOf(0.05, 0.1, 0.15, 0.2).forEach { p ->
                    FilterChip(selected = kotlin.math.abs(rule.percentOfBase - p) < 1e-9, onClick = { onChange(rule.copy(percentOfBase = p)) },
                        label = { Text(ExtraRule.percent(p)) })
                }
            }
            // In steps of 0.5%, kept on the step so 10% stays exactly 10%.
            fun step(by: Int) = onChange(rule.copy(percentOfBase = ((Math.round(rule.percentOfBase * 200) + by).coerceIn(1L, 200L)) / 200.0))
            Stepper(ExtraRule.percent(rule.percentOfBase), onMinus = { step(-1) }, onPlus = { step(1) })
            Hint("한 번 할 때마다 월 기본급의 이만큼을 받아요.")
        }
        ExtraBasis.HOURLY_RATE -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1.0, 1.5, 2.0, 2.5, 3.0).forEach { m ->
                    FilterChip(selected = kotlin.math.abs(rule.multiplier - m) < 1e-9, onClick = { onChange(rule.copy(multiplier = m)) },
                        label = { Text(PayCalculator.pct(m)) })
                }
            }
            fun step(by: Double) = onChange(rule.copy(multiplier = (Math.round((rule.multiplier + by) * 10) / 10.0).coerceIn(1.0, 5.0)))
            Stepper(PayCalculator.pct(rule.multiplier), onMinus = { step(-0.1) }, onPlus = { step(0.1) })
            Hint("일한 시간 × 통상시급 × 이 배율이에요. 150%면 연장처럼, 200%면 휴일 연장처럼 받아요.")
        }
        ExtraBasis.FIXED_PER_TIME, ExtraBasis.FIXED_PER_HOUR -> MoneyField(
            if (rule.basis == ExtraBasis.FIXED_PER_TIME) "한 번에 받는 금액" else "1시간에 받는 금액",
            rule.amount.takeIf { it > 0 }, { onChange(rule.copy(amount = it ?: 0)) },
        )
        ExtraBasis.TIERED -> {
            TierEditor("주간에 할 때", rule.dayTiers) { onChange(rule.copy(dayTiers = it)) }
            TierEditor("야간에 할 때 (일한 시간 절반 이상이 야간)", rule.nightTiers) { onChange(rule.copy(nightTiers = it)) }
            Hint("시작부터 순서대로 구간마다 통상시급 × 배율을 받아요. 마지막 구간은 남은 시간 전부에 적용돼요.")
        }
    }
}

@Composable
private fun Stepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("직접", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = onMinus) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 56.dp))
        OutlinedButton(onClick = onPlus) { Text("+") }
    }
}

/** Blocks of hours with their rate, in shift order [PAYX-07]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TierEditor(title: String, tiers: List<Tier>, onChange: (List<Tier>) -> Unit) {
    Label(title)
    tiers.forEachIndexed { i, t ->
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
            OutlinedButton(onClick = { onChange(tiers.toMutableList().also { it[i] = t.copy(minutes = (t.minutes - 60).coerceAtLeast(60)) }) }) { Text("−") }
            Text(PayCalculator.hours(t.minutes), style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterVertically))
            OutlinedButton(onClick = { onChange(tiers.toMutableList().also { it[i] = t.copy(minutes = (t.minutes + 60).coerceAtMost(24 * 60)) }) }) { Text("+") }
            listOf(0.0, 0.25, 0.5, 0.75, 1.0).forEach { p ->
                FilterChip(selected = t.premium == p, onClick = { onChange(tiers.toMutableList().also { it[i] = t.copy(premium = p) }) },
                    label = { Text(PayCalculator.pct(1.0 + p)) })
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { onChange(tiers + Tier(240, tiers.lastOrNull()?.premium ?: 0.5)) }) { Text("구간 추가") }
        if (tiers.size > 1) TextButton(onClick = { onChange(tiers.dropLast(1)) }) { Text("마지막 구간 빼기") }
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
