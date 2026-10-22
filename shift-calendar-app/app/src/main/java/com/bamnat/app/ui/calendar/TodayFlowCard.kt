package com.bamnat.app.ui.calendar

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.AlarmOff
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.components.contentColorOn
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.sleep.TodayFlow
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/** The clock to the minute, moving on at each minute's turn, for what changes with the time of day. */
@Composable
internal fun rememberMinute(): LocalDateTime {
    val now by produceState(LocalDateTime.now().withSecond(0).withNano(0)) {
        while (true) {
            val t = LocalDateTime.now()
            delay(60_000L - (t.second * 1000L + t.nano / 1_000_000))
            value = LocalDateTime.now().withSecond(0).withNano(0)
        }
    }
    return now
}

/**
 * [SLP-14] 오늘의 흐름 at the top of the calendar, worked out from the schedule, the alarms that will
 * ring and the sleep log, again each minute.
 */
@Composable
internal fun TodayFlowSection(vm: CalendarViewModel, snapshot: ScheduleSnapshot, settings: AppSettings, modifier: Modifier = Modifier) {
    val log by vm.sleepLog.collectAsStateWithLifecycle()
    val chosen by vm.flowFolded.collectAsStateWithLifecycle()
    // [CAL-21] On a common phone (under about 860dp tall) the open card pushes the month below the screen.
    val short = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenHeightDp < SHORT_SCREEN_DP && it.screenWidthDp < 600 }
    val folded = chosen ?: short
    val now = rememberMinute()
    val card = remember(snapshot, settings, log, now) { vm.todayFlow(snapshot, settings, log, now) }
    TodayFlowCard(
        card = card,
        targetMinutes = settings.sleepTargetMinutes,
        commuteMinutes = settings.commuteMinutes,
        asleepSince = log.firstOrNull { it.end == null && !it.imported }?.start,
        onAction = { action, date ->
            when (action) {
                TodayFlow.Action.ASLEEP -> vm.fellAsleep()
                TodayFlow.Action.AWAKE -> vm.wokeUp()
                TodayFlow.Action.ALARM_ON -> date?.let { vm.setAlarmSkipped(it, false, announce = true) }
                TodayFlow.Action.AWAKE_AT -> Unit // asks for the time first, below
            }
        },
        onWokeAt = { vm.wokeUp(it) },
        onNumbers = vm::setSleepNumbers,
        onHide = vm::hideTodayFlow,
        modifier = modifier,
        folded = folded,
        onFold = vm::setFlowFolded,
    )
}

/** [CAL-21] Shorter than this, 오늘의 흐름 starts as one line; a fold's cover screen (905dp) is not. */
private const val SHORT_SCREEN_DP = 860

/**
 * [SLP-14] The card: one thing to do now in a big line and why in a small one (ⓘ beside them), a rest
 * sign when there is one, the next 24 hours as a strip, and the one button that fits. The day's work
 * is on the strip, so it has no title line of its own; TalkBack reads it with the strip. A day off
 * folds to one line that opens on a tap. [CAL-16] Any day folds to that line with ⌃, and stays folded
 * until opened again, so a small screen keeps room for the month.
 */
