package com.bamnat.app.ui.money

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.money.Repeat
import com.bamnat.core.money.UpcomingPayments
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.bamnat.app.data.Capture
import com.bamnat.app.money.CaptureService
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.MoneyCategories
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.MoneySource
import com.bamnat.core.money.MoneyStats
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.money.Upcoming
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [LEDG-02] Lines the app wrote down from notifications, waiting for a glance, and texts it kept
 * because it could not finish them. Totals already include the lines; this is only a check.
 */
@Composable
fun NewRecordsCard(
    unseen: List<MoneyEntry>,
    kept: List<Capture>,
    onOpen: (MoneyEntry) -> Unit,
    onAllSeen: () -> Unit,
    onFinish: (Capture) -> Unit,
    onDrop: (Capture) -> Unit,
) {
    if (unseen.isEmpty() && kept.isEmpty()) return
    SectionCard(
        title = "새로 들어온 기록 ${unseen.size + kept.size}건",
        trailing = { if (unseen.isNotEmpty()) TextButton(onClick = onAllSeen) { Text("모두 확인") } },
    ) {
        if (unseen.isNotEmpty()) {
            Text("결제 알림에서 적었어요. 분류가 틀리면 눌러서 고치면 그 가게는 다음부터 그 분류로 적어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        unseen.take(10).forEach { e -> EntryRow(e, showDate = true) { onOpen(e) } }
        if (unseen.size > 10) {
            Text("그 밖에 ${unseen.size - 10}건은 아래 기록에 있어요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (kept.isNotEmpty()) {
            if (unseen.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("다 못 읽은 알림", style = MaterialTheme.typography.labelLarge)
            kept.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { onFinish(c) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.text.replace('\n', ' '), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${c.app} · ${c.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDrop(c) }) { Icon(Icons.Filled.Close, "이 알림 지우기") }
                }
            }
            Text("눌러서 금액을 적으면 기록이 되고 알림 글은 지워져요. 30일이 지나면 저절로 지워져요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What a kept notification becomes when the user opens it: as much as the parser read. */
fun Capture.draft(): MoneyEntry {
    // Read as of when it arrived, so a text kept on the 30th lands in that month, not today's.
    val arrived = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(postedAt), java.time.ZoneId.systemDefault())
    val day = arrived.toLocalDate()
    val parsed = (PaymentParser.parse(text, arrived) as? PaymentParser.Result.Ok)?.parsed
    return parsed?.let { PaymentParser.toEntry(it.copy(amount = it.amount ?: 0), day, MoneySource.NOTIFICATION) }?.copy(reviewed = true)
        ?: MoneyEntry(date = day, kind = MoneyKind.EXPENSE, amount = 0, category = "기타", merchant = text.lines().firstOrNull().orEmpty().take(30),
            source = MoneySource.NOTIFICATION)
}

/**
 * [LEDG-02][LEDG-03] Ways to stop typing: payment notifications and pasted texts. Turning the
 * notification reader on first says, in plain words, what it reads and that nothing leaves the
 * phone (Play's prominent disclosure), then opens the phone's own permission page.
 */
@Composable
fun AutoRecordCard(onPaste: () -> Unit, explainFirst: Boolean = false) {
    SectionCard(title = "자동으로 적기") { AutoRecordControls(onPaste, explainFirst) }
}

/**
 * [LEDG-16] 자동 기록 from the money tab's ⋮, always in the same place, and where settings'
 * 결제 알림 읽기 row lands ([NoticeLinks.Target.AUTO_RECORD]).
 */
@Composable
fun AutoRecordDialog(onPaste: () -> Unit, onDismiss: () -> Unit, onRules: (() -> Unit)? = null, onFilter: (() -> Unit)? = null) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자동 기록") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AutoRecordControls(onPaste = { onDismiss(); onPaste() })
                // [LEDG-21][LEDG-22] How it sorts and what it leaves out.
                if (onRules != null) OutlinedButton(onClick = onRules, modifier = Modifier.fillMaxWidth()) { Text("자동 분류 규칙") }
                if (onFilter != null) OutlinedButton(onClick = onFilter, modifier = Modifier.fillMaxWidth()) { Text("알림 거르기") }
                Text("돈 탭 위 ⋮ > 자동 기록에서 언제든 켜고 꺼요.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun AutoRecordControls(onPaste: () -> Unit, explainFirst: Boolean = false) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(CaptureService.isOn(context)) }
    var access by remember { mutableStateOf(CaptureService.hasAccess(context)) }
    var explaining by remember { mutableStateOf(explainFirst) }
    LifecycleResumeEffect(Unit) {
        on = CaptureService.isOn(context)
        access = CaptureService.hasAccess(context)
        onPauseOrDispose { }
    }
    Column {
        Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("결제 알림으로 적기", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        on && access -> "카드·은행·페이 알림이 오면 저절로 적어요"
                        on -> "휴대폰 설정에서 ‘알림 접근’을 허용해야 시작해요"
                        else -> "카드 결제 알림이 오면 금액·가게·날짜를 읽어 적어요"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = on && access, onCheckedChange = { want ->
                if (want) explaining = true else { CaptureService.setOn(context, false); on = false }
            }, modifier = Modifier.semantics { contentDescription = "결제 알림 읽기" })
        }
        if (on && !access) {
            TextButton(onClick = { openAccess(context) }) { Text("알림 접근 허용하러 가기") }
        }
        OutlinedButton(onClick = onPaste, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Icon(Icons.Filled.ContentPaste, null)
            Spacer(Modifier.width(6.dp))
            Text("결제 문자 붙여넣기")
        }
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text("결제 알림을 읽어도 될까요?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("휴대폰에 오는 알림 가운데 금액과 ‘승인·결제·입금·출금’ 같은 말이 함께 있는 알림만 읽어 가계부에 적어요.")
                    Text("대화·광고 같은 다른 알림은 읽자마자 버리고 저장하지 않아요. 읽은 내용은 이 휴대폰 안에만 있고 어디로도 보내지 않아요.")
                    Text("다 못 읽은 결제 알림만 직접 고치실 수 있게 최대 30일 휴대폰에 두고, 그 뒤 지워요. 언제든 여기서 끌 수 있어요.")
                    Text("다음 화면에서 ‘밤낮 결제 알림 기록’을 허용해 주세요.", fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    explaining = false
                    CaptureService.setOn(context, true)
                    on = true
                    if (!CaptureService.hasAccess(context)) openAccess(context)
                }) { Text("허용하러 가기") }
            },
            dismissButton = { TextButton(onClick = { explaining = false }) { Text("나중에") } },
        )
    }
}

private fun openAccess(context: Context) {
    val intent = CaptureService.accessSettings(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Some phones have no per-app page; the list of apps with notification access is everywhere.
        context.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** One pasted text read into a line, and whether it goes in. */
private data class PasteRow(val chunk: String, val entry: MoneyEntry?, val reason: String?, val duplicate: Boolean)

/**
 * [LEDG-03] Card and bank texts pasted at once (several, cancellations, installments, foreign,
 * deposits). Each one is shown as it will be written; the user unticks what should not go in.
 */
@Composable
fun PasteDialog(
    existing: List<MoneyEntry>,
    rules: Map<String, String>,
    onSave: (List<MoneyEntry>) -> Unit,
    onDismiss: () -> Unit,
    today: LocalDate = LocalDate.now(),
    initialText: String = "",
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf(initialText) }
    val rows = remember(text, existing, rules) {
        // Rows earlier in the same paste count too: the card text and the pay push for one payment.
        val seen = existing.toMutableList()
        PaymentParser.splitMessages(text).map { chunk ->
            when (val r = PaymentParser.parse(chunk, LocalDateTime.now(), rules)) {
                is PaymentParser.Result.Fail -> PasteRow(chunk, null, r.reason, false)
                is PaymentParser.Result.Ok -> {
                    val p = r.parsed
                    if (p.needsWon) PasteRow(chunk, null, "해외 결제라 원화 금액을 따로 적어 주세요", false)
                    else {
                        val day = p.date ?: today
                        val category = if (p.type == PaymentParser.Type.CANCEL) PaymentParser.findCancelTarget(p, day, existing)?.category ?: p.category else p.category
                        val entry = PaymentParser.toEntry(p, today, MoneySource.PASTE, category)
                        val duplicate = PaymentParser.findDuplicate(p, day, seen) != null
                        if (entry != null && !duplicate) seen += entry
                        PasteRow(chunk, entry, null, duplicate)
                    }
                }
            }
        }
    }
    val skipped = remember(rows) { mutableStateListOf<Int>().apply { rows.forEachIndexed { i, r -> if (r.duplicate) add(i) } } }
    val chosen = rows.withIndex().filter { (i, r) -> r.entry != null && i !in skipped }.map { it.value.entry!! }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("결제 문자 붙여넣기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("카드·은행 문자를 길게 눌러 복사한 뒤 붙여 넣어요. 여러 건도 한 번에 돼요. 문자 원문은 저장하지 않아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(value = text, onValueChange = { text = it.take(20_000) }, label = { Text("문자 내용") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), maxLines = 8)
                if (text.isBlank()) {
                    FilledTonalButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    }) { Icon(Icons.Filled.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text("복사한 문자 가져오기") }
                }
                rows.forEachIndexed { i, r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = r.entry != null && i !in skipped, enabled = r.entry != null,
                            onCheckedChange = { if (it) skipped.remove(i) else skipped.add(i) })
                        Column(Modifier.weight(1f)) {
                            val e = r.entry
                            if (e != null) {
                                EntryRow(e, showDate = true) { if (i in skipped) skipped.remove(i) else skipped.add(i) }
                                if (r.duplicate) Text("이미 적힌 결제 같아요", style = MaterialTheme.typography.labelSmall,
                                    color = LocalCalendarColors.current.warning)
                            } else {
                                Text(r.chunk.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(r.reason.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = chosen.isNotEmpty(), onClick = { onSave(chosen) }) {
                Text(if (chosen.isEmpty()) "적기" else "${chosen.size}건 적기")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * [LEDG-05][LEDG-09] Repeating money due within a week, both ways: what leaves (with card bills
 * worked out from what was spent on each card [LEDG-08]) and what comes in, each written down with
 * one tap. Lines set to write themselves say so; the whole list opens below.
 */
@Composable
fun UpcomingCard(
    upcoming: List<Upcoming>,
    all: List<FixedCost>,
    leftThisMonth: Long,
    onRecord: (Upcoming) -> Unit,
    onEdit: (FixedCost?) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val cal = LocalCalendarColors.current
    val incoming = upcoming.any { it.cost.kind == MoneyKind.INCOME } || all.any { it.kind == MoneyKind.INCOME }
    SectionCard(
        title = if (incoming) "곧 나갈 돈·들어올 돈" else "곧 나갈 돈",
        trailing = { TextButton(onClick = { onEdit(null) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("반복") } },
    ) {
        if (all.isEmpty() && upcoming.isEmpty()) {
            Text("월세·보험·통신비처럼 매달 나가는 돈, 월급처럼 들어오는 돈을 적어 두면 3일 전과 당일 아침에 알려 드려요. 그날 저절로 가계부에 적게 할 수도 있어요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        if (upcoming.isEmpty()) {
            Text("일주일 안에 오갈 돈이 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        upcoming.forEach { u ->
            val c = u.cost
            val card = u.cardId != null
            val name = if (card) "${c.name} ${UpcomingPayments.CARD_BILL}" else c.name
            val income = c.kind == MoneyKind.INCOME
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(10.dp))
                    .then(if (c.id > 0) Modifier.clickable { onEdit(c) } else Modifier).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryAvatar(c.category, size = 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(if (u.daysLeft == 0) "오늘" else "D-${u.daysLeft}", Fmt.shortDate(u.date),
                            if (c.auto) "저절로 적어요" else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (u.daysLeft <= 1 && !income) cal.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text((if (income) "+" else if (c.kind == MoneyKind.EXPENSE) "-" else "") + Fmt.won(c.amount),
                        style = MaterialTheme.typography.titleSmall.merge(NumberStyle), fontWeight = FontWeight.Bold,
                        color = when (c.kind) { MoneyKind.INCOME -> cal.income; MoneyKind.EXPENSE -> cal.expense; else -> MaterialTheme.colorScheme.onSurface })
                    AssistChip(onClick = { onRecord(u) }, label = { Text(if (income) "들어왔어요" else if (card) "냈어요" else "나갔어요") },
                        modifier = Modifier.semantics { contentDescription = "$name ${Fmt.won(c.amount)} ${if (income) "들어왔어요" else "나갔어요"}로 적기" })
                }
            }
        }
        if (leftThisMonth > 0) {
            Text("이번 달 남은 반복 지출 ${Fmt.won(leftThisMonth)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (all.isNotEmpty()) {
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "접기" else "반복 ${all.size}개 모두 보기") }
        }
        if (showAll) {
            all.forEach { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onEdit(c) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(c.schedule, c.category, if (c.auto) "자동" else null, c.end?.let { "${Fmt.shortFullDate(it)}까지" }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Fmt.won(c.amount), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                        color = if (c.kind == MoneyKind.INCOME) cal.income else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/**
 * Adds or edits one repeating line [LEDG-05][LEDG-09]: money out or in, or a card bill (a transfer,
 * so card spending is not counted twice), every week, month or year, from an account, with a first
 * and last day, reminded and, if wished, written down by itself on the day.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FixedCostDialog(
    initial: FixedCost?,
    onSave: (FixedCost) -> Unit,
    onDelete: (FixedCost) -> Unit,
    onDismiss: () -> Unit,
    book: MoneyBook? = null,
    today: LocalDate = LocalDate.now(),
) {
    val ledger = rememberLedger(book)
    var kind by rememberSaveable { mutableStateOf(initial?.kind ?: MoneyKind.EXPENSE) }
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var digits by rememberSaveable { mutableStateOf(amountDigits(initial?.amount ?: 0)) }
    var repeat by rememberSaveable { mutableStateOf(initial?.repeat ?: Repeat.MONTH) }
    var day by rememberSaveable { mutableIntStateOf(initial?.day ?: 25) }
    var weekday by rememberSaveable { mutableIntStateOf(if (initial?.repeat == Repeat.WEEK) initial.day else today.dayOfWeek.value) }
    var month by rememberSaveable { mutableIntStateOf(initial?.monthOfYear ?: today.monthValue) }
    val categories = remember(ledger, kind) { ledger.categoriesOf(kind) }
    var category by rememberSaveable {
        mutableStateOf(initial?.category ?: if (kind == MoneyKind.TRANSFER) UpcomingPayments.CARD_BILL else "주거")
    }
    var account by rememberSaveable { mutableStateOf(initial?.accountId) }
    var remind by rememberSaveable { mutableStateOf(initial?.remind ?: true) }
    var auto by rememberSaveable { mutableStateOf(initial?.auto ?: false) }
    var start by rememberSaveable { mutableStateOf((initial?.start ?: if (initial == null) today else null)?.toString()) }
    var end by rememberSaveable { mutableStateOf(initial?.end?.toString()) }
    var picking by remember { mutableStateOf<String?>(null) }
    val accounts = ledger.activeAccounts
    val chosenCategory = category.takeIf { it in categories } ?: categories.first()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (initial == null) "반복 추가" else "반복 고치기", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                if (initial != null) IconButton(onClick = { onDelete(initial) }) { Icon(Icons.Outlined.Delete, "지우기", tint = MaterialTheme.colorScheme.error) }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "닫기") }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val kinds = listOf(MoneyKind.EXPENSE to "나갈 돈", MoneyKind.INCOME to "들어올 돈", MoneyKind.TRANSFER to "카드값·이체")
                    kinds.forEachIndexed { i, (k, label) ->
                        SegmentedButton(selected = kind == k, onClick = {
                            kind = k
                            val list = ledger.categoriesOf(k)
                            if (category !in list) category = when (k) {
                                MoneyKind.TRANSFER -> UpcomingPayments.CARD_BILL
                                MoneyKind.INCOME -> MoneyCategories.SALARY
                                MoneyKind.EXPENSE -> "주거"
                            }.takeIf { it in list } ?: list.first()
                        }, shape = SegmentedButtonDefaults.itemShape(i, kinds.size)) { Text(label, maxLines = 1) }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                    label = { Text(when (kind) { MoneyKind.INCOME -> "이름 (예: 월급, 용돈)"; MoneyKind.TRANSFER -> "이름 (예: 신한카드, 적금)"; else -> "이름 (예: 월세, 보험)" }) },
                    modifier = Modifier.fillMaxWidth())
                AmountField("금액", digits, { digits = it })
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Repeat.entries.forEachIndexed { i, r ->
                        SegmentedButton(selected = repeat == r, onClick = { repeat = r }, shape = SegmentedButtonDefaults.itemShape(i, Repeat.entries.size)) { Text(r.label) }
                    }
                }
                when (repeat) {
                    // One row of seven, so the week reads as a week on the narrowest screen.
                    Repeat.WEEK -> SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        FixedCost.WEEKDAYS.forEachIndexed { i, w ->
                            SegmentedButton(
                                selected = weekday == i + 1, onClick = { weekday = i + 1 },
                                shape = SegmentedButtonDefaults.itemShape(i, FixedCost.WEEKDAYS.size),
                                icon = {},
                                modifier = Modifier.semantics { contentDescription = "${w}요일" },
                            ) { Text(w, maxLines = 1, softWrap = false) }
                        }
                    }
                    Repeat.MONTH -> Stepper("날짜", if (day >= 31) "말일" else "${day}일", { day = if (day <= 1) 31 else day - 1 }, { day = if (day >= 31) 1 else day + 1 })
                    Repeat.YEAR -> {
                        Stepper("달", "${month}월", { month = if (month <= 1) 12 else month - 1 }, { month = if (month >= 12) 1 else month + 1 })
                        Stepper("날짜", if (day >= 31) "말일" else "${day}일", { day = if (day <= 1) 31 else day - 1 }, { day = if (day >= 31) 1 else day + 1 })
                    }
                }
                Text("분류", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    categories.forEach { c ->
                        FilterChip(selected = chosenCategory == c, onClick = { category = c }, label = { Text(c) },
                            leadingIcon = { Icon(categoryIcon(c), null, Modifier.size(18.dp)) })
                    }
                }
                if (kind == MoneyKind.TRANSFER) {
                    Text("카드로 쓴 돈은 이미 나간 돈에 있어서, 카드값은 이체로 적어 두 번 세지 않아요. 결제수단에 신용카드를 더하면 카드값을 쓴 만큼 알아서 계산해요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (accounts.isNotEmpty()) {
                    Text(when (kind) { MoneyKind.INCOME -> "들어오는 곳"; MoneyKind.TRANSFER -> "어디서"; else -> "결제수단" },
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        FilterChip(selected = account == null, onClick = { account = null }, label = { Text("안 고름") })
                        accounts.forEach { a ->
                            FilterChip(selected = account == a.id, onClick = { account = a.id }, label = { Text(a.label) },
                                leadingIcon = { Icon(accountIcon(a.type), null, Modifier.size(18.dp)) })
                        }
                    }
                }
                SwitchLine("그날 저절로 가계부에 적기", "앱을 열거나 알림이 올 때 빠진 날까지 한 번씩만 적어요.", auto) { auto = it }
                SwitchLine(if (kind == MoneyKind.INCOME) "당일 아침 알림" else "3일 전·당일 아침 알림", null, remind) { remind = it }
                DateLine("시작", start?.let(LocalDate::parse)?.let(Fmt::shortFullDate) ?: "처음부터") { picking = "start" }
                DateLine("끝나는 날", end?.let(LocalDate::parse)?.let(Fmt::shortFullDate) ?: "계속", clear = if (end != null) ({ end = null }) else null) { picking = "end" }
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider()
            val ready = name.isNotBlank() && digits.toAmount() > 0
            Button(
                enabled = ready,
                onClick = {
                    onSave(FixedCost(
                        id = initial?.id ?: 0, name = name.trim(), amount = digits.toAmount(),
                        day = if (repeat == Repeat.WEEK) weekday else day, kind = kind, category = chosenCategory, remind = remind,
                        repeat = repeat, monthOfYear = month, accountId = account, auto = auto,
                        start = start?.let(LocalDate::parse), end = end?.let(LocalDate::parse),
                    ))
                },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp).heightIn(min = 48.dp),
            ) { Text("저장") }
        }
    }
    picking?.let { which ->
        val current = (if (which == "start") start else end)?.let(LocalDate::parse) ?: today
        val state = rememberDatePickerState(initialSelectedDateMillis = current.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
                        if (which == "start") start = d else end = d
                    }
                    picking = null
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("취소") } },
        ) { DatePicker(state, showModeToggle = false) }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onMinus) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 48.dp), textAlign = TextAlign.Center)
        IconButton(onClick = onPlus) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun SwitchLine(title: String, caption: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun DateLine(label: String, value: String, clear: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (clear != null) IconButton(onClick = clear) { Icon(Icons.Filled.Close, "$label 지우기") }
    }
}

/** [LEDG-04] Where the month's money went most often; a tap shows only that place's lines. */
@Composable
fun PlacesSection(places: List<MoneyStats.Place>, selected: String?, onSelect: (String?) -> Unit) {
    if (places.isEmpty()) return
    Text("자주 쓴 곳", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    places.forEach { p ->
        Row(
            Modifier.fillMaxWidth().clickable { onSelect(if (selected == p.merchant) null else p.merchant) }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.merchant, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected == p.merchant) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${p.category} · ${p.count}번", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(Fmt.won(p.amount), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
