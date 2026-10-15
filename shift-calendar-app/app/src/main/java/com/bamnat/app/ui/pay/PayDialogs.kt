package com.bamnat.app.ui.pay

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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.bamnat.core.pay.CandidateKind
import com.bamnat.core.pay.EditScope
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RaiseRule
import com.bamnat.core.pay.Rounding
import com.bamnat.core.pay.RuleCandidate
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
        supportingText = value?.let { { Text(won(it)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
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
    val plan = remember(v, editScope, profile) { v?.let { vm.planEdit(profile, snapshot, target, it, editScope, month) } }
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
                    Row(Modifier.fillMaxWidth().clickable { scope = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = scope == k, onClick = { scope = k })
                        Text(
                            when (k) {
                                ScopeKind.ONLY -> "${month.monthValue}월만 (다음 달부터는 원래대로)"
                                ScopeKind.FROM -> "${month.monthValue}월부터 계속 (이후 입력값도 바뀌어요)"
                                ScopeKind.RANGE -> "기간 지정"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
                                Text("${d.month.year % 100}.${d.month.monthValue}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp))
                                Text("${won(d.before)} → ${won(d.after)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text(signed(d.delta), style = MaterialTheme.typography.bodySmall,
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

private fun CandidateKind.label() = when (this) {
    CandidateKind.BASE_SALARY -> "기본급"
    CandidateKind.HOURLY_WAGE -> "시급"
    CandidateKind.ALLOWANCE -> "수당"
    CandidateKind.MULTIPLIER -> "가산율 (참고)"
    CandidateKind.RAISE -> "인상률"
}

/**
 * Fills values from pasted pay rules [PAYX-04]. Each line becomes a suggestion shown next to the
 * sentence it came from; nothing changes until the user ticks and applies.
 */
@Composable
fun RuleImportDialog(vm: PayViewModel, month: YearMonth, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val candidates = remember { mutableStateListOf<Pair<RuleCandidate, Boolean>>() }
    var read by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf(month) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (!read) "규칙 글로 채우기" else "찾은 값 ${candidates.size}개") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!read) {
                    Text("급여명세서나 취업규칙의 문장을 붙여 넣으세요. 한 줄에 하나씩이면 가장 잘 읽어요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        placeholder = { Text("예)\n기본급 3,100,000원\n나이트 수당 1회 35,000원\n매년 3월 3.5% 인상") },
                        minLines = 5, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text("휴대폰 안에서만 읽고 어디에도 보내지 않아요. 사진으로 읽기는 다음 업데이트에서 열려요.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                } else {
                    if (candidates.isEmpty()) Text("읽을 수 있는 금액이나 비율을 못 찾았어요. ‘나이트 수당 30,000원’처럼 이름과 금액을 같이 적어 주세요.")
                    candidates.forEachIndexed { i, (c, on) ->
                        val reference = c.kind == CandidateKind.MULTIPLIER
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Checkbox(checked = on && !reference, enabled = !reference, onCheckedChange = { candidates[i] = c to it })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${c.kind.label()} · ${c.name} " + when {
                                        c.amount != null -> won(c.amount!!)
                                        c.percent != null -> "${c.percent}%"
                                        else -> ""
                                    } + (c.cadence?.takeIf { c.kind == CandidateKind.ALLOWANCE }?.let { " · ${it.label}" } ?: ""),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text("“${c.sourceLine}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (reference) Text("가산율은 법정 기준으로 계산해요. 회사 기준이 더 높으면 수당으로 넣어 주세요.",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                else if (c.confidence < 0.6) Text("확실하지 않아요. 맞는지 확인해 주세요.",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                    if (candidates.isNotEmpty()) {
                        Text("언제부터 적용할까요?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        MonthStepper(from, { from = it })
                    }
                }
            }
        },
        confirmButton = {
            if (!read) {
                Button(onClick = {
                    candidates.clear()
                    candidates += vm.readRules(text).map { it to (it.confidence >= 0.6 && it.kind != CandidateKind.MULTIPLIER) }
                    read = true
                }, enabled = text.isNotBlank()) { Text("읽기") }
            } else {
                val chosen = candidates.filter { it.second && it.first.kind != CandidateKind.MULTIPLIER }.map { it.first }
                Button(onClick = { vm.applyCandidates(chosen, from); onDismiss() }, enabled = chosen.isNotEmpty()) { Text("${chosen.size}개 적용") }
            }
        },
        dismissButton = {
            if (read) TextButton(onClick = { read = false }) { Text("다시 쓰기") } else TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

/**
 * 명세서와 대조 [PAYX-05]: the user enters what the pay slip says; the app shows the gap and which
 * company choice would explain it, closest first, and can switch to it.
 */
@Composable
fun ReconcileDialog(
    vm: PayViewModel,
    profile: PayProfile,
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    total: Long,
    onDismiss: () -> Unit,
) {
    var received by remember { mutableStateOf<Long?>(null) }
    val r = received
    val alternatives = remember(r, profile, month) { r?.let { vm.alternatives(profile, snapshot, month, it) } ?: emptyList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${month.monthValue}월 명세서와 대조") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("명세서의 ‘지급 합계’(세금 빼기 전)를 넣어 주세요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MoneyField("명세서 지급 합계", received, { received = it }, Modifier.padding(top = 8.dp))
                if (r != null && r > 0) {
                    val diff = r - total
                    Fact("앱 계산", won(total))
                    Fact("차이", if (diff == 0L) "같아요" else signed(diff))
                    if (r < total * 0.85) {
                        Text("세금과 4대보험을 뺀 실수령액을 넣으셨다면, 명세서 위쪽의 지급 합계로 바꿔 넣어 주세요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    if (diff != 0L && alternatives.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("이렇게 계산하면 더 가까워요", style = MaterialTheme.typography.titleSmall)
                        alternatives.take(4).forEach { alt ->
                            val gap = r - alt.total
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(alt.label, style = MaterialTheme.typography.bodyMedium)
                                    Text("${won(alt.total)} · 차이 ${if (gap == 0L) "없음" else signed(gap)}", style = MaterialTheme.typography.bodySmall,
                                        color = if (abs(gap) < abs(diff)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (abs(gap) < abs(diff)) OutlinedButton(onClick = { vm.update { alt.profile }; onDismiss() }) { Text("바꾸기") }
                            }
                        }
                        Text("그래도 다르면 명세서의 수당 이름과 금액을 ‘내 급여 값’에 맞춰 주세요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** A new allowance from this month on [PAYX-03]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddItemDialog(onDismiss: () -> Unit, onAdd: (String, Long, Cadence, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf<Long?>(null) }
    var cadence by remember { mutableStateOf(Cadence.MONTHLY) }
    var ordinary by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("수당 추가") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(20) }, label = { Text("이름") },
                    placeholder = { Text("예: 나이트 수당") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                MoneyField(if (cadence == Cadence.MONTHLY) "한 달 금액" else "1회 금액", amount, { amount = it })
                Text("언제 받나요?", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Cadence.MONTHLY, Cadence.PER_WORK_DAY, Cadence.PER_NIGHT_SHIFT).forEach { c ->
                        FilterChip(selected = cadence == c, onClick = { cadence = c }, label = { Text(c.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { ordinary = !ordinary }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("통상임금에 포함", style = MaterialTheme.typography.bodyLarge)
                        Text("매달 고정으로 받는 수당이면 켜 주세요. 연장·야간 수당이 함께 올라가요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = ordinary, onCheckedChange = { ordinary = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(name, amount ?: 0, cadence, ordinary) }, enabled = name.isNotBlank() && (amount ?: 0) > 0) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