@Composable
internal fun TodayFlowCard(
    card: TodayFlow.Card,
    targetMinutes: Int,
    commuteMinutes: Int,
    asleepSince: LocalDateTime?,
    onAction: (TodayFlow.Action, LocalDate?) -> Unit,
    onWokeAt: (LocalDateTime) -> Unit,
    onNumbers: (target: Int, commute: Int) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
    folded: Boolean = false,
    onFold: (Boolean) -> Unit = {},
) {
    var info by remember { mutableStateOf(false) }
    var askWake by remember { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf(false) }
    val cal = LocalCalendarColors.current
    val warning = card.state == TodayFlow.State.ALARM_OFF || card.state == TodayFlow.State.FORGOT_WAKE
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (warning) cal.warningContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().animateContentSize(),
    ) {
        if (folded || card.compact && !opened) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clickable(onClickLabel = "오늘의 흐름 펼치기") { if (folded) onFold(false); opened = true }
                    .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(stateIcon(card), null, tint = if (warning) cal.warning else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(card.headline, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // [CAL-16] A warning keeps its one button on the folded line: an alarm is turned back on without opening.
                if (warning) card.action?.let { a ->
                    ActionButton(a, Modifier.padding(start = 8.dp)) { onPick(a, card, onAction) { askWake = true } }
                }
                Icon(Icons.Filled.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
            return@Surface
        }
        // What to do now leads; the strip under it shows the day around it, and the one button comes last.
        Column(Modifier.padding(start = 16.dp, top = 4.dp, end = 4.dp, bottom = 12.dp)) {
            // The buttons sit beside the headline only; the line under it runs the full width.
            Box {
                Column(Modifier.padding(top = 12.dp, end = 12.dp)) {
                    Row {
                        Icon(stateIcon(card), null, tint = if (warning) cal.warning else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 1.dp, end = 10.dp).size(22.dp))
                        Text(card.headline, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f).padding(end = 84.dp))
                    }
                    card.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 32.dp))
                    }
                }
                Row(Modifier.align(Alignment.TopEnd)) {
                    // [CAL-16] Folds to one line on every day, and stays so.
                    IconButton(onClick = { opened = false; onFold(true) }) { Icon(Icons.Filled.ExpandLess, contentDescription = "오늘의 흐름 접기") }
                    IconButton(onClick = { info = true }) { Icon(Icons.Outlined.Info, contentDescription = "오늘의 흐름 안내와 목표 수면") }
                }
            }
            card.rest?.let { rest ->
                Row(Modifier.padding(start = 32.dp, top = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, null, tint = cal.warning, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(rest, style = MaterialTheme.typography.bodySmall, color = cal.warning)
                }
            }
            FlowStrip(card, Modifier.padding(top = 12.dp, end = 12.dp))
            card.action?.let { a ->
                Row(Modifier.fillMaxWidth().padding(top = 2.dp, end = 12.dp), horizontalArrangement = Arrangement.End) {
                    ActionButton(a) { onPick(a, card, onAction) { askWake = true } }
                }
            }
        }
    }

    if (info) FlowInfoSheet(targetMinutes, commuteMinutes, onNumbers, onHide = { info = false; onHide() }, onDismiss = { info = false })
    if (askWake && asleepSince != null) {
        WakeTimeDialog(asleepSince, card.from, targetMinutes, onDismiss = { askWake = false }) {
            askWake = false
            onWokeAt(it)
        }
    }
}

private fun onPick(action: TodayFlow.Action, card: TodayFlow.Card, onAction: (TodayFlow.Action, LocalDate?) -> Unit, askWake: () -> Unit) {
    if (action == TodayFlow.Action.AWAKE_AT) askWake() else onAction(action, card.actionDate)
}

@Composable
private fun ActionButton(action: TodayFlow.Action, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val (label, icon) = when (action) {
        TodayFlow.Action.ASLEEP -> "잠들었어요" to Icons.Outlined.Bedtime
        TodayFlow.Action.AWAKE -> "깼어요" to Icons.Outlined.WbSunny
        TodayFlow.Action.AWAKE_AT -> "깬 시각 적기" to Icons.Outlined.Schedule
        TodayFlow.Action.ALARM_ON -> "알람 켜기" to Icons.Outlined.Alarm
    }
    val content: @Composable () -> Unit = {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
    if (action == TodayFlow.Action.ASLEEP) {
        val cal = LocalCalendarColors.current
        FilledTonalButton(onClick = onClick, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = cal.sleepContainer, contentColor = cal.onSleepContainer)) { content() }
    } else {
        Button(onClick = onClick, modifier = modifier, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) { content() }
    }
}

private fun stateIcon(card: TodayFlow.Card): ImageVector = when (card.state) {
    TodayFlow.State.ALARM_OFF -> Icons.Outlined.AlarmOff
    TodayFlow.State.SLEEPING -> Icons.Outlined.NightsStay
    TodayFlow.State.FORGOT_WAKE -> Icons.Outlined.Schedule
    TodayFlow.State.ON_SHIFT -> if (card.headline.endsWith("커피")) Icons.Outlined.LocalCafe else Icons.Outlined.WorkOutline
    TodayFlow.State.AFTER_SHIFT -> Icons.Outlined.DirectionsCar
    TodayFlow.State.SLEEP_TIME -> Icons.Outlined.Bedtime
    TodayFlow.State.NAP -> Icons.Outlined.Snooze
    TodayFlow.State.AWAKE -> Icons.Outlined.WbSunny
    TodayFlow.State.REST_DAY -> Icons.Outlined.Weekend
    TodayFlow.State.NEXT -> Icons.Outlined.Event
}

