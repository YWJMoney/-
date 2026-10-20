package com.bamnat.app.ui.calendar

import com.bamnat.app.appContainer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.holiday.KoreanLunar
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.ResolvedExtra
import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.work.CalendarFocus
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The day's alarms as they ring with its switch on, so a day turned off still shows what it would ring. */
internal fun plannedAlarms(snapshot: ScheduleSnapshot, day: ResolvedDay): List<PlannedAlarm> =
    AlarmPlanner.alarmsOn(snapshot.resolver, day.date).ifEmpty {
        if (!day.alarmSkipped) emptyList()
        else AlarmPlanner.alarmsOn(
            ScheduleResolver(snapshot.shiftTypes, snapshot.patterns,
                snapshot.entries.map { if (it.date == day.date) it.copy(alarmSkipped = false) else it }, snapshot.kinds),
            day.date,
        )
    }

/** "07:00" or "전날 22:30" for an alarm that rings the evening before. */
internal fun whenText(alarm: PlannedAlarm, date: LocalDate): String =
    (if (alarm.triggerAt.toLocalDate().isBefore(date)) "전날 " else "") + Fmt.time(alarm.triggerAt.toLocalTime())

/** [DAY-01] One fact about the day in the header: 알람 끔, 직접 바꿈, 메모 2 … */
private data class DayToken(val text: String, val tone: Tone) {
    enum class Tone { PLAIN, ACCENT, WARNING, ERROR }
}

/** "주간 · 09:00 – 18:00 · 8시간", "야간 · 연차 (11시간)", "쉬는 날". */
internal fun shiftLine(day: ResolvedDay): String {
    val type = day.shiftType
    val start = day.start
    val end = day.end
    val leave = day.leave
    return when {
        leave?.isFullDay == true -> "${type?.name ?: "근무"} · ${leave.label} (${HoursFormat.korean(day.leaveMinutes)})"
        day.isWorkDay && start != null && end != null ->
            "${type?.name ?: "근무"} · ${Fmt.timeRange(start, end)} · ${HoursFormat.korean(day.scheduledMinutes)}" +
                (leave?.let { " · ${it.label}" } ?: "")
        type != null -> type.name
        else -> "쉬는 날"
    }
}

/** "야간 대근 · 19:00 – 07:00 (다음날) · 11시간", "잔업 · 근무 뒤 2시간 (19–21)". */
internal fun extraLine(e: ResolvedExtra): String {
    val from = e.from
    val to = e.to
    return when {
        from == null || to == null -> "${ExtraText.name(e)} · 시각 모름"
        e.entry.hasTimes || e.covered != null -> "${ExtraText.name(e)} · ${Fmt.timeRange(from.toLocalTime(), to.toLocalTime())} · ${HoursFormat.korean(e.minutes)}"
        else -> "${e.kind.name} · ${if (e.kind.timing is com.bamnat.core.model.ExtraTiming.BeforeShift) "근무 전" else "근무 뒤"} " +
            "${HoursFormat.korean(e.minutes)} (${ExtraText.span(from.toLocalTime(), to.toLocalTime())})"
    }
}

/**
 * [DAY-01] The top of a day: ‹ date › (a swipe moves a day too), what is worked, the extra work,
 * the facts that matter at a glance, and one-tap chips for what people do most. The same header
 * opens the sheet, sits above the side pane, and is the preview under the calendar.
 */
