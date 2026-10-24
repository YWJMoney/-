package com.bamnat.app.ui.pay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CustomRule
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.Payslip
import com.bamnat.core.pay.PayslipMatch
import com.bamnat.core.pay.PayslipRead
import com.bamnat.core.pay.SlipRole
import com.bamnat.core.pay.ValueTimeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToInt
import com.bamnat.core.time.Today

private enum class MatchStage { MONTH, ROSTER, AMOUNTS, WORKING, RESULT, DONE }

/** 연장·야간·휴일 pay as the payslip states it, with the hours behind it when it lists them (근로기준법 제48조 제2항). */
private class Premium(val title: String) {
    var amount by mutableStateOf<Long?>(null)
    var hours by mutableStateOf("")
    /** "12.5" or "12,5" hours, in minutes; null when blank or not a month's worth. */
    val minutes: Int? get() = hours.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 744 }?.let { (it * 60).roundToInt() }
}

/** An allowance on the payslip with no allowance of the same name in the app. */
private class OtherLine {
    var name by mutableStateOf("")
    var amount by mutableStateOf<Long?>(null)
}

/** What the user typed from the payslip; it never leaves this screen except as a [Payslip] to the matcher. */
private class SlipForm {
    var gross by mutableStateOf<Long?>(null)
    var base by mutableStateOf<Long?>(null)
    val overtime = Premium("연장근로수당")
    val night = Premium("야간근로수당")
    val holiday = Premium("휴일근로수당")
    /** Amounts by the id of the profile's allowance. */
    val items = mutableStateMapOf<String, Long?>()
    val others = mutableStateListOf<OtherLine>()
    /** [PAYX-36] The payslip as read from a photo or text, for its 공제 lines; null when typed by hand. */
    var read by mutableStateOf<PayslipRead?>(null)

    val premiums: List<Premium> get() = listOf(overtime, night, holiday)

    /** How many optional figures are filled in, for the folded section's title. */
    val extras: Int get() = listOfNotNull(base).size + premiums.count { it.amount != null } +
        items.values.count { it != null } + others.count { it.amount != null && it.name.isNotBlank() }

    /** [PAYS-05] Fills the form from a payslip read from a photo or text; the user can still correct every field. */
    fun fill(read: PayslipRead, profile: PayProfile) {
        this.read = read
        read.gross?.let { gross = it }
        read.amount(SlipRole.BASE)?.let { base = it }
        listOf(overtime to SlipRole.OVERTIME, night to SlipRole.NIGHT, holiday to SlipRole.HOLIDAY).forEach { (p, role) ->
            read.amount(role)?.let { p.amount = it }
            read.minutes(role)?.let { m -> p.hours = if (m % 60 == 0) "${m / 60}" else "%.2f".format(m / 60.0).trimEnd('0').trimEnd('.') }
        }
        others.clear()
        fun norm(n: String) = n.filterNot { it.isWhitespace() }.lowercase()
        read.allowances.forEach { (name, amount) ->
            val item = profile.items.firstOrNull { norm(it.name) == norm(name) }
            if (item != null) items[item.id] = amount
            else if (others.size < MAX_OTHERS) others += OtherLine().also { it.name = name.take(20); it.amount = amount }
        }
    }

    fun payslip(profile: PayProfile): Payslip = Payslip(
        gross = gross ?: 0,
        base = base,
        overtimePay = overtime.amount, overtimeMinutes = overtime.minutes,
        nightPay = night.amount, nightMinutes = night.minutes,
        holidayPay = holiday.amount, holidayMinutes = holiday.minutes,
        allowances = buildMap {
            profile.items.forEach { i -> items[i.id]?.let { put(i.name, it) } }
            others.forEach { o -> o.amount?.let { a -> if (o.name.isNotBlank()) put(o.name.trim(), a) } }
        },
    )
}

