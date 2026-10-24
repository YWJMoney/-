package com.bamnat.app.ui.pay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import com.bamnat.app.ui.components.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.core.pay.Cadence
import com.bamnat.core.pay.CustomRule
import com.bamnat.core.pay.DatedValue
import com.bamnat.core.pay.PayItem
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.RuleDayKind
import com.bamnat.core.pay.RulePay
import com.bamnat.core.pay.RulePieces
import com.bamnat.core.pay.RuleWhen
import com.bamnat.core.pay.ValueTimeline
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * [PAYS-07] What a line on a company's payslip can be, in the words a payslip uses. 매달·일한 날마다 make an
 * allowance (it can count toward 통상임금); the rest make a [CustomRule], which stays on the phone.
 */
internal enum class ItemKind(val title: String, val example: String, val icon: ImageVector) {
    MONTHLY("매달 같은 금액", "자격수당 매달 5만원, 직책수당 10만원", Icons.Filled.CalendarMonth),
    PER_DAY("일한 날마다", "식대 출근한 날 하루 8,000원", Icons.Filled.Today),
    PER_SHIFT("정한 근무·요일에 한 번씩", "나이트 1회 2만원, 토요일 출근 3만원", Icons.Filled.EventRepeat),
    PER_HOUR("정한 시간에 시간당", "밤 10시 넘어 1시간에 2,000원", Icons.Filled.Schedule),
    PREMIUM("시급에 몇 %를 더", "토요일 근무는 시급의 50% 더", Icons.AutoMirrored.Filled.TrendingUp),
    SHARE("기본급의 몇 %", "근속수당 기본급의 5%", Icons.Filled.Percent),
    DEDUCT("빠지는 돈 (차감)", "기숙사비 매달 5만원, 식대 하루 3,000원", Icons.Filled.RemoveCircleOutline);

    /** The name used when none is typed, so a name is never one more thing to fill in. */
    val defaultName: String get() = when (this) {
        MONTHLY -> "매달 수당"
        PER_DAY -> "출근 수당"
        PER_SHIFT -> "근무 수당"
        PER_HOUR -> "시간 수당"
        PREMIUM -> "회사 가산"
        SHARE -> "기본급 비율 수당"
        DEDUCT -> "차감"
    }

    val namePlaceholder: String get() = when (this) {
        MONTHLY -> "예: 자격수당"
        PER_DAY -> "예: 식대"
        PER_SHIFT -> "예: 나이트 수당"
        PER_HOUR -> "예: 심야 수당"
        PREMIUM -> "예: 토요일 가산"
        SHARE -> "예: 근속수당"
        DEDUCT -> "예: 기숙사비"
    }

    /** Kinds that pay for some of the work only, so they ask when. */
    val asksWhen: Boolean get() = this == PER_SHIFT || this == PER_HOUR || this == PREMIUM

    /** Kinds counted on the clock, so they can be limited to a time of day or the hours past some. */
    val countsHours: Boolean get() = this == PER_HOUR || this == PREMIUM

    companion object {
        /** The kind the builder edits [rule] as; null for a rule it cannot show (8시간에 기본급의 몇 %, 시간당 차감). */
        fun of(rule: CustomRule): ItemKind? = when {
            rule.deduct -> if (rule.pay is RulePay.Monthly || rule.pay is RulePay.PerTime) DEDUCT else null
            rule.pay is RulePay.PerTime -> PER_SHIFT
            rule.pay is RulePay.PerHour -> PER_HOUR
            rule.pay is RulePay.OfHourly -> PREMIUM
            rule.pay is RulePay.OfBaseMonthly -> SHARE
            else -> null
        }
    }
}

/** [PAYS-07] How a deduction is counted. */
internal enum class DeductWay(val label: String) { MONTHLY("매달"), PER_DAY("일한 날마다"), PER_SHIFT("정한 근무·요일마다") }

/** [PAYS-07] What the builder made: an allowance, or a rule of the company's own that stays on the phone. */
internal sealed interface MadeItem {
    val name: String
    data class Item(val item: PayItem) : MadeItem { override val name get() = item.name }
    data class Rule(val rule: CustomRule) : MadeItem { override val name get() = rule.name }
}

/** [PAYS-07] What a new item does to the month: the amount it adds (negative when it takes off) and how often it came up. */
data class ItemEffect(val amount: Long, val times: Int)

