package com.bamnat.app.ui.money

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
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
    val context = LocalContext.current
    var on by remember { mutableStateOf(CaptureService.isOn(context)) }
    var access by remember { mutableStateOf(CaptureService.hasAccess(context)) }
    var explaining by remember { mutableStateOf(explainFirst) }
    LifecycleResumeEffect(Unit) {
        on = CaptureService.isOn(context)
        access = CaptureService.hasAccess(context)
        onPauseOrDispose { }
    }
    SectionCard(title = "자동으로 적기") {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            })
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

/** [LEDG-05] Fixed costs due within a week, with one tap to write each down when it leaves. */
@Composable
fun UpcomingCard(
    upcoming: List<Upcoming>,
    all: List<FixedCost>,
    leftThisMonth: Long,
    onRecord: (Upcoming) -> Unit,
    onEdit: (FixedCost?) -> Unit,
) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    SectionCard(
        title = "곧 나갈 돈",
        trailing = { TextButton(onClick = { onEdit(null) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("고정 지출") } },
    ) {
        if (all.isEmpty()) {
            Text("월세·보험·통신비·카드값처럼 매달 나가는 돈을 적어 두면 3일 전과 당일 아침에 알려 드려요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        if (upcoming.isEmpty()) {
            Text("일주일 안에 나갈 고정 지출이 없어요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        upcoming.forEach { u ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable { onEdit(u.cost) }) {
                    Text(u.cost.name, style = MaterialTheme.typography.titleSmall)
                    Text("${if (u.daysLeft == 0) "오늘" else "D-${u.daysLeft}"} · ${Fmt.shortDate(u.date)} · ${Fmt.won(u.cost.amount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (u.daysLeft <= 1) LocalCalendarColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AssistChip(onClick = { onRecord(u) }, label = { Text("나갔어요") })
            }
        }
        if (leftThisMonth > 0) {
            Text("이번 달 남은 고정 지출 ${Fmt.won(leftThisMonth)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "접기" else "고정 지출 ${all.size}개 모두 보기") }
        if (showAll) {
            all.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { onEdit(c) }.padding(vertical = 4.dp)) {
                    Text("${c.name} · 매달 ${if (c.day >= 31) "말일" else "${c.day}일"}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(Fmt.won(c.amount), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Adds or edits one fixed cost [LEDG-05]. A card bill is a transfer so card spending is not counted twice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FixedCostDialog(initial: FixedCost?, onSave: (FixedCost) -> Unit, onDelete: (FixedCost) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var digits by rememberSaveable { mutableStateOf(amountDigits(initial?.amount ?: 0)) }
    var day by rememberSaveable { mutableStateOf(initial?.day ?: 25) }
    var card by rememberSaveable { mutableStateOf(initial?.kind == MoneyKind.TRANSFER) }
    var category by rememberSaveable { mutableStateOf(initial?.category?.takeIf { initial.kind == MoneyKind.EXPENSE } ?: "주거") }
    var remind by rememberSaveable { mutableStateOf(initial?.remind ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "고정 지출 추가" else "고정 지출 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                    label = { Text("이름 (예: 월세, 보험, 신한카드)") }, modifier = Modifier.fillMaxWidth())
                AmountField("금액", digits, { digits = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("매달", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = { day = if (day <= 1) 31 else day - 1 }) { Text("−") }
                    Text(if (day >= 31) "말일" else "${day}일", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { day = if (day >= 31) 1 else day + 1 }) { Text("+") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("카드값이에요", style = MaterialTheme.typography.bodyLarge)
                        Text("카드로 쓴 돈은 이미 지출에 있어서, 카드값은 이체로 적어 두 번 세지 않아요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = card, onCheckedChange = { card = it })
                }
                if (!card) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("주거", "통신", "공과금", "구독", "교육", "자동차", "의료", "기타").forEach { c ->
                            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("3일 전·당일 아침 알림", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = remind, onCheckedChange = { remind = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && digits.toAmount() > 0, onClick = {
                onSave(FixedCost(id = initial?.id ?: 0, name = name.trim(), amount = digits.toAmount(), day = day,
                    kind = if (card) MoneyKind.TRANSFER else MoneyKind.EXPENSE,
                    category = if (card) "카드값" else category.takeIf { it in MoneyCategories.expense } ?: "기타", remind = remind))
            }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = { onDelete(initial) }) { Text("지우기", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
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
