package com.bamnat.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.contentColorOn
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.DutyCheck
import com.bamnat.core.schedule.RestRules
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * [NURS-01][NURS-02] A month's duty from the roster sheet, typed like a keypad: a code goes into the
 * marked day and the mark moves to the next, so D D E E N N O O is eight taps. What the month will look
 * like is checked as it is typed (역순, 나이트 뒤 쉬는 날, 연속 근무), then saved in one write with one 되돌리기.
 * [NURS-04] A roster photo opens here too, with the cells read with doubt marked to fix one by one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DutyTypingSheet(
    snap: ScheduleSnapshot,
    month: YearMonth,
    onDismiss: () -> Unit,
    onSave: (Map<LocalDate, ShiftOverride>) -> Unit,
    /** [REST-01] The same 근무표 점검 기준 as the calendar's 휴식 점검. */
    rules: RestRules = RestRules(),
    /** Days already typed, as when the sheet is shown again. */
    initial: Map<LocalDate, ShiftOverride> = emptyMap(),
    /**
     * [NURS-04] Days a roster photo was read with doubt (순번 275): marked, the mark starts on the first and each
     * key moves it to the next one still to look at, so only those cells need a tap.
     */
    doubtful: Set<LocalDate> = emptySet(),
    /** [REST-01] Opens the rules sheet from the warnings; null hides the button. */
    onRules: (() -> Unit)? = null,
    /** [NURS-04] Told on save which of [doubtful] were never typed over. */
    onDoubtLeft: (Set<LocalDate>) -> Unit = {},
    /** [CAL-27] The calendar's first day of the week, so a cell sits where it sits on the calendar (I 리뷰 P6). */
    weekStart: DayOfWeek = DayOfWeek.SUNDAY,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        DutyTypingPanel(snap, month, onDismiss, onSave, rules, initial, doubtful, onRules, onDoubtLeft, weekStart,
            Modifier.navigationBarsPadding())
    }
}

