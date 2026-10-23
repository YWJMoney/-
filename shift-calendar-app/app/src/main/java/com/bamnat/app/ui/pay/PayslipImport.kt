package com.bamnat.app.ui.pay

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.bamnat.app.pay.PayslipScanner
import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.pay.CustomRule
import com.bamnat.core.pay.PayslipRead
import com.bamnat.core.pay.PayslipReader
import com.bamnat.core.pay.RuleChange
import com.bamnat.core.pay.RuleFinding
import com.bamnat.core.pay.RuleText
import com.bamnat.core.pay.SlipRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * [PAYS-05] Two buttons that read the text of a photo on the phone: one picks a photo or screenshot, one takes a
 * picture. The text goes to [onText]; a photo taken here is deleted once read.
 */
@Composable
internal fun ScanButtons(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun read(uri: Uri, taken: Boolean) {
        reading = true
        error = null
        scope.launch {
            try {
                val text = PayslipScanner.read(context, uri)
                if (text.isBlank()) error = "글자를 찾지 못했어요. 밝은 곳에서 명세서가 화면에 꽉 차게 다시 찍어 주세요."
                else onText(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.bamnat.app.pay.OcrModel.NotReady) {
                error = e.message
            } catch (e: Exception) {
                error = "사진을 읽지 못했어요. 다른 사진으로 해 보거나 글로 붙여 넣어 주세요."
            } finally {
                if (taken) PayslipScanner.clear(context)
                reading = false
            }
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) read(uri, taken = false) }
    var shot by remember { mutableStateOf<Uri?>(null) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = shot
        if (ok && uri != null) read(uri, taken = true) else PayslipScanner.clear(context)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !reading,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.PhotoLibrary, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("사진 고르기")
            }
            OutlinedButton(
                onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", PayslipScanner.scanFile(context))
                    shot = uri
                    try {
                        take.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        error = "카메라 앱을 찾지 못했어요. 사진을 찍어 둔 뒤 ‘사진 고르기’로 골라 주세요."
                    }
                },
                enabled = !reading,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.PhotoCamera, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("사진 찍기")
            }
        }
        if (reading) Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("  휴대폰 안에서 글자를 읽고 있어요", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

private fun SlipRole.short(): String = when (this) {
    SlipRole.GROSS, SlipRole.TOTAL -> "지급 총액"
    SlipRole.BASE -> "기본급"
    SlipRole.HOURLY -> "시급"
    SlipRole.OVERTIME -> "연장"
    SlipRole.NIGHT -> "야간"
    SlipRole.HOLIDAY -> "휴일"
    SlipRole.WEEKLY_HOLIDAY -> "주휴"
    SlipRole.EXTRA_WORK -> "잔업·특근·대근"
    SlipRole.BONUS -> "상여"
    SlipRole.ALLOWANCE -> "수당"
    SlipRole.DEDUCTION, SlipRole.DEDUCTION_TOTAL -> "공제"
    SlipRole.NET -> "실수령"
}

/** [PAYS-05] What was read from a payslip, line by line, and whether the lines add up to its total. */
@Composable
internal fun SlipPreview(read: PayslipRead) {
    val shown = read.lines.filter { it.role != SlipRole.DEDUCTION }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEach { line ->
            Row(Modifier.fillMaxWidth()) {
                Text(line.role.short(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(76.dp))
                Text(line.name + (line.minutes?.let { " · ${com.bamnat.core.pay.PayCalculator.hours(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(line.amount?.let(::won) ?: "", style = MaterialTheme.typography.bodyMedium)
            }
        }
        val deductions = read.lines.count { it.role == SlipRole.DEDUCTION }
        if (deductions > 0) Text("공제 ${deductions}줄은 빼고 봐요", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        read.gross?.let { g ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (read.consistent) {
                    Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text("  항목을 더하면 지급 총액 ${won(g)}과 맞아요", style = MaterialTheme.typography.bodySmall)
                } else Text("항목 합 ${won(read.earningsSum)} · 지급 총액 ${won(g)}. 빠진 줄이 있으면 맞춰 보기 화면에서 고쳐 주세요",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

/**
 * [PAYS-04][PAYS-05] 명세서로 채우기: a photo, a screenshot or pasted text of one payslip becomes its lines, shown
 * before anything is filled. [onRead] gets them once the user agrees.
 */
@Composable
fun PayslipFillDialog(title: String, onDismiss: () -> Unit, onRead: (PayslipRead) -> Unit) {
    var text by remember { mutableStateOf("") }
    var read by remember { mutableStateOf<PayslipRead?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val r = read
                if (r == null) {
                    Text("명세서 사진이나 캡처 한 장이면 돼요. 앱이 휴대폰 안에서 읽고, 사진과 금액은 어디에도 보내지 않아요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ScanButtons(onText = { t -> text = t; read = PayslipReader.read(t) })
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text("또는 명세서 글 붙여 넣기") },
                        placeholder = { Text("기본급 2,508,000\n연장근로수당 288,000\n식대 200,000\n지급합계 2,996,000") },
                        minLines = 4, modifier = Modifier.fillMaxWidth(),
                    )
                } else if (r.lines.isEmpty()) {
                    Text("금액을 찾지 못했어요. ‘기본급 2,508,000’처럼 이름과 금액이 함께 보이게 찍거나 붙여 넣어 주세요.")
                } else {
                    r.month?.let { Text("${it.year}년 ${it.monthValue}월 명세서", style = MaterialTheme.typography.titleSmall) }
                    SlipPreview(r)
                }
            }
        },
        confirmButton = {
            val r = read
            when {
                r == null -> Button(onClick = { read = PayslipReader.read(text) }, enabled = text.isNotBlank()) { Text("읽기") }
                r.lines.isEmpty() -> Button(onClick = { read = null }) { Text("다시 하기") }
                else -> Button(onClick = { onRead(r) }) { Text("이대로 채우기") }
            }
        },
        dismissButton = {
            if (read != null) TextButton(onClick = { read = null }) { Text("다시 하기") } else TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

/**
 * [PAYS-06] 규칙을 말로 적기: sentences from a 취업규칙, a contract or a colleague, typed, pasted or read from a
 * photo, become changes the user ticks before they apply. Anything the app does not know as a preset becomes a
 * rule of the company's own.
 */
@Composable
internal fun RuleSentenceBox(
    kinds: List<ExtraKind>,
    shiftTypes: List<ShiftType>,
    fiveOrMore: Boolean,
    onApply: (List<RuleChange>) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val found = remember { mutableStateListOf<Pair<RuleFinding, Boolean>>() }
    var read by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!read) {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("회사 규칙을 적어 주세요") },
                placeholder = { Text("예)\n대근은 8시간에 기본급의 10%, 8시간 넘으면 50% 가산, 야간 50% 가산\n토요일 근무는 50% 더\n상여금 연 800% 짝수달") },
                minLines = 4, modifier = Modifier.fillMaxWidth(),
            )
            ScanButtons(onText = { t -> text = if (text.isBlank()) t else text + "\n" + t })
            Button(
                onClick = {
                    found.clear()
                    found += RuleText.read(text, kinds, shiftTypes, fiveOrMore).map { it to (it.confidence >= 0.6) }
                    read = true
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("읽기") }
            Hint("한 줄에 규칙 하나씩 적으면 가장 잘 읽어요. 명세서 금액(연장근로수당 523,000원 같은 줄)은 ‘명세서로 맞춰 보기’에 넣어 주세요.")
        } else {
            if (found.isEmpty()) Text("읽을 수 있는 규칙을 못 찾았어요. ‘야간 50% 가산’, ‘토요일 근무 1회 2만원’처럼 무엇에 얼마인지 함께 적어 주세요.",
                style = MaterialTheme.typography.bodyMedium)
            found.forEachIndexed { i, (f, on) ->
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { found[i] = f to !on }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                        Text(f.change.summary, style = MaterialTheme.typography.bodyLarge)
                        Text("“${f.sourceLine}”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (f.confidence < 0.6) Text("확실하지 않아요. 맞는지 봐 주세요.", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
            val chosen = found.filter { it.second }.map { it.first.change }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { read = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 쓰기") }
                Button(
                    onClick = { onApply(chosen); text = ""; found.clear(); read = false },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("${chosen.size}개 넣기") }
            }
        }
    }
}

/** [PAYS-06] The user's own rules, each with its sentence, on or off, or removed. */
@Composable
internal fun CustomRuleList(
    rules: List<CustomRule>,
    shiftNames: Map<Long, String>,
    extraNames: Map<Long, String>,
    onChange: (List<CustomRule>) -> Unit,
) {
    rules.forEach { rule ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.bodyLarge)
                    Text(rule.describe(shiftNames, extraNames), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = rule.on, onCheckedChange = { on -> onChange(rules.map { if (it.id == rule.id) it.copy(on = on) else it }) })
                IconButton(onClick = { onChange(rules.filterNot { it.id == rule.id }) }) { Icon(Icons.Filled.Delete, "‘${rule.name}’ 지우기") }
            }
        }
    }
}
