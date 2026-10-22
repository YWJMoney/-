package com.bamnat.app.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.core.schedule.RestRules
import com.bamnat.core.schedule.RotationSpeed

/**
 * [REST-01][REST-02][REST-03] 근무표 점검 기준: one sheet for the calendar's 휴식 점검, the month typed at once and
 * received rosters. Opened from the check's own card (기준 바꾸기) and from 설정 › 알림과 잠, never a menu of its own.
 * A hospital's rules are a preset away, each limit can be moved one by one (0 is not checked), and the whole check
 * turns off here.
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RestRulesSheet(rules: RestRules, speed: RotationSpeed, onDismiss: () -> Unit, onSave: (RestRules) -> Unit) {
    var draft by remember(rules) { mutableStateOf(rules) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("근무표 점검 기준", style = MaterialTheme.typography.titleLarge)
            Text("병원·회사마다 기준이 달라요. 우리 기준으로 맞추면 달력, 한 달 연달아 넣기, 받은 근무표가 모두 이 기준으로 알려 줘요. " +
                "알려만 주고 저장은 막지 않아요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { draft = draft.copy(on = !draft.on) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("근무표 점검", style = MaterialTheme.typography.bodyLarge)
                    Text(if (draft.on) "켜 두면 휴식이 짧은 날을 미리 알 수 있어요" else "꺼져 있어요. 아무것도 알려 주지 않아요",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = draft.on, onCheckedChange = { draft = draft.copy(on = it) })
            }
            if (draft.on) {
                if (speed != RotationSpeed.NONE && speed.note.isNotEmpty()) {
                    Text("내 근무: ${speed.label} · ${speed.note}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RestRules.Preset.entries.forEach { p ->
                        FilterChip(selected = draft.preset == p, onClick = { draft = p.rules }, label = { Text(p.label) })
                    }
                }
                draft.preset?.let { Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                HorizontalDivider()
                Limit("근무 사이 휴식", "${draft.minRestHours}시간", draft.minRestHours, 0..16) { draft = draft.copy(minRestHours = it) }
                Limit("나이트 뒤 쉬는 날", days(draft.offsAfterNights), draft.offsAfterNights, 0..4) { draft = draft.copy(offsAfterNights = it) }
                Limit("연속 나이트", days(draft.maxNightsInRow, "까지"), draft.maxNightsInRow, 0..7) { draft = draft.copy(maxNightsInRow = it) }
                Limit("연속 근무", days(draft.maxWorkDaysInRow, "까지"), draft.maxWorkDaysInRow, 0..13) { draft = draft.copy(maxWorkDaysInRow = it) }
                Limit("한 달 나이트", if (draft.maxNightsPerMonth == 0) "안 봐요" else "${draft.maxNightsPerMonth}개까지", draft.maxNightsPerMonth, 0..22) {
                    draft = draft.copy(maxNightsPerMonth = it)
                }
                Text("0으로 두면 그 항목은 보지 않아요. 기본값의 근거: ${RestRules.Sources.REST}, ${RestRules.Sources.NIGHTS}, ${RestRules.Sources.WEEK}.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("끄는 법: 이 화면 맨 위 '근무표 점검'을 끄거나 설정 › 알림과 잠 › 휴식 점검을 꺼요.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = { draft = RestRules() }) { Text("기본값으로") }
                TextButton(onClick = onDismiss) { Text("닫기") }
                Button(onClick = { onSave(draft) }) { Text("저장") }
            }
        }
    }
}

private fun days(n: Int, suffix: String = "") = if (n == 0) "안 봐요" else "${n}일$suffix"

@Composable
private fun Limit(label: String, value: String, current: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onChange((current - 1).coerceIn(range)) }, enabled = current > range.first,
            modifier = Modifier.semantics { contentDescription = "$label 줄이기" }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text(value, style = MaterialTheme.typography.titleSmall, modifier = Modifier.widthIn(min = 72.dp), textAlign = TextAlign.Center)
        IconButton(onClick = { onChange((current + 1).coerceIn(range)) }, enabled = current < range.last,
            modifier = Modifier.semantics { contentDescription = "$label 늘리기" }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}
