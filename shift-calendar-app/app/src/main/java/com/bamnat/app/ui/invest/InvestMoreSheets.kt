package com.bamnat.app.ui.invest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.money.AmountField
import com.bamnat.app.ui.money.amountDigits
import com.bamnat.app.ui.money.toAmount
import com.bamnat.core.invest.AlarmKind
import com.bamnat.core.invest.DayGrade
import com.bamnat.core.invest.EventKind
import com.bamnat.core.invest.InvestAlarm
import com.bamnat.core.invest.InvestAlarms
import com.bamnat.core.invest.InvestEvent
import com.bamnat.core.invest.InvestGoal
import com.bamnat.core.invest.InvestSettings
import com.bamnat.core.invest.InvestTaxes
import com.bamnat.core.invest.JournalEntry
import com.bamnat.core.invest.Market
import com.bamnat.core.invest.ReturnMethod
import com.bamnat.core.invest.Side
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

private enum class Repeat(val label: String) { WEEKDAYS("평일"), DAILY("매일"), PICK("요일 고르기"), MONTHLY("매달"), ONCE("한 번") }
private val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

private fun repeatOf(a: InvestAlarm): Repeat = when {
    a.once != null -> Repeat.ONCE
    a.monthDay != null -> Repeat.MONTHLY
    a.weekdays == WEEKDAYS -> Repeat.WEEKDAYS
    a.weekdays.size == 7 -> Repeat.DAILY
    else -> Repeat.PICK
}

/**
 * [INV-08] An alarm: 장 시작·마감, 정기 매수 (what to buy and how much, so its notice can write the trade),
 * 목표가 확인, or one of the user's own. Weekly ones skip the days the market is shut; a monthly plan
 * on a shut day comes on the next open day.
 */
