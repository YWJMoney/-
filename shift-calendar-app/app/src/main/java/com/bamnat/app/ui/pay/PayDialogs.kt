package com.bamnat.app.ui.pay

import com.bamnat.core.pay.Unknowns
import java.time.LocalDate
import com.bamnat.core.pay.PayScale
import com.bamnat.app.ui.components.labelWidth
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.EditScope
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.OrdinaryGuess
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.Rounding
import java.time.YearMonth
import kotlin.math.abs

/** A won amount typed as digits; shows the grouped value underneath so long numbers stay readable. */
@Composable
internal fun MoneyField(
    label: String,
    value: Long?,
    onChange: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { s -> onChange(s.filter(Char::isDigit).take(10).toLongOrNull()) },
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
        suffix = { Text("원") },
        // [PAYS-04] Read back in words (250만 8천원) so a missing or extra zero shows at a glance.
        supportingText = value?.let { { Text("${won(it)} · ${com.bamnat.core.pay.wonWords(it)}") } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.labelWidth(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** 계산 풀이 for one line [PAYX-05]: formula, minutes, hourly rate, multiplier, legal basis, rate version. */
@Composable
fun LineDetailDialog(line: PayLine, result: MonthPay, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(line.label) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(won(line.amount), style = MaterialTheme.typography.headlineSmall, color = kindColor(line.kind))
                Text(line.formula, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 8.dp))
                HorizontalDivider()
                if (line.minutes > 0) Fact("시간", PayCalculator.hours(line.minutes))
                if (line.hourly > 0) Fact("기준 시급", won(line.hourly) + if (line.hourly == result.ordinaryHourly) " (통상시급)" else "")
                if (line.multiplier > 0) Fact("배율", PayCalculator.pct(line.multiplier))
                if (line.basis.isNotEmpty()) Fact("근거", line.basis)
                Fact("요율 기준", result.ratesVersion)
                if (line.estimated) {
                    Text("입력하지 않은 값을 앱이 짐작해 넣은 부분이 있어요. ‘내 급여 값’에서 실제 값으로 바꾸면 정확해져요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

private enum class ScopeKind(val label: String) { ONLY("이 달만"), FROM("이 달부터 계속"), RANGE("기간 지정") }

/**
 * Changes a value with a scope and shows what it does to the next 12 months before applying
 * [PAYX-03]. Applying can be undone in one tap on the result screen.
 */
@Composable
fun EditValueDialog(
    vm: PayViewModel,
    profile: PayProfile,
    snapshot: ScheduleSnapshot,
    target: ValueTarget,
    month: YearMonth,
    onDismiss: () -> Unit,
    /** [MON-01] 기간 지정 is 값 일괄 적용, a pro feature; locked, it opens the 프로 screen. */
    rangeOpen: Boolean = true,
) {
    val timeline = remember(profile, target) { vm.timelineOf(profile, target) }
    val current = timeline.on(month).value
    var value by remember { mutableStateOf<Long?>(current) }
    var scope by remember { mutableStateOf(ScopeKind.FROM) }
    var until by remember { mutableStateOf(month.plusMonths(2)) }
    val editScope = when (scope) {
        ScopeKind.ONLY -> EditScope.OnlyMonth(month)
        ScopeKind.FROM -> EditScope.FromMonth(month)
        ScopeKind.RANGE -> EditScope.Range(month, maxOf(month, until))
    }
    val v = value
    // [QA-01] A plan recalculates a year of months; wait for typing to pause and work off the main thread.
    val plan by androidx.compose.runtime.produceState<com.bamnat.core.pay.EditPlan?>(null, v, editScope, profile) {
        this.value = null // never apply a plan for a number that has since changed
        kotlinx.coroutines.delay(250)
        this.value = v?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.planEdit(profile, snapshot, target, it, editScope, month) } }
    }
    val name = when (target) {
        ValueTarget.Base -> "기본 금액"
        is ValueTarget.Item -> profile.items.firstOrNull { it.id == target.id }?.name ?: "수당"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name 바꾸기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("${month.monthValue}월 지금 값: ${won(current)}", style = MaterialTheme.typography.bodyMedium)
                MoneyField("새 금액", value, { value = it }, Modifier.padding(top = 8.dp))
                Text("어디에 적용할까요?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                ScopeKind.entries.forEach { k ->
                    val locked = k == ScopeKind.RANGE && !rangeOpen
                    val pick = { if (locked) { onDismiss(); com.bamnat.app.ui.pro.openPro() } else scope = k }
                    Row(Modifier.fillMaxWidth().clickable(onClick = pick), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = scope == k, onClick = pick)
                        Text(
                            when (k) {
                                ScopeKind.ONLY -> "${month.monthValue}월만 (다음 달부터는 원래대로)"
                                ScopeKind.FROM -> "${month.monthValue}월부터 계속 (이후 입력값도 바뀌어요)"
                                ScopeKind.RANGE -> "기간 지정"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (locked) com.bamnat.app.ui.money.ProPill(Modifier.padding(start = 8.dp))
                    }
                }
                if (scope == ScopeKind.RANGE) {
                    Text("언제까지", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
                    MonthStepper(maxOf(month, until), { until = it }, Modifier.padding(start = 12.dp))
                }
                plan?.let { p ->
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    val changed = p.changedMonths
                    Text(
                        if (changed.isEmpty()) "앞으로 12개월 합계는 그대로예요"
                        else "${changed.size}개월이 바뀌어요 · 합계 ${signed(p.totalDelta)}",
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        changed.forEach { d ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text("${d.month.year % 100}.${d.month.monthValue}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.labelWidth(44.dp))
                                Text("${won(d.before)} → ${won(d.after)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(if (d.locked) "마감" else signed(d.delta), style = MaterialTheme.typography.bodySmall,
                                    color = if (d.delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Text("적용한 뒤에도 ‘방금 바꾼 것 되돌리기’로 한 번에 되돌릴 수 있어요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        confirmButton = {
            Button(onClick = { plan?.let { vm.applyEdit(target, it) }; onDismiss() }, enabled = plan != null) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

private fun signed(v: Long) = (if (v > 0) "+" else if (v < 0) "−" else "") + won(abs(v))

/** 연도별 인상 규칙 [PAYX-02]: month, percent or amount, rounding unit; previews three years. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaiseDialog(vm: PayViewModel, profile: PayProfile, target: ValueTarget, onDismiss: () -> Unit) {
    val timeline = remember(profile, target) { vm.timelineOf(profile, target) }
    val existing = timeline.raise
    var month by remember { mutableStateOf(existing?.month ?: 1) }
    var byPercent by remember { mutableStateOf(existing?.amount == null) }
    var text by remember { mutableStateOf(existing?.let { it.percent?.toString() ?: it.amount?.toString() } ?: "") }
    var unit by remember { mutableStateOf(existing?.unit ?: 10L) }
    var rounding by remember { mutableStateOf(existing?.rounding ?: Rounding.UP) }
    var scale by remember { mutableStateOf(false) }
    if (scale) {
        PayScaleDialog(vm, profile, onDismiss = { scale = false; onDismiss() })
        return
    }
    val rule = runCatching {
        val n = text.toDouble()
        require(n > 0)
        if (byPercent) RaiseRule(month, percent = n, unit = unit, rounding = rounding)
        else RaiseRule(month, amount = n.toLong(), unit = unit, rounding = rounding)
    }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("매년 인상 규칙") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("입력한 값이 없는 해는 이 규칙으로 올려서 계산해요. 실제로 받은 금액을 입력하면 그 값이 우선이에요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // [PAYX-20] A company with a 호봉표 raises by step, not by a rate.
                if (target == ValueTarget.Base) OutlinedButton(onClick = { scale = true }) { Text("호봉표로 넣기") }
                Text("인상되는 달", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..12).forEach { m -> FilterChip(selected = month == m, onClick = { month = m }, label = { Text("${m}월") }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = byPercent, onClick = { byPercent = true }, label = { Text("비율(%)") })
                    FilterChip(selected = !byPercent, onClick = { byPercent = false }, label = { Text("금액(원)") })
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { s -> text = s.filter { it.isDigit() || (byPercent && it == '.') }.take(9) },
                    label = { Text(if (byPercent) "인상률" else "인상 금액") },
                    suffix = { Text(if (byPercent) "%" else "원") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (byPercent) KeyboardType.Decimal else KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("끝자리 처리", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(1L, 10L, 100L, 1000L, 10000L).forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(won(u)) }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Rounding.entries.forEach { r ->
                        FilterChip(selected = rounding == r, onClick = { rounding = r },
                            label = { Text(when (r) { Rounding.UP -> "올림"; Rounding.NEAREST -> "반올림"; Rounding.DOWN -> "버림" }) })
                    }
                }
                rule?.let { r ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(r.describe(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    val t = timeline.copy(raise = r)
                    val start = YearMonth.now()
                    (0..3).forEach { y ->
                        val m = YearMonth.of(start.year + y, r.month).let { if (it < start) start else it }
                        Fact(Fmt.month(m), won(t.on(m).value))
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { vm.setRaise(target, rule); onDismiss() }, enabled = rule != null) { Text("적용") } },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { vm.setRaise(target, null); onDismiss() }) { Text("규칙 없애기") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}

/**
 * Fills values from pay rules written in words [PAYX-04][PAYS-06]: typed, pasted or read from a photo. Each
 * sentence becomes a change shown next to it; nothing changes until the user ticks and applies, from [month] on.
 */
@Composable
fun RuleImportDialog(vm: PayViewModel, month: YearMonth, onDismiss: () -> Unit) {
    val snapshot by vm.snapshot.collectAsState()
    val settings by vm.settings.collectAsState()
    val profile by vm.profile.collectAsState()
    var from by remember { mutableStateOf(month) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("규칙을 말로 적기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("취업규칙, 근로계약서, 동료에게 들은 말 그대로 적어도 돼요. 정해진 방식이 아니어도 규칙대로 계산해요. 휴대폰 안에서만 읽어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("언제부터 적용할까요?", style = MaterialTheme.typography.titleSmall)
                MonthStepper(from, { from = it })
                RuleSentenceBox(settings.extraKinds.kinds, snapshot?.shiftTypes.orEmpty(), profile?.fiveOrMoreEmployees ?: true) { changes ->
                    vm.applyRuleChanges(changes, from)
                    onDismiss()
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/**
 * A bonus [PAY-05]: paid in chosen months, as a percent of 기본급 or a fixed amount. Counted in
 * 통상임금 by default, since a regular bonus is 통상임금 even with an employment condition
 * (대법원 2024.12.19. 2020다247190 전원합의체).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddBonusDialog(
    onDismiss: () -> Unit,
    onAdd: (String, Set<Int>, Double?, Long, Boolean, Set<com.bamnat.core.pay.Festival>, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("상여금") }
    var byPercent by remember { mutableStateOf(true) }
    var percent by remember { mutableStateOf(100) }
    // A yearly total such as 상여 800% is split over the chosen months.
    var annual by remember { mutableStateOf<Int?>(null) }
    var amount by remember { mutableStateOf<Long?>(null) }
    var months by remember { mutableStateOf(setOf(1, 4, 7, 10)) }
    var ordinary by remember { mutableStateOf(true) }
    // [PAYX-10] 명절상여 follows 설날·추석, whose months move every year.
    var festivals by remember { mutableStateOf(emptySet<com.bamnat.core.pay.Festival>()) }
    var festivalBefore by remember { mutableStateOf(false) }
    val times = months.size + festivals.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("상여금 추가") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, label = { Text("이름") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("한 번에 얼마?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = byPercent, onClick = { byPercent = true }, label = { Text("기본급의 %") })
                    FilterChip(selected = !byPercent, onClick = { byPercent = false }, label = { Text("정해진 금액") })
                }
                if (byPercent) {
                    Text("연 합계로", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(400, 600, 800, 1000).forEach { a ->
                            FilterChip(selected = annual == a, onClick = { annual = a }, label = { Text("연 $a%") })
                        }
                    }
                    annual?.takeIf { times > 0 }?.let { a ->
                        Text("한 번에 기본급의 ${PayCalculator.pct(a / 100.0 / times)}씩 ${times}번",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text("한 번에", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(50, 100, 150, 200).forEach { p ->
                            FilterChip(selected = annual == null && percent == p, onClick = { percent = p; annual = null }, label = { Text("$p%") })
                        }
                        OutlinedButton(onClick = { percent = (percent - 10).coerceAtLeast(10); annual = null }) { Text("−") }
                        Text("$percent%", style = MaterialTheme.typography.titleSmall, modifier = Modifier.align(Alignment.CenterVertically))
                        OutlinedButton(onClick = { percent = (percent + 10).coerceAtMost(1000); annual = null }) { Text("+") }
                    }
                } else {
                    MoneyField("1회 금액", amount, { amount = it })
                }
                Text("명절상여", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.bamnat.core.pay.Festival.entries.forEach { f ->
                        val date = remember(f) { f.date(java.time.LocalDate.now().year) }
                        FilterChip(selected = f in festivals, onClick = {
                            festivals = if (f in festivals) festivals - f else festivals + f
                            if (festivals.isNotEmpty() && name == "상여금") name = "명절상여"
                        }, label = { Text(f.label + (date?.let { " (올해 ${it.monthValue}/${it.dayOfMonth})" } ?: "")) })
                    }
                }
                if (festivals.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = !festivalBefore, onClick = { festivalBefore = false }, label = { Text("명절 있는 달") })
                        FilterChip(selected = festivalBefore, onClick = { festivalBefore = true }, label = { Text("명절 전 달") })
                    }
                    Text("음력이라 해마다 받는 달이 바뀌어요. 달력에 맞춰 자동으로 계산해요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (festivals.isEmpty()) "받는 달" else "그 밖에 받는 달 (없으면 비워 두세요)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("분기 (1·4·7·10월)" to setOf(1, 4, 7, 10), "짝수 달" to setOf(2, 4, 6, 8, 10, 12),
                        "매달" to (1..12).toSet(), "반기 (6·12월)" to setOf(6, 12)).forEach { (label, set) ->
                        FilterChip(selected = months == set, onClick = { months = set }, label = { Text(label) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..12).forEach { m ->
                        FilterChip(selected = m in months, onClick = { months = if (m in months) months - m else months + m },
                            label = { Text("${m}월") })
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { ordinary = !ordinary }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("통상임금에 포함", style = MaterialTheme.typography.bodyLarge)
                        Text("정해진 조건으로 정기적으로 받는 상여는 재직 조건이 있어도 통상임금이에요(대법원 2024.12.19. 판결). 성과에 따라 달라지면 꺼 주세요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = ordinary, onCheckedChange = { ordinary = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onAdd(name, months, if (byPercent) (annual?.let { it / 100.0 / times } ?: (percent / 100.0)) else null,
                        if (byPercent) 0 else amount ?: 0, ordinary, festivals, festivalBefore)
                },
                enabled = times > 0 && (byPercent || (amount ?: 0) > 0),
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * A new allowance from this month on [PAYX-03]. [PAYS-01] With [allowUnknownAmount] it can be added as
 * 금액 모름 (null), left out of the total until the amount is known.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddItemDialog(onDismiss: () -> Unit, allowUnknownAmount: Boolean = false, onAdd: (String, Long?, Cadence, Boolean, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf<Long?>(null) }
    var cadence by remember { mutableStateOf(Cadence.MONTHLY) }
    var ordinary by remember { mutableStateOf(false) }
    // 순번 285: the switch follows the name until the user sets it (OrdinaryGuess).
    var ordinaryTouched by remember { mutableStateOf(false) }
    var fixedOtHours by remember { mutableStateOf(0) }
    fun guess() { if (!ordinaryTouched) ordinary = OrdinaryGuess.of(name, cadence) ?: false }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("수당 추가") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(20); guess() }, label = { Text("이름") },
                    placeholder = { Text("예: 나이트 수당") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MoneyField(if (cadence == Cadence.MONTHLY) "한 달 금액" else "1회 금액", amount, { amount = it },
                    placeholder = if (allowUnknownAmount) "모르면 비워 두세요" else "")
                Text("언제 받나요?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Cadence.MONTHLY, Cadence.PER_WORK_DAY, Cadence.PER_NIGHT_SHIFT).forEach { c ->
                        FilterChip(selected = cadence == c, onClick = { cadence = c; guess() }, label = { Text(c.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = fixedOtHours == 0) { ordinary = !ordinary; ordinaryTouched = true },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("통상임금에 포함", style = MaterialTheme.typography.bodyLarge)
                        Text("매달 고정으로 받는 수당이면 켜 주세요. 연장·야간 수당이 함께 올라가요." +
                            if (!ordinaryTouched && OrdinaryGuess.of(name, cadence) != null) " 이름을 보고 미리 골라 두었어요." else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = ordinary, onCheckedChange = { ordinary = it; ordinaryTouched = true }, enabled = fixedOtHours == 0)
                }
                if (cadence == Cadence.MONTHLY) {
                    // [PAY-05] 고정 연장수당: prepaid overtime, never 통상임금.
                    Text("연장수당을 미리 주는 고정 수당인가요?", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(0, 10, 20, 30, 40, 52).forEach { h ->
                            FilterChip(selected = fixedOtHours == h, onClick = {
                                fixedOtHours = h
                                if (h > 0) ordinary = false
                            }, label = { Text(if (h == 0) "아니요" else "${h}시간분") })
                        }
                    }
                    if (fixedOtHours > 0) Text("실제 연장수당이 이 금액보다 많으면 차액을 알려 드려요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, amount?.takeIf { it > 0 }, cadence, ordinary && fixedOtHours == 0, if (cadence == Cadence.MONTHLY) fixedOtHours * 60 else 0) },
                enabled = name.isNotBlank() && (allowUnknownAmount || (amount ?: 0) > 0),
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * [PAYX-20] 호봉표: the company's table pasted as text, the step now and the month a step goes up. Applied, it
 * becomes the 기본급's dated values from [PayScale.since] on (the table is the raise, so no yearly rule).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayScaleDialog(vm: PayViewModel, profile: PayProfile, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var stepMonth by remember { mutableStateOf(1) }
    var step by remember { mutableStateOf(1) }
    val since = remember { LocalDate.now().withDayOfMonth(1) }
    val steps = remember(text) { PayScale.parse(text) }
    val scale = runCatching { PayScale(steps, stepMonth, step.coerceIn(steps.keys.minOrNull() ?: 1, steps.keys.maxOrNull() ?: 1), since) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("호봉표로 기본급 넣기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("한 줄에 한 호봉씩 붙여 넣어요. 예: 1호봉 2,400,000", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it.take(4000) }, label = { Text("호봉표") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                if (steps.isNotEmpty()) {
                    Text("지금 호봉", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        steps.keys.sorted().forEach { n -> FilterChip(selected = step == n, onClick = { step = n }, label = { Text("${n}호봉") }) }
                    }
                }
                Text("승급하는 달", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..12).forEach { m -> FilterChip(selected = stepMonth == m, onClick = { stepMonth = m }, label = { Text("${m}월") }) }
                }
                scale?.let { sc ->
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(sc.describe(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    sc.values(since.plusYears(4)).take(5).forEach { v -> Fact(Fmt.month(YearMonth.from(v.from)), won(v.value)) }
                }
            }
        },
        confirmButton = {
            Button(enabled = scale != null, onClick = {
                scale?.let { sc -> vm.update { p -> p.copy(base = sc.applyTo(p.base, since.plusYears(40)), estimatedFields = p.estimatedFields - Unknowns.BASE) } }
                onDismiss()
            }) { Text("적용") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