/** The builder's answers; [made] turns them into the item, with a stand-in amount for [probe] so the count shows early. */
@Stable
internal class ItemDraft(kind: ItemKind?, private val id: String) {
    var kind by mutableStateOf(kind)
    var name by mutableStateOf("")
    var amount by mutableStateOf<Long?>(null)
    var percent by mutableStateOf("")
    var deductWay by mutableStateOf(DeductWay.MONTHLY)
    var shifts by mutableStateOf<Set<Long>>(emptySet())
    var days by mutableStateOf<Set<DayOfWeek>>(emptySet())
    var dayKind by mutableStateOf(RuleDayKind.ANY)
    var pieces by mutableStateOf(RulePieces.ALL)
    var window by mutableStateOf(false)
    var from by mutableStateOf(22)
    var to by mutableStateOf(6)
    var overHours by mutableStateOf(0)
    var ordinary by mutableStateOf(false)

    /** The share the percent field holds, 50 → 0.5; null when it is not a number above 0. */
    val share: Double? get() = percent.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 && it <= 1000 }?.div(100)

    val usesPercent: Boolean get() = kind == ItemKind.PREMIUM || kind == ItemKind.SHARE

    /** Whether a deduction asks when, as the paying kinds do. */
    val asksWhen: Boolean get() = kind?.asksWhen == true || kind == ItemKind.DEDUCT && deductWay == DeductWay.PER_SHIFT

    val ready: Boolean get() = kind != null && if (usesPercent) share != null else (amount ?: 0) > 0

    fun whenever(): RuleWhen {
        val k = kind
        return when {
            k == ItemKind.DEDUCT && deductWay == DeductWay.PER_DAY -> RuleWhen(dayKind = RuleDayKind.WORK_DAY)
            asksWhen -> RuleWhen(
                dayKind = dayKind, daysOfWeek = days, shiftTypeIds = shifts, pieces = pieces,
                from = if (window && k?.countsHours == true) LocalTime.of(from % 24, 0) else null,
                to = if (window && k?.countsHours == true) LocalTime.of(to % 24, 0) else null,
                overMinutes = if (k?.countsHours == true) overHours * 60 else 0,
            )
            else -> RuleWhen()
        }
    }

    fun made(start: java.time.LocalDate, probe: Boolean = false): MadeItem? {
        val k = kind ?: return null
        val won = amount?.takeIf { it > 0 } ?: if (probe) 1_000L else return null
        val pct = share ?: if (probe) 0.1 else null
        val title = name.trim().ifEmpty { k.defaultName }
        fun rule(pay: RulePay, deduct: Boolean = false) =
            MadeItem.Rule(CustomRule(id, title.take(CustomRule.MAX_NAME), whenever(), pay, deduct = deduct))
        return when (k) {
            ItemKind.MONTHLY, ItemKind.PER_DAY -> MadeItem.Item(PayItem(
                id, title.take(20), ValueTimeline(listOf(DatedValue(start, won))),
                if (k == ItemKind.MONTHLY) Cadence.MONTHLY else Cadence.PER_WORK_DAY, ordinary = ordinary,
            ))
            ItemKind.PER_SHIFT -> rule(RulePay.PerTime(won))
            ItemKind.PER_HOUR -> rule(RulePay.PerHour(won))
            ItemKind.PREMIUM -> rule(RulePay.OfHourly(pct ?: return null))
            ItemKind.SHARE -> rule(RulePay.OfBaseMonthly(pct ?: return null))
            ItemKind.DEDUCT -> rule(if (deductWay == DeductWay.MONTHLY) RulePay.Monthly(won) else RulePay.PerTime(won), deduct = true)
        }
    }

    companion object {
        /** The builder filled in from a rule made before, to change it. */
        fun of(rule: CustomRule): ItemDraft? {
            val kind = ItemKind.of(rule) ?: return null
            val w = rule.whenever
            return ItemDraft(kind, rule.id).apply {
                name = rule.name
                when (val p = rule.pay) {
                    is RulePay.PerTime -> amount = p.amount
                    is RulePay.PerHour -> amount = p.amount
                    is RulePay.Monthly -> amount = p.amount
                    is RulePay.OfHourly -> percent = ExtraRuleText.number(p.rate * 100)
                    is RulePay.OfBaseMonthly -> percent = ExtraRuleText.number(p.share * 100)
                    is RulePay.OfBase -> Unit
                }
                if (rule.deduct) deductWay = when {
                    rule.pay is RulePay.Monthly -> DeductWay.MONTHLY
                    w == RuleWhen(dayKind = RuleDayKind.WORK_DAY) -> DeductWay.PER_DAY
                    else -> DeductWay.PER_SHIFT
                }
                shifts = w.shiftTypeIds
                days = w.daysOfWeek
                dayKind = w.dayKind
                pieces = w.pieces
                window = w.hasWindow
                w.from?.let { from = it.hour }
                w.to?.let { to = it.hour }
                overHours = w.overMinutes / 60
            }
        }
    }
}

