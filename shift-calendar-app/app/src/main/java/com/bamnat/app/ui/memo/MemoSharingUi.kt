package com.bamnat.app.ui.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.core.memo.MemoSharing

/** [MEMO-02] What 설정 › 메모 공유 says it is set to. */
fun memoSharingText(sharing: MemoSharing, calendars: List<PersonalCalendar>): String {
    val clean = sharing.cleaned(calendars.map { it.id })
    if (calendars.size < 2) return "달력을 더 만들면 달력끼리 메모를 함께 볼 수 있어요"
    if (clean.separate) return "달력마다 따로 · 메모는 쓴 달력에만 보여요"
    return clean.groups.joinToString(" / ") { g -> calendars.filter { it.id in g }.joinToString("+") { it.name } } + " 함께 봐요"
}

/**
 * [MEMO-02] 메모 공유: 달력마다 따로 (the default: a memo shows only on the calendar it was written on),
 * or groups of calendars that see each other's memos, like 내 달력 and 알바. A calendar is in one group
 * at most; memos written for 모든 달력 show everywhere either way.
 */
@Composable
fun MemoSharingDialog(
    initial: MemoSharing,
    calendars: List<PersonalCalendar>,
    onDismiss: () -> Unit,
    onSave: (MemoSharing) -> Unit,
) {
    val ids = calendars.map { it.id }
    val start = initial.cleaned(ids)
    var together by remember { mutableStateOf(!start.separate) }
    // Each calendar's group: -1 for its own, else the group's number.
    var picks by remember {
        mutableStateOf(ids.associateWith { id -> start.groups.indexOfFirst { id in it } })
    }
    val groupCount = if (calendars.size >= 4) 2 else 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("메모 공유") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Choice("달력마다 따로 (기본)", "메모는 쓴 달력에만 보여요. 다른 달력에서는 보이지 않아요.", !together) { together = false }
                Choice("몇 달력끼리 함께 보기", "같이 묶은 달력끼리는 서로의 메모가 보여요. 예: 내 달력 + 알바", together) {
                    together = true
                    // Starting out, 내 달력 and the next calendar make the first group, as the example says.
                    if (picks.values.none { it >= 0 } && ids.size >= 2) picks = picks + (ids[0] to 0) + (ids[1] to 0)
                }
                if (together) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    calendars.forEach { cal ->
                        val pick = picks[cal.id] ?: -1
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(cal.color)))
                            Spacer(Modifier.width(8.dp))
                            Text(cal.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                        val options = listOf(-1 to "따로") + (0 until groupCount).map { it to if (groupCount == 1) "함께" else "묶음 ${it + 1}" }
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            options.forEachIndexed { i, (value, label) ->
                                SegmentedButton(
                                    selected = pick == value,
                                    onClick = { picks = picks + (cal.id to value) },
                                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                                    label = { Text(label, maxLines = 1) },
                                )
                            }
                        }
                    }
                }
                Text(
                    "‘모든 달력’으로 쓴 공용 메모는 어느 쪽이든 모든 달력에 보여요. 메모 하나만 다른 달력에도 보이게 하려면 메모 쓰는 화면의 달력 칩을 눌러요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val groups = (0 until groupCount).map { g -> picks.filterValues { it == g }.keys }.filter { it.size > 1 }
                onSave(if (together) MemoSharing(groups) else MemoSharing.SEPARATE)
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
