package com.bamnat.app.ui.memo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import com.bamnat.core.memo.MemoCalendars
import com.bamnat.core.memo.MemoNotebook

/**
 * [NOTE-02] The memo tab's notebooks, once there is more than one calendar: the calendar on screen's
 * memos (its own and the shared ones), the shared notebook, and every memo.
 */
@Composable
internal fun NotebookBar(state: MemoListState, onPick: (MemoNotebook) -> Unit) {
    if (!state.manyCalendars) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            MemoNotebook.entries.forEachIndexed { i, n ->
                SegmentedButton(
                    selected = state.notebook == n,
                    onClick = { onPick(n) },
                    shape = SegmentedButtonDefaults.itemShape(i, MemoNotebook.entries.size),
                    label = {
                        Text(if (n == MemoNotebook.CALENDAR) state.activeName else n.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
        Text(
            when (state.notebook) {
                // [MEMO-01] Each calendar keeps its own memos unless 설정 › 메모 공유 puts calendars together.
                MemoNotebook.CALENDAR -> "‘${state.activeName}’에 보이는 메모예요. 다른 달력의 메모는 그 달력에서 보여요. 달력끼리 함께 보려면 설정 › 메모 공유에서 묶어요."
                MemoNotebook.SHARED -> "여러 달력에 함께 보이는 메모예요. 여기서 쓴 메모는 모든 달력에 보여요."
                MemoNotebook.ALL -> "모든 달력의 메모예요."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** [NOTE-02] What a memo's calendars read as: "모든 달력", one name, or "내 달력 외 1"; [MEMO-01] none is 내 달력. */
internal fun calendarsText(ids: Set<String>, calendars: List<PersonalCalendar>): String {
    if (MemoCalendars.EVERY in ids) return "모든 달력"
    val names = calendars.filter { it.id in ids }.map { it.name }
    return when {
        names.isEmpty() -> PersonalCalendar.MAIN_NAME
        names.size == 1 -> names.first()
        else -> "${names.first()} 외 ${names.size - 1}"
    }
}

/** [NOTE-02] The chip in the memo editor: which calendars this memo shows on. Nothing with one calendar. */
@Composable
internal fun MemoCalendarsChip(ids: Set<String>, calendars: List<PersonalCalendar>, onChange: (Set<String>) -> Unit) {
    if (calendars.size < 2) return
    var open by remember { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(calendarsText(ids, calendars), maxLines = 1) },
        leadingIcon = { Icon(Icons.Outlined.Groups, null, Modifier.size(18.dp)) },
    )
    if (open) {
        MemoCalendarsDialog(ids, calendars, onDismiss = { open = false }, onSave = { onChange(it); open = false })
    }
}

/**
 * [NOTE-02] Choosing where a memo shows: every calendar (공용), or the calendars ticked. At least one
 * stays ticked, so a memo never ends up nowhere.
 */
@Composable
internal fun MemoCalendarsDialog(
    initial: Set<String>,
    calendars: List<PersonalCalendar>,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit,
    title: String = "어느 달력에 보일까요?",
) {
    // [MEMO-01] An old memo with no calendar is 내 달력's; "모든 달력" is the switch alone.
    var all by remember { mutableStateOf(MemoCalendars.EVERY in initial) }
    var picked by remember {
        mutableStateOf((initial - MemoCalendars.EVERY).ifEmpty { if (initial.isEmpty()) setOf(PersonalCalendar.MAIN) else calendars.map { it.id }.toSet() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Switch) { all = !all },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("모든 달력에 보이기", style = MaterialTheme.typography.titleSmall)
                        Text("나중에 만드는 달력에도 보이는 공용 메모예요", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = all, onCheckedChange = { all = it })
                }
                HorizontalDivider()
                calendars.forEach { cal ->
                    val on = all || cal.id in picked
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .clickable(enabled = !all, role = Role.Checkbox) {
                                picked = if (on) (picked - cal.id).ifEmpty { picked } else picked + cal.id
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = on, enabled = !all, onCheckedChange = null)
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.size(12.dp).clip(CircleShape).background(Color(cal.color)))
                        Spacer(Modifier.width(8.dp))
                        Text(cal.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text(
                    "고른 달력에서만 이 메모가 보이고, 달력 칸의 메모 표시도 그 달력에만 생겨요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ids = calendars.map { it.id }.toSet()
                // Every calendar ticked one by one is still a choice of those calendars; only the switch means "all, later ones too".
                onSave(if (all) setOf(MemoCalendars.EVERY) else picked.filter { it in ids }.toSet().ifEmpty { setOf(PersonalCalendar.MAIN) })
            }) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