/** Numbers as people write them: 50, 12.5. */
internal object ExtraRuleText {
    fun number(v: Double): String = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
}

/**
 * [PAYS-07] 우리 회사 방식 직접 만들기: one line of the company's payslip the app does not have, made in two
 * steps: what kind of line it is (with an example each), then its name, amount and, for the kinds that need it,
 * when it is paid. What it does to this month shows as it is typed. [gap] is set when it comes from 명세서로
 * 맞춰 보기 [PAYS-08]: the amount starts at the difference and the bar shows what is left of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomItemDialog(
    vm: PayViewModel,
    profile: PayProfile,
    snapshot: ScheduleSnapshot,
    month: YearMonth,
    onDismiss: () -> Unit,
    onSave: (MadeItem) -> Unit,
    editing: CustomRule? = null,
    gap: Long? = null,
    startKind: ItemKind? = null,
) {
    val id = remember { editing?.id ?: "rule-" + System.currentTimeMillis().toString(36) }  // real clock: an id, not a time
    val draft = remember { editing?.let { ItemDraft.of(it) } ?: ItemDraft(startKind, id).also { d -> if (startKind != null) prefill(d, gap) } }
    val start = profile.period.start(month)
    val base = remember(profile, editing) { if (editing == null) profile else profile.copy(customRules = profile.customRules.filterNot { it.id == editing.id }) }
    // Only rules count to the limit: 월 고정·일당 go in as pay items.
    val full = editing == null && draft.kind.let { it != null && it != ItemKind.MONTHLY && it != ItemKind.PER_DAY } &&
        base.customRules.size >= CustomRule.MAX
    val made = draft.made(start)
    val probe = draft.made(start, probe = true)
    val effect by produceState<ItemEffect?>(null, probe, base) { value = probe?.let { vm.effectOf(base, snapshot, month, it) } }
    val real = if (made != null && effect != null) effect else null
    fun back() { if (draft.kind != null && editing == null) draft.kind = null else onDismiss() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler(enabled = draft.kind != null && editing == null) { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (editing != null) "항목 고치기" else "우리 회사 항목 만들기", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            if (draft.kind != null && editing == null) IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "종류 다시 고르기") }
                            else IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "닫기") }
                        },
                    )
                },
                bottomBar = {
                    if (draft.kind != null) {
                        EffectBar(month, draft, effect = real, timesOnly = effect?.times, gap = gap, enabled = made != null && !full,
                            label = if (editing != null) "저장" else "만들기") { made?.let(onSave) }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val k = draft.kind
                        if (k == null) KindStep(gap) { picked -> draft.kind = picked; prefill(draft, gap) }
                        else FormStep(draft, k, snapshot, gap, onChangeKind = if (editing == null) ({ draft.kind = null }) else null)
                        if (full) Text("직접 만든 항목은 ${CustomRule.MAX}개까지예요. 쓰지 않는 항목을 지운 뒤 만들어 주세요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** [PAYS-08] From a payslip gap, the amount starts at the difference for the kinds where that is the whole answer. */
private fun prefill(d: ItemDraft, gap: Long?) {
    if (gap == null || d.amount != null) return
    when {
        gap > 0 && d.kind == ItemKind.MONTHLY -> d.amount = gap
        gap < 0 && d.kind == ItemKind.DEDUCT && d.deductWay == DeductWay.MONTHLY -> d.amount = -gap
    }
}