/**
 * [SLP-14] The next 24 hours from now: work in its shift's colour, sleep and naps in night blues,
 * alarms as ticks, with the times under it where they fit. TalkBack reads it as one sentence.
 */
@Composable
private fun FlowStrip(card: TodayFlow.Card, modifier: Modifier = Modifier) {
    val cal = LocalCalendarColors.current
    val measurer = rememberTextMeasurer()
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val inside = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    val under = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp)
    val total = Duration.between(card.from, card.to).toMinutes().toFloat().coerceAtLeast(1f)
    val said = remember(card) { stripText(card) }
    Canvas(modifier.fillMaxWidth().height(42.dp).semantics { contentDescription = said }) {
        val bar = 22.dp.toPx()
        val radius = CornerRadius(6.dp.toPx())
        fun x(t: LocalDateTime) = (Duration.between(card.from, t).toMinutes() / total * size.width).coerceIn(0f, size.width)
        drawRoundRect(track, Offset.Zero, Size(size.width, bar), radius)
        for (s in card.segments) {
            val color = when (s.part) {
                TodayFlow.Part.WORK -> s.colorArgb?.asColor() ?: tick
                TodayFlow.Part.SLEEP -> cal.sleep
                TodayFlow.Part.NAP -> cal.nap
            }
            val left = x(s.from)
            val width = (x(s.to) - left).coerceAtLeast(2.dp.toPx())
            drawRoundRect(color, Offset(left, 0f), Size(width, bar), radius)
            val text = measurer.measure(s.name, inside.copy(color = contentColorOn(color)), maxLines = 1)
            if (text.size.width + 8.dp.toPx() <= width) {
                drawText(text, topLeft = Offset(left + (width - text.size.width) / 2, (bar - text.size.height) / 2))
            }
        }
        for (a in card.alarms) {
            val ax = x(a)
            drawRect(tick, Offset(ax - 1.dp.toPx(), -2.dp.toPx()), Size(2.dp.toPx(), bar + 4.dp.toPx()))
        }
        // Times under the strip, most useful first. A time may slide sideways while it still sits under
        // its own point, so one close to "지금" or to an alarm moves over rather than dropping out.
        val marks = buildList {
            add(Mark(0f, "지금", label, NOW))
            card.alarms.forEach { add(Mark(x(it), hm(it), tick, 3)) }
            card.segments.forEach { s ->
                if (s.from.isAfter(card.from)) add(Mark(x(s.from), hm(s.from), label, 2))
                if (s.to.isBefore(card.to)) add(Mark(x(s.to), hm(s.to), label, 1))
            }
        }
        val gap = 6.dp.toPx()
        val placed = mutableListOf<ClosedFloatingPointRange<Float>>()
        for (m in marks.sortedByDescending { it.rank }) {
            val text = measurer.measure(m.text, under.copy(color = m.color), maxLines = 1)
            val w = text.size.width.toFloat()
            val lo = if (m.rank == NOW) 0f else (m.x - w).coerceIn(0f, size.width - w)
            val hi = if (m.rank == NOW) 0f else m.x.coerceIn(0f, size.width - w)
            val want = (m.x - w / 2).coerceIn(lo, hi)
            fun free(left: Float) = placed.none { left < it.endInclusive + gap - 0.5f && it.start < left + w + gap - 0.5f }
            val left = (listOf(want) + placed.flatMap { listOf(it.endInclusive + gap, it.start - gap - w) })
                .filter { it in lo..hi && free(it) }
                .minByOrNull { abs(it - want) } ?: continue
            // Twice the room after "지금", so a time beside it does not read as the time now.
            placed += left..(left + w + if (m.rank == NOW) gap else 0f)
            drawText(text, topLeft = Offset(left, bar + 4.dp.toPx()))
        }
    }
}

private fun hm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

/** A time under the strip; higher [rank] is placed first. */
private data class Mark(val x: Float, val text: String, val color: Color, val rank: Int)

/** The rank of "지금", which always sits at the left end. */
private const val NOW = 4

