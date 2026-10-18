package com.bamnat.app.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.AlarmAdd
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreTime
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.app.ui.components.LocalFlatSections
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.TimePickerDialog
import com.bamnat.app.ui.components.memoBackground
import com.bamnat.app.ui.money.DayMoneySections
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.memo.Memo
import com.bamnat.core.model.DayAlarm
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.ShiftType
import com.bamnat.core.pay.LeaveBook
import com.bamnat.core.pay.WageType
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** [DAY-02] Which of the day's groups are open: the user's own choice per group, kept from day to day. */
@Stable
class DayGroupState(initial: Map<String, Boolean> = emptyMap()) {
    private val open = mutableStateMapOf<String, Boolean>().apply { putAll(initial) }

    /** Open as the user left it, else [default] (open when the day has something set there). */
    fun isOpen(key: String, default: Boolean): Boolean = open[key] ?: default

    fun toggle(key: String, default: Boolean) {
        open[key] = !isOpen(key, default)
    }

    companion object {
        val Saver: Saver<DayGroupState, List<String>> = Saver(
            save = { s -> s.open.map { (k, v) -> "$k=$v" } },
            restore = { list ->
                DayGroupState(list.mapNotNull { item -> item.split('=').takeIf { it.size == 2 }?.let { it[0] to (it[1] == "true") } }.toMap())
            },
        )
    }
}

@Composable
fun rememberDayGroupState(): DayGroupState = rememberSaveable(saver = DayGroupState.Saver) { DayGroupState() }

/**
 * [DAY-01][DAY-02] One day in full: the header (‹ date ›, the day's work, quick chips) stays put and
 * the groups under it scroll. The sheet on phones and the pane beside the unfolded calendar.
 */
