package com.bamnat.app.ui.work

import com.bamnat.app.ui.guide.FeatureCard
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.data.WorkToolsStore
import com.bamnat.app.share.QuoteImage
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.money.AmountField
import com.bamnat.app.ui.money.ClientField
import com.bamnat.app.ui.money.amountDigits
import com.bamnat.app.ui.money.toAmount
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.DailyWorkerTax
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.Quote
import com.bamnat.core.money.QuoteItem
import com.bamnat.core.money.QuoteVat
import com.bamnat.core.money.TradeWages
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * [FLD-11][FLD-13] A field calendar's extra cards on 일감·수입: 일용직 원천징수 for the month's day-rate
 * work, and the saved 견적서 with 새 견적서.
 */
@Composable
fun FieldToolCards(calendarId: String, book: MoneyBook, month: YearMonth, today: LocalDate = LocalDate.now()) {
    // [GUIDE-05] Each card can be turned off for its calendar.
    FeatureCard("field.dailyTax", calendarId) { DailyTaxCard(book, month) }
    FeatureCard("field.quote", calendarId) { QuotesCard(calendarId, today) }
}

/**
 * [FLD-13] 일용직 세금: what the month's 일당 owe as 일용근로소득 — paid together (one 정산) and paid day by
 * day — apart from 3.3% 사업소득, with how it is worked out.
 */