@Composable
internal fun AlarmSheet(vm: InvestViewModel, state: InvestState, kind0: AlarmKind, editing: InvestAlarm?, onDismiss: () -> Unit) {
    val start = editing ?: InvestAlarms.default(kind0)
    var kind by rememberSaveable { mutableStateOf(start.kind) }
    var label by rememberSaveable { mutableStateOf(start.label) }
    var time by rememberSaveable { mutableStateOf(minuteText(start.minute)) }
    var repeat by rememberSaveable { mutableStateOf(repeatOf(start)) }
    val days = remember { mutableStateListOf<DayOfWeek>().apply { addAll(start.weekdays) } }
    var monthDay by rememberSaveable { mutableStateOf((start.monthDay ?: 25).toString()) }
    var once by rememberSaveable { mutableStateOf((start.once ?: state.today.plusDays(1)).toString()) }
    var pickingOnce by remember { mutableStateOf(false) }
    var skip by rememberSaveable { mutableStateOf(start.skipHolidays) }
    var name by rememberSaveable { mutableStateOf(start.name) }
    var symbol by rememberSaveable { mutableStateOf(start.symbol) }
    var market by rememberSaveable { mutableStateOf(start.market) }
    var side by rememberSaveable { mutableStateOf(start.side) }
    var accountId by rememberSaveable { mutableStateOf(start.accountId ?: state.settings.lastAccountId ?: state.full.activeAccounts.firstOrNull()?.id ?: 0L) }
    var byAmount by rememberSaveable { mutableStateOf(start.quantity == null) }
    var qty by rememberSaveable { mutableStateOf(InvestFmt.input(start.quantity)) }
    var amount by rememberSaveable { mutableStateOf(amountDigits(start.amount ?: 0)) }
    var target by rememberSaveable { mutableStateOf(InvestFmt.input(start.targetPrice)) }
    var ring by rememberSaveable { mutableStateOf(start.ring) }
    var deleting by remember { mutableStateOf(false) }
    val needsHolding = kind == AlarmKind.PLAN || kind == AlarmKind.TARGET

    fun build(): InvestAlarm {
        val minute = parseMinute(time) ?: start.minute
        return start.copy(
            kind = kind, label = label.trim(), minute = minute,
            weekdays = when (repeat) { Repeat.WEEKDAYS -> WEEKDAYS; Repeat.DAILY -> DayOfWeek.entries.toSet(); Repeat.PICK -> days.toSet(); else -> emptySet() },
            monthDay = if (repeat == Repeat.MONTHLY) monthDay.toIntOrNull()?.coerceIn(1, 31) ?: 25 else null,
            once = if (repeat == Repeat.ONCE) runCatching { LocalDate.parse(once) }.getOrNull() else null,
            skipHolidays = skip, symbol = symbol.trim(), name = name.trim(), market = market,
            accountId = accountId.takeIf { it != 0L }, side = side,
            quantity = if (kind == AlarmKind.PLAN && !byAmount) qty.toDecimal()?.takeIf { it > 0 } else null,
            amount = if (kind == AlarmKind.PLAN && byAmount) amount.toAmount().takeIf { it > 0 } else null,
            targetPrice = target.toDecimal()?.takeIf { it > 0 }, ring = ring, enabled = true,
        )
    }
    val preview = build()
    val next = InvestAlarms.next(preview, LocalDateTime.now())
    val valid = parseMinute(time) != null && (repeat != Repeat.PICK || days.isNotEmpty()) && (!needsHolding || (name.isNotBlank() || symbol.isNotBlank()))

    InvestSheetFrame(
        title = if (editing == null) "투자 알람 추가" else "투자 알람 고치기",
        onDismiss = onDismiss, canSave = valid,
        onSave = { vm.saveAlarm(build()); onDismiss() },
        actions = {
            if (editing != null) IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.Delete, "지우기", tint = MaterialTheme.colorScheme.error) }
        },
    ) {
        ChoiceChips(AlarmKind.entries, { it == kind }, { it.label }, { k ->
            kind = k
            if (editing == null) InvestAlarms.default(k).let { d ->
                time = minuteText(d.minute); repeat = repeatOf(d); days.clear(); days.addAll(d.weekdays); skip = d.skipHolidays
                d.monthDay?.let { monthDay = it.toString() }
            }
        })
        OutlinedTextField(label, { label = it.take(30) }, label = { Text("이름 (선택)") }, placeholder = { Text(preview.title) }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(time, { time = it.filter { c -> c.isDigit() || c == ':' }.take(5) }, label = { Text("시각") }, placeholder = { Text("09:00") },
            singleLine = true, isError = parseMinute(time) == null,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
        ChoiceChips(Repeat.entries, { it == repeat }, { it.label }, { repeat = it })
        when (repeat) {
            Repeat.PICK -> ChoiceChips(DayOfWeek.entries, { it in days }, { DAY_NAMES[it.ordinal] }, { d -> if (d in days) days.remove(d) else days.add(d) })
            Repeat.MONTHLY -> OutlinedTextField(monthDay, { monthDay = it.filter(Char::isDigit).take(2) }, label = { Text("매달 며칠") }, suffix = { Text("일") },
                singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth())
            Repeat.ONCE -> OutlinedButton(onClick = { pickingOnce = true }) { Text(Fmt.dayTitle(runCatching { LocalDate.parse(once) }.getOrDefault(state.today))) }
            else -> Unit
        }
        if (repeat != Repeat.ONCE) SwitchRow(
            if (repeat == Repeat.MONTHLY) "휴장일이면 다음 장날에" else "주말·공휴일·휴장일은 건너뛰기", skip) { skip = it }
        if (needsHolding || kind == AlarmKind.CUSTOM) {
            FieldLabel("종목")
            val known = state.fullResult.byKey.take(8)
            if (known.isNotEmpty()) ChoiceChips(known, { it.key == com.bamnat.core.invest.holdingKey(symbol, name) }, { it.title }, { h ->
                name = h.name; symbol = h.symbol; market = h.market
            })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("종목명") }, singleLine = true, modifier = Modifier.weight(1.4f))
                OutlinedTextField(symbol, { symbol = it.take(20) }, label = { Text("코드") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            ChoiceChips(Market.entries, { it == market }, { it.label }, { market = it })
        }
        if (kind == AlarmKind.PLAN) {
            AccountPicker(state.full.activeAccounts, accountId) { accountId = it }
            ChoiceChips(Side.entries, { it == side }, { it.label }, { side = it })
            ChoiceChips(listOf(true, false), { it == byAmount }, { if (it) "금액으로" else "수량으로" }, { byAmount = it })
            if (byAmount) AmountField(label = "한 번에 얼마어치", digits = amount, onChange = { amount = it }, modifier = Modifier.fillMaxWidth())
            else DecimalField("한 번에 몇 주", qty, { qty = it }, Modifier.fillMaxWidth(), suffix = if (market == Market.CRYPTO) "개" else "주")
            Text("알림에서 ‘매매 기록’을 누르면 이 내용이 채워진 기록 창이 열려요. 현재가를 적어 두면 ‘바로 기록’ 한 번으로 끝나요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (kind == AlarmKind.TARGET || kind == AlarmKind.PLAN) {
            DecimalField(if (kind == AlarmKind.TARGET) "목표가" else "기준 가격 (선택)", target, { target = it }, Modifier.fillMaxWidth(),
                suffix = if (market.foreign) "$" else "원",
                supporting = if (kind == AlarmKind.TARGET) "그 시각에 목표가에 닿았는지 확인하라고 알려요. 가격을 받아 오지는 않아요" else "현재가가 없을 때 이 가격으로 수량을 계산해요")
        }
        SwitchRow("알람처럼 소리로 울리기", ring) { ring = it }
        Text(next?.let { "다음 알림: ${Fmt.dayTitle(it.toLocalDate())} ${minuteText(it.hour * 60 + it.minute)}" } ?: "다시 울릴 날이 없어요",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
    if (pickingOnce) InvestDatePicker(runCatching { LocalDate.parse(once) }.getOrDefault(state.today), { once = it.toString() }) { pickingOnce = false }
    if (deleting && editing != null) com.bamnat.app.ui.components.ConfirmDialog(
        title = "이 알람을 지울까요?", message = "‘${editing.title}’ 알람이 더는 울리지 않아요. 이 알람으로 적은 매매 기록은 남아요.",
        confirmLabel = "지우기", destructive = true, onDismiss = { deleting = false },
        onConfirm = { deleting = false; vm.deleteAlarm(editing); onDismiss() },
    )
}

@Composable
internal fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** [INV-14] A date to watch, written by the user: 배당락일, 실적 발표, 청약… told about that morning. */
@Composable
internal fun EventSheet(vm: InvestViewModel, date0: LocalDate, editing: InvestEvent?, onDismiss: () -> Unit) {
    var kind by rememberSaveable { mutableStateOf(editing?.kind ?: EventKind.EARNINGS) }
    var date by rememberSaveable { mutableStateOf((editing?.date ?: date0).toString()) }
    var symbol by rememberSaveable { mutableStateOf(editing?.symbol ?: "") }
    var title by rememberSaveable { mutableStateOf(editing?.title ?: "") }
    var memo by rememberSaveable { mutableStateOf(editing?.memo ?: "") }
    var remind by rememberSaveable { mutableStateOf(editing?.remind ?: true) }
    var picking by remember { mutableStateOf(false) }
    val day = runCatching { LocalDate.parse(date) }.getOrDefault(date0)
    InvestSheetFrame(
        title = if (editing == null) "투자 일정 적기" else "투자 일정 고치기",
        onDismiss = onDismiss,
        onSave = {
            vm.saveEvent(InvestEvent(editing?.id ?: 0, day, kind, symbol.trim(), title.trim(), memo.trim(), remind))
            onDismiss()
        },
        actions = {
            if (editing != null) IconButton(onClick = { vm.deleteEvent(editing); onDismiss() }) {
                Icon(Icons.Outlined.Delete, "지우기", tint = MaterialTheme.colorScheme.error)
            }
        },
    ) {
        ChoiceChips(EventKind.entries, { it == kind }, { it.label }, { kind = it })
        OutlinedButton(onClick = { picking = true }) { Text(Fmt.dayTitle(day)) }
        OutlinedTextField(symbol, { symbol = it.take(30) }, label = { Text("종목 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(title, { title = it.take(40) }, label = { Text("내용") }, placeholder = { Text(kind.label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(memo, { memo = it.take(200) }, label = { Text("메모") }, modifier = Modifier.fillMaxWidth())
        SwitchRow("그날 아침 8시 30분에 알려 주기", remind) { remind = it }
        Text("날짜는 직접 적어요. 공시나 배당 일정을 받아 오지는 않아요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (picking) InvestDatePicker(day, { date = it.toString() }) { picking = false }
}

/**
 * [INV-05][INV-11][INV-12] 목표 (금액, 수익률, 날짜, 매달 넣을 돈), which 수익률 the summary shows and
 * the ISA limit. The progress moves as the numbers are typed.
 */
@Composable
internal fun GoalSheet(vm: InvestViewModel, state: InvestState, onDismiss: () -> Unit) {
    val s0 = state.settings
    var amount by rememberSaveable { mutableStateOf(amountDigits(s0.goalAmount ?: 0)) }
    var rate by rememberSaveable { mutableStateOf(InvestFmt.input(s0.goalReturnPercent)) }
    var date by rememberSaveable { mutableStateOf(s0.goalDate?.toString() ?: "") }
    var monthly by rememberSaveable { mutableStateOf(amountDigits(s0.monthlyAdd ?: 0)) }
    var method by rememberSaveable { mutableStateOf(s0.returnMethod) }
    var isa by rememberSaveable { mutableStateOf(s0.isaLimit) }
    var picking by remember { mutableStateOf(false) }
    fun settings(s: InvestSettings) = s.copy(
        goalAmount = amount.toAmount().takeIf { it > 0 }, goalReturnPercent = rate.toDecimal()?.takeIf { it > 0 },
        goalDate = runCatching { LocalDate.parse(date) }.getOrNull(), monthlyAdd = monthly.toAmount().takeIf { it > 0 },
        returnMethod = method, isaLimit = isa,
    )
    val preview = InvestGoal.progress(settings(s0), state.fullResult.summary, state.today)
    val hide = state.hide
    InvestSheetFrame(title = "목표와 수익률", onDismiss = onDismiss, onSave = { vm.saveSettings(::settings); onDismiss() }) {
        AmountField(label = "모으고 싶은 총자산", digits = amount, onChange = { amount = it }, modifier = Modifier.fillMaxWidth())
        DecimalField("목표 수익률", rate, { rate = it }, Modifier.fillMaxWidth(), suffix = "%")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picking = true }) {
                Text(runCatching { LocalDate.parse(date) }.getOrNull()?.let { "${it.year}년 ${it.monthValue}월까지" } ?: "언제까지 (선택)")
            }
            if (date.isNotEmpty()) TextButton(onClick = { date = "" }) { Text("날짜 빼기") }
        }
        AmountField(label = "매달 더 넣을 돈 (선택)", digits = monthly, onChange = { monthly = it }, modifier = Modifier.fillMaxWidth())
        if (preview.hasGoal) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                preview.amountRatio?.let { Text("총자산 목표의 ${"%.0f".format(it * 100)}% · 남은 ${InvestFmt.short(preview.amountLeft ?: 0, hide)}") }
                preview.returnRatio?.let { Text("수익률 목표의 ${"%.0f".format(it)}%") }
                preview.monthsLeft?.let { Text(if (it == 0) "벌써 닿았어요" else "매달 넣으면 약 ${it}개월 뒤 (수익 없이 계산)") }
                preview.perMonthToDate?.let { Text("날짜까지 닿으려면 매달 ${InvestFmt.short(it, hide)}씩") }
            }
        }
        FieldLabel("먼저 보여 줄 수익률")
        ReturnMethod.entries.forEach { m ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { method = m }.padding(vertical = 4.dp)) {
                androidx.compose.material3.RadioButton(selected = method == m, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                    Text(m.explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        FieldLabel("ISA 비과세 한도")
        ChoiceChips(listOf(InvestTaxes.ISA_LIMIT, InvestTaxes.ISA_LIMIT_LOW_INCOME), { it == isa }, { (if (it == InvestTaxes.ISA_LIMIT) "일반형 " else "서민형 ") + Fmt.wonShort(it) }, { isa = it })
    }
    if (picking) InvestDatePicker(runCatching { LocalDate.parse(date) }.getOrDefault(state.today.plusYears(1)), { date = it.toString() }) { picking = false }
}

/** [INV-07] The user's own rules, checked off in each day's journal and tagged on each trade. */
@Composable
internal fun PrinciplesSheet(vm: InvestViewModel, state: InvestState, onDismiss: () -> Unit) {
    val rules = remember { mutableStateListOf<String>().apply { addAll(state.settings.principles) } }
    InvestSheetFrame(
        title = "내 투자 원칙",
        onDismiss = onDismiss,
        onSave = { vm.saveSettings { it.copy(principles = rules.map { r -> r.trim() }.filter { r -> r.isNotEmpty() }) }; onDismiss() },
    ) {
        Text("매일 일지에서 체크해요. 지킨 날과 어긴 날의 손익을 통계에서 비교할 수 있어요.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        rules.forEachIndexed { i, r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(r, { rules[i] = it.replace('\n', ' ').take(40) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("원칙 ${i + 1}") })
                IconButton(onClick = { rules.removeAt(i) }) { Icon(Icons.Outlined.RemoveCircleOutline, "원칙 ${i + 1} 빼기") }
            }
        }
        if (rules.size < 10) TextButton(onClick = { rules.add("") }) {
            Icon(Icons.Filled.Add, null)
            Text("원칙 더하기")
        }
    }
}

/**
 * [INV-07] The day's journal: how it went, which rules were kept, what the market did and looking back,
 * with the day's trades and result above so the review has them at hand.
 */
@Composable
internal fun JournalSheet(vm: InvestViewModel, state: InvestState, date: LocalDate, onDismiss: () -> Unit) {
    val j0 = state.full.journalOn(date) ?: JournalEntry(date)
    var grade by rememberSaveable { mutableStateOf(j0.grade) }
    // [INV-07] Kept rules by their text; ones since taken off the list stay on the day as they were.
    val checked = remember { mutableStateListOf<String>().apply { addAll(j0.checked) } }
    var market by rememberSaveable { mutableStateOf(j0.market) }
    var review by rememberSaveable { mutableStateOf(j0.review) }
    val principles = state.settings.principles
    InvestSheetFrame(
        title = "${Fmt.dayTitle(date)} 일지",
        onDismiss = onDismiss,
        onSave = { vm.saveJournal(j0.copy(grade = grade, checked = (principles.filter { it in checked } + checked).toCollection(LinkedHashSet()),market = market.trim(), review = review.trim())); onDismiss() },
    ) {
        val trades = state.tradesOn(date)
        val pnl = state.daily[date]
        if (trades.isNotEmpty() || pnl != null) {
            Text(
                listOfNotNull(
                    trades.takeIf { it.isNotEmpty() }?.let { "매매 ${it.size}건 · " + it.take(3).joinToString(", ") { t -> "${t.title} ${t.side.label}" } },
                    pnl?.let { "이날 손익 ${InvestFmt.signed(it, state.hide)}" },
                ).joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium, color = pnl?.let { pnlColor(it) } ?: MaterialTheme.colorScheme.onSurface,
            )
        }
        FieldLabel("오늘 어땠나요")
        ChoiceChips(DayGrade.entries, { it == grade }, { it.label }, { g -> grade = if (grade == g) null else g })
        if (principles.isNotEmpty()) {
            FieldLabel("지킨 원칙")
            principles.distinct().forEach { p ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { if (p in checked) checked.remove(p) else checked.add(p) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = p in checked, onCheckedChange = null)
                    Text(p, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        OutlinedTextField(market, { market = it.take(1000) }, label = { Text("시장 메모") }, placeholder = { Text("코스피 −1.2%, 금리 발표, 환율 1,400원") },
            modifier = Modifier.fillMaxWidth(), minLines = 2)
        OutlinedTextField(review, { review = it.take(2000) }, label = { Text("복기") }, placeholder = { Text("잘한 점, 아쉬운 점, 내일 할 일") },
            modifier = Modifier.fillMaxWidth(), minLines = 3)
    }
}