@Composable
private fun ColumnScope.KindStep(gap: Long?, onPick: (ItemKind) -> Unit) {
    if (gap != null) GapBanner(gap)
    StepTitle(
        "어떤 항목인가요?",
        "명세서에서 앱에 없는 줄 하나를 떠올려 보세요. 가장 비슷한 걸 고르면 돼요. 만든 뒤에도 언제든 고치거나 끌 수 있어요.",
    )
    // When the app comes to more than the payslip, something is taken off: that kind goes first.
    val kinds = if (gap != null && gap < 0) listOf(ItemKind.DEDUCT) + ItemKind.entries.filter { it != ItemKind.DEDUCT } else ItemKind.entries
    kinds.forEach { k -> KindCard(k) { onPick(k) } }
}

@Composable
private fun GapBanner(gap: Long) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (gap > 0) "앱이 명세서보다 ${won(gap)} 적어요" else "앱이 명세서보다 ${won(-gap)} 많아요",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                if (gap > 0) "회사만 주는 수당이 있나 봐요. 그 항목을 만들면 금액을 차이에 맞춰 채워 둘게요."
                else "회사가 빼는 돈(차감)이 있나 봐요. 그 항목을 만들면 금액을 차이에 맞춰 채워 둘게요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun KindCard(kind: ItemKind, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon, null, tint = if (kind == ItemKind.DEDUCT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(kind.title, style = MaterialTheme.typography.titleMedium)
                Text("예: ${kind.example}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FormStep(draft: ItemDraft, kind: ItemKind, snapshot: ScheduleSnapshot, gap: Long?, onChangeKind: (() -> Unit)?) {
    if (gap != null) GapBanner(gap)
    // The kind chosen, with the way back to the list.
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(kind.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
            if (onChangeKind != null) TextButton(onClick = onChangeKind, modifier = Modifier.heightIn(min = 48.dp)) { Text("바꾸기") }
        }
    }

    OutlinedTextField(
        value = draft.name,
        onValueChange = { draft.name = it.take(if (kind == ItemKind.MONTHLY || kind == ItemKind.PER_DAY) 20 else CustomRule.MAX_NAME) },
        label = { Text("이름 (선택)") },
        placeholder = { Text(kind.namePlaceholder) },
        supportingText = { Text("명세서에 적힌 이름 그대로 쓰면 다음에 맞춰 보기 쉬워요") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (kind == ItemKind.DEDUCT) {
        Label("언제 빠지나요?")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeductWay.entries.forEach { w ->
                FilterChip(selected = draft.deductWay == w, onClick = { draft.deductWay = w; prefill(draft, gap) }, label = { Text(w.label) },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }

    when {
        draft.usesPercent -> PercentField(draft, kind)
        else -> MoneyField(
            when {
                kind == ItemKind.MONTHLY || kind == ItemKind.DEDUCT && draft.deductWay == DeductWay.MONTHLY -> "한 달 금액"
                kind == ItemKind.PER_DAY || kind == ItemKind.DEDUCT && draft.deductWay == DeductWay.PER_DAY -> "하루 금액"
                kind == ItemKind.PER_HOUR -> "1시간 금액"
                else -> "1회 금액"
            },
            draft.amount, { draft.amount = it },
        )
    }
    when (kind) {
        ItemKind.PREMIUM -> {
            Hint("통상시급에 이만큼을 더해요. 50%면 1시간에 시급의 반을 더 받아요. 법정 가산(연장·야간·휴일)과 따로, 그 위에 더해요.")
            TermLink("통상시급")
        }
        ItemKind.SHARE -> Hint("매달 기본급에 이 비율을 곱해 더해요. 기본급이 오르면 함께 올라요.")
        ItemKind.DEDUCT -> {
            Hint("명세서 지급 칸에서 빠지는 돈만 넣어요. 세금과 4대보험은 넣지 않아도 돼요. 앱은 세금 떼기 전 금액을 계산해요.")
            TermLink("차감")
        }
        ItemKind.MONTHLY, ItemKind.PER_DAY -> {
            Toggle(
                "통상임금에 넣어요",
                if (kind == ItemKind.MONTHLY) "매달 모두에게 똑같이 주는 수당이면 켜요. 연장·야간 수당도 함께 올라가요. 모르면 꺼 두세요."
                else "일한 날마다 받는 수당은 보통 넣지 않아요. 모르면 꺼 두세요.",
                draft.ordinary,
            ) { draft.ordinary = it }
            TermLink("통상임금")
        }
        else -> Unit
    }

    if (draft.asksWhen) WhenSection(draft, kind, snapshot)
}

/** [INPUT-02] The share or premium in %: the usual values as chips, or stepped (1% of 기본급, 5% of the hourly), not typed. */
@Composable
private fun PercentField(draft: ItemDraft, kind: ItemKind) {
    val share = kind == ItemKind.SHARE
    Text(if (share) "기본급의" else "시급에 더하는 몫", style = MaterialTheme.typography.labelLarge)
    com.bamnat.app.ui.components.QuickDecimalChips(
        value = draft.percent.replace(',', '.').toDoubleOrNull(), onChange = { draft.percent = com.bamnat.app.ui.components.decimalText(it) },
        name = if (share) "기본급의 비율" else "시급에 더하는 몫",
        quick = if (share) listOf(1.0, 3.0, 5.0, 10.0) else listOf(25.0, 50.0, 100.0),
        step = if (share) 0.5 else 5.0, range = 0.5..1000.0, unit = "%",
    )
}

/** When the item is paid: the shifts and days first, the rest folded away until asked for. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WhenSection(draft: ItemDraft, kind: ItemKind, snapshot: ScheduleSnapshot) {
    var more by remember { mutableStateOf(draft.dayKind != RuleDayKind.ANY || draft.window || draft.overHours > 0 || draft.pieces != RulePieces.ALL) }
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    Text(if (kind == ItemKind.DEDUCT) "어떤 날 빠지나요?" else "언제 주나요?", style = MaterialTheme.typography.titleMedium)
    Hint("고르지 않으면 모든 근무에 줘요. 여러 개를 고르면 모두 맞는 근무에만 줘요.")
    val types = snapshot.shiftTypes.filter { !it.isOff }
    if (types.isNotEmpty()) {
        Label("근무")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            types.forEach { t ->
                FilterChip(selected = t.id in draft.shifts, onClick = { draft.shifts = draft.shifts.toggle(t.id) }, label = { Text(t.name) },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
    Label("요일")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { d ->
            FilterChip(selected = d in draft.days, onClick = { draft.days = draft.days.toggle(d) }, label = { Text(Fmt.dayOfWeekShort(d)) },
                modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (more) "접기" else "펼치기") { more = !more }.heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("조건 더 정하기 (선택)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(if (more) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
    }
    AnimatedVisibility(more) {
        Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("어떤 날")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RuleDayKind.ANY to "상관없어요", RuleDayKind.WORK_DAY to "근무일", RuleDayKind.OFF_DAY to "쉬는 날", RuleDayKind.HOLIDAY to "공휴일·휴일")
                    .forEach { (v, l) -> FilterChip(selected = draft.dayKind == v, onClick = { draft.dayKind = v }, label = { Text(l) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            Label("어떤 근무")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(RulePieces.ALL to "모두", RulePieces.SHIFT to "근무표 근무만", RulePieces.EXTRA to "잔업·특근·대근만")
                    .forEach { (v, l) -> FilterChip(selected = draft.pieces == v, onClick = { draft.pieces = v }, label = { Text(l) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            if (kind.countsHours) {
                Toggle("시간대를 정해요", "이 시간 안에 일한 시간만 세요. 끝이 시작보다 이르면 밤을 넘겨요.", draft.window) { draft.window = it }
                if (draft.window) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    HourStepper("시작", draft.from) { draft.from = it }
                    Text("~", style = MaterialTheme.typography.titleMedium)
                    HourStepper("끝", draft.to) { draft.to = it }
                }
                Label("하루에 몇 시간 넘는 시간만")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0, 8, 10, 12).forEach { h ->
                        FilterChip(selected = draft.overHours == h, onClick = { draft.overHours = h }, label = { Text(if (h == 0) "모두" else "${h}시간 넘게") },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HourStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    // [UX-08] The app's one stepper, running round the clock.
    com.bamnat.app.ui.components.CountStepper(value, onChange, name = label, range = 0..23, wrap = true, format = { "${it}시" }, valueWidth = 56.dp)
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v

/**
 * What the item does to this month, as it is typed: "10월엔 +80,000원 · 4번". From a payslip gap, what is
 * left of the difference, and a tick when it is closed.
 */
@Composable
private fun EffectBar(
    month: YearMonth, draft: ItemDraft, effect: ItemEffect?, timesOnly: Int?, gap: Long?, enabled: Boolean, label: String, onSave: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val per = draft.kind == ItemKind.PER_SHIFT || draft.kind == ItemKind.PER_DAY || draft.kind == ItemKind.PER_HOUR || draft.kind == ItemKind.PREMIUM ||
                draft.kind == ItemKind.DEDUCT && draft.deductWay != DeductWay.MONTHLY
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text("${month.monthValue}월 급여에", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    effect != null -> Text(
                        (if (effect.amount >= 0) "+" else "−") + won(abs(effect.amount)) + if (per) " · ${effect.times}번" else "",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = if (effect.amount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    timesOnly != null && per -> Text(
                        if (timesOnly == 0) "이번 달엔 맞는 근무가 없어요" else "이번 달 ${timesOnly}번 해당돼요. 금액을 넣어 주세요",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    else -> Text("금액을 넣으면 바로 계산해요", style = MaterialTheme.typography.titleSmall)
                }
                if (gap != null && effect != null) {
                    val left = gap - effect.amount
                    val closed = abs(left) <= 1_000
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (closed) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp).padding(end = 4.dp))
                        Text(
                            if (closed) "명세서와 맞아요" else "명세서와 남은 차이 ${if (left > 0) "+" else "−"}${won(abs(left))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (closed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // A count is known: the amount that would close the gap, one tap away.
                    val each = if (per && effect.times > 0 && !closed && draft.amount != null) (abs(gap).toDouble() / effect.times / 10).roundToLong() * 10 else null
                    if (each != null && each > 0 && draft.kind != ItemKind.PREMIUM) {
                        TextButton(onClick = { draft.amount = each }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("차이에 맞춰 1번에 ${won(each)}로 넣기")
                        }
                    }
                }
            }
            Button(onClick = onSave, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
        }
    }
}

/**
 * [PAYS-07] The way in on the pay screen: what it is for in one line, so a company the presets do not fit knows
 * it can still get its payslip exactly.
 */
@Composable
internal fun MakeItemCard(enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("우리 회사 항목 만들기", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("명세서에 있는데 앱에 없는 수당·가산·차감을 직접 만들어 정확히 맞춰요. 금액은 휴대폰에만 남아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** [PAYS-07] An item just made, said back in a sentence with the way to take it back. */
@Composable
internal fun MadeNotice(made: MadeItem, onUndo: (() -> Unit)?) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("‘${made.name}’ 항목을 만들었어요. 합계에 바로 들어갔어요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
            if (onUndo != null) TextButton(onClick = onUndo, modifier = Modifier.heightIn(min = 48.dp)) { Text("되돌리기") }
        }
    }
}

/** [PAYS-07] One rule of the company's own: what it does, its switch, and changing or removing it. */
@Composable
internal fun CustomRuleRow(
    rule: CustomRule, shiftNames: Map<Long, String>, enabled: Boolean,
    onEdit: (() -> Unit)?, onOn: (Boolean) -> Unit, onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && onEdit != null, onClickLabel = "고치기") { onEdit?.invoke() }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rule.name, style = MaterialTheme.typography.bodyLarge,
                color = if (rule.on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            // The way it is paid (or taken off), and that a tap changes it, as the alarm rows say it.
            Text(rule.describe(shiftNames) + if (enabled && onEdit != null) " · 눌러서 고치기" else "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = rule.on, onCheckedChange = onOn, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = "‘${rule.name}’ ${if (rule.on) "켜짐" else "꺼짐"}" })
        IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Filled.Close, "‘${rule.name}’ 지우기") }
    }
}

/**
 * [PAYS-09] The company's own items in words without amounts, to start 회사 방식 알려 주기 from; the user reads and
 * changes it before anything is sent.
 */
internal fun waysText(profile: PayProfile, shiftNames: Map<Long, String>, made: MadeItem?): String {
    val lines = buildList {
        profile.customRules.filter { it.on && (made !is MadeItem.Rule || it.id != made.rule.id) }
            .forEach { add("• ${it.name}: ${it.describeWithoutAmount(shiftNames)}") }
        when (made) {
            is MadeItem.Rule -> add("• ${made.rule.name}: ${made.rule.describeWithoutAmount(shiftNames)}")
            is MadeItem.Item -> add("• ${made.item.name}: ${made.item.cadence.label} 정액")
            null -> Unit
        }
    }
    return if (lines.isEmpty()) "" else "우리 회사에는 이런 항목이 있어요 (금액은 빼고 적었어요)\n" + lines.joinToString("\n")
}