@Composable
private fun DailyTaxCard(book: MoneyBook, month: YearMonth) {
    val m = remember(book, month) { DailyWorkerTax.month(month, book.jobs) }
    if (m.days == 0) return
    SectionCard(title = "일용직 세금 (${month.monthValue}월)") {
        ValueLine("일당 ${m.days}일", Fmt.won(m.wages))
        ValueLine("한 번에 받을 때", if (m.together.total > 0) "${Fmt.won(m.together.total)} 떼요" else "안 떼요", strong = true)
        if (m.together.total > 0) Hint("소득세 ${Fmt.won(m.together.income)} + 지방소득세 ${Fmt.won(m.together.local)}")
        ValueLine("날마다 받을 때", if (m.daily.total > 0) "${Fmt.won(m.daily.total)} 떼요" else "안 떼요")
        Hint(
            "일용직은 (일당 − 15만 원) × 2.7%를 소득세로, 그 10%를 지방소득세로 떼요. 한 번에 받는 세금이 1,000원이 안 되면 떼지 않아서 " +
                "일당 ${Fmt.won(DailyWorkerTax.TAX_FREE_UP_TO)}까지는 날마다 받으면 0원이에요. 여러 날을 모아 받으면 합쳐서 봐요. " +
                "3.3%(사업소득)와는 다른 세금이에요. 참고용이에요.",
            Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun QuotesCard(calendarId: String, today: LocalDate) {
    val context = LocalContext.current
    val store = remember { WorkToolsStore.get(context) }
    val tools by store.of(calendarId).collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Quote?>(null) }
    // [GUIDE-01] Opened from the guide or the help search: a new 견적서.
    com.bamnat.app.ui.guide.FocusedTool("quote", "field.quote") { editing = Quote(date = today, items = listOf(QuoteItem(""))) }
    SectionCard(title = "견적서", trailing = {
        TextButton(onClick = { editing = Quote(date = today, items = listOf(QuoteItem(""))) }) {
            Icon(Icons.Filled.Add, null)
            Text("새 견적서")
        }
    }) {
        if (tools.quotes.isEmpty()) Hint("품목·수량·단가를 적으면 견적서 사진을 만들어 보낼 수 있어요. 손님이 좋다고 하면 일감으로 저장해요.")
        tools.quotes.sortedByDescending { it.date }.take(8).forEach { q ->
            Row(Modifier.fillMaxWidth().clickable { editing = q }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(listOf(q.client, q.title).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "견적서" },
                        style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    Text(Fmt.shortFullDate(q.date) + if (q.jobId != null) " · 일감으로 저장함" else "", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Fmt.won(q.total), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
            }
        }
    }
    editing?.let { q -> QuoteScreen(calendarId, q) { editing = null } }
}

/**
 * [FLD-11] 견적서 만들기: client, title, date, items (품목·수량·단위·단가), 부가세 (없음·별도·포함), how long it is
 * valid and a note; then 보내기 as an image, 저장, or 일감으로 저장 (a 가계약 job for the total).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuoteScreen(calendarId: String, initial: Quote, onClose: () -> Unit) {
    val context = LocalContext.current
    val c = context.appContainer
    val store = remember { WorkToolsStore.get(context) }
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf(initial) }
    var sender by rememberSaveable { mutableStateOf("") }
    var jobDay by remember { mutableStateOf(initial.date) }
    var deleting by remember { mutableStateOf(false) }

    fun persist(next: Quote = q): Quote {
        val withId = if (next.id == 0L) next.copy(id = System.currentTimeMillis()) else next
        // The job made from it is written in the background; its id is kept once it is there.
        val madeJob = store.current(calendarId).quotes.firstOrNull { it.id == withId.id }?.jobId?.takeIf { it > 0 }
        val saved = if (madeJob != null) withId.copy(jobId = madeJob) else withId
        store.update(calendarId) { t -> t.copy(quotes = t.quotes.filter { it.id != saved.id } + saved) }
        q = saved
        return saved
    }

    ToolScreen(if (initial.id == 0L) "새 견적서" else "견적서", onClose, action = "저장", actionEnabled = !q.isBlank, onAction = { persist(); onClose() }) {
        SectionCard(title = null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ClientField(q.client, { q = q.copy(client = it.take(30)) }, label = "손님·업체")
                OutlinedTextField(q.title, { q = q.copy(title = it.take(40)) }, label = { Text("공사·작업 이름 (예: 욕실 타일)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField("견적일", q.date, { if (it != null) q = q.copy(date = it) }, clearable = false)
            }
        }
        SectionCard(title = "품목") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                q.items.forEachIndexed { i, item ->
                    key(i, q.items.size) {
                        QuoteItemRow(item, onChange = { n -> q = q.copy(items = q.items.toMutableList().also { it[i] = n }) },
                            onRemove = if (q.items.size > 1) ({ q = q.copy(items = q.items.filterIndexed { k, _ -> k != i }) }) else null)
                    }
                    if (i < q.items.lastIndex) HorizontalDivider()
                }
                if (q.items.size < 20) OutlinedButton(onClick = { q = q.copy(items = q.items + QuoteItem("")) }) { Text("품목 더하기") }
            }
        }
        SectionCard(title = "부가세") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuoteVat.entries.forEach { v -> FilterChip(selected = q.vat == v, onClick = { q = q.copy(vat = v) }, label = { Text(v.label) }) }
            }
            if (q.vat != QuoteVat.NONE) {
                ValueLine("공급가액", Fmt.won(q.supply))
                ValueLine("부가세 10%", Fmt.won(q.vatAmount))
            }
            ValueLine("합계", Fmt.won(q.total), strong = true, color = MaterialTheme.colorScheme.primary)
            Hint(when (q.vat) {
                QuoteVat.NONE -> "사업자가 아니거나 부가세를 따로 받지 않을 때예요."
                QuoteVat.ADDED -> "적은 단가에 부가세 10%를 더해요."
                QuoteVat.INCLUDED -> "적은 단가에 부가세가 들어 있어요. 공급가액은 합계 ÷ 1.1이에요."
            })
        }
        SectionCard(title = "그 밖에") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("유효기간", q.validDays.toString(), { q = q.copy(validDays = it.toIntOrNull()?.coerceIn(0, 365) ?: 0) }, suffix = "일", maxLength = 3)
                OutlinedTextField(q.note, { q = q.copy(note = it.take(200)) }, label = { Text("메모 (자재 포함 여부, 계좌 등)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                OutlinedTextField(sender, { sender = it.take(30) }, label = { Text("보내는 사람 (선택)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !q.isBlank, modifier = Modifier.weight(1f), onClick = {
                val saved = persist()
                scope.launch { runCatching { QuoteImage.share(context, saved, sender.trim()) } }
            }) { Text("사진으로 보내기") }
        }
        SectionCard(title = "일감으로 저장") {
            if (q.jobId != null) Hint("이 견적서로 일감을 만들었어요. 달력에서 그 날을 눌러 고칠 수 있어요.")
            else {
                DateField("일할 날", jobDay, { if (it != null) jobDay = it }, clearable = false)
                Hint("가계약 상태로 ${Fmt.won(q.total)}짜리 일감을 만들어요. 계약금을 받으면 일감에서 확정으로 바꿔요.")
                OutlinedButton(enabled = !q.isBlank && q.total > 0, onClick = {
                    val saved = persist()
                    c.appScope.launch {
                        val job = c.calendars.money(calendarId).save(saved.toJob(jobDay))
                        store.update(calendarId) { t -> t.copy(quotes = t.quotes.map { if (it.id == saved.id) it.copy(jobId = job.id) else it }) }
                    }
                    q = saved.copy(jobId = -1)
                    Toast.makeText(context, "${Fmt.monthDay(jobDay)}에 일감으로 저장했어요", Toast.LENGTH_SHORT).show()
                }) { Text("일감으로 저장") }
            }
        }
        if (initial.id != 0L) TextButton(onClick = { deleting = true }) { Text("견적서 지우기", color = MaterialTheme.colorScheme.error) }
    }
    if (deleting) ConfirmDialog("견적서를 지울까요?", "만든 일감은 그대로 남아요.", "지우기", onDismiss = { deleting = false }, onConfirm = {
        store.update(calendarId) { t -> t.copy(quotes = t.quotes.filter { it.id != initial.id }) }
        deleting = false
        onClose()
    }, destructive = true)
}

@Composable
private fun QuoteItemRow(item: QuoteItem, onChange: (QuoteItem) -> Unit, onRemove: (() -> Unit)?) {
    var qty by remember { mutableStateOf(item.quantity.asField()) }
    var unitMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(item.name, { onChange(item.copy(name = it.take(30))) }, label = { Text("품목") }, singleLine = true, modifier = Modifier.weight(1f))
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "품목 빼기") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField("수량", qty, { qty = it; onChange(item.copy(quantity = it.toNumber())) }, Modifier.weight(0.8f), decimal = true)
            Column(Modifier.weight(0.6f)) {
                OutlinedButton(onClick = { unitMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(item.unit) }
                DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                    Quote.UNITS.forEach { u -> DropdownMenuItem(text = { Text(u) }, onClick = { onChange(item.copy(unit = u)); unitMenu = false }) }
                }
            }
            AmountField("단가", amountDigits(item.unitPrice), { onChange(item.copy(unitPrice = it.toAmount())) }, Modifier.weight(1.4f))
        }
        if (item.amount > 0) Text("= ${Fmt.won(item.amount)}", style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * [FLD-12] 공종별 평균 일당 참고표: the built-in table ([TradeWages], 대한건설협회 시중노임단가) with a search,
 * shown when setting 일당; tapping a trade fills in its 일당.
 */
@Composable
fun TradeWageDialog(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val list = TradeWages.find(query)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("공종별 평균 일당") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it.take(10) }, label = { Text("직종 찾기 (예: 타일)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(list, key = { it.name }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { onPick(t.daily) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text(Fmt.won(t.daily), style = MaterialTheme.typography.bodyLarge.merge(NumberStyle))
                        }
                    }
                }
                Hint("출처: ${TradeWages.SOURCE}. ${TradeWages.NOTE} 누르면 일당에 넣어요.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

/** [FLD-13] One line under an 일당: what a day of it owes as 일용근로소득 when paid by itself. */
fun dailyTaxLine(rate: Long): String? {
    if (rate <= 0) return null
    val tax = DailyWorkerTax.paidTogether(listOf(rate))
    return if (tax.total == 0L) "일용직이면 하루치로 받을 때 세금 0원 (일당 ${Fmt.won(DailyWorkerTax.TAX_FREE_UP_TO)}까지)"
    else "일용직이면 하루 세금 ${Fmt.won(tax.total)} (소득세 ${Fmt.won(tax.income)} + 지방세 ${Fmt.won(tax.local)})"
}

/** [FLD-12] "공종별 평균 일당" link under an 일당 field. */
@Composable
fun TradeWageLink(onPick: (Long) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("공종별 평균 일당 보기 (${TradeWages.SOURCE_SHORT})") }
    if (open) TradeWageDialog(onDismiss = { open = false }) { onPick(it); open = false }
}