@Composable
fun DayPanel(
    date: LocalDate,
    today: LocalDate,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    groups: DayGroupState,
    onDateChange: (LocalDate) -> Unit,
    onOpenMemo: (Long) -> Unit,
    onNewMemo: (LocalDate) -> Unit,
    onOpenShiftTypes: () -> Unit,
    onOpenPay: () -> Unit,
    onOpenExtraKinds: () -> Unit,
    modifier: Modifier = Modifier,
    /** Bumped to put the cursor in the quick memo, e.g. after the 메모 chip. */
    memoFocus: Int = 0,
    onMemo: () -> Unit = {},
    /** Room under the last group, e.g. for the floating button beside the unfolded calendar. */
    bottomPadding: androidx.compose.ui.unit.Dp = 24.dp,
    /** [CAL-01] The week of this day over the hour limit, in one line. */
    weekNote: String? = null,
    /** [CAL-01] Beside the unfolded calendar the panel is narrow, so the title is smaller. */
    compact: Boolean = false,
) {
    val day = remember(snapshot, date) { snapshot.resolver.resolve(date) }
    Column(modifier) {
        DayHeader(day, today, snapshot, vm, onDateChange, onMemo, Modifier.padding(bottom = 8.dp), compact = compact)
        weekNote?.let { WeekNoteLine(it, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Column(
            Modifier
                .weight(1f, fill = false)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomPadding),
        ) {
            DayGroups(day, today, snapshot, vm, groups, memoFocus, onOpenMemo, onNewMemo, onOpenShiftTypes, onOpenPay, onOpenExtraKinds)
        }
    }
}

private object G {
    const val WORK = "work"
    const val EXTRA = "extra"
    const val LEAVE = "leave"
    const val ALARM = "alarm"
    const val MEMO = "memo"
    const val MONEY = "money"
    const val BODY = "body"
    const val RECORD = "record"
}

/**
 * [DAY-02] The day in eight groups, each one line until opened: 근무, 추가 근무, 휴가, 알람, 메모 (always
 * open), 돈, 몸, 기록. A group with something set on this day opens by itself; one the user opened
 * or closed stays that way.
 */
@Composable
private fun DayGroups(
    day: ResolvedDay,
    today: LocalDate,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    groups: DayGroupState,
    memoFocus: Int,
    onOpenMemo: (Long) -> Unit,
    onNewMemo: (LocalDate) -> Unit,
    onOpenShiftTypes: () -> Unit,
    onOpenPay: () -> Unit,
    onOpenExtraKinds: () -> Unit,
) {
    val date = day.date
    val cal = LocalCalendarColors.current
    val haptics = LocalHapticFeedback.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val memos by remember(date) { vm.memosOn(date) }.collectAsStateWithLifecycle(emptyList())
    val changes by remember(date) { vm.changesOn(date) }.collectAsStateWithLifecycle(emptyList())
    val book by vm.moneyBook.collectAsStateWithLifecycle()
    val dayBook = remember(book, date) { book.on(date) }
    val profile by vm.payProfile.collectAsStateWithLifecycle()
    val tier by vm.tier.collectAsStateWithLifecycle()
    val unlocked = FeatureGate.allows(tier, Feature.PAY_ENGINE)
    // [EXTRA-02][EXTRA-03] Minutes and pay are worked out off the main thread, for this date only.
    val insightFor by produceState<Pair<LocalDate, DayInsight>?>(null, profile, snapshot, date, unlocked) {
        value = date to withContext(Dispatchers.Default) { vm.dayInsight(profile, snapshot, date, unlocked) }
    }
    val insight = insightFor?.takeIf { it.first == date }?.second ?: DayInsight()
    val alarms = remember(snapshot, date) { plannedAlarms(snapshot, day) }
    val rest = remember(snapshot, date, settings.restCheck, settings.strictRest) {
        if (settings.restCheck) vm.restIssuesOn(snapshot, date, settings.strictRest) else emptyList()
    }
    val tips = remember(snapshot, date, settings) {
        if (settings.sleepTips) vm.sleepTips(snapshot, date, settings) else emptyList()
    }
    val style = settings.workStyle
    val moneyFirst = style != null && !style.usesPayEngine
    var restShift by remember(date) { mutableStateOf<ShiftType?>(null) }

    val order = when {
        moneyFirst -> listOf(G.MONEY, G.MEMO, G.WORK, G.EXTRA, G.LEAVE, G.ALARM, G.BODY, G.RECORD)
        settings.memoFirst -> listOf(G.MEMO, G.WORK, G.EXTRA, G.LEAVE, G.ALARM, G.MONEY, G.BODY, G.RECORD)
        else -> listOf(G.WORK, G.EXTRA, G.LEAVE, G.ALARM, G.MEMO, G.MONEY, G.BODY, G.RECORD)
    }
    val showLeave = day.leave != null || day.isWorkDay && day.shiftType?.hasTimes == true
    val showBody = rest.isNotEmpty() || settings.sleepTips && (tips.isNotEmpty() || date == today)
    val showRecord = changes.isNotEmpty() || day.entry != null
    val visible = order.filter {
        when (it) {
            G.LEAVE -> showLeave
            G.BODY -> showBody
            G.RECORD -> showRecord
            else -> true
        }
    }

    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            visible.forEachIndexed { i, key ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                when (key) {
                    G.WORK -> {
                        val changed = day.source == DaySource.OVERRIDE || day.hasCustomTimes || day.entry?.customBreakMinutes != null
                        DayGroup(
                            title = "근무", icon = Icons.Outlined.Work,
                            summary = workSummary(day, snapshot),
                            summaryColor = if (changed) MaterialTheme.colorScheme.tertiary else null,
                            open = groups.isOpen(key, changed), onToggle = { groups.toggle(key, changed) },
                        ) { WorkContent(day, snapshot, vm, onRestShift = { restShift = it }) }
                    }
                    G.EXTRA -> {
                        val names = day.extras.map(ExtraText::name)
                        val review = day.extras.any { it.entry.needsReview }
                        DayGroup(
                            title = "추가 근무", icon = Icons.Outlined.MoreTime,
                            summary = when {
                                review -> "확인이 필요해요 · " + names.joinToString(" · ")
                                names.isEmpty() -> "없음"
                                // "잔업 2시간" says its hours already; a total adds to a named piece or several.
                                day.extras.size == 1 && ExtraText.namesHours(day.extras[0]) -> names[0]
                                else -> names.joinToString(" · ") + " · ${HoursFormat.korean(day.extraMinutes)}"
                            },
                            summaryColor = if (review) cal.warning else if (names.isNotEmpty()) cal.holidayWork else null,
                            open = groups.isOpen(key, day.hasExtraWork), onToggle = { groups.toggle(key, day.hasExtraWork) },
                        ) {
                            ExtrasContent(day, snapshot, vm, insight, showAmounts = profile != null && unlocked, onOpenExtraKinds = onOpenExtraKinds)
                        }
                    }
                    G.LEAVE -> {
                        val leave = day.leave
                        DayGroup(
                            title = "휴가", icon = Icons.Outlined.BeachAccess,
                            summary = leave?.let { l -> l.label + if (l.kind.annual) " · 연차 ${LeaveBook.days(l.days)} 차감" else if (!l.kind.paid) " · 무급" else "" } ?: "없음",
                            open = groups.isOpen(key, leave != null), onToggle = { groups.toggle(key, leave != null) },
                        ) { LeaveContent(day, vm) }
                    }
                    G.ALARM -> {
                        val custom = day.entry?.customAlarms != null
                        val off = day.alarmSkipped || !settings.alarmsEnabled
                        val summary = alarmSummary(day, alarms, settings) + if (custom) " · 직접 정함" else ""
                        DayGroup(
                            title = "알람", icon = Icons.Outlined.Alarm,
                            summary = summary,
                            summaryColor = if (off && alarms.isNotEmpty()) cal.alarmOff else null,
                            open = groups.isOpen(key, custom), onToggle = { groups.toggle(key, custom) },
                            trailing = if (alarms.isNotEmpty()) ({
                                Switch(
                                    checked = !day.alarmSkipped,
                                    onCheckedChange = {
                                        haptics.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                                        vm.setAlarmSkipped(date, !it)
                                    },
                                    // [VIS-02] Read with what it does: "이 날 알람, 06:10에 울려요, 켜짐".
                                    modifier = Modifier.semantics { contentDescription = "이 날 알람, $summary" },
                                )
                            }) else null,
                        ) { AlarmContent(day, snapshot, vm, alarms, settings, onOpenShiftTypes) }
                    }
                    G.MEMO -> DayGroup(
                        title = "메모", icon = Icons.AutoMirrored.Outlined.StickyNote2,
                        summary = if (memos.isEmpty()) null else "${memos.size}개",
                        open = true, onToggle = null,
                        trailing = {
                            TextButton(onClick = { onNewMemo(date) }) {
                                Icon(Icons.AutoMirrored.Outlined.NoteAdd, contentDescription = null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("자세히 쓰기")
                            }
                        },
                    ) { MemoContent(date, memos, vm, memoFocus, onOpenMemo) }
                    G.MONEY -> {
                        val pay = insight.pay
                        val spent = dayBook.entries.filter { it.kind == com.bamnat.core.money.MoneyKind.EXPENSE }.sumOf { it.amount }
                        val payText = when {
                            moneyFirst -> null
                            profile == null -> "급여 설정 전"
                            !unlocked -> "급여는 프로 등급에서 보여요"
                            pay == null -> null
                            pay.amount == 0L -> "더해지는 급여 없음"
                            profile?.wageType == WageType.MONTHLY -> "급여 +${Fmt.won(pay.amount)}"
                            else -> "급여 ${Fmt.won(pay.amount)}"
                        }
                        val moneyText = listOfNotNull(
                            payText,
                            dayBook.sales?.let { "매출 ${Fmt.won(it.sales)}" },
                            dayBook.jobs.takeIf { it.isNotEmpty() }?.let { "일감 ${it.size}건" },
                            spent.takeIf { it > 0 }?.let { "지출 ${Fmt.won(it)}" },
                        ).joinToString(" · ").ifEmpty { "기록 없음" }
                        DayGroup(
                            title = "돈", icon = Icons.Outlined.Payments,
                            summary = moneyText,
                            open = groups.isOpen(key, moneyFirst), onToggle = { groups.toggle(key, moneyFirst) },
                        ) {
                            if (!moneyFirst) DayPayContent(insight, profile?.wageType, profile != null, unlocked, onOpenPay)
                            val memoText = remember(memos) {
                                memos.joinToString("\n") { m -> (listOf(m.title, m.body) + m.checklist.map { it.text }).filter { it.isNotBlank() }.joinToString("\n") }
                            }
                            DayMoneySections(date, style, dayBook, vm, memoText)
                        }
                    }
                    G.BODY -> {
                        if (date == today) {
                            // Each time the app comes back (after the night's sleep, say) the read is fresh.
                            androidx.lifecycle.compose.LifecycleResumeEffect(settings.healthSleep) {
                                vm.refreshHealthSleep(settings.healthSleep)
                                onPauseOrDispose { }
                            }
                        }
                        val log by vm.sleepLog.collectAsStateWithLifecycle()
                        val health by vm.healthSleepState.collectAsStateWithLifecycle()
                        val now = rememberMinute()
                        // [SLP-14] Today says the same line as the card on the calendar.
                        val flow = remember(snapshot, settings, log, now, date == today) {
                            if (date == today && settings.sleepTips) vm.todayFlow(snapshot, settings, log, now) else null
                        }
                        DayGroup(
                            title = "몸", icon = Icons.Outlined.Bedtime,
                            summary = rest.firstOrNull()?.kind?.label ?: flow?.line ?: tipSummary(tips, date) ?: "수면 기록",
                            summaryColor = if (rest.isNotEmpty()) cal.warning else null,
                            open = groups.isOpen(key, rest.isNotEmpty()), onToggle = { groups.toggle(key, rest.isNotEmpty()) },
                        ) {
                            if (rest.isNotEmpty()) RestWarning(rest)
                            if (settings.sleepTips) {
                                SleepSection(date = date, today = today, now = now, flow = flow, tips = tips, log = log,
                                    targetMinutes = settings.sleepTargetMinutes, health = health,
                                    onAsleep = vm::fellAsleep, onAwake = { vm.wokeUp() }, onWokeAt = { vm.wokeUp(it) })
                            }
                        }
                    }
                    G.RECORD -> DayGroup(
                        title = "기록", icon = Icons.Outlined.History,
                        summary = if (changes.isEmpty()) "바꾼 내용 있음" else "바꾼 기록 ${changes.size}건",
                        open = groups.isOpen(key, false), onToggle = { groups.toggle(key, false) },
                    ) { RecordContent(day, changes, vm) }
                }
            }
        }
    }

    restShift?.let { type ->
        RestDayShiftDialog(
            date = date,
            type = type,
            onCover = { restShift = null; vm.coverInstead(date, type, snapshot) },
            onRoster = { restShift = null; vm.assign(date, ShiftOverride.Shift(type.id)) },
            onDismiss = { restShift = null },
        )
    }
}

