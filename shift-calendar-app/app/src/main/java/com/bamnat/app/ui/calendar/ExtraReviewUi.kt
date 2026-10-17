package com.bamnat.app.ui.calendar

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
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.core.model.DayEntry
import java.time.LocalDate

/**
 * [EXTRA-05] A card above the calendar while something from before 1.9 needs a look: extra work
 * moved into pieces that may hold hours nobody meant, or 대근 entered by changing a day off into a shift.
 */
@Composable
internal fun ExtraReviewCard(title: String, message: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier, onLater: (() -> Unit)? = null) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.TaskAlt, null, Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleSmall)
            }
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, end = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onLater != null) TextButton(onClick = onLater) { Text("그대로 두기") }
                TextButton(onClick = onAction) { Text(action) }
            }
        }
    }
}

/**
 * [EXTRA-05] Every day with moved extra work, with what each piece now says. "맞아요" keeps it; a tap
 * on the day opens it to change or take a piece off.
 */
@Composable
internal fun ExtraReviewDialog(snapshot: ScheduleSnapshot, vm: CalendarViewModel, onOpenDay: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val days = remember(snapshot) { snapshot.entries.filter { e -> e.extras.any { it.needsReview } }.sortedBy { it.date } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("옮겨 온 추가 근무 확인") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "이제 추가 근무마다 시각이 있어요. 예전 기록을 그대로 옮겼으니 날짜를 눌러 종류와 시간이 맞는지 봐 주세요. " +
                        "맞으면 ‘맞아요’를 누르세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (days.isEmpty()) Text("확인할 날이 없어요.", style = MaterialTheme.typography.bodyMedium)
                days.forEachIndexed { i, entry ->
                    if (i > 0) HorizontalDivider()
                    ReviewDayRow(entry, snapshot, onOpen = { onOpenDay(entry.date) }, onOk = { vm.markExtrasReviewed(entry.date) })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.markAllExtrasReviewed(); onDismiss() }, enabled = days.isNotEmpty()) { Text("모두 맞아요") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}

@Composable
private fun ReviewDayRow(entry: DayEntry, snapshot: ScheduleSnapshot, onOpen: () -> Unit, onOk: () -> Unit) {
    val day = remember(snapshot, entry.date) { snapshot.resolver.resolve(entry.date) }
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "이 날 열기", onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(Fmt.dayTitle(entry.date) + " · " + (day.shiftType?.name ?: "쉬는 날"), style = MaterialTheme.typography.titleSmall)
            for (e in day.extras) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    ExtraKindMark(e.kind, size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(extraLine(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        TextButton(onClick = onOk) { Text("맞아요") }
    }
}

/** [EXTRA-05] Why 대근 entered as a changed shift should become a 대근 with times, before anything changes. */
@Composable
internal fun SwapCoverDialog(days: List<DayEntry>, snapshot: ScheduleSnapshot, onConvert: () -> Unit, onKeep: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("쉬는 날 대근을 바르게 고칠까요?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "아래 날은 쉬는 날인데 근무를 바꾸고 대근을 표시해 적었어요. 이렇게 적으면 근무표 근무로 세져서 " +
                        "쉬는 날 근무 급여가 맞지 않아요.\n\n고치면 근무표는 쉬는 날로 두고, 그 근무 시간을 대근으로 넣어요. " +
                        "따로 적었던 대근 시간은 실제 일한 시간이 아니라서 빠져요.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                for (e in days) {
                    val type = (e.override as? com.bamnat.core.model.ShiftOverride.Shift)?.let { snapshot.typesById[it.shiftTypeId] }
                    Text("· ${Fmt.dayTitle(e.date)} ${type?.name ?: "근무"} → 쉬는 날 + ${type?.name ?: ""} 대근",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { Button(onClick = onConvert) { Text("대근으로 고치기") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("그대로 두기") } },
    )
}
