package com.bamnat.app.ui.pay

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Tier
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.ValueSource
import com.bamnat.core.pay.WageType
import java.time.YearMonth

internal fun won(v: Long): String = "%,d원".format(v)

/** Colours for line kinds, matching the walkthrough's time bar. */
@Composable
internal fun kindColor(kind: LineKind): Color = when (kind) {
    LineKind.BASE -> MaterialTheme.colorScheme.primary
    LineKind.OVERTIME -> Color(0xFFF08C00)
    LineKind.NIGHT -> Color(0xFF6741D9)
    LineKind.HOLIDAY -> Color(0xFFE03131)
    LineKind.ALLOWANCE -> Color(0xFF2F9E44)
}

/**
 * 급여 계산 [PAY-09, PAYX-01..06]: set up once with the wizard, then every month is calculated from the
 * schedule. Every amount explains itself; free users see the sample and a blurred total.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayScreen(vm: PayViewModel, onBack: () -> Unit) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val tier by vm.tier.collectAsStateWithLifecycle()
    var editingSetup by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (profile == null || editingSetup) "급여 계산 설정" else "급여 계산") },
                navigationIcon = { IconButton(onClick = { if (editingSetup) editingSetup = false else onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
            )
        },
    ) { padding ->
        val snap = snapshot
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                snap == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                profile == null || editingSetup -> PayWizard(
                    initial = profile,
                    onCancel = if (profile != null) ({ editingSetup = false }) else null,
                    onDone = { p -> vm.saveSetup(p); editingSetup = false },
                )
                else -> PayResult(vm, profile!!, snap, tier, onEditSetup = { editingSetup = true })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayResult(vm: PayViewModel, profile: PayProfile, snapshot: ScheduleSnapshot, tier: Tier, onEditSetup: () -> Unit) {
    val month by vm.month.collectAsStateWithLifecycle()
    val undo by vm.undoAvailable.collectAsStateWithLifecycle()
    val unlocked = vm.canUse(Feature.PAY_ENGINE, tier)
    val result = remember(profile, snapshot, month) { vm.calculate(profile, snapshot, month) }
    val year = remember(profile, snapshot, month) { (0L until 12L).map { month.plusMonths(it) }.map { it to vm.calculate(profile, snapshot, it).total } }

    var detail by remember { mutableStateOf<PayLine?>(null) }
    var editTarget by remember { mutableStateOf<ValueTarget?>(null) }
    var raiseTarget by remember { mutableStateOf<ValueTarget?>(null) }
    var importing by remember { mutableStateOf(false) }
    var reconciling by remember { mutableStateOf(false) }
    var addingItem by remember { mutableStateOf(false) }
    var walkthrough by rememberSaveable { mutableStateOf(true) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${profile.occupation.label} · ${if (profile.wageType == WageType.MONTHLY) "월급제" else "시급제"}",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.moveMonth(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
            Text(Fmt.month(month), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { vm.moveMonth(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
        }

        TotalCard(result, unlocked)

        if (!unlocked) {
            LockedCard()
        }

        if (walkthrough && unlocked) {
            Walkthrough(result, onClose = { walkthrough = false })
        }

        SectionCard(title = "항목별 내역", trailing = { Text("누르면 계산 풀이", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
            for (line in result.lines) {
                LineRow(line, unlocked) { if (unlocked) detail = line }
            }
            if (result.lines.isEmpty()) Text("이 달은 계산할 근무가 없어요", style = MaterialTheme.typography.bodyMedium)
        }

        if (result.warnings.isNotEmpty()) {
            SectionCard(title = "확인해 주세요") {
                result.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }

        SectionCard(title = "앞으로 12개월 예상") {
            val max = (year.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
            year.forEach { (m, total) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${m.year % 100}.${m.monthValue}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(44.dp))
                    Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(total.toFloat() / max).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(if (unlocked) won(total) else "●●●만원", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(min = 92.dp).padding(start = 8.dp))
                }
            }
            Text("근무표와 인상 규칙으로 계산했어요. 근무표를 바꾸면 바로 다시 계산돼요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard(title = "내 급여 값") {
            ValueRow(
                title = if (profile.wageType == WageType.MONTHLY) "기본급" else "시급",
                value = profile.base.on(month),
                raise = profile.base.raise?.describe(),
                estimated = "base" in profile.estimatedFields,
                enabled = unlocked,
                onEdit = { editTarget = ValueTarget.Base },
                onRaise = { raiseTarget = ValueTarget.Base },
            )
            profile.items.forEach { item ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                ValueRow(
                    title = "${item.name} · ${item.cadence.label}",
                    value = item.amount.on(month),
                    raise = item.amount.raise?.describe(),
                    estimated = item.id in profile.estimatedFields,
                    enabled = unlocked,
                    onEdit = { editTarget = ValueTarget.Item(item.id) },
                    onRaise = { raiseTarget = ValueTarget.Item(item.id) },
                    ordinary = item.ordinary,
                    onOrdinary = { on -> vm.updateItem(item.id) { it.copy(ordinary = on) } },
                    onRemove = { vm.removeItem(item.id) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { addingItem = true }, enabled = unlocked) {
                    Icon(Icons.Filled.Add, null); Text(" 수당 추가")
                }
                OutlinedButton(onClick = { importing = true }, enabled = vm.canUse(Feature.PAY_RULE_IMPORT, tier)) {
                    Icon(Icons.Filled.ContentPaste, null); Text(" 규칙 글로 채우기")
                }
                if (undo != null) {
                    TextButton(onClick = vm::undo) { Icon(Icons.Filled.Undo, null); Text(" 방금 바꾼 것 되돌리기") }
                }
            }
        }

        SectionCard(title = "회사 규칙") {
            RuleSwitch("5인 이상 사업장", "연장·야간·휴일 가산은 5인 이상일 때만 붙어요", profile.fiveOrMoreEmployees, unlocked) { on ->
                vm.update { it.copy(fiveOrMoreEmployees = on) }
            }
            RuleSwitch("하루 8시간 넘으면 연장", "탄력적 근로시간제라면 꺼 주세요", profile.dailyLimitMinutes != null, unlocked) { on ->
                vm.update { it.copy(dailyLimitMinutes = if (on) 480 else null) }
            }
            if (profile.wageType == WageType.MONTHLY) {
                Text("월급제 휴일 근무 계산", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Text("회사마다 해석이 달라요. 급여명세서와 맞는 쪽을 골라 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HolidayPayMode.entries.forEach { mode ->
                        FilterChip(
                            selected = profile.holidayPayMode == mode, enabled = unlocked,
                            onClick = { vm.update { it.copy(holidayPayMode = mode, estimatedFields = it.estimatedFields - "holidayPayMode") } },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { reconciling = true }, enabled = unlocked) {
                    Icon(Icons.Filled.ReceiptLong, null); Text(" 명세서와 대조")
                }
                TextButton(onClick = onEditSetup) { Icon(Icons.Filled.Edit, null); Text(" 처음부터 설정") }
            }
        }

        Glossary()

        Text(
            "회사 급여명세서와 다를 수 있어요. 세금과 4대보험을 빼기 전 금액이에요. 계산 기준: 요율 ${result.ratesVersion}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }

    detail?.let { LineDetailDialog(it, result) { detail = null } }
    editTarget?.let { target ->
        EditValueDialog(vm, profile, snapshot, target, month, onDismiss = { editTarget = null })
    }
    raiseTarget?.let { target ->
        RaiseDialog(vm, profile, target, onDismiss = { raiseTarget = null })
    }
    if (importing) RuleImportDialog(vm, month, onDismiss = { importing = false })
    if (reconciling) ReconcileDialog(vm, profile, snapshot, month, result.total, onDismiss = { reconciling = false })
    if (addingItem) AddItemDialog(onDismiss = { addingItem = false }) { name, amount, cadence, ordinary ->
        vm.addItem(name, amount, cadence, ordinary, month)
        addingItem = false
    }
}

@Composable
private fun TotalCard(result: MonthPay, unlocked: Boolean) {
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer).padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${result.month.monthValue}월 예상 급여 (세전)", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
            if (result.estimated) EstimatedChip()
        }
        val target = if (unlocked) result.total.toFloat() else 0f
        val shown by animateFloatAsState(target, tween(700), label = "total")
        // Locked: the digits are never drawn, only their shape, so screen readers cannot read them either.
        Text(
            if (unlocked) won(shown.toLong()) else won(result.total).replace(Regex("\\d"), "●"),
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = if (unlocked || Build.VERSION.SDK_INT < 31) Modifier else Modifier.blur(6.dp),
        )
        Text("통상시급 ${if (unlocked) won(result.ordinaryHourly) else "●●,●●●원"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
internal fun EstimatedChip(text: String = "추정 포함") {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun LockedCard() {
    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${Tier.PRO.label} 등급에서 열려요", style = MaterialTheme.typography.titleSmall)
                Text("내 급여 합계, 항목별 계산 풀이, 연도별 인상 계산, 일괄 수정, 명세서 대조를 쓸 수 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("결제는 Play 출시 때 열려요.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * First-run walkthrough [PAYX-05]: one shift's time bar split by colour, then minutes → hourly rate
 * → multiplier → amount, one step at a time.
 */