/** What the strip shows, read out: "오늘 · 야간 19:00–07:00. 앞으로 24시간: 15:00부터 16:30까지 낮잠, 17:30 알람, …". */
private fun stripText(card: TodayFlow.Card): String {
    val parts = (card.segments.map { it.from to "${hm(it.from)}부터 ${hm(it.to)}까지 ${it.name}" } + card.alarms.map { it to "${hm(it)} 알람" })
        .sortedBy { it.first }.map { it.second }
    return "${card.caption}. " + if (parts.isEmpty()) "앞으로 24시간 동안 정해진 일정이 없어요" else "앞으로 24시간: " + parts.joinToString(", ")
}

/**
 * [SLP-14] ⓘ: how the times are worked out and how sure each rule is, with the two numbers they rest on
 * (목표 수면, 퇴근 후 잠들기까지), and a way to take the card off the calendar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlowInfoSheet(target: Int, commute: Int, onNumbers: (Int, Int) -> Unit, onHide: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text("오늘의 흐름은 이렇게 정해요", style = MaterialTheme.typography.titleLarge)
            Text("근무표와 실제로 울릴 알람으로 앞으로 24시간을 계산한 참고 정보예요. 진단이나 치료가 아니니 몸 상태와 회사 사정에 맞게 조절하세요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(16.dp))
            NumberRow("목표 수면", HoursFormat.korean(target), onMinus = { onNumbers((target - 30).coerceAtLeast(MIN_TARGET), commute) },
                onPlus = { onNumbers((target + 30).coerceAtMost(MAX_TARGET), commute) }, minusOk = target > MIN_TARGET, plusOk = target < MAX_TARGET)
            NumberRow("퇴근 후 잠들기까지", if (commute == 0) "바로" else HoursFormat.korean(commute),
                hint = "집에 가고 씻는 시간",
                onMinus = { onNumbers(target, (commute - 10).coerceAtLeast(0)) },
                onPlus = { onNumbers(target, (commute + 10).coerceAtMost(MAX_COMMUTE)) }, minusOk = commute > 0, plusOk = commute < MAX_COMMUTE)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Rule("밤새 일한 뒤", "집에 오자마자 목표만큼, 적어도 6시간 이어서 자요. 다음 근무 알람이 먼저 울리면 거기까지예요.", "안전보건 지침")
            Rule("이른 근무 전날", "알람에서 목표 수면만큼 거꾸로 잠자리 시각을 정해요. 알람이 없으면 근무 1시간 30분 전에 일어나는 걸로 봐요.", "일반 권고")
            Rule("첫 야간 전", "근무 2시간 30분 전에 끝나는 90분 안쪽 낮잠이에요.", "일반 권고")
            Rule("커피", "잠들기 6시간 전까지만 마셔요.", "근거 수준 낮음")
            Rule("휴식", "휴식 점검을 켜 두면 근무 사이가 11시간보다 짧을 때 작은 줄로 알려 줘요.", null)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("설정 > 알림과 잠에서 다시 켤 수 있어요", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onHide) { Text("달력에서 숨기기") }
            }
        }
    }
}

@Composable
private fun NumberRow(title: String, value: String, hint: String? = null, onMinus: () -> Unit, onPlus: () -> Unit, minusOk: Boolean, plusOk: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        OutlinedIconButton(onClick = onMinus, enabled = minusOk) { Text("−", style = MaterialTheme.typography.titleMedium) }
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 84.dp))
        OutlinedIconButton(onClick = onPlus, enabled = plusOk) { Text("+", style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun Rule(title: String, text: String, basis: String?) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(text + (basis?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** [SLP-14] When the user woke, for a sleep left open: a time after it began, by now at the latest. */
@Composable
internal fun WakeTimeDialog(since: LocalDateTime, now: LocalDateTime, targetMinutes: Int, onDismiss: () -> Unit, onConfirm: (LocalDateTime) -> Unit) {
    val guess = minOf(since.plusMinutes(targetMinutes.toLong()), now)
    TimePickerDialog(
        title = "${since.monthValue}/${since.dayOfMonth} ${hm(since)}에 잠들고 언제 깼나요?",
        initial = guess.toLocalTime(),
        presets = listOf(6L, 7L, 8L, 9L).map { since.plusHours(it) }.filter { !it.isAfter(now) }.map { it.toLocalTime() },
        onDismiss = onDismiss,
    ) { time ->
        var at = since.toLocalDate().atTime(time)
        if (!at.isAfter(since)) at = at.plusDays(1)
        onConfirm(minOf(at, now))
    }
}

private const val MIN_TARGET = 4 * 60
private const val MAX_TARGET = 10 * 60
private const val MAX_COMMUTE = 180
