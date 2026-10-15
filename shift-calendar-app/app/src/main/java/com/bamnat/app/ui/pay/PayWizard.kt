package com.bamnat.app.ui.pay

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.core.pay.BulkEditPlanner
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.EditScope
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.Occupation
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayPresets
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueTimeline
import com.bamnat.core.pay.WageType
import java.time.YearMonth

/** One allowance row in the wizard; [original] is set when it came from the saved profile. */
private class ItemRow(
    val id: String,
    val name: String,
    val cadence: Cadence,
    val ordinary: Boolean,
    val hint: String,
    suggested: Long,
    checked: Boolean,
    val original: PayItem? = null,
) {
    var checked by mutableStateOf(checked)
    var amount by mutableStateOf(suggested)
    /** Still the preset's guess, so the result is labelled "추정". */
    var untouched by mutableStateOf(original == null)
}

/**
 * Five-step setup [PAYX-01]: job, pay type, base pay and since when, allowances, company rules.
 * Every step can be skipped; skipped values are filled from the job preset and marked "추정".
 * Running it again keeps the history of values that did not change (raises, earlier months).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PayWizard(initial: PayProfile?, onCancel: (() -> Unit)?, onDone: (PayProfile) -> Unit) {
    val thisMonth = remember { YearMonth.now() }
    var step by remember { mutableIntStateOf(0) }
    var occupation by remember { mutableStateOf(initial?.occupation) }
    var wageType by remember { mutableStateOf(initial?.wageType ?: WageType.MONTHLY) }
    val initialBase = initial?.base?.on(thisMonth)?.value
    var base by remember { mutableStateOf(initialBase) }
    var baseSince by remember { mutableStateOf(thisMonth) }
    var fiveOrMore by remember { mutableStateOf(initial?.fiveOrMoreEmployees ?: true) }
    var holidayMode by remember { mutableStateOf(initial?.holidayPayMode ?: HolidayPayMode.FULL) }
    var holidayChosen by remember { mutableStateOf(initial != null && "holidayPayMode" !in initial.estimatedFields) }
    var dailyLimit by remember { mutableStateOf(initial?.dailyLimitMinutes != null || initial == null) }
    val rows = remember { mutableStateListOf<ItemRow>() }

    fun choose(o: Occupation) {
        val preset = PayPresets.of(o)
        occupation = o
        if (initial == null || initial.occupation != o) {
            wageType = preset.wageType
            dailyLimit = preset.dailyLimitMinutes != null
        }
        rows.clear()
        if (initial != null && initial.occupation == o) {
            initial.items.forEach {
                rows += ItemRow(it.id, it.name, it.cadence, it.ordinary, "", it.amount.on(thisMonth).value, true, original = it)
                    .also { r -> r.untouched = it.id in initial.estimatedFields }
            }
        }
        preset.items.filter { p -> rows.none { it.id == p.id } }.forEach {
            rows += ItemRow(it.id, it.name, it.cadence, it.ordinary, it.hint, it.suggestedAmount,
                checked = it.checked && (initial == null || initial.occupation != o))
        }
    }
    // Running the wizard again starts from the saved allowances.
    remember { initial?.let { choose(it.occupation) }; true }

    fun finish() {
        val preset = PayPresets.of(occupation ?: Occupation.CUSTOM)
        val estimated = buildSet {
            if (base == null) add("base")
            rows.filter { it.checked && it.untouched }.forEach { add(it.id) }
            if (wageType == WageType.MONTHLY && !holidayChosen) add("holidayPayMode")
        }
        val since = baseSince.atDay(1)
        val baseValue = base ?: preset.baseHint
        val baseTimeline = when {
            initial == null || initial.wageType != wageType -> ValueTimeline(listOf(DatedValue(since, baseValue)))
            baseValue == initialBase -> initial.base
            else -> BulkEditPlanner.apply(initial.base, baseValue, EditScope.FromMonth(baseSince))
        }
        val items = rows.filter { it.checked }.map { r ->
            val o = r.original
            when {
                o == null -> PayItem(r.id, r.name, ValueTimeline(listOf(DatedValue(since, r.amount))), r.cadence, ordinary = r.ordinary)
                o.amount.on(thisMonth).value == r.amount -> o
                else -> o.copy(amount = BulkEditPlanner.apply(o.amount, r.amount, EditScope.FromMonth(thisMonth)))
            }
        }
        onDone(
            (initial ?: PayProfile(preset.occupation, wageType, baseTimeline)).copy(
                occupation = preset.occupation,
                wageType = wageType,
                base = baseTimeline,
                items = items,
                fiveOrMoreEmployees = fiveOrMore,
                holidayPayMode = holidayMode,
                dailyLimitMinutes = if (dailyLimit) 480 else null,
                estimatedFields = estimated,
            ),
        )
    }

    val titles = listOf("어떤 일을 하세요?", "급여는 어떻게 받으세요?", "기본 금액을 알려 주세요", "받는 수당을 골라 주세요", "회사 규칙을 확인해요")
    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(progress = { (step + 1) / titles.size.toFloat() }, modifier = Modifier.fillMaxWidth())
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${step + 1} / ${titles.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(titles[step], style = MaterialTheme.typography.headlineSmall)
            AnimatedContent(step, label = "wizard") { s ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (s) {
                        0 -> {
                            Text("직군에 맞는 수당과 기준을 미리 채워 드려요. 나중에 모두 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium)
                            PayPresets.all.forEach { preset ->
                                ChoiceCard(
                                    title = preset.occupation.label,
                                    subtitle = preset.items.joinToString { it.name }.ifEmpty { "수당을 직접 넣어요" },
                                    selected = occupation == preset.occupation,
                                ) { choose(preset.occupation) }
                            }
                            // Out of scope for the first release, said plainly [PAY-07].
                            Text("공무원·소방·경찰 수당, 포괄임금제는 아직 계산하지 않아요.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        1 -> {
                            ChoiceCard("월급제", "매달 정해진 기본급을 받아요. 통상시급 = (기본급 + 고정 수당) ÷ 월 소정근로시간", wageType == WageType.MONTHLY) {
                                wageType = WageType.MONTHLY
                            }
                            ChoiceCard("시급제", "일한 시간만큼 받아요. 연장·야간·휴일은 시급에 가산을 더해요", wageType == WageType.HOURLY) {
                                wageType = WageType.HOURLY
                            }
                        }
                        2 -> {
                            val hint = PayPresets.of(occupation ?: Occupation.CUSTOM).baseHint
                            Text(
                                if (wageType == WageType.MONTHLY) "급여명세서의 ‘기본급’을 넣어 주세요. 수당은 다음 단계에서 따로 넣어요."
                                else "계약서의 시급을 넣어 주세요. 2026년 최저시급은 10,320원이에요.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            MoneyField(
                                label = if (wageType == WageType.MONTHLY) "기본급 (월)" else "시급",
                                value = base,
                                placeholder = if (hint > 0) "예: ${won(hint)}" else "",
                                onChange = { base = it },
                            )
                            Text("언제부터 이 금액이에요?", style = MaterialTheme.typography.titleSmall)
                            MonthStepper(baseSince, onChange = { baseSince = it })
                            Text("이전 달은 이 금액을 기준으로, 이후는 인상 규칙(나중에 설정)으로 계산해요.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (base == null) {
                                Text("비워 두면 ${if (hint > 0) won(hint) else "0원"}으로 계산하고 ‘추정’이라고 표시해요.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        3 -> {
                            PayPresets.of(occupation ?: Occupation.CUSTOM).note.takeIf { it.isNotEmpty() }?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (rows.isEmpty()) Text("미리 넣을 수당이 없어요. 결과 화면에서 ‘수당 추가’로 넣을 수 있어요.", style = MaterialTheme.typography.bodyMedium)
                            rows.forEach { row -> ItemRowEditor(row) }
                            Text("금액을 그대로 두면 ‘추정’으로 표시돼요. 명세서 금액으로 바꾸면 사라져요.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            RuleToggle("5인 이상 사업장이에요", "아니면 연장·야간·휴일 가산이 붙지 않아요 (근로기준법 제11조)", fiveOrMore) { fiveOrMore = it }
                            RuleToggle("하루 8시간 넘으면 연장으로 계산", "탄력적·선택적 근로시간제라면 꺼 주세요. 주 40시간 기준은 그대로예요", dailyLimit) { dailyLimit = it }
                            if (wageType == WageType.MONTHLY) {
                                Text("쉬는 날(휴일)에 일하면", style = MaterialTheme.typography.titleSmall)
                                HolidayPayMode.entries.forEach { mode ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { holidayMode = mode; holidayChosen = true },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = holidayMode == mode, onClick = { holidayMode = mode; holidayChosen = true })
                                        Column {
                                            Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                if (mode == HolidayPayMode.FULL) "그날 일한 시간의 100%를 더 주고 가산 50%도 더해요 (대부분 이렇게 계산해요)"
                                                else "월급에 그날 몫이 이미 들어 있다고 보고 가산 50%만 더해요",
                                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                if (!holidayChosen) Text("모르면 그대로 두세요. 명세서와 대조할 때 다시 고를 수 있어요.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    step > 0 -> OutlinedButton(onClick = { step-- }) { Text("이전") }
                    onCancel != null -> TextButton(onClick = onCancel) { Text("취소") }
                }
                Spacer(Modifier.weight(1f))
                if (step in 2..3) TextButton(onClick = { step++ }) { Text("건너뛰기") }
                Spacer(Modifier.width(8.dp))
                val last = step == titles.lastIndex
                Button(
                    onClick = { if (last) finish() else step++ },
                    enabled = step != 0 || occupation != null,
                ) { Text(if (last) "계산 시작" else "다음") }
            }
        }
    }
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = onClick)
        }
    }
}

@Composable
private fun ItemRowEditor(row: ItemRow) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = row.checked, onCheckedChange = { row.checked = it })
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(row.name, style = MaterialTheme.typography.bodyLarge)
                        if (row.checked && row.untouched) { Spacer(Modifier.width(6.dp)); EstimatedChip() }
                    }
                    Text(row.cadence.label + if (row.hint.isNotEmpty()) " · ${row.hint}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (row.checked) {
                MoneyField(
                    label = if (row.cadence == Cadence.MONTHLY) "한 달 금액" else "1회 금액",
                    value = row.amount,
                    onChange = { row.amount = it ?: 0; row.untouched = false },
                    modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 6.dp),
                )
            }
        }
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