/**
 * [DAY-02] One group of the day: a 56dp row with the group's icon, its name and a one-line summary,
 * a chevron that turns, and the content sliding open under it.
 */
@Composable
private fun DayGroup(
    title: String,
    icon: ImageVector,
    summary: String?,
    open: Boolean,
    onToggle: (() -> Unit)?,
    summaryColor: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(
                    if (onToggle != null) Modifier.clickable(role = Role.Button, onClickLabel = if (open) "접기" else "펼치기", onClick = onToggle)
                        .semantics { stateDescription = if (open) "펼쳐짐" else "접힘" }
                    else Modifier,
                )
                .padding(start = 16.dp, end = if (onToggle != null) 8.dp else 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                if (summary != null && !(open && onToggle == null)) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                        color = summaryColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
            if (onToggle != null) {
                Icon(Icons.Filled.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = 4.dp).rotate(turn),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CompositionLocalProvider(LocalFlatSections provides true) { content() }
            }
        }
    }
}

private fun workSummary(day: ResolvedDay, snapshot: ScheduleSnapshot): String {
    val name = day.shiftType?.name ?: if (day.isWorkDay) "근무" else "쉬는 날"
    val start = day.start
    val end = day.end
    val time = if (day.isWorkDay && start != null && end != null) " · ${ExtraText.span(start, end)}" else ""
    return when {
        day.source == DaySource.OVERRIDE -> {
            val pattern = snapshot.resolver.patternShiftIdOn(day.date)?.let { snapshot.typesById[it] }
            "$name$time · 직접 바꿈" + if (snapshot.hasPattern) " (패턴: ${pattern?.name ?: "쉬는 날"})" else ""
        }
        day.hasCustomTimes -> "$name$time · 이 날만 시간 바꿈"
        day.source == DaySource.PATTERN -> "$name$time · 패턴대로"
        else -> "$name$time"
    }
}