@Composable
private fun Walkthrough(result: MonthPay, onClose: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val example = result.lines.firstOrNull { it.kind == LineKind.NIGHT } ?: result.lines.firstOrNull { it.minutes > 0 }
    SectionCard(title = "이렇게 계산해요 (${step + 1}/4)", trailing = { TextButton(onClick = onClose) { Text("닫기") } }) {
        val texts = listOf(
            "1. 근무표의 시간을 분 단위로 나눠요. 22시~06시는 야간, 하루 8시간·주 40시간을 넘으면 연장, 쉬는 날 일하면 휴일 근무예요.",
            "2. 통상시급을 구해요. 월급제는 (기본급 + 통상임금에 들어가는 수당) ÷ 월 소정근로시간(보통 209시간)이에요.",
            "3. 시간마다 배율을 곱해요. 연장 150%, 야간은 가산 50%를 더하고, 휴일은 8시간까지 150%, 넘으면 200%예요.",
            "4. 항목을 모두 더해요. 금액을 누르면 어떤 식으로 나왔는지 그대로 보여 드려요.",
        )
        TimeBar(step)
        AnimatedVisibility(true) {
            Text(texts[step], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }
        if (example != null && step >= 1) {
            Text(
                when (step) {
                    1 -> "이번 달 통상시급: ${won(result.ordinaryHourly)}"
                    2 -> "${example.label}: ${PayCalculator.hours(example.minutes)} × ${won(example.hourly)} × ${PayCalculator.pct(example.multiplier)}"
                    else -> "${example.label} = ${won(example.amount)} · 합계 ${won(result.total)}"
                },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("이전") }
            if (step < 3) Button(onClick = { step++ }) { Text("다음") } else Button(onClick = onClose) { Text("알겠어요") }
        }
    }
}

/** A sample night shift 22:00~07:00 coloured by what each hour counts as. */
@Composable
private fun TimeBar(step: Int) {
    val night = kindColor(LineKind.NIGHT)
    val base = kindColor(LineKind.BASE)
    val ot = kindColor(LineKind.OVERTIME)
    val parts = listOf(8f to night, 1f to (if (step >= 0) ot else base))
    Column {
        Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp))) {
            parts.forEach { (w, c) -> Box(Modifier.weight(w).fillMaxSize().background(c)) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("22시", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("06시", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(24.dp))
            Text("07시", style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(night, "야간 (가산 50%)")
            Legend(ot, "8시간 넘는 부분 (연장)")
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(" $label", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LineRow(line: PayLine, unlocked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = unlocked, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(kindColor(line.kind)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                if (line.estimated) { Spacer(Modifier.width(6.dp)); EstimatedChip("추정") }
            }
            if (line.minutes > 0) {
                Text("${PayCalculator.hours(line.minutes)} × ${PayCalculator.pct(line.multiplier)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(if (unlocked) won(line.amount) else "●●●,●●●원", style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ValueRow(
    title: String,
    value: com.bamnat.core.pay.ValueAt,
    raise: String?,
    estimated: Boolean,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRaise: () -> Unit,
    ordinary: Boolean? = null,
    onOrdinary: ((Boolean) -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (estimated) { Spacer(Modifier.width(6.dp)); EstimatedChip() }
                }
                Text(
                    won(value.value) + when (value.source) {
                        ValueSource.RAISED -> " (인상 ${value.raisesApplied}회 반영)"
                        else -> ""
                    },
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                )
                if (raise != null) Text(raise, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit, enabled = enabled) { Icon(Icons.Filled.Edit, "값 바꾸기") }
            IconButton(onClick = onRaise, enabled = enabled) { Icon(Icons.Filled.TrendingUp, "인상 규칙") }
        }
        if (ordinary != null && onOrdinary != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("통상임금에 포함", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = ordinary, onCheckedChange = onOrdinary, enabled = enabled)
                if (onRemove != null) TextButton(onClick = onRemove, enabled = enabled) { Text("삭제") }
            }
        }
    }
}

@Composable
private fun RuleSwitch(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** One-line explanations of the words pay slips use [PAYX-05 용어 설명]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Glossary() {
    val terms = listOf(
        "통상임금" to "정해진 일을 하면 매달 고정으로 받는 임금. 연장·야간·휴일 수당을 계산하는 기준이에요.",
        "통상시급" to "통상임금을 월 소정근로시간으로 나눈 1시간 값이에요.",
        "소정근로시간" to "근로계약으로 정한 일하는 시간. 주 40시간이면 한 달 209시간(주휴 포함)으로 봐요.",
        "가산" to "법이 정한 추가분. 연장·야간·휴일 근무에 통상시급의 50%(휴일 8시간 초과는 100%)를 더해요.",
        "추정" to "입력하지 않은 값을 앱이 짐작해 넣은 부분이에요. 실제 값으로 바꾸면 사라져요.",
    )
    var open by remember { mutableStateOf<String?>(null) }
    SectionCard(title = "용어 풀이") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            terms.forEach { (t, _) -> AssistChip(onClick = { open = if (open == t) null else t }, label = { Text(t) }) }
        }
        open?.let { t -> Text(terms.first { it.first == t }.second, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
    }
}
