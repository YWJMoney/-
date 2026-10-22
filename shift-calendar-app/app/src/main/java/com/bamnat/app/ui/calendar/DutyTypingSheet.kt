package com.bamnat.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.DutyCheck
import java.time.LocalDate
import java.time.YearMonth

/**
 * [NURS-01][NURS-02] A month's duty from the roster sheet, typed like a keypad: a code goes into the
 * marked day and the mark moves to the next, so D D E E N N O O is eight taps. What the month will look
 * like is checked as it is typed (역순, 나이트 뒤 쉬는 날, 연속 근무), then saved in one write with one 되돌리기.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DutyTypingSheet(
    snap: ScheduleSnapshot,
    month: YearMonth,
    onDismiss: () -> Unit,
    onSave: (Map<LocalDate, ShiftOverride>) -> Unit,
    limits: DutyCheck.Limits = DutyCheck.Limits(),
    /** Days already typed, as when the sheet is shown again. */
    initial: Map<LocalDate, ShiftOverride> = emptyMap(),
) {
    val types = snap.shiftTypes
    val offType = types.firstOrNull { it.isOff }
    // What each day holds now; typed days are kept apart so only they are saved.
    val typed = remember(month) { mutableStateMapOf<LocalDate, ShiftOverride>().apply { putAll(initial) } }
    var cursor by remember(month) { mutableIntStateOf((initial.keys.maxOfOrNull { it.dayOfMonth } ?: 0).plus(1).coerceAtMost(month.lengthOfMonth())) }
    val days = month.lengthOfMonth()
    fun shiftOf(date: LocalDate): ShiftType? = when (val o = typed[date]) {
        is ShiftOverride.Shift -> snap.typesById[o.shiftTypeId]
        ShiftOverride.Off -> offType
        ShiftOverride.None -> snap.resolver.patternOn(date)?.shiftTypeIdOn(date)?.let(snap.typesById::get)
        null -> snap.resolver.resolve(date).shiftType
    }
    fun put(o: ShiftOverride) {
        typed[month.atDay(cursor)] = o
        if (cursor < days) cursor++
    }
    // The days around the month count too: a night on the 31st needs days off on the 1st and 2nd.
    val checked = (-2..days + 2).map { i -> month.atDay(1).plusDays(i - 1L) }.map { it to shiftOf(it) }
    val warnings = DutyCheck.check(checked, limits).filter { YearMonth.from(it.date) == month }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${month.monthValue}월 근무 연달아 넣기", style = MaterialTheme.typography.titleLarge)
            Text("근무표를 보며 누르면 ${month.monthValue}월 ${cursor}일에 들어가고 다음 날로 넘어가요. 날짜를 누르면 그 날부터 넣어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MonthCells(month, cursor, typed.keys, ::shiftOf, warnings.map { it.date }.toSet()) { cursor = it }
            // The keypad: every kind of day, then back-to-pattern and moving the mark.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { t ->
                    FilledTonalButton(onClick = { put(ShiftOverride.Shift(t.id)) },
                        modifier = Modifier.heightIn(min = 52.dp).semantics { contentDescription = "${t.name} 넣기" }) {
                        ShiftBadge(t, size = 22.dp, outlined = t.isOff)
                        Text(" ${t.name}", maxLines = 1)
                    }
                }
                if (offType == null) FilledTonalButton(onClick = { put(ShiftOverride.Off) }, modifier = Modifier.heightIn(min = 52.dp)) { Text("쉬는 날") }
                OutlinedButton(onClick = { put(ShiftOverride.None) }, modifier = Modifier.heightIn(min = 52.dp)) {
                    Icon(Icons.Filled.Autorenew, null)
                    Text(" 패턴대로")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (cursor > 1) cursor-- }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null); Text("앞 날")
                }
                TextButton(onClick = { if (cursor < days) cursor++ }) {
                    Text("다음 날"); Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { typed.remove(month.atDay(cursor)) }, enabled = typed.containsKey(month.atDay(cursor))) { Text("이 날 넣은 것 빼기") }
            }
            if (warnings.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("확인해 보세요 ${warnings.size}건", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    warnings.take(8).forEach { w -> Text("· ${w.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                    Text("병원·회사마다 기준이 달라요. 알려만 드리고, 넣은 대로 저장돼요.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("닫기") }
                Button(onClick = { onSave(typed.toMap()) }, enabled = typed.isNotEmpty()) { Text(if (typed.isEmpty()) "저장" else "${typed.size}일 저장") }
            }
        }
    }
}

@Composable
private fun MonthCells(
    month: YearMonth,
    cursor: Int,
    typed: Set<LocalDate>,
    shiftOf: (LocalDate) -> ShiftType?,
    warned: Set<LocalDate>,
    onPick: (Int) -> Unit,
) {
    val lead = month.atDay(1).dayOfWeek.value % 7 // Sunday first
    val cells = lead + month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { listOf("일", "월", "화", "수", "목", "금", "토").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
        for (week in 0 until (cells + 6) / 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 7) {
                    val day = week * 7 + col - lead + 1
                    Box(Modifier.weight(1f).heightIn(min = 52.dp)) {
                        if (day in 1..month.lengthOfMonth()) {
                            val date = month.atDay(day)
                            val t = shiftOf(date)
                            val here = day == cursor
                            val color = t?.let { Color(it.colorArgb.toInt()) }
                            Column(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .background(if (typed.contains(date)) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(8.dp))
                                    .border(if (here) 2.dp else if (date in warned) 1.dp else 0.dp,
                                        if (here) MaterialTheme.colorScheme.primary else if (date in warned) MaterialTheme.colorScheme.error else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { onPick(day) }
                                    .semantics { contentDescription = "${month.monthValue}월 ${day}일 ${t?.name ?: "비어 있음"}" + if (here) ", 지금 넣을 날" else "" },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$day", style = MaterialTheme.typography.labelSmall, fontWeight = if (here) FontWeight.Bold else null)
                                Text(t?.label ?: "·", style = MaterialTheme.typography.titleSmall, color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