private fun alarmSummary(day: ResolvedDay, alarms: List<PlannedAlarm>, settings: AppSettings): String = when {
    !settings.alarmsEnabled && alarms.isNotEmpty() -> "알람 사용이 꺼져 있어요"
    day.alarmSkipped -> "이 날은 알람 끔"
    alarms.isEmpty() -> "울릴 알람 없음"
    alarms.size == 1 -> "${whenText(alarms.first(), day.date)}에 울려요"
    else -> "알람 ${alarms.size}개 · 첫 알람 ${whenText(alarms.first(), day.date)}"
}

/** [DAY-02] The shift (one choice), back to the pattern when changed, and the day's own times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkContent(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel, onRestShift: (ShiftType) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (type in snapshot.shiftTypes) {
            FilterChip(
                selected = day.shiftType?.id == type.id,
                onClick = {
                    when {
                        day.shiftType?.id == type.id -> Unit
                        // [EXTRA-01] A shift on a day the roster gives off is usually a 대근: ask which.
                        vm.isRosterRestDay(day, type) -> onRestShift(type)
                        else -> vm.assign(day.date, ShiftOverride.Shift(type.id))
                    }
                },
                label = { Text(type.name) },
                leadingIcon = { ShiftBadge(type, size = 18.dp, outlined = type.isOff) },
            )
        }
        if (snapshot.shiftTypes.none { it.isOff }) {
            FilterChip(
                selected = !day.isWorkDay && day.shiftType == null,
                onClick = { vm.assign(day.date, ShiftOverride.Off) },
                label = { Text("쉬는 날") },
            )
        }
    }
    if (day.source == DaySource.OVERRIDE) {
        val patternType = snapshot.resolver.patternShiftIdOn(day.date)?.let { snapshot.typesById[it] }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (snapshot.hasPattern) "직접 바꾼 날이에요 · 패턴: ${patternType?.name ?: "쉬는 날"}" else "직접 정한 날이에요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.assign(day.date, ShiftOverride.None) }) {
                Icon(Icons.Filled.Autorenew, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (snapshot.hasPattern) "패턴대로" else "지우기")
            }
        }
    }
    if (day.isWorkDay) WorkTimeContent(day, snapshot, vm)
}

@Composable
private fun WorkTimeContent(day: ResolvedDay, snapshot: ScheduleSnapshot, vm: CalendarViewModel) {
    val start = day.start ?: return
    val end = day.end ?: return
    var editing by remember { mutableStateOf<String?>(null) }
    val hasCustom = day.hasCustomTimes || day.entry?.customBreakMinutes != null
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimeBox(label = "시작", time = start, onClick = { editing = "start" }, modifier = Modifier.weight(1f))
        TimeBox(label = "끝", time = end, onClick = { editing = "end" }, modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("휴게 시간", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        MinutesStepper(
            minutes = day.breakMinutes,
            onChange = { vm.setBreak(day.date, it) },
            step = 10,
            max = 12 * 60,
            dialogTitle = "휴게 시간",
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "근무 ${HoursFormat.korean(day.scheduledMinutes)}" + if (hasCustom) " · 이 날만 바꿨어요" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (hasCustom) TextButton(onClick = { vm.clearCustomTimes(day.date) }) { Text("기본 시간으로") }
    }
    editing?.let { which ->
        TimePickerDialog(
            title = if (which == "start") "시작 시각" else "끝 시각",
            initial = if (which == "start") start else end,
            presets = timePresets(snapshot),
            onDismiss = { editing = null },
        ) {
            editing = null
            if (which == "start") vm.setCustomTimes(day.date, it, end) else vm.setCustomTimes(day.date, start, it)
        }
    }
}

@Composable
internal fun TimeBox(label: String, time: LocalTime, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = "$label 시각 바꾸기", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.time(time), style = MaterialTheme.typography.headlineSmall.merge(NumberStyle))
    }
}

/**
 * [EXTRA-01] A working shift chosen on a day the roster gives off. Work on a day off is a 대근 (or
 * 특근) paid as work on a day off; changing the roster means the company moved the day off.
 */
