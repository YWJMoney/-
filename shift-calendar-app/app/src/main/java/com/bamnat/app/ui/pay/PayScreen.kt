package com.bamnat.app.ui.pay

import com.bamnat.app.ui.theme.CalendarColors
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.components.labelWidth
import com.bamnat.app.pay.named

import com.bamnat.core.pay.LeaveBook
import android.os.Build
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
import androidx.compose.material.icons.automirrored.filled.HelpOutline
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Gate
import com.bamnat.app.ui.money.ProPill
import com.bamnat.app.ui.pro.openPro
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.pay.CalcSnapshot
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.LineKind
import com.bamnat.core.pay.MonthPay
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayLine
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.PayRange
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.RestDayWork
import com.bamnat.core.pay.ValueSource
import com.bamnat.core.pay.WageType
import java.time.YearMonth

internal fun won(v: Long): String = "%,d원".format(v)

/** Colours for line kinds, matching the walkthrough's time bar. [VIS-03] The theme's tokens, light and dark. */
@Composable
internal fun kindColor(kind: LineKind): Color = payKindColor(kind, LocalCalendarColors.current, MaterialTheme.colorScheme.primary)

/** [VIS-03] The same without a composition, for tests and drawings. */
internal fun payKindColor(kind: LineKind, c: CalendarColors, primary: Color): Color = when (kind) {
    LineKind.BASE -> primary
    LineKind.OVERTIME -> c.payOvertime
    LineKind.NIGHT -> c.payNight
    LineKind.HOLIDAY -> c.payHoliday
    LineKind.EXTRA -> c.payExtra
    LineKind.EXTRA_HOURS -> c.payExtraHours
    LineKind.ALLOWANCE -> c.payAllowance
    LineKind.LEAVE -> c.payLeave
}