/**
 * [CAL-45] The keypad itself, in the sheet on a phone and as the right-hand panel beside the calendar on an
 * unfolded screen (I 리뷰 N5), where the month being typed is in view while the roster is read.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DutyTypingPanel(
    snap: ScheduleSnapshot,
    month: YearMonth,
    onDismiss: () -> Unit,
    onSave: (Map<LocalDate, ShiftOverride>) -> Unit,
    rules: RestRules = RestRules(),
    initial: Map<LocalDate, ShiftOverride> = emptyMap(),
    doubtful: Set<LocalDate> = emptySet(),
    onRules: (() -> Unit)? = null,
    onDoubtLeft: (Set<LocalDate>) -> Unit = {},
    weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    modifier: Modifier = Modifier,
) {
    val types = snap.shiftTypes
    val offType = types.firstOrNull { it.isOff }
    // What each day holds now; typed days are kept apart so only they are saved. [CAL-30] Kept through a
    // fold or a turn of the phone (I 리뷰 P12): a whole month typed must never vanish with the configuration.
    val typed = rememberSaveable(month, saver = TypedSaver) { mutableStateMapOf<LocalDate, ShiftOverride>().apply { putAll(initial) } }
    val toCheck = rememberSaveable(month, saver = DatesSaver) { mutableStateListOf<LocalDate>().apply { addAll(doubtful.filter { YearMonth.from(it) == month }.sorted()) } }
    var cursor by rememberSaveable(month) {
        mutableIntStateOf(toCheck.firstOrNull()?.dayOfMonth ?: (initial.keys.maxOfOrNull { it.dayOfMonth } ?: 0).plus(1).coerceAtMost(month.lengthOfMonth()))
    }
    // [CAL-36] Each key and each copy can be taken back, a step at a time (I 리뷰 I8).
    val history = remember(month) { mutableStateListOf<Map<LocalDate, ShiftOverride>>() }
    fun checkpoint() {
        history.add(typed.toMap())
        if (history.size > MAX_UNDO) history.removeAt(0)
    }
    val days = month.lengthOfMonth()
    fun shiftOf(date: LocalDate): ShiftType? = when (val o = typed[date]) {
        is ShiftOverride.Shift -> snap.typesById[o.shiftTypeId]
        ShiftOverride.Off -> offType
        ShiftOverride.None -> snap.resolver.patternOn(date)?.shiftTypeIdOn(date)?.let(snap.typesById::get)
        null -> snap.resolver.resolve(date).shiftType
    }
    fun put(o: ShiftOverride) {
        checkpoint()
        val date = month.atDay(cursor)
        typed[date] = o
        val wasDoubt = toCheck.remove(date)
        // [NURS-04] Fixing the photo's doubtful days: on to the next of them, else the next day.
        val next = if (wasDoubt) toCheck.firstOrNull { it.isAfter(date) } ?: toCheck.firstOrNull() else null
        if (next != null) cursor = next.dayOfMonth else if (cursor < days) cursor++
    }
    // The days around the month count too: a night on the 31st needs days off on the 1st and 2nd.
    val checked = (-2..days + 2).map { i -> month.atDay(1).plusDays(i - 1L) }.map { it to shiftOf(it) }
    val warnings = DutyCheck.check(checked, rules).filter { YearMonth.from(it.date) == month }

    // [CAL-36] Last month as it ended up on the calendar, day for day (1일 to 1일), for rosters that repeat by date.
    val previous = month.minusMonths(1)
    val lastMonth = remember(snap, month) {
        (1..minOf(days, previous.lengthOfMonth())).mapNotNull { d ->
            val t = snap.resolver.resolve(previous.atDay(d)).shiftType ?: return@mapNotNull null
            month.atDay(d) to (if (t.isOff && offType == null) ShiftOverride.Off else ShiftOverride.Shift(t.id))
        }.toMap()
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${month.monthValue}월 근무 연달아 넣기", style = MaterialTheme.typography.titleLarge)
            Text("근무표를 보며 누르면 ${month.monthValue}월 ${cursor}일에 들어가고 다음 날로 넘어가요. 날짜를 누르면 그 날부터 넣어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (toCheck.isNotEmpty()) {
                Text("사진에서 확실하지 않은 칸 ${toCheck.size}개가 남았어요. 노란 칸을 근무표와 맞춰 눌러 주세요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            MonthCells(month, cursor, typed.keys, ::shiftOf, warnings.map { it.date }.toSet(), toCheck.toSet(), weekStart) { cursor = it }
            // [ROST-08] Sideways, so three buttons never squeeze under a big font.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (lastMonth.isNotEmpty()) OutlinedButton(onClick = {
                    checkpoint()
                    typed.putAll(lastMonth)
                    cursor = days
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.ContentCopy, null)
                    Text(" ${previous.monthValue}월 그대로", maxLines = 1)
                }
                // [ROST-08] 이 순서 반복: D D E E N N O O typed, the same order to the month's end (리뷰 개선 다).
                val rest = com.bamnat.core.imports.MonthFill.repeat(typed.filterKeys { YearMonth.from(it) == month }.mapKeys { it.key.dayOfMonth }, cursor, days)
                if (rest != null) OutlinedButton(onClick = {
                    checkpoint()
                    rest.forEach { (d, o) -> typed[month.atDay(d)] = o }
                    cursor = days
                }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Repeat, null)
                    Text(" 이 순서 반복", maxLines = 1)
                }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = {
                    val back = history.removeAt(history.lastIndex)
                    typed.clear()
                    typed.putAll(back)
                }, enabled = history.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null)
                    Text(" 되돌리기")
                }
            }
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
                TextButton(onClick = { checkpoint(); typed.remove(month.atDay(cursor)) }, enabled = typed.containsKey(month.atDay(cursor))) { Text("이 날 넣은 것 빼기") }
            }
            if (warnings.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("확인해 보세요 ${warnings.size}건", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    warnings.take(8).forEach { w -> Text("· ${w.text}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer) }
                    Text("병원·회사마다 기준이 달라요. 알려만 드리고, 넣은 대로 저장돼요.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    if (onRules != null) TextButton(onClick = onRules) { Text("기준 바꾸기") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("닫기") }
                Button(onClick = { onDoubtLeft(toCheck.toSet()); onSave(typed.toMap()) }, enabled = typed.isNotEmpty()) { Text(if (typed.isEmpty()) "저장" else "${typed.size}일 저장") }
            }
    }
}

private const val MAX_UNDO = 60

/** [CAL-30] A typed day as one number: a shift's id, −1 for a day off, −2 for back to the pattern. */
internal fun overrideCode(o: ShiftOverride): Long = when (o) {
    is ShiftOverride.Shift -> o.shiftTypeId
    ShiftOverride.Off -> -1L
    ShiftOverride.None -> -2L
}

