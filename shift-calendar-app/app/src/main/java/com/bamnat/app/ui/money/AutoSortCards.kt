package com.bamnat.app.ui.money

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.AutoRecordPrefs
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.core.money.CaptureFilter
import com.bamnat.core.money.OtherPlaces
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.money.RepeatFinder

/**
 * [LEDG-21] The rules 자동 기록 sorts by: those learned when the user moved a place, and those
 * written here ("이 말이 들어가면"). Each can be moved to another category or forgotten.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryRulesDialog(
    rules: Map<String, String>,
    categories: List<String>,
    onSave: (merchant: String, category: String) -> Unit,
    onDelete: (merchant: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var adding by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("자동 분류 규칙") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("가게를 다른 분류로 옮기면 여기 생겨요. 같은 가게의 다른 지점에도 맞아요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rules.isEmpty()) {
                    Text("아직 규칙이 없어요", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                }
                rules.entries.sortedBy { it.key.removePrefix(PaymentParser.CONTAINS) }.forEach { (m, c) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { editing = m to c }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ruleLabel(m), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("→ $c", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onDelete(m) }) { Icon(Icons.Outlined.Delete, "${ruleLabel(m)} 규칙 지우기") }
                    }
                }
                FilledTonalButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("규칙 더하기") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
    editing?.let { (m, c) ->
        RuleEditor(initialWord = m.removePrefix(PaymentParser.CONTAINS), initialContains = PaymentParser.isContainsRule(m), initialCategory = c,
            categories = categories, onSave = { key, cat -> if (key != m) onDelete(m); onSave(key, cat); editing = null },
            onDismiss = { editing = null })
    }
    if (adding) {
        RuleEditor(initialWord = "", initialContains = true, initialCategory = categories.firstOrNull().orEmpty(), categories = categories,
            onSave = { key, cat -> onSave(key, cat); adding = false }, onDismiss = { adding = false })
    }
}

private fun ruleLabel(key: String): String =
    if (PaymentParser.isContainsRule(key)) "‘${key.removePrefix(PaymentParser.CONTAINS)}’ 들어가면" else key

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(
    initialWord: String,
    initialContains: Boolean,
    initialCategory: String,
    categories: List<String>,
    onSave: (key: String, category: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var word by rememberSaveable { mutableStateOf(initialWord) }
    var contains by rememberSaveable { mutableStateOf(initialContains) }
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    val clean = word.replace(Regex("\\s+"), " ").trim().take(40)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialWord.isEmpty()) "규칙 더하기" else "규칙 고치기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = word, onValueChange = { word = it.replace("*", "") }, singleLine = true,
                    label = { Text(if (contains) "이 말이 들어가면" else "가게 이름") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { contains = !contains }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = contains, onCheckedChange = { contains = it })
                    Text("이름 어디에든 이 말이 있으면", style = MaterialTheme.typography.bodyMedium)
                }
                Text("분류", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { c -> FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = clean.isNotEmpty() && category.isNotEmpty(), onClick = {
                onSave(if (contains) PaymentParser.CONTAINS + clean else clean, category)
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

/**
 * [LEDG-22] What 결제 알림으로 적기 leaves out: apps that sent payment notifications (each can be
 * turned off), words that drop a text, and the smallest amount written down.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptureFilterDialog(state: AutoRecordPrefs.State, onChange: (CaptureFilter) -> Unit, onDismiss: () -> Unit) {
    val filter = state.filter
    var word by rememberSaveable { mutableStateOf("") }
    var min by rememberSaveable { mutableStateOf(amountDigits(filter.minAmount)) }
    fun addWord() {
        val w = CaptureFilter.clean(word)
        if (w.isNotEmpty() && w !in filter.words && filter.words.size < CaptureFilter.MAX_WORDS) onChange(filter.copy(words = filter.words + w))
        word = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("알림 거르기") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("읽을 앱", style = MaterialTheme.typography.labelLarge)
                if (state.seenApps.isEmpty()) {
                    Text("결제 알림을 보낸 앱이 생기면 여기 나와요", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.seenApps.entries.sortedBy { it.value }.forEach { (pkg, label) ->
                    val on = pkg !in filter.blockedApps
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Switch(checked = on, onCheckedChange = { want ->
                            onChange(filter.copy(blockedApps = if (want) filter.blockedApps - pkg else filter.blockedApps + pkg))
                        }, modifier = Modifier.semantics { contentDescription = "$label 알림 읽기" })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("이 말이 있으면 적지 않기", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    filter.words.forEach { w ->
                        InputChip(selected = false, onClick = { onChange(filter.copy(words = filter.words - w)) }, label = { Text(w) },
                            trailingIcon = { Icon(Icons.Filled.Close, "$w 지우기") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = word, onValueChange = { word = it.take(CaptureFilter.MAX_WORD) }, singleLine = true,
                        label = { Text("예: 회사 법인카드") }, modifier = Modifier.weight(1f))
                    TextButton(enabled = word.isNotBlank(), onClick = ::addWord) { Text("더하기") }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                AmountField("이 금액보다 적으면 적지 않기", min, { d ->
                    min = d
                    onChange(filter.copy(minAmount = d.toAmount()))
                })
                Text("0이면 모두 적어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { addWord(); onDismiss() }) { Text("닫기") } },
    )
}

/**
 * [LEDG-23] Spending still in 기타, one row per place with its branches together: one tap on a
 * category moves them all and teaches the place for next time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OtherPlacesCard(groups: List<OtherPlaces.Group>, categories: List<String>, onPick: (OtherPlaces.Group, String) -> Unit) {
    if (groups.isEmpty()) return
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val choices = categories.filter { it != com.bamnat.core.money.MoneyCategories.OTHER }
    SectionCard(title = "기타로 남은 곳 ${groups.size}곳") {
        Text("가게를 눌러 분류를 고르면 그곳 기록이 모두 옮겨지고 다음부터 그 분류로 적어요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        groups.take(5).forEach { g ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { open = if (open == g.place) null else g.place },
                verticalAlignment = Alignment.CenterVertically) {
                Text(g.place, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${g.entries.size}건 · ${Fmt.won(g.total)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open == g.place) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    choices.forEach { c -> AssistChip(onClick = { open = null; onPick(g, c) }, label = { Text(c) }) }
                }
            }
        }
        if (groups.size > 5) {
            Text("그 밖에 ${groups.size - 5}곳은 정리하면 이어서 나와요", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [LEDG-24] Payments that came back every month, offered as a 반복 지출 (never added by itself). */
@Composable
fun RepeatSuggestCard(suggestions: List<RepeatFinder.Suggestion>, onAdd: (RepeatFinder.Suggestion) -> Unit, onNo: (RepeatFinder.Suggestion) -> Unit) {
    if (suggestions.isEmpty()) return
    SectionCard(title = "매달 나가는 돈 같아요") {
        Text("반복으로 등록하면 나가기 사흘 전과 당일에 알려 주고, 곧 나갈 돈에 들어가요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        suggestions.forEach { s ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(s.place, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("매달 ${s.day}일쯤 ${Fmt.won(s.amount)} · 최근 ${s.months}달", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onAdd(s) }) { Text("반복으로 등록") }
                    TextButton(onClick = { onNo(s) }) { Text("아니에요") }
                }
            }
        }
    }
}