/**
 * 급여 계산 [PAY-09, PAYX-01..06]: set up once with the wizard, then every month is calculated from the
 * schedule. Every amount explains itself; free users see the sample and a blurred total.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayScreen(
    vm: PayViewModel,
    onBack: () -> Unit,
    onOpenExtraKinds: () -> Unit = {},
    /** [LEAVE-06] The 연차 screen of this calendar. */
    onOpenLeave: () -> Unit = {},
    /** [COMM-02][COMM-03] Cards above the result: sharing a hand-made setup, 맞았어요·달라요. */
    top: @Composable () -> Unit = {},
    /** [DAILY-16] A card right under the month's total (광장 시세와 견주기). */
    underTotal: @Composable () -> Unit = {},
    /** [ADMIN-02] 계산이 이상해요 opens 문의하기 with this month's pay check; null sends it by mail or chat app. */
    onReport: ((String) -> Unit)? = null,
    /** [PAYS-09] 회사 설정 보내기, for the invitation after a match or an item of the company's own; null leaves it out. */
    onShareWay: (() -> Unit)? = null,
    /** [PAYS-10] The ‘?’ in the top bar: pay help first, then 문의. Null leaves it out. */
    onHelp: (() -> Unit)? = null,
) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val gate by vm.gate.collectAsStateWithLifecycle()
    var editingSetup by rememberSaveable { mutableStateOf(false) }
    var editingRules by rememberSaveable { mutableStateOf(false) }
    // The back gesture does what the arrow does: close the editor, not the whole pay screen.
    androidx.activity.compose.BackHandler(enabled = editingSetup || editingRules) {
        if (editingSetup) editingSetup = false else editingRules = false
    }
    // [PAYX-22] A change that moves a month matched with a payslip.
    val verified by vm.verifiedPrompt.collectAsStateWithLifecycle()
    verified?.let { v ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::keepVerifiedChange,
            title = { Text("명세서와 맞춘 달이 바뀌어요") },
            text = {
                Text(v.change.text + if (v.scoped != null) " 이번 급여기간부터 바꾸면 그 달은 그대로예요." else " 이 바꿈은 날짜가 없는 규칙이라 그 달에도 적용돼요.")
            },
            confirmButton = {
                if (v.scoped != null) TextButton(onClick = vm::startChangeNow) { Text("이번 급여기간부터") }
                else TextButton(onClick = vm::keepVerifiedChange) { Text("그대로 두기") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = vm::undoVerifiedChange) { Text("되돌리기") }
                    if (v.scoped != null) TextButton(onClick = vm::keepVerifiedChange) { Text("모든 달에") }
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when {
                        profile == null || editingSetup -> "급여 계산 설정"
                        editingRules -> "회사 규칙"
                        else -> "급여 계산"
                    })
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            editingSetup -> editingSetup = false
                            editingRules -> editingRules = false
                            else -> onBack()
                        }
                    }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") }
                },
                actions = {
                    // [PAYS-10] 계산이 이상해요 and 명세서로 맞춰 보기 sit far down; help is one tap from the top.
                    if (onHelp != null) IconButton(onClick = onHelp) { Icon(Icons.AutoMirrored.Filled.HelpOutline, "급여 도움말과 문의") }
                },
            )
        },
    ) { padding ->
        val snap = snapshot
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                snap == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                profile == null || editingSetup -> {
                    val month by vm.month.collectAsStateWithLifecycle()
                    val settings by vm.settings.collectAsStateWithLifecycle()
                    PayWizard(
                        initial = profile,
                        onCancel = if (profile != null) ({ editingSetup = false }) else null,
                        onDone = { p -> vm.saveSetup(p); editingSetup = false },
                        snapshot = snap,
                        preview = { draft -> vm.preview(draft, snap, month) },
                        suggestedWage = if (settings.workStyle == com.bamnat.core.work.WorkStyle.HOURLY) WageType.HOURLY else null,
                    )
                }
                editingRules -> {
                    val month by vm.month.collectAsStateWithLifecycle()
                    CompanyRulesEditor(vm, profile!!, snap, month, onClose = { editingRules = false }, onOpenKinds = onOpenExtraKinds)
                }
                else -> PayResult(vm, profile!!, snap, gate, onEditSetup = { editingSetup = true }, onEditRules = { editingRules = true }, top = top,
                    underTotal = underTotal, onReport = onReport, onShareWay = onShareWay, onOpenLeave = onOpenLeave)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PayResult(
    vm: PayViewModel,
    profile: PayProfile,
    snapshot: ScheduleSnapshot,
    gate: Gate,
    onEditSetup: () -> Unit,
    onEditRules: () -> Unit,
    top: @Composable () -> Unit = {},
    underTotal: @Composable () -> Unit = {},
    onReport: ((String) -> Unit)? = null,
    onShareWay: (() -> Unit)? = null,
    onOpenLeave: () -> Unit = {},
) {
    val month by vm.month.collectAsStateWithLifecycle()
    val undo by vm.undoAvailable.collectAsStateWithLifecycle()
    // [MON-01] The month's pay, its lines and its settings are free (결정 2). 명세서 대조, 규칙 글로 채우기
    // and 값 일괄 적용 (기간 지정, 연도별 인상) are pro; locked, they open the 프로 screen.
    val unlocked = true
    val bulkOpen = gate.allows(Feature.PAY_BULK_EDIT)
    val closed by vm.closed.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // [PAYS-01] Worked out on a background thread; the month shown stays until the next one is ready.
    val view by produceState<PayView?>(null, profile, snapshot, month, closed, settings.extraKinds, settings.restDayCompared) {
        // [STAB-01] A calculation that fails keeps the last month shown instead of closing the app.
        value = runCatching { vm.view(profile, snapshot, month) }
            .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it else value }
    }
    val v = view
    if (v == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val result = v.result
    val closedSnap = closed[result.month]
    // A closed month shows what was saved, not today's recalculation [PAYX-03].
    val shown = remember(result, closedSnap) {
        closedSnap?.let { result.copy(lines = it.lines, ratesVersion = it.ratesVersion) } ?: result
    }
    val year = v.year
    val range = v.range.takeIf { closedSnap == null }
    val context = LocalContext.current

    var detail by remember { mutableStateOf<PayLine?>(null) }
    var editTarget by remember { mutableStateOf<ValueTarget?>(null) }
    var raiseTarget by remember { mutableStateOf<ValueTarget?>(null) }
    var importing by remember { mutableStateOf(false) }
    var matching by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var amountFor by remember { mutableStateOf<com.bamnat.core.pay.MissingItem?>(null) }
    var addingItem by remember { mutableStateOf(false) }
    var addingBonus by remember { mutableStateOf(false) }
    var walkthrough by rememberSaveable { mutableStateOf(true) }
    // [PAYS-07] 우리 회사 항목 만들기, a rule made before being changed, and the item just made.
    var building by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<com.bamnat.core.pay.CustomRule?>(null) }
    var justMade by remember { mutableStateOf<MadeItem?>(null) }
    // [PAYS-11] A 추정 badge tapped: what to put in so it goes away.
    var estimate by remember { mutableStateOf<EstimateHint?>(null) }
    val shiftNames = remember(snapshot) { snapshot.shiftTypes.associate { it.id to it.name } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${profile.occupation.label} · ${profile.wageLabel()}",
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.moveMonth(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
            Text(Fmt.month(month), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { vm.moveMonth(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
        }
        // The month asked for is still being worked out; the one before stays meanwhile.
        if (result.month != month) LinearProgressIndicator(Modifier.fillMaxWidth())

        top()

        TotalCard(shown, range, unlocked, closed = closedSnap != null, onChecks = { checking = true },
            onEstimate = { estimate = Estimates.forMonth(shown, profile) })

        underTotal()

        // [HRLY-04..HRLY-08] 예상 실수령 and the settings only a part-timer needs.
        if (settings.workStyle == com.bamnat.core.work.WorkStyle.HOURLY) HourlyPayCard(vm, profile, shown.total, unlocked, snapshot, month)

        // [PAYS-01] Allowances whose amount is not known are in no figure; each says how often it came up.
        if (closedSnap == null && result.missing.isNotEmpty()) {
            SectionCard(title = "합계에 넣지 않은 수당") {
                result.missing.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${m.name} · 금액 모름" + (m.times?.let { " · ${it}회" } ?: ""), style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { amountFor = m }, enabled = unlocked) { Text("금액 넣기") }
                    }
                }
                Text("금액을 넣으면 합계에 더해요. 명세서로 맞춰 보면 한 번에 채울 수도 있어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // [EXTRA-05] 1.8 paid a day off as 휴일근로; the law's way is shown next to it once.
        val law = v.restDayLaw
        if (unlocked && closedSnap == null && law != null && law != result.total) {
            RestDayCompare(vm, result.month, result.total, law)
        }

        if (walkthrough && unlocked) {
            Walkthrough(shown, onClose = { walkthrough = false })
        }

        SectionCard(title = "항목별 내역", trailing = { Text("누르면 계산 풀이", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
            for (line in shown.lines) {
                LineRow(line, unlocked, onEstimate = { estimate = Estimates.forLine(line, profile, shown.month) }) { if (unlocked) detail = line }
            }
            if (shown.lines.isEmpty()) Text("이 달은 계산할 근무가 없어요", style = MaterialTheme.typography.bodyMedium)
        }

        DayLedger(result, unlocked)

        LeaveCard(vm, snapshot, month, onOpenLeave)

        if (unlocked && closedSnap == null && v.holidayModes.isNotEmpty()) {
            HolidayModes(vm, profile, v.holidayModes)
        }

        if (unlocked && !month.isAfter(YearMonth.now())) {
            CloseMonthCard(vm, result, closedSnap)
        }

        // [PROV-01] The month or its year as a PDF or CSV (프로).
        PayReportCard(vm, profile, snapshot, shown, closed = closedSnap != null, gate = gate)

        if (result.warnings.isNotEmpty()) {
            SectionCard(title = "확인해 주세요") {
                result.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }

        SectionCard(title = "앞으로 12개월 예상") {
            val max = (year.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1L)
            year.forEach { (m, total) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${m.year % 100}.${m.monthValue}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.labelWidth(44.dp))
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

        // [JOB-02] 공무원·경찰·소방만: 봉급표에서 계급·호봉으로 채우기.
        if (com.bamnat.core.pay.PublicServantPay.isPublic(profile.occupation)) {
            PublicGradeCard(profile, month.atDay(1), unlocked) { g -> vm.update { com.bamnat.core.pay.PublicServantPay.apply(it, g, month.atDay(1)) } }
        }

        SectionCard(title = "내 급여 값") {
            // [PAYS-07][PAYS-09] What was just made, and then the invitation to share the company's way.
            justMade?.let { made ->
                MadeNotice(made, onUndo = if (undo != null) ({ vm.undo(); justMade = null }) else null)
                com.bamnat.app.ui.company.ShareWayCard(
                    com.bamnat.app.ui.company.ShareMoment.MADE, onShare = onShareWay,
                    modifier = Modifier.padding(vertical = 8.dp),
                    ways = waysText(profile, shiftNames, made),
                    notInFile = made is MadeItem.Rule || profile.customRules.isNotEmpty(),
                )
            }
            ValueRow(
                title = profile.wageType.baseName() + if (profile.wageType == WageType.DAILY) " (하루 ${PayCalculator.hours(profile.dailyHours)})" else "",
                value = profile.base.on(month),
                raise = profile.base.raise?.describe() ?: profile.annual?.let { "연봉 ${won(it.yearly)}에서 계산 (÷${it.divisor})" },
                estimated = Unknowns.BASE in profile.estimatedFields,
                enabled = unlocked,
                onEdit = { editTarget = ValueTarget.Base },
                onRaise = { if (bulkOpen) raiseTarget = ValueTarget.Base else openPro() },
                onEstimate = { estimate = Estimates.forValue(profile, month, null) },
            )
            profile.items.forEach { item ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                if (item.cadence == Cadence.BONUS) {
                    BonusRow(item, profile.base.on(month).value, month, unlocked,
                        onOrdinary = { on -> vm.updateItem(item.id) { it.copy(ordinary = on) } },
                        onRemove = { vm.removeItem(item.id) })
                    return@forEach
                }
                ValueRow(
                    title = "${item.name} · " + if (item.isFixedOvertime) "고정 연장 ${item.fixedOvertimeMinutes / 60}시간분" else item.cadence.label,
                    value = item.amount.on(month),
                    raise = item.amount.raise?.describe(),
                    estimated = item.id in profile.estimatedFields,
                    unknownAmount = Unknowns.item(item.id) in profile.estimatedFields,
                    enabled = unlocked,
                    onEdit = { editTarget = ValueTarget.Item(item.id) },
                    onRaise = { if (bulkOpen) raiseTarget = ValueTarget.Item(item.id) else openPro() },
                    // Only a monthly allowance can raise the 통상시급, so only it has the switch.
                    ordinary = item.ordinary.takeIf { item.cadence == Cadence.MONTHLY && !item.isFixedOvertime },
                    onOrdinary = { on -> vm.updateItem(item.id) { it.copy(ordinary = on) } },
                    onRemove = { vm.removeItem(item.id) },
                    onEstimate = { estimate = Estimates.forValue(profile, month, item) },
                    // Only on allowances that may be 비과세, so the list does not grow a switch per line.
                    taxFree = (!item.taxable).takeIf {
                        item.cadence == Cadence.MONTHLY && !item.isFixedOvertime && (!item.taxable || com.bamnat.core.pay.TakeHome.looksNonTaxable(item.name))
                    },
                    onTaxFree = { on -> vm.updateItem(item.id) { it.copy(taxable = !on) } },
                )
            }
            // [PAYS-07] Rules of the company's own, each with its switch; the ones the builder can show open in it.
            if (profile.customRules.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("직접 만든 항목", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                profile.customRules.forEach { rule ->
                    CustomRuleRow(rule, shiftNames, unlocked,
                        onEdit = if (ItemKind.of(rule) != null) ({ editingRule = rule }) else null,
                        onOn = { vm.setCustomRuleOn(rule.id, it) },
                        onRemove = { vm.removeCustomRule(rule.id) })
                }
            }
            MakeItemCard(enabled = unlocked) { building = true }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { addingItem = true }, enabled = unlocked) {
                    Icon(Icons.Filled.Add, null); Text(" 수당 추가")
                }
                OutlinedButton(onClick = { addingBonus = true }, enabled = unlocked) {
                    Icon(Icons.Filled.Add, null); Text(" 상여금 추가")
                }
                OutlinedButton(onClick = { if (gate.allows(Feature.PAY_RULE_IMPORT)) importing = true else openPro() }) {
                    Icon(Icons.Filled.ContentPaste, null); Text(" 규칙 글로 채우기")
                    if (gate.locked(Feature.PAY_RULE_IMPORT)) { Spacer(Modifier.width(6.dp)); ProPill() }
                }
                if (undo != null) {
                    TextButton(onClick = vm::undo) { Icon(Icons.Filled.Undo, null); Text(" 방금 바꾼 것 되돌리기") }
                }
            }
        }

        SectionCard(title = "회사 규칙", trailing = { TextButton(onClick = onEditRules, enabled = unlocked) { Text("설정") } }) {
            val summary = profile.named(com.bamnat.app.ui.calendar.LocalExtraKinds.current).rules.summary(profile)
            Text(
                (if (profile.fiveOrMoreEmployees) "5인 이상" else "5인 미만") +
                    (if (profile.scheduleIsRegular) " · 근무표 시간은 기본 근무" + (profile.flexPeriod?.let { " (탄력제 ${it.unit})" } ?: "")
                    else if (profile.dailyLimitMinutes == null) " · 하루 8시간 기준 없음" else "") +
                    (if (profile.wageType == WageType.MONTHLY) " · 월급제 휴일 ${profile.holidayPayMode.label} · 월 ${profile.monthlyHours}시간" else "") +
                    (if (profile.wageType == WageType.DAILY) " · 일당은 하루 ${PayCalculator.hours(profile.dailyHours)}분" else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            // [PAYS-02] The last payslip check, kept on the phone only.
            profile.verified?.let { c ->
                val gap = c.gross - c.appTotal
                Text("${Fmt.month(c.month)} 명세서와 맞춰 봤어요" + if (gap == 0L) "" else " (그때 차이 ${if (gap > 0) "+" else "−"}${won(kotlin.math.abs(gap))})",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // [PAY-05] The regular bonus decides the hourly rate, so it is shown next to the hours.
            profile.items.filter { it.cadence == Cadence.BONUS }.forEach { b ->
                val yearly = b.percentOfBase?.let { "연 ${PayCalculator.pct(it * b.yearlyTimes)}" } ?: "${b.yearlyTimes}번"
                Text("${b.name} $yearly (${b.paidWhen()})" + if (b.ordinary) " · 통상임금 포함" else "",
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (summary.isEmpty()) {
                Text("나머지는 모두 법정 기준으로 계산하고 있어요. 대근·특근 단가나 가산율이 다르면 ‘설정’에서 바꿔 주세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                summary.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { if (gate.allows(Feature.PAYSLIP_MATCH)) matching = true else openPro() }) {
                    Icon(Icons.Filled.ReceiptLong, null); Text(" 명세서로 맞춰 보기")
                    if (gate.locked(Feature.PAYSLIP_MATCH)) { Spacer(Modifier.width(6.dp)); ProPill() }
                }
                TextButton(onClick = onEditSetup) { Icon(Icons.Filled.Edit, null); Text(" 처음부터 설정") }
            }
        }

        Glossary()

        TextButton(onClick = {
            if (onReport != null) onReport(payCheck(profile, shown)) else reportProblem(context, profile, shown)
        }) { Text("계산이 이상해요 (오류 제보)") }
        Text(
            "회사 급여명세서와 다를 수 있어요. 세금과 4대보험을 빼기 전 금액이에요. 계산 기준: 요율 ${shown.ratesVersion}, " +
                "공휴일 ${KoreanHolidays.VERSION}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }

    detail?.let { LineDetailDialog(it, shown) { detail = null } }
    editTarget?.let { target ->
        EditValueDialog(vm, profile, snapshot, target, month, onDismiss = { editTarget = null }, rangeOpen = bulkOpen)
    }
    raiseTarget?.let { target ->
        RaiseDialog(vm, profile, target, onDismiss = { raiseTarget = null })
    }
    if (importing) RuleImportDialog(vm, month, onDismiss = { importing = false })
    if (matching) PayslipMatchDialog(vm, profile, snapshot, result.month, onDismiss = { matching = false }, onShareWay = onShareWay)
    if (building || editingRule != null) CustomItemDialog(
        vm, profile, snapshot, month,
        onDismiss = { building = false; editingRule = null },
        onSave = { made -> vm.saveMade(made); if (editingRule == null) justMade = made; building = false; editingRule = null },
        editing = editingRule,
    )
    if (checking && range != null) UnknownsSheet(vm, range, profile, result.month,
        onMatch = { checking = false; matching = true },
        onEdit = { target -> checking = false; editTarget = target },
        onDismiss = { checking = false })
    amountFor?.let { m ->
        AmountDialog("${m.name} 금액", if (m.times == null) "한 달 금액" else "1회 금액", onDismiss = { amountFor = null }) { amount ->
            vm.setItemAmount(m.id, amount, result.month)
            amountFor = null
        }
    }
    estimate?.let { hint ->
        EstimateDialog(hint, onDismiss = { estimate = null }, onAct = {
            hint.fill?.let { editTarget = it }
            if (hint.checks) checking = true
        })
    }
    if (addingBonus) AddBonusDialog(onDismiss = { addingBonus = false }) { name, months, percent, amount, ordinary, festivals, before ->
        vm.addBonus(name, months, percent, amount, ordinary, month, festivals, before)
        addingBonus = false
    }
    if (addingItem) AddItemDialog(onDismiss = { addingItem = false }, allowUnknownAmount = true) { name, amount, cadence, ordinary, fixedOt ->
        vm.addItem(name, amount, cadence, ordinary, month, fixedOt)
        addingItem = false
    }
}

/**
 * The month's total [PAY-09]. [PAYS-01] While answers are 모르겠어요 it is a range, "약 312만~341만 원",
 * with how many answers need checking; tapping it shows what each one changes.
 */
@Composable
private fun TotalCard(
    result: MonthPay, range: PayRange?, unlocked: Boolean, closed: Boolean = false, onChecks: () -> Unit = {},
    onEstimate: (() -> Unit)? = null,
) {
    val checks = range?.effects?.size ?: 0
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = unlocked && checks > 0, onClickLabel = "확인 필요한 답 보기", onClick = onChecks)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${result.month.monthValue}월 예상 급여 (세전)", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                // [PAYX-15] Which days it covers and when it arrives.
                val from = result.periodStart
                val to = result.periodEnd
                val note = listOfNotNull(
                    if (from != null && to != null) "${from.monthValue}/${from.dayOfMonth}~${to.monthValue}/${to.dayOfMonth} 근무분" else null,
                    result.payDate?.let { "${it.monthValue}월 ${it.dayOfMonth}일 지급" },
                ).joinToString(" · ")
                if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
            if (closed) { EstimatedChip("마감"); Spacer(Modifier.width(6.dp)) }
            if (checks > 0) EstimatedChip("확인 필요 $checks") else if (result.estimated) EstimatedChip(onClick = onEstimate.takeIf { unlocked })
        }
        val spread = range != null && !range.exact
        val target = if (unlocked) result.total.toFloat() else 0f
        val shown by animateFloatAsState(target, tween(700), label = "total")
        // Locked: the digits are never drawn, only their shape, so screen readers cannot read them either.
        val text = when {
            !unlocked -> won(result.total).replace(Regex("\\d"), "●")
            spread -> rangeText(range!!.low, range.high)
            else -> won(shown.toLong())
        }
        Text(
            text,
            style = (if (spread) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall).copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = if (unlocked || Build.VERSION.SDK_INT < 31) Modifier else Modifier.blur(6.dp),
        )
        if (spread && unlocked) {
            Text("지금 답으로는 ${won(result.total)} · 눌러서 확인 필요 ${checks}개 보기", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        } else if (checks > 0 && unlocked) {
            Text("눌러서 확인 필요 ${checks}개 보기", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text("통상시급 ${if (unlocked) won(result.ordinaryHourly) else "●●,●●●원"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * [PAYS-01] Every 확인 필요 answer: what the app assumes, how far the month moves with the other answers,
 * and the question again, so one tap settles it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun UnknownsSheet(
    vm: PayViewModel,
    range: PayRange,
    profile: PayProfile,
    month: YearMonth,
    onMatch: () -> Unit,
    onEdit: (ValueTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("확인이 필요한 답 ${range.effects.size}개", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (range.exact) "답을 몰라도 이번 달 금액은 같아요. 아는 대로 골라 두면 다음 달부터 더 정확해져요."
                else "답에 따라 이번 달이 ${won(range.low)}~${won(range.high)}까지 달라져요. 아는 답을 고르면 범위가 좁아져요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            range.effects.forEach { e ->
                val key = e.key
                androidx.compose.material3.Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.unknown.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(effectText(e), style = MaterialTheme.typography.labelMedium,
                                color = if (e.moves) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(e.unknown.assumed, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val item = profile.items.firstOrNull { Unknowns.item(it.id) == key || it.id == key }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                key.startsWith(Unknowns.item("")) && item != null -> {
                                    OutlinedButton(onClick = { onEdit(ValueTarget.Item(item.id)) }) { Text("금액 넣기") }
                                }
                                key == Unknowns.BASE || item != null -> {
                                    OutlinedButton(onClick = { vm.answer(key) }) { Text("이 금액이 맞아요") }
                                    TextButton(onClick = { onEdit(if (item != null) ValueTarget.Item(item.id) else ValueTarget.Base) }) { Text("금액 고치기") }
                                }
                                else -> {
                                    OutlinedButton(onClick = { vm.answer(key) }) { Text("지금대로 맞아요") }
                                    e.unknown.answers.forEach { a ->
                                        OutlinedButton(onClick = { vm.answer(key, a.apply) }) { Text(a.label) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(onClick = onMatch, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.ReceiptLong, null); Text(" 명세서로 한 번에 맞춰 보기")
            }
            Text("명세서 금액은 이 휴대폰에만 남아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "−8.3만~+4.1만 원", "최대 +4.1만 원", or that the month does not move. */
private fun effectText(e: com.bamnat.core.pay.UnknownEffect): String = when {
    !e.moves -> "금액 변화 없음"
    e.down < 0 && e.up > 0 -> "−${manwon(-e.down)}~+${manwon(e.up)} 원"
    e.up > 0 -> "최대 +${manwon(e.up)} 원"
    else -> "최대 −${manwon(-e.down)} 원"
}

/** [PAYS-01] An amount for an allowance that had none. */
@Composable
private fun AmountDialog(title: String, label: String, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var amount by remember { mutableStateOf<Long?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { MoneyField(label, amount, { amount = it }) },
        confirmButton = { Button(onClick = { amount?.let(onSave) }, enabled = (amount ?: 0) > 0) { Text("넣기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/** [PAYS-11] With [onClick] the badge says, when tapped, what to put in so it goes away; 48 dp to hit. */
@Composable
internal fun EstimatedChip(text: String = "추정 포함", onClick: (() -> Unit)? = null) {
    val tap = if (onClick == null) Modifier else Modifier.minimumInteractiveComponentSize()
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = tap.clip(RoundedCornerShape(8.dp))
            .then(if (onClick == null) Modifier else Modifier.clickable(onClickLabel = "무엇을 넣으면 확정되는지 보기", role = Role.Button, onClick = onClick))
            .background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 8.dp, vertical = 2.dp),
    )
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
        // [285] Each step's text fades in over the last instead of jumping.
        androidx.compose.animation.AnimatedContent(step, label = "walkthrough") { s ->
            Text(texts[s], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
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
private fun LineRow(line: PayLine, unlocked: Boolean, onEstimate: () -> Unit = {}, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = unlocked, onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(kindColor(line.kind)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                if (line.estimated) { Spacer(Modifier.width(6.dp)); EstimatedChip("추정", onClick = onEstimate.takeIf { unlocked }) }
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
    /** [PAYR-07] 비과세 (식대 등), for the take-home estimate; null hides the switch. */
    taxFree: Boolean? = null,
    onTaxFree: ((Boolean) -> Unit)? = null,
    /** [PAYS-01] 금액 모름: left out of the total. */
    unknownAmount: Boolean = false,
    /** [PAYS-11] The badge tapped. */
    onEstimate: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                    val tap = onEstimate.takeIf { enabled }
                    if (unknownAmount) { Spacer(Modifier.width(6.dp)); EstimatedChip("확인 필요", onClick = tap) }
                    else if (estimated) { Spacer(Modifier.width(6.dp)); EstimatedChip(onClick = tap) }
                }
                Text(
                    if (unknownAmount) "금액 모름 · 합계에서 뺐어요" else won(value.value) + when (value.source) {
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
        if ((ordinary != null && onOrdinary != null) || onRemove != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ordinary != null && onOrdinary != null) {
                    Text("통상임금에 포함", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(checked = ordinary, onCheckedChange = onOrdinary, enabled = enabled)
                } else Spacer(Modifier.weight(1f))
                if (onRemove != null) TextButton(onClick = onRemove, enabled = enabled) { Text("삭제") }
            }
        }
        if (taxFree != null && onTaxFree != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("세금 없는 돈 (식대 등, 월 20만 원까지)", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Switch(checked = taxFree, onCheckedChange = onTaxFree, enabled = enabled)
            }
        }
    }
}

/** A bonus line [PAY-05]: when it is paid, how much, and whether it raises the 통상시급. */
@Composable
private fun BonusRow(item: PayItem, base: Long, month: YearMonth, enabled: Boolean, onOrdinary: (Boolean) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("${item.name} · ${item.paidWhen()}", style = MaterialTheme.typography.bodyLarge)
        Text(
            won(item.bonusPayment(base, month)) + (item.percentOfBase?.let { " (기본급 ${PayCalculator.pct(it)})" } ?: "") +
                " × 연 ${item.timesInYear(month.year)}회",
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        )
        Text(
            if (item.ordinary) "통상임금에 한 달 ${won(item.bonusMonthlyShare(base, month))}씩 들어가 연장·야간 수당이 올라가요"
            else "통상임금에서 뺐어요 (성과에 따라 달라지는 상여 등)",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("통상임금에 포함", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Switch(checked = item.ordinary, onCheckedChange = onOrdinary, enabled = enabled)
            TextButton(onClick = onRemove, enabled = enabled) { Text("삭제") }
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
    // [SETUP-04] The same words the setup explains where it asks them.
    val terms = PayTerms.all.map { t -> t.name to (t.meaning + (t.example?.let { "\n예) $it" } ?: "")) }
    var open by remember { mutableStateOf<String?>(null) }
    SectionCard(title = "용어 풀이") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            terms.forEach { (t, _) -> AssistChip(onClick = { open = if (open == t) null else t }, label = { Text(t) }) }
        }
        open?.let { t -> Text(terms.first { it.first == t }.second, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/**
 * 날짜별 내역 [PAYX-08]: every calendar day that adds to pay, with its shift, 잔업·특근·대근 mark and
 * holiday, so a change on the calendar can be traced to the money.
 */
@Composable
private fun DayLedger(result: MonthPay, unlocked: Boolean) {
    val days = result.days.filter { it.amount > 0 }
    if (days.isEmpty()) return
    var expanded by rememberSaveable(result.month) { mutableStateOf(false) }
    var open by remember(result.month) { mutableStateOf<java.time.LocalDate?>(null) }
    val shown = if (expanded) days else days.take(6)
    SectionCard(title = "날짜별 내역 (${days.size}일)", trailing = {
        Text("달력과 연결돼 있어요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }) {
        shown.forEach { d ->
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable(enabled = unlocked) { open = if (open == d.date) null else d.date }
                .padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${d.date.monthValue}/${d.date.dayOfMonth} (${Fmt.dayOfWeekShort(d.date.dayOfWeek)})", style = MaterialTheme.typography.labelLarge,
                        color = if (d.holidayName != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.labelWidth(76.dp))
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(d.label, d.holidayName).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            d.parts.map { it.kind }.distinct().forEach { k ->
                                Box(Modifier.padding(top = 3.dp).size(8.dp).clip(RoundedCornerShape(2.dp)).background(kindColor(k)))
                            }
                        }
                    }
                    Text(if (unlocked) won(d.amount) else "●●,●●●원", style = MaterialTheme.typography.titleSmall)
                }
                if (open == d.date) {
                    d.parts.forEach { part ->
                        Row(Modifier.fillMaxWidth().padding(start = 76.dp, top = 2.dp)) {
                            Text("${part.label} · ${part.formula}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text(won(part.amount), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (days.size > 6) {
            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "${days.size - 6}일 더 보기") }
        }
    }
}

/**
 * [EXTRA-05] Work on a day off the way 1.8 paid it (휴일근로) next to the law's (휴무일: 8·40시간 안 100%),
 * shown once when the month differs. The user keeps their way or moves to the law's; either is final.
 */
@Composable
private fun RestDayCompare(vm: PayViewModel, month: YearMonth, now: Long, law: Long) {
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("쉬는 날 근무, 법대로와 비교해 보세요", style = MaterialTheme.typography.titleSmall)
            Text(
                "지금은 예전처럼 쉬는 날 근무를 휴일근로(8시간까지 150%)로 계산해요. 법으로 휴무일 근무는 하루 8시간·주 40시간 " +
                    "안이면 100%, 넘는 시간만 150%예요(근로기준법 제50조·제56조).",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("지금 방식", style = MaterialTheme.typography.labelMedium)
                    Text(won(now), style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text("법대로", style = MaterialTheme.typography.labelMedium)
                    Text(won(law), style = MaterialTheme.typography.titleMedium)
                }
            }
            Text("${Fmt.monthShort(month)} 차이 ${won(kotlin.math.abs(now - law))}. 취업규칙이나 명세서에 휴일근로로 준다고 되어 있으면 그대로 두세요.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = vm::restDayCompared) { Text("지금대로 두기") }
                Button(onClick = {
                    vm.updateRules { it.copy(restDayWork = RestDayWork.LAW) }
                    vm.restDayCompared()
                }) { Text("법대로 바꾸기") }
            }
        }
    }
}

/** Both readings of 월급제 휴일 근무 side by side, the chosen one marked [PAY-06]. */
@Composable
private fun HolidayModes(vm: PayViewModel, profile: PayProfile, totals: List<Pair<HolidayPayMode, Long>>) {
    SectionCard(title = "휴일 근무, 두 가지 해석") {
        Text("월급제 휴일 근무는 회사마다 계산이 달라요. 명세서와 맞는 쪽을 고르세요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            totals.forEach { (mode, total) ->
                val chosen = profile.holidayPayMode == mode
                androidx.compose.material3.Surface(
                    onClick = { vm.update { it.copy(holidayPayMode = mode, estimatedFields = it.estimatedFields - Unknowns.HOLIDAY_PAY_MODE) } },
                    shape = MaterialTheme.shapes.medium,
                    color = if (chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(mode.label + if (chosen) " ✓" else "", style = MaterialTheme.typography.labelLarge)
                        Text(won(total), style = MaterialTheme.typography.titleMedium)
                        Text(if (mode == HolidayPayMode.FULL) "그날 몫 100%를 더 주고 가산" else "월급에 그날 몫 포함, 가산만",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** 연차 usable, planned and lapsing, from the 연차 원장 [FEAT-10][LEAVE-01]; the whole ledger is one tap away. */
@Composable
private fun LeaveCard(vm: PayViewModel, snapshot: ScheduleSnapshot, month: YearMonth, onOpenLeave: () -> Unit) {
    val s by vm.leave.collectAsStateWithLifecycle()
    val summary = s ?: return
    val others = remember(snapshot, month.year) {
        (1..month.lengthOfYear()).map { month.withMonth(1).atDay(1).plusDays(it - 1L) }
            .mapNotNull { snapshot.resolver.resolve(it).leave }.filter { !it.kind.annual }
            .groupBy { it.kind }.mapValues { (_, l) -> l.sumOf { it.days } }
    }
    if (summary.lines.isEmpty() && others.isEmpty()) {
        SectionCard(title = "연차", trailing = { TextButton(onClick = onOpenLeave) { Text("연차 화면") } }) {
            Text("입사일을 넣으면 연차를 법대로 세고, 쓴 날과 없어질 날을 알려 드려요. 달력에서 날을 길게 눌러 연차·반차를 넣어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    SectionCard(title = "연차", trailing = { TextButton(onClick = onOpenLeave) { Text("연차 화면") } }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LeaveStat("쓸 수 있는", LeaveBook.days(summary.available), Modifier.weight(1f), warn = summary.available < 0)
            LeaveStat("예정", LeaveBook.days(summary.planned), Modifier.weight(1f))
            LeaveStat("예정 뒤 남는", LeaveBook.days(summary.afterPlanned), Modifier.weight(1f), warn = summary.afterPlanned < 0)
        }
        // [LEAVE-03] The next lapse with what would go after the planned days.
        summary.nextLapse?.takeIf { it.second > 0 && !it.first.isAfter(summary.today.plusMonths(6)) }?.let { (date, days) ->
            Text("${date.monthValue}/${date.dayOfMonth} 소멸 예정 ${LeaveBook.days(days)}. 회사 규정에 따라 이월·수당으로 바뀔 수 있어요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp))
        }
        if (summary.comp > 0) {
            Text("보상휴가 ${LeaveBook.days(summary.comp)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        if (others.isNotEmpty()) {
            Text("${month.year}년 " + others.entries.joinToString(" · ") { "${it.key.label} ${LeaveBook.days(it.value)}" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
        }
        Text("달력에서 날을 길게 눌러 연차·반차·반반차를 넣으면 여기와 급여에 바로 반영돼요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun LeaveStat(label: String, value: String, modifier: Modifier, warn: Boolean = false) {
    androidx.compose.material3.Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium,
                color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** 마감 [PAYX-03]: keeps a month's result fixed once the pay slip is in, with a way to compare and redo. */
@Composable
private fun CloseMonthCard(vm: PayViewModel, result: MonthPay, closed: CalcSnapshot?) {
    SectionCard(title = "${result.month.monthValue}월 마감") {
        if (closed == null) {
            Text("급여를 받은 달은 마감해 두세요. 나중에 규칙이나 요율이 바뀌어도 이 달 결과는 그대로 남아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { vm.closeMonth(result) }, modifier = Modifier.padding(top = 6.dp)) {
                Icon(Icons.Filled.Lock, null); Text(" 이 달 마감하기")
            }
        } else {
            val at = java.time.Instant.ofEpochMilli(closed.closedAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            Text("${at.monthValue}월 ${at.dayOfMonth}일에 마감한 결과를 보여 주고 있어요 (요율 ${closed.ratesVersion}).",
                style = MaterialTheme.typography.bodyMedium)
            val diff = result.total - closed.total
            if (diff != 0L) {
                Text("지금 다시 계산하면 ${won(result.total)} (${if (diff > 0) "+" else "−"}${won(kotlin.math.abs(diff))})",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            if (closed.previous.isNotEmpty()) {
                Text("이전 마감 ${closed.previous.size}개 보관 중 · 처음 ${won(closed.previous.last().total)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (diff != 0L) OutlinedButton(onClick = { vm.closeMonth(result) }) { Text("다시 계산한 값으로") }
                TextButton(onClick = { vm.reopenMonth(result.month) }) { Text("마감 풀기") }
            }
        }
    }
}

/** 오류 제보 [PAY-09]: the user picks where to send it; nothing leaves the phone otherwise. */
private fun reportProblem(context: android.content.Context, profile: PayProfile, result: MonthPay) {
    val text = buildString {
        appendLine("[밤낮 급여 계산 오류 제보]")
        appendLine("월: ${result.month} · 요율 ${result.ratesVersion} · 공휴일 ${KoreanHolidays.VERSION} · 앱 ${com.bamnat.app.BuildConfig.VERSION_NAME}")
        appendLine("직군: ${profile.occupation.label} · ${profile.wageLabel()} · " +
            "${if (profile.fiveOrMoreEmployees) "5인 이상" else "5인 미만"} · 소정 ${profile.monthlyHours}시간")
        profile.rules.summary(profile).forEach { appendLine("규칙: $it") }
        appendLine()
        result.lines.forEach { appendLine("${it.label}: ${won(it.amount)} — ${it.formula}") }
        appendLine("합계: ${won(result.total)}")
        result.warnings.forEach { appendLine("경고: $it") }
        appendLine()
        appendLine("명세서 금액과 다른 부분을 적어 주세요:")
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "밤낮 급여 계산 오류 제보 (${result.month})")
        putExtra(android.content.Intent.EXTRA_TEXT, text)
    }
    context.startActivity(android.content.Intent.createChooser(send, "오류 제보 보내기"))
}

/**
 * [ADMIN-02] What 계산이 이상해요 sends with a question: the month, the rules and each line's hours and
 * rate, but no amount. The server refuses amounts in a question, so a line that still holds one is left out.
 */
internal fun payCheck(profile: PayProfile, result: MonthPay): String = buildList {
    add("월: ${result.month} · 요율 ${result.ratesVersion} · 공휴일 ${KoreanHolidays.VERSION} · 앱 ${com.bamnat.app.BuildConfig.VERSION_NAME}")
    add("직군: ${profile.occupation.label} · ${profile.wageLabel()} · " +
        "${if (profile.fiveOrMoreEmployees) "5인 이상" else "5인 미만"} · 소정 ${profile.monthlyHours}시간")
    profile.rules.summary(profile).forEach { add("규칙: $it") }
    result.lines.forEach { l ->
        val how = listOfNotNull(
            l.minutes.takeIf { it > 0 }?.let { PayCalculator.hours(it) },
            l.multiplier.takeIf { it > 0 }?.let { PayCalculator.pct(it) },
            // A basis such as "일당 ÷ 소정근로" often names an amount; the line stays without it.
            l.basis.takeIf { it.isNotBlank() && com.bamnat.app.company.CompanyText.personal(it) == null },
            "추정".takeIf { l.estimated },
        ).joinToString(" · ")
        add(if (how.isEmpty()) l.label else "${l.label}: $how")
    }
    result.warnings.forEach { add("경고: $it") }
}.filter { com.bamnat.app.company.CompanyText.personal(it) == null }
    .joinToString("\n").take(com.bamnat.app.company.InquiryClient.PAY_MAX)

/** When a bonus is paid: its fixed months and the 명절 it follows [PAYX-10]. */
private fun PayItem.paidWhen(): String = listOfNotNull(
    months.sorted().takeIf { it.isNotEmpty() }?.joinToString("·")?.let { "${it}월" },
    festivals.takeIf { it.isNotEmpty() }?.joinToString("·") { it.label }?.let { "$it ${if (festivalMonthBefore) "전 달" else "있는 달"}" },
).joinToString(" · ")