@Composable
fun DayHeader(
    day: ResolvedDay,
    today: LocalDate,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    onDateChange: (LocalDate) -> Unit,
    onMemo: () -> Unit,
    modifier: Modifier = Modifier,
    /** Shown in the calendar's preview card: the sheet opens with [onOpen]. */
    onOpen: (() -> Unit)? = null,
    /** [CAL-01] The smaller title, for the preview card and the narrow unfolded panel. */
    compact: Boolean = onOpen != null,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var dragged by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .pointerInput(day.date) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (dragged > threshold) onDateChange(day.date.minusDays(1))
                        else if (dragged < -threshold) onDateChange(day.date.plusDays(1))
                        dragged = 0f
                    },
                    onDragCancel = { dragged = 0f },
                ) { change, amount ->
                    dragged += amount
                    change.consume()
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The title takes the room the buttons on the right leave, so its weekday and 오늘 are never cut.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onDateChange(day.date.minusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "전날")
                }
                AnimatedContent(
                    targetState = day.date,
                    transitionSpec = {
                        val forward = targetState.isAfter(initialState)
                        (slideInHorizontally(tween(220)) { w -> if (forward) w / 2 else -w / 2 } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(160)) { w -> if (forward) -w / 2 else w / 2 } + fadeOut(tween(160))) using
                            SizeTransform(clip = false)
                    },
                    label = "day-title",
                    modifier = Modifier.weight(1f, fill = false),
                ) { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Fmt.dayTitle(d),
                            style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).semantics { heading() },
                        )
                        if (d == today) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "오늘",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = { onDateChange(day.date.plusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 날")
                }
            }
            if (day.date != today) TextButton(onClick = { onDateChange(today) }) { Text("오늘") }
            if (onOpen != null) {
                IconButton(onClick = onOpen) { Icon(Icons.Filled.ExpandLess, "하루 자세히 보기") }
            }
        }
        AnimatedContent(
            targetState = day,
            contentKey = { it.date },
            transitionSpec = {
                val forward = targetState.date.isAfter(initialState.date)
                (slideInHorizontally(tween(220)) { w -> if (forward) w / 4 else -w / 4 } + fadeIn(tween(220))) togetherWith
                    (fadeOut(tween(120))) using SizeTransform(clip = false)
            },
            label = "day-body",
        ) { d ->
            DayFacts(d, today, snapshot, vm, settings, Modifier.padding(horizontal = 12.dp))
        }
        // Under the calendar the folded FAB sits over the row's end, so the last chip can scroll out from under it.
        QuickChips(day, today, snapshot, vm, settings, onMemo, Modifier.padding(top = 6.dp), endSpace = if (onOpen != null) 76.dp else 12.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayFacts(day: ResolvedDay, today: LocalDate, snapshot: ScheduleSnapshot, vm: CalendarViewModel, settings: AppSettings, modifier: Modifier) {
    val cal = LocalCalendarColors.current
    val focus = CalendarFocus.of(settings.workStyle)
    val book by vm.moneyBook.collectAsStateWithLifecycle()
    val dayBook = remember(book, day.date) { book.on(day.date) }
    // [CAL-05] Field and shop work show a shift only when the day has one; an empty day is not a day off.
    val hasShift = focus == CalendarFocus.SHIFTS || day.isWorkDay || day.shiftType != null || day.leave != null ||
        day.extras.isNotEmpty() || day.hasCustomTimes
    val sales = dayBook.sales?.takeIf { it.sales != 0L || it.costs != 0L }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val holiday = KoreanHolidays.nameOf(day.date)
        if (settings.showLunar || holiday != null) {
            Row {
                if (holiday != null) Text(holiday, style = MaterialTheme.typography.labelLarge, color = cal.sunday)
                if (settings.showLunar) {
                    if (holiday != null) Spacer(Modifier.width(8.dp))
                    Text(KoreanLunar.fromSolar(day.date).label, style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // [CAL-05] A field worker's jobs and a shop's sales come first: they are what the day is about.
        for (j in dayBook.jobs.take(MAX_JOB_LINES)) DayJobLine(j, day.date)
        if (dayBook.jobs.size > MAX_JOB_LINES) {
            Text("일감 외 ${dayBook.jobs.size - MAX_JOB_LINES}건", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp))
        }
        sales?.let { DaySalesLine(it) }
        if (hasShift) Row(verticalAlignment = Alignment.CenterVertically) {
            val type = day.shiftType
            val leave = day.leave
            if (leave?.isFullDay == true) LeaveMark(leave, size = 20.dp)
            else ShiftBadge(type, size = 20.dp, text = type?.label ?: "휴", outlined = type == null || type.isOff)
            Spacer(Modifier.width(8.dp))
            Text(shiftLine(day), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        } else if (dayBook.jobs.isEmpty() && sales == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.EventBusy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(focus.emptyDay, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        for (e in day.extras.take(MAX_EXTRA_LINES)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(2.dp))
                ExtraKindMark(e.kind, size = 16.dp)
                Spacer(Modifier.width(10.dp))
                Text(extraLine(e), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (day.extras.size > MAX_EXTRA_LINES) {
            Text("외 ${day.extras.size - MAX_EXTRA_LINES}건", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp))
        }
        if (day.hasExtraWork) {
            Text(
                "이 날 합계 ${HoursFormat.korean(day.totalMinutes)}",
                style = MaterialTheme.typography.titleSmall.merge(NumberStyle),
                color = cal.holidayWork,
            )
        }
        val tokens = dayTokens(day, snapshot, vm, settings)
        if (tokens.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (t in tokens) Token(t)
            }
        }
    }
}

private const val MAX_EXTRA_LINES = 3
private const val MAX_JOB_LINES = 3

/** [CAL-05] "09:00–17:00 욕실 타일 시공 · 3일 중 2일째", with what it pays or what is still owed. */
@Composable
private fun DayJobLine(job: com.bamnat.core.money.Job, date: LocalDate) {
    val cal = LocalCalendarColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        JobBadge(count = 1, owed = job.unpaid > 0)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            val time = job.start?.let { s -> Fmt.time(s) + (job.end?.let { "–" + Fmt.time(it) } ?: "") + " " }.orEmpty()
            Text(time + job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                job.place.ifBlank { null },
                job.client.ifBlank { null },
                if (job.days > 1) "${job.days}일 중 ${(date.toEpochDay() - job.date.toEpochDay()).toInt() + 1}일째" else null,
            )
            if (sub.isNotEmpty()) {
                Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (job.unpaid > 0) {
            Text("받을 돈 ${Fmt.wonShort(job.unpaid)}", style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = cal.warning)
        } else if (job.payable > 0) {
            Text(Fmt.wonShort(job.payable), style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = cal.income)
        }
    }
}

/** [CAL-05] "매출 1,200,000원 · 남은 돈 900,000원". */
@Composable
private fun DaySalesLine(sales: com.bamnat.core.money.DailySales) {
    val cal = LocalCalendarColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Storefront, null, tint = cal.income, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("매출 ${Fmt.won(sales.sales)}", style = MaterialTheme.typography.bodyLarge.merge(NumberStyle), maxLines = 1,
            modifier = Modifier.weight(1f, fill = false))
        if (sales.costs != 0L) {
            Text(" · 남은 돈 ${Fmt.wonShort(sales.net)}", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                color = if (sales.net < 0) cal.expense else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun dayTokens(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel, settings: AppSettings): List<DayToken> {
    val memos by vm.datedMemos.collectAsStateWithLifecycle()
    val dayMemos = remember(memos, day.date) { memos.filter { it.date == day.date } }
    val alarms = remember(snapshot, day.date) { plannedAlarms(snapshot, day) }
    val rest = remember(snapshot, day.date, settings.restCheck, settings.strictRest) {
        if (settings.restCheck) vm.restIssuesOn(snapshot, day.date, settings.strictRest) else emptyList()
    }
    val calendarAlarms by vm.calendarAlarmsOn.collectAsStateWithLifecycle()
    return buildList {
        // [CAL-13] An added calendar kept from ringing says so on each of its days.
        if (alarms.isNotEmpty() && !calendarAlarms) add(DayToken("이 달력 알람 꺼짐", DayToken.Tone.PLAIN))
        else if (alarms.isNotEmpty() && (day.alarmSkipped || !settings.alarmsEnabled)) add(DayToken("알람 끔", DayToken.Tone.ERROR))
        else if (alarms.isNotEmpty()) {
            val first = alarms.first()
            add(DayToken("알람 ${whenText(first, day.date)}" + if (alarms.size > 1) " 외 ${alarms.size - 1}" else "", DayToken.Tone.PLAIN))
        }
        if (day.extras.any { it.entry.needsReview }) add(DayToken("추가 근무 확인 필요", DayToken.Tone.WARNING))
        if (day.source == DaySource.OVERRIDE || day.hasCustomTimes) add(DayToken("직접 바꿈", DayToken.Tone.ACCENT))
        // [NOTE-06] The first memo's title, cut to one line, and how many more there are.
        dayMemos.firstOrNull()?.let { first ->
            val title = first.title.ifBlank { first.preview }.lineSequence().firstOrNull().orEmpty().trim()
            val shown = if (title.length > 14) title.take(13) + "…" else title
            val more = if (dayMemos.size > 1) " 외 ${dayMemos.size - 1}" else ""
            add(DayToken(if (shown.isEmpty()) "메모 ${dayMemos.size}" else "메모 · $shown$more", DayToken.Tone.PLAIN))
        }
        rest.firstOrNull()?.let { add(DayToken(it.kind.label, DayToken.Tone.WARNING)) }
        // [CAL-01] A week over the limit is said in its own line under the header, with the limit.
    }
}

@Composable
private fun Token(t: DayToken) {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (t.tone) {
        DayToken.Tone.PLAIN -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
        DayToken.Tone.ACCENT -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        DayToken.Tone.WARNING -> cal.warningContainer to cal.warning
        DayToken.Tone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
    }
    Text(
        t.text,
        style = MaterialTheme.typography.labelMedium.merge(NumberStyle),
        color = fg,
        maxLines = 1,
        modifier = Modifier.clip(MaterialTheme.shapes.small).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * [DAY-01][EXTRA-01] What people do most on a day, one tap each: the likely extra work (taken off
 * with a second tap), 반차·연차, the day's alarm, sleep on today, and a memo. Each says itself back
 * with undo.
 */
@Composable
private fun QuickChips(
    day: ResolvedDay,
    today: LocalDate,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    settings: AppSettings,
    onMemo: () -> Unit,
    modifier: Modifier = Modifier,
    endSpace: Dp = 12.dp,
) {
    val focus = CalendarFocus.of(settings.workStyle)
    val moneyFirst = focus.moneyFirst
    val calendarAlarms by vm.calendarAlarmsOn.collectAsStateWithLifecycle()
    var addingMoney by remember { mutableStateOf(false) }
    val book by vm.moneyBook.collectAsStateWithLifecycle()
    val choices = remember(snapshot, day) {
        if (moneyFirst) emptyList() else {
            val (on, off) = ExtraText.choices(snapshot.kinds, snapshot.shiftTypes, day).partition { ExtraText.indexOf(day, it) >= 0 }
            on + off.take((QUICK_EXTRAS - on.size).coerceAtLeast(0))
        }
    }
    val alarms = remember(snapshot, day.date) { plannedAlarms(snapshot, day) }
    val log by vm.sleepLog.collectAsStateWithLifecycle()
    // [FLD-01][FLD-02] A day-rate field calendar offers 공수 buttons instead of 일감 적기.
    val calendar by vm.activeCalendar.collectAsStateWithLifecycle()
    val calendars = androidx.compose.ui.platform.LocalContext.current.appContainer.calendars
    val fieldRules = calendar.field ?: com.bamnat.core.money.FieldRules()
    val dayRate = focus == CalendarFocus.JOBS && fieldRules.dayRateMode
    ChipRow(modifier, start = 12.dp, end = endSpace) {
        if (dayRate) dayRateChips(day.date, book, fieldRules, onDelete = vm::deleteJob, onSave = { job, r ->
            calendars.updateField(calendar.id) { it.copy(dayRate = r.dayRate, lastClient = r.lastClient) }
            vm.saveJob(job)
        })
        // [CAL-05] Field and shop work: the day's job or sales is the first chip.
        focus.addLabel?.takeIf { !dayRate }?.let { label ->
            val hasSales = focus == CalendarFocus.SALES && book.on(day.date).sales != null
            item {
                AssistChip(
                    onClick = { addingMoney = true },
                    label = { Text(if (hasSales) "매출 고치기" else label, maxLines = 1) },
                    leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                    border = null,
                )
            }
        }
        // [HRLY-01][HRLY-08] 알바: 출근·퇴근, 지각·조퇴·결근, 대타.
        if (settings.workStyle == com.bamnat.core.work.WorkStyle.HOURLY) hourlyChips(day, today, snapshot, vm)
        // [DAYW-03] A day worker's 지금 퇴근, first on today's working day.
        if (offersLeaveNow(settings.workStyle, day, today)) item { LeaveNowChip(day, vm) }
        for (c in choices) item {
            val i = ExtraText.indexOf(day, c)
            FilterChip(
                selected = i >= 0,
                onClick = { vm.toggleExtra(day, c) },
                label = { Text(if (i >= 0) ExtraText.name(day.extras[i]) else c.label, maxLines = 1) },
                leadingIcon = {
                    if (i >= 0) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) else ExtraKindMark(c.kind, size = 18.dp)
                },
            )
        }
        if (!moneyFirst && (day.leave != null || day.isWorkDay && day.shiftType?.hasTimes == true)) {
            val kind = day.leave?.kind ?: LeaveKind.ANNUAL
            for (q in listOf(2, 4)) item {
                val l = Leave(kind, q)
                val on = day.leave?.quarters == q
                FilterChip(
                    selected = on,
                    onClick = { vm.setLeave(day.date, if (on) null else l, announce = true) },
                    label = { Text(l.label, maxLines = 1) },
                    leadingIcon = { LeaveMark(l, size = 18.dp) },
                )
            }
        }
        if (alarms.isNotEmpty() && calendarAlarms) item {
            val off = day.alarmSkipped
            AssistChip(
                onClick = { vm.setAlarmSkipped(day.date, !off, announce = true) },
                label = { Text(if (off) "알람 켜기" else "알람 끄기", maxLines = 1) },
                leadingIcon = { Icon(if (off) Icons.Filled.Alarm else Icons.Filled.AlarmOff, null, Modifier.size(18.dp)) },
                colors = if (off) AssistChipDefaults.assistChipColors(labelColor = LocalCalendarColors.current.alarmOff,
                    leadingIconContentColor = LocalCalendarColors.current.alarmOff) else AssistChipDefaults.assistChipColors(),
                border = if (off) BorderStroke(1.dp, LocalCalendarColors.current.alarmOff) else AssistChipDefaults.assistChipBorder(true),
            )
        }
        // [CAL-05] Sleep goes with shifts, as 오늘의 흐름 does; a job or shop day keeps its chips for the day's work.
        if (day.date == today && settings.sleepTips && !moneyFirst) item {
            val asleep = log.firstOrNull { it.end == null } != null
            AssistChip(
                onClick = { if (asleep) vm.wokeUp() else vm.fellAsleep() },
                label = { Text(if (asleep) "깼어요" else "잠들었어요", maxLines = 1) },
                leadingIcon = { Icon(if (asleep) Icons.Filled.WbSunny else Icons.Filled.Bedtime, null, Modifier.size(18.dp)) },
            )
        }
        item {
            AssistChip(
                onClick = onMemo,
                label = { Text("메모", maxLines = 1) },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null, Modifier.size(18.dp)) },
            )
        }
    }
    if (addingMoney) {
        // [FLD-01] The first 일감 적기 on a field calendar asks once how it counts its work.
        if (focus == CalendarFocus.JOBS && calendar.field?.mode == null) {
            FieldModePrompt { m ->
                calendars.updateField(calendar.id) { it.copy(mode = m) }
                if (m == com.bamnat.core.money.FieldMode.DAY_RATE) addingMoney = false
            }
        } else if (focus == CalendarFocus.INVEST) {
            // [INV-03] 매매 기록 for this day.
            com.bamnat.app.ui.invest.InvestQuickTrade(calendar.id, day.date, onDismiss = { addingMoney = false })
        } else if (focus == CalendarFocus.JOBS) {
            com.bamnat.app.ui.money.JobDialog(day.date, null, onSave = { vm.saveJob(it); addingMoney = false }, onDismiss = { addingMoney = false })
        } else {
            com.bamnat.app.ui.money.SalesDialog(day.date, book.on(day.date).sales,
                onSave = { vm.saveSales(it); addingMoney = false }, onDismiss = { addingMoney = false })
        }
    }
}

private const val QUICK_EXTRAS = 3

/**
 * Fades the ends of a row that scrolls sideways, each only while there is more that way, so a cut-off
 * row reads as "there is more" and a row shown whole is not dimmed. Read while drawing: scrolling
 * redraws the edge without recomposing the row.
 */
internal fun Modifier.fadingEdges(back: () -> Boolean, forward: () -> Boolean, width: Dp = 32.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val w = width.toPx().coerceAtMost(size.width / 3)
        if (forward()) drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
            topLeft = Offset(size.width - w, 0f),
            size = androidx.compose.ui.geometry.Size(w, size.height),
            blendMode = BlendMode.DstIn,
        )
        if (back()) drawRect(
            brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
            size = androidx.compose.ui.geometry.Size(w, size.height),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * [CAL-18] Chips in one line that scrolls sideways. While some are out of sight to the right, the
 * end fades and a "+2 ›" button says how many; a tap on it brings them in.
 */
@Composable
internal fun ChipRow(modifier: Modifier = Modifier, start: Dp, end: Dp, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val reserve = with(LocalDensity.current) { MORE_RESERVE.toPx() }
    // A chip that ends under the button or past the edge counts as out of sight; none once at the end.
    val more by remember(state, reserve) {
        derivedStateOf {
            if (!state.canScrollForward) 0 else {
                val info = state.layoutInfo
                val edge = info.viewportEndOffset - reserve
                val lastShown = info.visibleItemsInfo.lastOrNull { it.offset + it.size <= edge }?.index
                    ?: ((info.visibleItemsInfo.firstOrNull()?.index ?: 0) - 1)
                info.totalItemsCount - 1 - lastShown
            }
        }
    }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        LazyRow(
            state = state,
            modifier = Modifier.fillMaxWidth().fadingEdges(back = { state.canScrollBackward }, forward = { state.canScrollForward }),
            contentPadding = PaddingValues(start = start, end = end),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
        if (more > 0) {
            MoreButton(more, Modifier.padding(end = 4.dp)) {
                scope.launch { state.animateScrollBy(state.layoutInfo.viewportSize.width * 0.6f) }
            }
        }
    }
}

/** [CAL-18] "+2 ›": how many chips are out of sight, as a button that scrolls to them. */
@Composable
private fun MoreButton(count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Row(Modifier.heightIn(min = 32.dp).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("+$count", style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                modifier = Modifier.semantics { contentDescription = "${count}개 더 보기" })
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp))
        }
    }
}

private val MORE_RESERVE = 48.dp

/**
 * [DAY-01] The selected day under the calendar on phones: the same header as the sheet, so most
 * things are done right here; a tap on the card (or on the day again) opens everything.
 */
@Composable
fun DayPreview(
    day: ResolvedDay,
    today: LocalDate,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    onDateChange: (LocalDate) -> Unit,
    onOpen: () -> Unit,
    onMemo: () -> Unit,
    modifier: Modifier = Modifier,
    /** [CAL-01] The week of this day over the hour limit, in one line. */
    weekNote: String? = null,
) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            DayHeader(day, today, snapshot, vm, onDateChange, onMemo,
                Modifier.padding(top = 4.dp, bottom = if (weekNote != null) 4.dp else 10.dp), onOpen = onOpen)
            weekNote?.let { WeekNoteLine(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) }
        }
    }
}

/** [CAL-01] "이 주 73.5시간 · 64시간 넘음": the line that goes with the bar beside an over-limit week. */
@Composable
internal fun WeekNoteLine(text: String, modifier: Modifier = Modifier) {
    val warning = LocalCalendarColors.current.warning
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.WarningAmber, null, tint = warning, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = warning)
    }
}