/**
 * [PAYS-02] 명세서로 맞춰 보기: the user types what one payslip says (only 지급 총액 is needed) and the app finds
 * the settings that come closest. What would change is shown first and nothing is saved until the user
 * agrees; 되돌리기 brings the old settings back. Legal facts are only suggested, never changed, and the
 * figures stay on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PayslipMatchDialog(
    vm: PayViewModel,
    profile: PayProfile,
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    onDismiss: () -> Unit,
    /** [PAYS-09] 회사 설정 보내기 for the invitation once the payslip and the app agree; null leaves that way out. */
    onShareWay: (() -> Unit)? = null,
) {
    val latest = Today.month()
    var chosen by remember { mutableStateOf(minOf(month, latest)) }
    var stage by remember { mutableStateOf(MatchStage.MONTH) }
    val form = remember { SlipForm() }
    var more by remember { mutableStateOf(false) }
    var match by remember { mutableStateOf<PayslipMatch?>(null) }
    var failed by remember { mutableStateOf(false) }
    // Payslip allowances the user chose to add; ids stay the same when the match runs again.
    val added = remember { mutableStateListOf<PayItem>() }
    // [PAYS-08] Rules of the company's own made from the gap, and the gap the builder is open for.
    val addedRules = remember { mutableStateListOf<CustomRule>() }
    var makingFor by remember { mutableStateOf<PayslipMatch?>(null) }
    val scope = rememberCoroutineScope()
    val appTotal by produceState<Long?>(null, chosen) { value = null; value = vm.calculateOff(profile, snapshot, chosen).total }
    // [PAYS-05] 명세서 사진으로 채우기, and what it filled.
    var scanning by remember { mutableStateOf(false) }
    var filled by remember { mutableStateOf<String?>(null) }
    if (scanning) PayslipFillDialog("명세서 사진으로 채우기", onDismiss = { scanning = false }) { read ->
        form.fill(read, profile)
        more = true
        val moved = read.month?.takeIf { !it.isAfter(latest) && it != chosen }
        moved?.let { chosen = it }
        filled = "명세서에서 ${read.lines.count { it.isEarning || it.role == SlipRole.GROSS || it.role == SlipRole.TOTAL }}줄을 채웠어요" +
            (moved?.let { " · ${it.monthValue}월 명세서로 맞춰 볼게요" } ?: "") + ". 틀린 칸은 고쳐 주세요."
        scanning = false
    }

    fun startMatch() {
        val slip = form.payslip(profile)
        stage = MatchStage.WORKING
        failed = false
        scope.launch {
            try {
                match = vm.match(profile.copy(items = profile.items + added, customRules = profile.customRules + addedRules), snapshot, chosen, slip)
                stage = MatchStage.RESULT
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                stage = MatchStage.AMOUNTS
            }
        }
    }

    fun back() {
        stage = when (stage) {
            MatchStage.ROSTER, MatchStage.AMOUNTS -> MatchStage.MONTH
            MatchStage.RESULT -> MatchStage.AMOUNTS
            else -> stage
        }
    }
    val canGoBack = stage == MatchStage.ROSTER || stage == MatchStage.AMOUNTS || stage == MatchStage.RESULT

    makingFor?.let { m ->
        CustomItemDialog(
            vm, m.profile, snapshot, chosen,
            onDismiss = { makingFor = null },
            onSave = { made ->
                when (made) {
                    is MadeItem.Item -> added += made.item
                    is MadeItem.Rule -> addedRules += made.rule
                }
                makingFor = null
                startMatch()
            },
            gap = m.gap,
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(enabled = canGoBack) { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("명세서로 맞춰 보기") },
                        navigationIcon = {
                            if (canGoBack) IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "이전") }
                            else IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "닫기") }
                        },
                    )
                },
                bottomBar = {
                    val m = match
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (stage) {
                            MatchStage.AMOUNTS -> Button(
                                onClick = ::startMatch,
                                enabled = (form.gross ?: 0) > 0,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            ) { Text("맞춰 보기") }
                            MatchStage.RESULT -> if (m != null) {
                                OutlinedButton(onClick = { stage = MatchStage.AMOUNTS }, modifier = Modifier.heightIn(min = 52.dp)) { Text("다시 넣기") }
                                Button(
                                    onClick = { vm.applyMatch(m); stage = MatchStage.DONE },
                                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                ) { Text(if (m.changes.isEmpty() && added.isEmpty() && addedRules.isEmpty()) "확인했어요" else "이 설정으로 바꾸기") }
                            }
                            MatchStage.DONE -> Button(onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("닫기") }
                            else -> Unit
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "payslip-stage",
                        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                    ) { s ->
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            when (s) {
                                MatchStage.MONTH -> MonthStage(profile, chosen, latest, appTotal, { chosen = it }) { same ->
                                    stage = if (same) MatchStage.AMOUNTS else MatchStage.ROSTER
                                }
                                MatchStage.ROSTER -> RosterStage(onFix = onDismiss, onAnyway = { stage = MatchStage.AMOUNTS })
                                MatchStage.AMOUNTS -> AmountsStage(profile, chosen, form, appTotal, more, { more = it }, failed, filled) { scanning = true }
                                MatchStage.WORKING -> Working()
                                MatchStage.RESULT -> match?.let { m ->
                                    ResultStage(m, added, addedRules, onMakeGap = { makingFor = m },
                                        audit = { SlipAuditCard(m, form.payslip(profile), form.read, snapshot) }) { names ->
                                        val from = profile.period.start(chosen)
                                        names.forEachIndexed { i, (name, amount) ->
                                            added += PayItem(
                                                "item-${System.currentTimeMillis()}-$i", name,  // real clock: an id, not a time
                                                ValueTimeline(listOf(DatedValue(from, amount))), Cadence.MONTHLY, ordinary = true,
                                            )
                                        }
                                        startMatch()
                                    }
                                }
                                MatchStage.DONE -> match?.let { m ->
                                    DoneStage(m.changes.isNotEmpty() || added.isNotEmpty() || addedRules.isNotEmpty())
                                    // [PAYS-09] Only once the setup proved itself: the invitation to share it.
                                    if (m.matched) com.bamnat.app.ui.company.ShareWayCard(
                                        com.bamnat.app.ui.company.ShareMoment.MATCHED, onShare = onShareWay?.let { go -> { onDismiss(); go() } },
                                        modifier = Modifier.padding(top = 16.dp),
                                        ways = waysText(m.profile, snapshot.shiftTypes.associate { it.id to it.name }, null),
                                        notInFile = m.profile.customRules.isNotEmpty(),
                                    )
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.MonthStage(
    profile: PayProfile,
    month: YearMonth,
    latest: YearMonth,
    appTotal: Long?,
    onMonth: (YearMonth) -> Unit,
    onRoster: (Boolean) -> Unit,
) {
    StepTitle("어느 달 명세서인가요?", "가지고 있는 명세서 한 장이면 돼요. 앱이 계산한 그 달과 나란히 놓고 맞춰 봐요.")
    MonthStepper(month, { if (!it.isAfter(latest)) onMonth(it) }, Modifier.align(Alignment.CenterHorizontally))
    val start = profile.period.start(month)
    val end = profile.period.end(month)
    Text(
        "급여 기간 ${start.monthValue}월 ${start.dayOfMonth}일~${end.monthValue}월 ${end.dayOfMonth}일 · 앱 계산 " +
            (appTotal?.let(::won) ?: "계산 중"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    StepTitle("그 달 근무가 달력과 같았나요?", "근무, 잔업·특근·대근, 쉬는 날이 달력과 같아야 정확히 맞춰 볼 수 있어요.")
    AnswerCard("네, 같았어요", null, false) { onRoster(true) }
    AnswerCard("아니요, 달라요", "달력을 먼저 고치면 더 정확해요", false) { onRoster(false) }
}

@Composable
private fun RosterStage(onFix: () -> Unit, onAnyway: () -> Unit) {
    StepTitle("먼저 달력을 고쳐 주세요", "근무표가 실제와 다르면 맞춰 본 결과도 어긋나요. 달력 탭에서 그 달 근무와 잔업·특근·대근을 고친 뒤 다시 와 주세요.")
    Button(onClick = onFix, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("닫고 달력 고치기") }
    TextButton(onClick = onAnyway, modifier = Modifier.fillMaxWidth()) { Text("그래도 맞춰 볼게요") }
}

@Composable
private fun ColumnScope.AmountsStage(
    profile: PayProfile,
    month: YearMonth,
    form: SlipForm,
    appTotal: Long?,
    more: Boolean,
    onMore: (Boolean) -> Unit,
    failed: Boolean,
    filled: String?,
    onScan: () -> Unit,
) {
    // [PAYS-04][PAYS-05] The easiest way first: one photo fills every field below.
    Surface(
        onClick = onScan,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PhotoCamera, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("명세서 사진으로 한 번에 채우기", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("사진이나 캡처 한 장이면 아래 칸을 앱이 채워요. 휴대폰 안에서만 읽어요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
    filled?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    StepTitle("${month.monthValue}월 명세서의 지급 총액을 넣어 주세요", "세금과 4대보험을 빼기 전 금액이에요. ‘지급 합계’나 ‘지급액 계’로 적혀 있기도 해요.")
    MoneyField("지급 총액", form.gross, { form.gross = it })
    val gross = form.gross
    if (gross != null && appTotal != null && gross < appTotal * 0.85) {
        Text(
            "앱 계산(${won(appTotal)})보다 많이 적어요. 실수령액을 넣으셨다면 공제 전 지급 총액으로 바꿔 주세요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        // [PAYX-23] A 실수령액 typed in: the pay before tax that leaves it, by the same 4대보험·간이세액표 the app uses.
        val mode = profile.taxMode.takeIf { it != com.bamnat.core.pay.TaxMode.NONE } ?: com.bamnat.core.pay.TaxMode.INSURANCE_4
        val before = remember(gross, profile, month) {
            com.bamnat.core.pay.TakeHome.grossFromNet(gross, mode, profile.taxFamily, month.atDay(1), profile.taxChildren,
                com.bamnat.core.pay.TakeHome.nonTaxable(profile, month))
        }
        if (before > gross) TextButton(onClick = { form.gross = before }) {
            Text("실수령액이었다면: 공제 전 약 ${won(before)}으로 바꾸기")
        }
    }
    if (failed) Text("맞춰 보지 못했어요. 한 번 더 눌러 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

    SectionCard(title = null) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { onMore(!more) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("더 넣으면 더 정확해요 (선택)", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (form.extras > 0) "${form.extras}개 넣었어요" else "기본급, 연장·야간·휴일 수당과 시간, 다른 수당",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (more) "접기" else "펼치기")
        }
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (more) MoreFields(profile, month, form)
        }
    }
    Text("넣은 금액은 이 휴대폰 밖으로 나가지 않아요.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MoreFields(profile: PayProfile, month: YearMonth, form: SlipForm) {
    Spacer(Modifier.height(4.dp))
    MoneyField("기본급", form.base, { form.base = it })
    form.premiums.forEach { p ->
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoneyField(p.title, p.amount, { p.amount = it }, Modifier.weight(1.6f))
            OutlinedTextField(
                value = p.hours,
                onValueChange = { v -> p.hours = v.filter { it.isDigit() || it == '.' || it == ',' }.take(6) },
                label = { Text("시간") },
                suffix = { Text("시간") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
    }
    Text("명세서 아래쪽 ‘계산 방법’에 적힌 시간을 넣으면 통상시급까지 맞춰 봐요.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    // A bonus set as a share of 기본급 follows the 기본급, so only fixed amounts are asked.
    val items = profile.items.filter { it.cadence != Cadence.BONUS || it.percentOfBase == null && it.timesIn(month) > 0 }
    if (items.isNotEmpty()) {
        Text("앱에 넣어 둔 수당", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        items.forEach { item -> MoneyField(item.name, form.items[item.id], { form.items[item.id] = it }) }
    }
    Text("명세서에만 있는 수당", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    form.others.forEachIndexed { i, line ->
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = line.name,
                onValueChange = { line.name = it.take(20) },
                label = { Text("이름") },
                placeholder = { Text("예: 자격수당") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            MoneyField("금액", line.amount, { line.amount = it }, Modifier.weight(1.2f))
            IconButton(onClick = { form.others.removeAt(i) }, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Filled.Close, "이 줄 지우기") }
        }
    }
    if (form.others.size < MAX_OTHERS) {
        com.bamnat.app.ui.components.AddButton("수당 넣기", onClick = { form.others += OtherLine() })
    }
}

private const val MAX_OTHERS = 6

/** A gap of this share of the payslip or more points at the roster or an allowance first. */
private const val FAR_PERCENT = 5.0

@Composable
private fun ColumnScope.Working() {
    Spacer(Modifier.heightIn(min = 48.dp))
    CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    Text("여러 계산 방식으로 맞춰 보는 중이에요", style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally))
}

@Composable
private fun ResultStage(
    m: PayslipMatch,
    added: List<PayItem>,
    addedRules: List<CustomRule>,
    onMakeGap: () -> Unit,
    audit: @Composable () -> Unit = {},
    onAdd: (List<Pair<String, Long>>) -> Unit,
) {
    val pct = "%.1f".format(abs(m.gapPercent))
    val (title, body) = when {
        m.matched && m.changes.isEmpty() && added.isEmpty() -> "지금 설정이 명세서와 맞아요" to "차이 ${won(abs(m.gap))} ($pct%)"
        m.matched -> "이렇게 바꾸면 명세서와 맞아요" to "차이 ${won(abs(m.gap))} ($pct%)"
        else -> "가장 가까운 설정이에요" to
            "앱이 ${won(abs(m.gap))} ${if (m.gap > 0) "적게" else "많이"} 계산해요 ($pct%)"
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (m.matched) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (m.matched) {
                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Text("명세서 ${won(m.gross)} · 앱 ${won(m.total)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                // A gap this wide is more often a day or an allowance missing than a way of calculating.
                if (!m.matched && abs(m.gapPercent) >= FAR_PERCENT) {
                    Text("차이가 커요. 그 달 근무와 수당이 실제와 같은지 먼저 봐 주세요.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }

    if (m.changes.isNotEmpty() || added.isNotEmpty() || addedRules.isNotEmpty()) {
        SectionCard(title = "바뀌는 것") {
            m.changes.forEach { c -> ChangeRow(c.label, "${c.from} → ${c.to}") }
            added.forEach { a ->
                val now = m.profile.items.firstOrNull { it.id == a.id } ?: a
                ChangeRow(now.name, "새 수당 · ${now.cadence.label} ${won(now.amount.on(m.month).value)} · 통상임금 ${if (now.ordinary) "포함" else "제외"}")
            }
            addedRules.forEach { r -> ChangeRow(r.name, (if (r.deduct) "새 차감 · " else "새 항목 · ") + r.describe()) }
        }
    }

    // [PAYX-41] Line by line: what to ask the company and what to fix in the app, and the 공제 next to the estimate.
    audit()

    // [PAYS-08] What is left of the gap points at the line the app does not have, and makes it in one go.
    if (!m.matched) GapCard(m.gap, onMakeGap)

    if (m.unmatched.isNotEmpty()) {
        val pick = remember(m) { mutableStateMapOf<String, Boolean>().apply { m.unmatched.keys.forEach { put(it, true) } } }
        SectionCard(title = "앱에 없는 수당") {
            Text("명세서에는 있는데 앱에는 없는 수당이에요. 매달 받는 수당으로 넣고 다시 맞춰 볼게요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            m.unmatched.forEach { (name, amount) ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { pick[name] = !(pick[name] ?: true) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = pick[name] ?: true, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(won(amount), style = MaterialTheme.typography.bodyMedium)
                }
            }
            val chosen = m.unmatched.filterKeys { pick[it] ?: true }.toList()
            OutlinedButton(onClick = { onAdd(chosen) }, enabled = chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text("수당으로 넣고 다시 맞춰 보기")
            }
        }
    }

    val hints = m.hints.filterNot { it.key.startsWith("unmatched:") }
    if (hints.isNotEmpty()) {
        SectionCard(title = "회사에 확인해 보세요") {
            Text("법이나 회사 사정에 달린 것이라 앱이 바꾸지 않았어요. 맞다면 설정에서 직접 바꿔 주세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hints.forEach { h ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(h.text, style = MaterialTheme.typography.bodyMedium)
                    Text("그렇다면 ${won(h.total)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    m.belowLaw?.let { below ->
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("법 기준으로 계산하면 이 달 ${won(below)} 더 많아요", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text("회사 계산 방식이 법 기준보다 적게 주는 부분이 있을 수 있어요. 회사에 물어보거나 고용노동부 상담센터(국번 없이 1350)에서 확인해 보세요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }

    m.payslipHourly?.let {
        Text("명세서의 수당과 시간으로 거꾸로 계산한 통상시급은 ${won(it)}이에요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * [PAYX-36][PAYX-41] 내 월급 맞나요?: the pay lines that differ in two baskets, then the payslip's 공제 next to the
 * app's estimate for the same 지급 총액.
 */
@Composable
internal fun SlipAuditCard(m: PayslipMatch, slip: Payslip, read: PayslipRead?, snapshot: ScheduleSnapshot) {
    val pay = remember(m) { com.bamnat.core.pay.PayCalculator().calculate(m.profile, m.month, snapshot.resolver::resolve, checks = false) }
    val gaps = remember(m, slip) { com.bamnat.core.pay.SlipAudit.gaps(slip, pay) }
    val deductions = remember(m, read) { read?.let { com.bamnat.core.pay.SlipAudit.deductions(it, m.profile, m.month) }.orEmpty() }
    if (gaps.isEmpty() && deductions.isEmpty()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val ui = androidx.compose.runtime.rememberCoroutineScope()
    SectionCard(title = "줄마다 맞춰 보기", trailing = {
        TextButton(onClick = {
            ui.launch {
                com.bamnat.app.report.ReportShare.pdf(context, com.bamnat.core.pay.SlipAudit.doc(m.month, m.gross, m.total, gaps, deductions, m.belowLaw),
                    "bamnat-명세서대조-${m.month}.pdf")
            }
        }) { Text("PDF") }
    }) {
        com.bamnat.core.pay.SlipAudit.Basket.entries.forEach { b ->
            val mine = gaps.filter { it.basket == b }
            if (mine.isEmpty()) return@forEach
            Text(b.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp),
                color = if (b == com.bamnat.core.pay.SlipAudit.Basket.COMPANY_LESS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            mine.forEach { g ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(g.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("명세서 ${won(g.slip)} · 앱 ${won(g.app)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(b.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (gaps.any { it.basket == com.bamnat.core.pay.SlipAudit.Basket.COMPANY_LESS } || m.belowLaw != null) {
            Text(com.bamnat.core.pay.SlipAudit.ASK_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (deductions.isNotEmpty()) {
            Text("공제 (명세서 · 앱 예상)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            deductions.forEach { d ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(d.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${d.slip?.let(::won) ?: "없음"} · ${d.estimate?.let(::won) ?: "-"}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("국민연금은 작년 소득으로 정한 금액이라 다를 수 있어요. 소득세는 회사가 고른 비율(80·100·120%)과 가족 수에 따라 달라요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [PAYS-08] "명세서보다 12,000원 적어요 → 이 차이를 항목으로 만들기". */
@Composable
private fun GapCard(gap: Long, onMake: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (gap > 0) "앱이 명세서보다 ${won(gap)} 적어요" else "앱이 명세서보다 ${won(-gap)} 많아요",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                if (gap > 0) "회사만 주는 수당이 앱에 없을 수 있어요. 그 항목을 만들면 차이가 메워지는지 바로 다시 맞춰 봐요."
                else "회사가 빼는 돈(차감)이 앱에 없을 수 있어요. 그 항목을 만들면 바로 다시 맞춰 봐요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onMake, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Add, null)
                Text(" 이 차이를 항목으로 만들기")
            }
        }
    }
}

@Composable
private fun ChangeRow(label: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ColumnScope.DoneStage(changed: Boolean) {
    Spacer(Modifier.height(40.dp))
    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp).align(Alignment.CenterHorizontally))
    Text(if (changed) "바꿨어요" else "맞춰 본 것을 남겨 뒀어요", style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.align(Alignment.CenterHorizontally))
    Text(
        (if (changed) "급여 화면의 ‘방금 바꾼 것 되돌리기’로 예전 설정으로 돌아갈 수 있어요. " else "") +
            "명세서 금액은 이 휴대폰에만 남아요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
}