@Composable
private fun RestDayShiftDialog(date: LocalDate, type: ShiftType, onCover: () -> Unit, onRoster: () -> Unit, onDismiss: () -> Unit) {
    val span = if (type.start != null && type.end != null) " ${ExtraText.span(type.start!!, type.end!!)}" else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("원래 쉬는 날이에요") },
        text = {
            Text(
                "${Fmt.monthDay(date)}에 쉬는 날인데 ${type.name} 근무를 했다면 대근으로 넣어 주세요. 근무표는 그대로 두고 " +
                    "${type.name}$span 시간이 추가 근무로 들어가 급여가 맞게 계산돼요.\n\n" +
                    "회사가 근무표 자체를 바꿨다면 ‘근무표 변경’을 고르세요.",
            )
        },
        confirmButton = { TextButton(onClick = onCover) { Text("대근으로 넣기") } },
        dismissButton = { TextButton(onClick = onRoster) { Text("근무표 변경") } },
    )
}

/**
 * 연차·반차·반반차 [FEAT-10]. Partial leave comes off the end of the shift; the pay screen and the
 * day's pay follow at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LeaveContent(day: ResolvedDay, vm: CalendarViewModel) {
    val leave = day.leave
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (q in Leave.QUARTER_OPTIONS) {
            val option = Leave(leave?.kind ?: LeaveKind.ANNUAL, q)
            FilterChip(
                selected = leave?.quarters == q,
                onClick = { vm.setLeave(day.date, if (leave?.quarters == q) null else option) },
                label = { Text(when (q) { 4 -> "종일"; 2 -> "반차"; 1 -> "반반차"; else -> "0.75일" }) },
                leadingIcon = { LeaveMark(option, size = 18.dp) },
            )
        }
    }
    if (leave != null) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LeaveKind.entries.forEachIndexed { i, k ->
                SegmentedButton(
                    selected = leave.kind == k,
                    onClick = { vm.setLeave(day.date, leave.copy(kind = k)) },
                    shape = SegmentedButtonDefaults.itemShape(i, LeaveKind.entries.size),
                ) { Text(k.label, maxLines = 1) }
            }
        }
        Text(
            buildString {
                append("${leave.label} · ${HoursFormat.korean(day.leaveMinutes)} 쉼")
                if (!leave.isFullDay && day.start != null && day.end != null) {
                    append(" · 남은 근무 ${Fmt.timeRange(day.start!!, day.end!!)}")
                }
                if (leave.kind.annual) append(" · 연차에서 ${LeaveBook.days(leave.days)} 차감")
                if (!leave.kind.paid) append(" · 무급")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "반차는 근무 뒤쪽 절반을 쉬는 것으로 계산해요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The day's alarms: what rings and why, and alarms set by hand for this day only. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlarmContent(
    day: ResolvedDay,
    snapshot: ScheduleSnapshot,
    vm: CalendarViewModel,
    planned: List<PlannedAlarm>,
    settings: AppSettings,
    onOpenShiftTypes: () -> Unit,
) {
    val type = day.shiftType?.takeIf { day.isWorkDay }
    val custom = day.entry?.customAlarms
    var editing by remember(day.date) { mutableStateOf(false) }
    if (!settings.alarmsEnabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.NotificationsOff, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text("알람 사용이 꺼져 있어 아무 알람도 울리지 않아요", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.setAlarmsEnabled(true) }) { Text("켜기") }
        }
    }
    Text(
        when {
            custom != null -> "이 날 알람이에요. 근무 알람 대신 울려요."
            day.alarmSkipped -> "이 날은 알람이 울리지 않아요. 위 스위치로 다시 켤 수 있어요."
            type != null && type.alarmEnabled -> "${type.name} 근무 알람 설정을 따라요."
            type != null -> "${type.name} 근무는 알람이 꺼져 있어요."
            planned.isNotEmpty() -> "대근한 근무의 알람이 울려요."
            else -> "쉬는 날이에요. 필요하면 이 날만 알람을 정할 수 있어요."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (planned.isNotEmpty() && !day.alarmSkipped) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (a in planned) SuggestionChip(onClick = { editing = true }, label = { Text(whenText(a, day.date), style = NumberStyle) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { editing = true }) {
            Icon(Icons.Filled.EditCalendar, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (custom != null) "이 날 알람 고치기" else "이 날만 알람 정하기")
        }
        if (custom != null) TextButton(onClick = { vm.setDayAlarms(day.date, null) }) { Text("근무 알람으로") }
        if (type != null && !type.alarmEnabled && custom == null) TextButton(onClick = onOpenShiftTypes) { Text("${type.name} 알람 켜기") }
    }

    if (editing) {
        val start = day.start
        DayAlarmEditorDialog(
            date = day.date,
            initial = custom ?: planned.filter { it.triggerAt.toLocalDate() == day.date }.map { DayAlarm(it.triggerAt.toLocalTime()) },
            suggested = start?.minusHours(1) ?: LocalTime.of(8, 0),
            // An alarm the evening before cannot be set here; say that saving drops it [QA-01].
            eveningBefore = if (custom != null) emptyList() else planned.filter { it.triggerAt.toLocalDate().isBefore(day.date) }
                .map { Fmt.time(it.triggerAt.toLocalTime()) },
            onDismiss = { editing = false },
            onSave = { list ->
                editing = false
                vm.setDayAlarms(day.date, list)
            },
        )
    }
}

@Composable
private fun DayAlarmEditorDialog(
    date: LocalDate,
    initial: List<DayAlarm>,
    suggested: LocalTime,
    eveningBefore: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (List<DayAlarm>) -> Unit,
) {
    val alarms = remember { mutableStateListOf<DayAlarm>().apply { addAll(initial.sortedBy { it.time }) } }
    var picking by remember { mutableStateOf<Int?>(null) } // index to change, or -1 for a new alarm
    AlertDialog(
        onDismissRequest = onDismiss,
        // A stray tap outside must not throw away alarms just added; 취소 or 완료 decides.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("${Fmt.dayTitle(date)} 알람") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "이 날은 아래 알람만 울려요. 근무 알람 대신 쓰여요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (eveningBefore.isNotEmpty()) {
                    Text(
                        "저장하면 전날 ${eveningBefore.joinToString(", ")} 알람은 울리지 않아요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (alarms.isEmpty()) {
                    Text("알람이 없어요. 저장하면 이 날은 울리지 않아요.", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                alarms.forEachIndexed { i, alarm ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            Fmt.time(alarm.time),
                            style = MaterialTheme.typography.headlineSmall.merge(NumberStyle),
                            color = if (alarm.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                            modifier = Modifier
                                .weight(1f)
                                .clip(MaterialTheme.shapes.small)
                                .clickable(onClickLabel = "시각 바꾸기") { picking = i }
                                .padding(vertical = 6.dp),
                        )
                        Switch(checked = alarm.enabled, onCheckedChange = { on -> alarms[i] = alarm.copy(enabled = on) },
                            modifier = Modifier.semantics { contentDescription = "${Fmt.time(alarm.time)} 알람" })
                        IconButton(onClick = { alarms.removeAt(i) }) { Icon(Icons.Filled.Close, "${Fmt.time(alarm.time)} 알람 지우기") }
                    }
                }
                OutlinedButton(
                    onClick = { picking = -1 },
                    enabled = alarms.size < ScheduleRepository.MAX_ALARMS,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.AlarmAdd, null)
                    Spacer(Modifier.width(6.dp))
                    Text("알람 추가")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(alarms.toList()) }) { Text("완료") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    picking?.let { index ->
        val initialTime = if (index >= 0) alarms[index].time else alarms.maxOfOrNull { it.time }?.plusMinutes(10) ?: suggested
        TimePickerDialog(if (index >= 0) "알람 시각 바꾸기" else "알람 추가", initialTime, onDismiss = { picking = null }) { t ->
            if (alarms.none { it.time == t }) {
                if (index >= 0) alarms[index] = alarms[index].copy(time = t) else alarms.add(DayAlarm(t))
                alarms.sortBy { it.time }
            }
            picking = null
        }
    }
}

/** The day's memos: a quick line that saves with one tap, and the list; deleting asks first [DAY-03]. */
@Composable
private fun MemoContent(date: LocalDate, memos: List<Memo>, vm: CalendarViewModel, memoFocus: Int, onOpenMemo: (Long) -> Unit) {
    var text by rememberSaveable(date) { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Memo?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(memoFocus) {
        if (memoFocus > 0) {
            // After the sheet has slid up, so the keyboard does not fight the animation.
            delay(350)
            runCatching { focus.requestFocus() }
        }
    }
    val submit = {
        if (text.isNotBlank()) {
            vm.addQuickMemo(date, text)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        placeholder = { Text("빠른 메모 (예: 인수인계, 준비물)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "메모 추가")
            }
        },
    )
    for (memo in memos) {
        MemoRow(memo = memo, onClick = { onOpenMemo(memo.id) }, onDelete = { deleting = memo })
    }
    deleting?.let { m ->
        ConfirmDialog(
            title = "이 메모를 지울까요?",
            message = "휴지통으로 옮겨요. 메모 탭의 휴지통에서 되살릴 수 있어요.",
            confirmLabel = "지우기",
            destructive = true,
            onDismiss = { deleting = null },
            onConfirm = {
                deleting = null
                vm.deleteMemo(m)
            },
        )
    }
}

@Composable
private fun MemoRow(memo: Memo, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(memoBackground(memo.colorIndex))
            .clickable(onClick = onClick)
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (memo.isChecklist) {
            Icon(Icons.Filled.Checklist, contentDescription = "체크리스트", Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.weight(1f)) {
            if (memo.title.isNotBlank()) {
                Text(memo.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val preview = memo.preview
            if (preview.isNotBlank()) {
                Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (memo.isChecklist && memo.itemCount > 0) {
            AssistChip(onClick = onClick, label = { Text("${memo.checkedCount}/${memo.itemCount}") })
        }
        if (memo.pinned) {
            Icon(Icons.Filled.PushPin, contentDescription = "고정됨", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "메모 지우기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * What this day adds to pay, straight from the pay engine [PAYX-08]: the shift, each piece of extra
 * work and the holiday show up here as soon as they change on the calendar.
 */
@Composable
private fun DayPayContent(insight: DayInsight, wageType: WageType?, hasProfile: Boolean, unlocked: Boolean, onOpenPay: () -> Unit) {
    if (!hasProfile) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("급여 계산을 설정하면 이 날 더해지는 돈이 보여요.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenPay) { Text("설정하기") }
        }
        return
    }
    if (!unlocked) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("이 날 급여는 프로 등급에서 보여요.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onOpenPay) { Text("급여 화면") }
        }
        return
    }
    val pay = insight.pay ?: return
    SectionCard(
        title = if (wageType == WageType.MONTHLY) "이 날 더해지는 급여" else "이 날 급여",
        trailing = { TextButton(onClick = onOpenPay) { Text("자세히") } },
    ) {
        if (pay.parts.isEmpty()) {
            Text(
                if (wageType == WageType.MONTHLY) "기본급 안의 날이에요. 따로 더해지는 돈이 없어요." else "이 날 받는 돈이 없어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        for (part in pay.parts) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(part.label, style = MaterialTheme.typography.bodyLarge)
                    Text(part.formula, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(Fmt.won(part.amount), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("합계 (세전)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(Fmt.won(pay.amount), style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** [MKT-08][DAY-03] What was changed and when; putting the day back to the pattern asks first. */
@Composable
private fun RecordContent(day: ResolvedDay, changes: List<com.bamnat.app.data.DayChange>, vm: CalendarViewModel) {
    var confirm by remember { mutableStateOf(false) }
    if (changes.isNotEmpty()) ChangeHistory(changes)
    if (day.entry != null) {
        TextButton(onClick = { confirm = true }) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("이 날을 패턴대로 되돌리기")
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "이 날을 패턴대로 되돌릴까요?",
            message = "근무 변경, 시간, 추가 근무·휴가, 알람 설정이 지워져요. 메모와 가계부는 남아요.",
            confirmLabel = "되돌리기",
            destructive = true,
            onDismiss = { confirm = false },
            onConfirm = {
                confirm = false
                vm.resetDay(day.date)
            },
        )
    }
}

/** [SLP-01][MKT-07] Rest-gap signs for one day. Reference only: 근무표는 회사가 정해요. */
@Composable
private fun RestWarning(issues: List<com.bamnat.core.schedule.RestCheck.Issue>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("휴식 점검", style = MaterialTheme.typography.titleSmall)
            for (issue in issues) {
                Text("${issue.kind.label}: ${issue.text}", style = MaterialTheme.typography.bodyMedium)
            }
            Text("참고용이에요. 몸 상태를 먼저 살피세요.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** [MKT-08] When and what was changed on this day; folded after the first few. */
@Composable
private fun ChangeHistory(changes: List<com.bamnat.app.data.DayChange>) {
    var all by rememberSaveable { mutableStateOf(false) }
    val zone = java.time.ZoneId.systemDefault()
    val fmt = remember { java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm") }
    SectionCard(title = "변경 이력") {
        val shown = if (all) changes else changes.take(HISTORY_ROWS)
        for (c in shown) {
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(
                    java.time.Instant.ofEpochMilli(c.atMillis).atZone(zone).format(fmt),
                    style = MaterialTheme.typography.bodySmall.merge(NumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(84.dp),
                )
                Text(c.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (changes.size > HISTORY_ROWS) {
            TextButton(onClick = { all = !all }) { Text(if (all) "접기" else "${changes.size - HISTORY_ROWS}개 더 보기") }
        }
    }
}

private const val HISTORY_ROWS = 3

/**
 * [SLP-03][SLP-05][SLP-06][SLP-13] Sleep times that fit this day's shifts, each with how strong its
 * basis is, and on today a two-tap sleep log with the week's shortfall. Wording stays at
 * "일정에 맞춘 수면 시간 제안": nothing here diagnoses or treats.
 */
@Composable
private fun SleepSection(
    date: LocalDate,
    today: LocalDate,
    now: java.time.LocalDateTime,
    flow: com.bamnat.core.sleep.TodayFlow.Card?,
    tips: List<com.bamnat.core.sleep.SleepPlan.Tip>,
    log: List<com.bamnat.core.sleep.SleepEntry>,
    targetMinutes: Int,
    health: com.bamnat.app.sleep.HealthSleep.State,
    onAsleep: () -> Unit,
    onAwake: () -> Unit,
    onWokeAt: (java.time.LocalDateTime) -> Unit,
) {
    val isToday = date == today
    if (tips.isEmpty() && !isToday) return
    var askWake by remember { mutableStateOf(false) }
    SectionCard(title = "수면") {
        // [SLP-14] Now, as the card on the calendar says it.
        if (flow != null && !flow.compact) {
            Text(flow.headline, style = MaterialTheme.typography.titleSmall)
            flow.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (tips.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        for (tip in tips) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    sleepTime(tip, date),
                    style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(104.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(tip.kind.label, style = MaterialTheme.typography.bodyMedium)
                    Text("${tip.text} · ${tip.basis.label}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (tips.isNotEmpty()) {
            Text("일정에 맞춘 수면 시간 제안이에요. 몸 상태와 회사 사정에 맞게 조절하세요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        if (isToday) {
            if (tips.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val open = log.firstOrNull { it.end == null && !it.imported }
            // A sleep open for more than 16 hours missed its "깼어요": ask when instead of ending it now.
            val stale = open != null && java.time.Duration.between(open.start, now).toMinutes() > com.bamnat.core.sleep.SleepDebt.MAX_SLEEP_MINUTES
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (open != null) "${"%02d:%02d".format(open.start.hour, open.start.minute)}부터 자는 중" else "수면 기록",
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                )
                when {
                    open == null -> OutlinedButton(onClick = onAsleep) { Text("잠들었어요") }
                    stale -> androidx.compose.material3.Button(onClick = { askWake = true }) { Text("깬 시각 적기") }
                    else -> androidx.compose.material3.Button(onClick = onAwake) { Text("깼어요") }
                }
            }
            if (askWake && open != null) {
                WakeTimeDialog(open.start, now, targetMinutes, onDismiss = { askWake = false }) {
                    askWake = false
                    onWokeAt(it)
                }
            }
            val debt = remember(log, today, targetMinutes) { com.bamnat.core.sleep.SleepDebt.of(log, today, targetMinutes) }
            Text(
                if (debt.days == 0) "잠들 때와 깼을 때 한 번씩 누르면 최근 7일 수면과 근무별 평균(야간 뒤·쉬는 날)을 보여 줘요. 기록은 이 휴대폰과 백업 파일에만 남아요."
                else "최근 7일 중 ${debt.days}일 기록 · 평균 ${hoursText(debt.averageMinutes)}" +
                    if (debt.shortMinutes > 0) " · 목표보다 ${hoursText(debt.shortMinutes)} 부족" else " · 목표만큼 잤어요",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            // [SLP-16] The log, the user's own averages and fixing a record are one tap away.
            androidx.compose.material3.TextButton(
                onClick = { com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.SLEEP) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp),
            ) { Text(if (debt.days == 0) "수면 기록은 어디에 쓰이나요?" else "수면 기록 보기 · 고치기") }
            // [SLP-08] Where the numbers come from once Health Connect is on.
            val measured = remember(log, today) {
                log.count { it.imported && it.wakeDate?.let { d -> !d.isBefore(today.minusDays(com.bamnat.core.sleep.SleepDebt.WINDOW_DAYS - 1)) } == true }
            }
            healthSleepNote(health, measured)?.let { note ->
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

private fun healthSleepNote(state: com.bamnat.app.sleep.HealthSleep.State, measured: Int): String? = when (state) {
    com.bamnat.app.sleep.HealthSleep.State.OFF -> null
    com.bamnat.app.sleep.HealthSleep.State.READY ->
        if (measured > 0) "헬스 커넥트에서 수면 ${measured}번을 함께 셌어요. 직접 누른 기록과 겹치면 직접 기록을 써요."
        else "헬스 커넥트에 최근 7일 수면 기록이 없어요. 워치나 수면 앱이 헬스 커넥트에 기록하는지 확인하세요."
    com.bamnat.app.sleep.HealthSleep.State.NEEDS_PERMISSION -> "헬스 커넥트 수면 읽기 허용이 꺼져 있어요. 설정 > 수면에서 다시 켜 주세요."
    com.bamnat.app.sleep.HealthSleep.State.NEEDS_UPDATE -> "헬스 커넥트를 업데이트하면 워치 수면 기록을 함께 셀 수 있어요."
    com.bamnat.app.sleep.HealthSleep.State.UNAVAILABLE -> "이 휴대폰에서는 헬스 커넥트를 쓸 수 없어요."
    com.bamnat.app.sleep.HealthSleep.State.FAILED -> "헬스 커넥트 수면 기록을 읽지 못했어요. 잠시 뒤 다시 열어 주세요."
}

/** [SLP-14] The day's main sleep (or its nap) in a few words, for the group's summary. */
private fun tipSummary(tips: List<com.bamnat.core.sleep.SleepPlan.Tip>, date: LocalDate): String? =
    tips.firstOrNull { it.kind == com.bamnat.core.sleep.SleepPlan.Kind.SLEEP_AFTER_NIGHT || it.kind == com.bamnat.core.sleep.SleepPlan.Kind.BEDTIME }
        ?.let { "잘 시간 " + sleepTime(it, date) }
        ?: tips.firstOrNull { it.kind == com.bamnat.core.sleep.SleepPlan.Kind.NAP }?.let { "낮잠 " + sleepTime(it, date) }

private fun hoursText(minutes: Long): String = if (minutes % 60 == 0L) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"

private fun sleepTime(tip: com.bamnat.core.sleep.SleepPlan.Tip, date: LocalDate): String {
    fun hm(x: java.time.LocalDateTime) = "%02d:%02d".format(x.hour, x.minute)
    val day = when (tip.at.toLocalDate()) {
        date -> ""
        date.plusDays(1) -> "다음 날 "
        else -> "${tip.at.monthValue}/${tip.at.dayOfMonth} "
    }
    return day + hm(tip.at) + (tip.until?.let { "–" + hm(it) } ?: "")
}