internal fun overrideOf(code: Long): ShiftOverride = when (code) {
    -1L -> ShiftOverride.Off
    -2L -> ShiftOverride.None
    else -> ShiftOverride.Shift(code)
}

internal val TypedSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<LocalDate, ShiftOverride>, Long>(
    save = { m -> m.flatMap { (d, o) -> listOf(d.toEpochDay(), overrideCode(o)) } },
    restore = { l -> mutableStateMapOf<LocalDate, ShiftOverride>().apply { l.chunked(2).forEach { (d, c) -> put(LocalDate.ofEpochDay(d), overrideOf(c)) } } },
)

internal val DatesSaver = listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<LocalDate>, Long>(
    save = { l -> l.map { it.toEpochDay() } },
    restore = { l -> mutableStateListOf<LocalDate>().apply { addAll(l.map(LocalDate::ofEpochDay)) } },
)

@Composable
private fun MonthCells(
    month: YearMonth,
    cursor: Int,
    typed: Set<LocalDate>,
    shiftOf: (LocalDate) -> ShiftType?,
    warned: Set<LocalDate>,
    doubtful: Set<LocalDate>,
    weekStart: DayOfWeek,
    onPick: (Int) -> Unit,
) {
    // [CAL-27] Counted from the calendar's own first day of the week.
    val lead = Math.floorMod(month.atDay(1).dayOfWeek.value - weekStart.value, 7)
    val cells = lead + month.lengthOfMonth()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row { (0 until 7).map { weekStart.plus(it.toLong()) }.forEach { Text(Fmt.dayOfWeekShort(it), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
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
                                    .background(when {
                                        date in doubtful -> MaterialTheme.colorScheme.tertiaryContainer
                                        typed.contains(date) -> MaterialTheme.colorScheme.secondaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainer
                                    }, RoundedCornerShape(8.dp))
                                    .border(if (here) 2.dp else if (date in warned) 1.dp else 0.dp,
                                        if (here) MaterialTheme.colorScheme.primary else if (date in warned) MaterialTheme.colorScheme.error else Color.Transparent, RoundedCornerShape(8.dp))
                                    .clickable { onPick(day) }
                                    .semantics {
                                        contentDescription = "${month.monthValue}월 ${day}일 ${t?.name ?: "비어 있음"}" +
                                            (if (date in doubtful) ", 확인할 칸" else "") + if (here) ", 지금 넣을 날" else ""
                                    },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("$day", style = MaterialTheme.typography.labelSmall, fontWeight = if (here) FontWeight.Bold else null)
                                // [CAL-42] The code on a chip of its own colour with readable ink, not a light yellow
                                // letter on a light cell (I 리뷰 P22).
                                if (t != null && color != null) Text(
                                    t.label, style = MaterialTheme.typography.titleSmall, color = contentColorOn(color),
                                    fontWeight = FontWeight.Bold, maxLines = 1,
                                    modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp),
                                ) else Text("·", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
