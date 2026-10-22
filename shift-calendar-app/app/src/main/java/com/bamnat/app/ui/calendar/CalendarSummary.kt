package com.bamnat.app.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ColorDot
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.money.DailySales
import com.bamnat.core.money.Job
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.CalendarStart
import com.bamnat.core.work.MonthOverview
import java.time.LocalDate
import java.time.YearMonth

/** [CAL-06] One figure of the month summary: its name, its value, an optional line under it. */
private data class Figure(val label: String, val value: String, val note: String? = null, val color: Color? = null)

/**
 * [CAL-06] The month in three figures over the calendar, in the words of the kind of work: days,
 * hours and expected pay for shifts; jobs, money earned and money owed for field work; sales, what
 * is left and the best day for a shop. A tap opens where the numbers come from. Side by side when
 * they fit, one under another on a narrow screen or a big font.
 */
@Composable
internal fun MonthSummaryBar(
    overview: MonthOverview,
    payEstimate: Long?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cal = LocalCalendarColors.current
    val month = overview.month.monthValue
    val figures = when (overview.focus) {
        CalendarFocus.SHIFTS -> listOf(
            Figure("${month}월 근무", "${overview.workDays}일",
                overview.leaveDays.takeIf { it > 0 }?.let { "휴가 ${HoursFormat.hours(it * 60).removeSuffix(".0")}일" }),
            Figure("근무 시간", HoursFormat.hours(overview.workMinutes.toDouble()) + "시간",
                overview.extraMinutes.takeIf { it > 0 }?.let { "추가 근무 ${HoursFormat.korean(it)}" }, null),
            if (payEstimate != null) Figure("예상 급여", Fmt.wonShort(payEstimate), "세전", MaterialTheme.colorScheme.primary)
            else Figure("공휴일 근무", "${overview.holidayWorkDays}일", null, if (overview.holidayWorkDays > 0) cal.holidayWork else null),
        )
        CalendarFocus.JOBS -> listOf(
            Figure("${month}월 일감", "${overview.jobs}건", "${overview.jobDays}일 일해요"),
            Figure("번 돈", Fmt.wonShort(overview.jobEarned), overview.jobReceived.takeIf { it > 0 }?.let { "받음 ${Fmt.wonShort(it)}" }, cal.income),
            Figure("받을 돈", Fmt.wonShort(overview.jobOwed), null, if (overview.jobOwed > 0) cal.warning else null),
        )
        CalendarFocus.SALES -> listOf(
            Figure("${month}월 매출", Fmt.wonShort(overview.sales), "${overview.openDays}일 적음", cal.income),
            Figure("남은 돈", Fmt.wonShort(overview.salesNet), overview.salesCosts.takeIf { it > 0 }?.let { "경비 ${Fmt.wonShort(it)}" },
                if (overview.salesNet < 0) cal.expense else null),
            Figure("하루 평균", Fmt.wonShort(overview.salesPerDay),
                overview.bestDay?.let { "최고 ${it.monthValue}/${it.dayOfMonth} ${Fmt.wonShort(overview.bestSales)}" }),
        )
        // [INV-06] The investor's month has its own bar (InvestMonthBar); this one only names the tab.
        CalendarFocus.INVEST -> listOf(Figure("${month}월", "투자·수익 탭에서", null))
    }
    val spoken = figures.joinToString(", ") { f -> "${f.label} ${f.value}" + (f.note?.let { " $it" } ?: "") }
    val target = when (overview.focus) {
        CalendarFocus.SHIFTS -> "근무·급여 열기"
        else -> "돈 탭 열기"
    }
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            // [CAL-06] One stop for TalkBack: the three figures and where the tap goes.
            .clearAndSetSemantics { contentDescription = "$spoken. $target" },
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val big = LocalDensity.current.fontScale > 1.3f
            // [CAL-06] Three figures fit side by side from about 250dp; a big font stacks them.
            val stacked = big || maxWidth < 250.dp
            // Moving months fades the numbers instead of snapping them.
            AnimatedContent(
                targetState = figures,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "month-summary",
            ) { shown ->
                if (stacked) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                        shown.forEach { f ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(f.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(f.value, style = MaterialTheme.typography.titleMedium.merge(NumberStyle),
                                        color = f.color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                    f.note?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        shown.forEachIndexed { i, f ->
                            if (i > 0) Box(Modifier.width(1.dp).height(36.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
                            FigureColumn(f, Modifier.weight(1f).padding(horizontal = 6.dp))
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun FigureColumn(f: Figure, modifier: Modifier) {
    Column(modifier) {
        Text(f.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        // [CAL-06] A long amount shrinks to fit its third instead of being cut.
        BasicText(
            f.value,
            style = MaterialTheme.typography.titleMedium.merge(NumberStyle).merge(
                TextStyle(color = f.color ?: MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold),
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = 17.sp, stepSize = 0.5.sp),
        )
        f.note?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * [CAL-06][CAL-08] The row over the weekdays: what the marks mean (ⓘ), and the month as a grid
 * or as a list.
 */
@Composable
internal fun CalendarViewSwitch(listView: Boolean, onListView: (Boolean) -> Unit, onLegend: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onLegend) { Icon(Icons.Outlined.Info, "달력 표시 안내", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        // [CAL-08] One button that shows the other view, so the month's shift counts keep their room beside it.
        FilledTonalIconButton(
            onClick = { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onListView(!listView) },
            modifier = Modifier.semantics { contentDescription = if (listView) "달력으로 보기" else "목록으로 보기" },
        ) {
            AnimatedContent(listView, transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(90)) }, label = "view-switch") { list ->
                Icon(if (list) Icons.Filled.CalendarMonth else Icons.AutoMirrored.Filled.ViewList, null, Modifier.size(20.dp))
            }
        }
    }
}

/**
 * [CAL-06] What every mark on a day means, for the kind of work on screen: today, the picked day,
 * the shifts, leave, extra work, jobs, sales, alarms off, changes, memos, holidays and the hour limit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarLegendSheet(focus: CalendarFocus, snapshot: ScheduleSnapshot, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("달력 표시 안내", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }.padding(bottom = 8.dp))
            LegendRow("오늘", "날짜에 색이 칠해진 동그라미") {
                Box(Modifier.size(22.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                    Text("19", color = scheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            LegendRow("고른 날", "테두리와 옅은 색") {
                Box(Modifier.size(width = 30.dp, height = 24.dp).clip(RoundedCornerShape(6.dp)).background(scheme.primary.copy(alpha = 0.08f))
                    .border(2.dp, scheme.primary, RoundedCornerShape(6.dp)))
            }
            LegendRow("공휴일·일요일", "빨간 날짜와 공휴일 이름") { Text("9", color = cal.sunday, fontWeight = FontWeight.Bold) }
            when (focus) {
                CalendarFocus.SHIFTS -> {
                    val types = snapshot.shiftTypes.take(6)
                    if (types.isNotEmpty()) {
                        LegendRow("근무", types.joinToString(" · ") { it.name }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                types.take(2).forEach { ShiftBadge(it, size = 18.dp, outlined = it.isOff, showIcon = false) }
                            }
                        }
                    }
                    LegendRow("연차·반차", "하루 쉬면 칸 전체, 반차는 모서리") { LeaveMark(Leave(LeaveKind.ANNUAL, 4), size = 20.dp) }
                    snapshot.kinds.kinds.firstOrNull()?.let { k ->
                        LegendRow("추가 근무", "잔업·특근·대근은 모서리 표시, 시간은 +2h처럼") { ExtraKindMark(k, size = 18.dp) }
                    }
                    LegendRow("부업 일감", "돈 탭에서 적은 일감이 있는 날 · 빨강은 받을 돈이 남은 일감") { CornerMark(Corner.Job(false)) }
                    LegendRow("주 시간 넘음", "왼쪽 빨간 막대: 그 주 근무가 한도를 넘었어요") {
                        Box(Modifier.size(width = 4.dp, height = 22.dp).clip(RoundedCornerShape(2.dp)).background(cal.warning))
                    }
                }
                CalendarFocus.JOBS -> {
                    LegendRow("일감", "일감이 있는 날 · 2건이면 일2") { JobBadge(1, owed = false) }
                    LegendRow("받을 돈 남음", "아직 다 받지 못한 일감") { JobBadge(1, owed = true) }
                    LegendRow("여러 날 일감", "막대가 이어진 날까지 같은 일감이에요") {
                        Box(Modifier.size(width = 40.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(scheme.secondaryContainer))
                    }
                    LegendRow("받은 돈", "칸 아래 초록 숫자 (만 원 단위)") { Text("45만", color = cal.income, fontWeight = FontWeight.Bold, style = NumberStyle) }
                }
                CalendarFocus.INVEST -> {
                    LegendRow("수익 난 날", "빨간 칸과 +숫자 · 많이 벌수록 진해요") { Text("+12만", color = cal.sunday, fontWeight = FontWeight.Bold, style = NumberStyle) }
                    LegendRow("손실 난 날", "파란 칸과 −숫자") { Text("−5만", color = cal.saturday, fontWeight = FontWeight.Bold, style = NumberStyle) }
                    LegendRow("매매한 날", "매2는 그날 매매 2건") { Text("매", fontWeight = FontWeight.Bold) }
                    LegendRow("투자 일정", "왼쪽 위 점: 배당락일·실적 발표처럼 적어 둔 날") {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(scheme.tertiary))
                    }
                }
                CalendarFocus.SALES -> {
                    LegendRow("매출", "칸 아래 초록 숫자 (만 원 단위)") { Text("120만", color = cal.income, fontWeight = FontWeight.Bold, style = NumberStyle) }
                    LegendRow("잘 된 날", "매출이 많을수록 칸이 진해져요") {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf(0.3f, 0.6f, 1f).forEach { a -> Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(cal.income.copy(alpha = a * 0.3f))) }
                        }
                    }
                }
            }
            // Alarms and changes from a pattern only mean something where shifts are kept.
            val shifts = focus == CalendarFocus.SHIFTS || snapshot.hasPattern
            if (shifts) {
                LegendRow("알람 끔", "이 날은 알람이 울리지 않아요") { Icon(Icons.Filled.AlarmOff, null, tint = cal.alarmOff, modifier = Modifier.size(18.dp)) }
                LegendRow("직접 바꿈", "패턴과 다르게 바꾼 날") { Box(Modifier.size(7.dp).clip(CircleShape).background(cal.changed)) }
            }
            LegendRow("메모", "메모가 있는 날") { ColorDot(cal.memoDot, size = 7.dp) }
            Text(
                when {
                    focus == CalendarFocus.JOBS && !shifts -> "날짜를 길게 누르면 그날 일감이나 메모를 바로 적을 수 있어요. 목록으로 보면 한 달 일감이 차례로 보여요."
                    focus == CalendarFocus.SALES && !shifts -> "날짜를 길게 누르면 그날 매출이나 메모를 바로 적을 수 있어요. 목록으로 보면 주마다 매출 합계가 보여요."
                    else -> "날짜를 길게 누르면 근무·휴가·알람·메모를 바로 바꿀 수 있어요. 빠른 입력에서는 길게 눌러 시작하고 끝 날짜를 누르면 사이가 한 번에 칠해져요."
                },
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun LegendRow(title: String, text: String, mark: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { mark() }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [CAL-05] An empty calendar's first step, in the words of the kind of work. */
@Composable
internal fun CalendarStartCard(start: CalendarStart, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(start.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.semantics { heading() })
            Text(start.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Button(onClick = onAction) { Text(start.action) }
        }
    }
}

/**
 * [CAL-07] Weekday work set by the week runs into public holidays: asked once a month whether those
 * days are off, with the dates named. Either answer can be taken back.
 */
@Composable
internal fun HolidayWorkCard(month: YearMonth, dates: List<LocalDate>, onRest: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    val cal = LocalCalendarColors.current
    val names = dates.joinToString(", ") { d -> "${d.dayOfMonth}일 ${com.bamnat.core.holiday.KoreanHolidays.nameOf(d).orEmpty()}".trim() }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, cal.sunday.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.EventBusy, null, tint = cal.sunday, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("${month.monthValue}월 공휴일 ${dates.size}일이 근무로 잡혀 있어요", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
            }
            Text("$names · 쉬는 날이면 한 번에 바꿀 수 있어요.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onKeep) { Text("그대로 일해요") }
                Spacer(Modifier.width(4.dp))
                Button(onClick = onRest) { Text("쉬는 날로") }
            }
        }
    }
}

/**
 * [CAL-05] Today for field work, where 오늘의 흐름 shows shifts: today's jobs with their times and
 * money, or the next job when today is free.
 */
@Composable
internal fun TodayJobsCard(today: LocalDate, jobs: List<Job>, onOpen: (LocalDate) -> Unit, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val cal = LocalCalendarColors.current
    val todays = remember(jobs, today) { jobs.filter { it.covers(today) }.sortedWith(compareBy({ it.start }, { it.date })) }
    val next = remember(jobs, today) { jobs.filter { it.date.isAfter(today) }.minWithOrNull(compareBy({ it.date }, { it.start })) }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Work, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (todays.isEmpty()) "오늘은 잡힌 일감이 없어요" else "오늘 일감 ${todays.size}건",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = onAdd) { Text("일감 적기") }
            }
            for (j in todays.take(3)) {
                JobRow(j, today, onClick = { onOpen(today) }, owedColor = cal.warning)
            }
            if (todays.isEmpty() && next != null) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val gap = (next.date.toEpochDay() - today.toEpochDay()).toInt()
                    Text(
                        "다음 일감 · ${if (gap == 1) "내일" else "${Fmt.monthDay(next.date)} (${gap}일 뒤)"}" +
                            (next.start?.let { " ${Fmt.time(it)}" } ?: "") + " ${next.title}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onOpen(next.date) }) { Text("보기") }
                }
            }
        }
    }
}

@Composable
private fun JobRow(j: Job, today: LocalDate, onClick: () -> Unit, owedColor: Color) {
    val time = j.start?.let { Fmt.time(it) } ?: "종일"
    val sub = listOfNotNull(j.place.ifBlank { null },
        if (j.days > 1) "${j.days}일 중 ${(today.toEpochDay() - j.date.toEpochDay()).toInt() + 1}일째" else null).joinToString(" · ")
    val owed = j.unpaid.takeIf { it > 0 }?.let { "받을 돈 ${Fmt.wonShort(it)}" }
    // [CAL-05][VIS-02] At a big font the time, the job and the money each take a line instead of squeezing into one.
    val big = LocalDensity.current.fontScale > 1.3f
    Surface(onClick = onClick, color = Color.Transparent, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        if (big) {
            Column(Modifier.heightIn(min = 48.dp).padding(end = 8.dp, top = 4.dp, bottom = 4.dp)) {
                Text(time, style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = MaterialTheme.colorScheme.primary)
                Text(j.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                owed?.let { Text(it, style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = owedColor) }
            }
        } else Row(Modifier.heightIn(min = 48.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(time, style = MaterialTheme.typography.labelLarge.merge(NumberStyle), color = MaterialTheme.colorScheme.primary,
                maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 52.dp).padding(end = 6.dp))
            Column(Modifier.weight(1f)) {
                Text(j.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            owed?.let { Text(it, style = MaterialTheme.typography.labelMedium.merge(NumberStyle), color = owedColor, maxLines = 1) }
        }
    }
}

/**
 * [CAL-05] Today for a shop: today's sales against the same weekday last week, and one tap to write
 * or fix them.
 */
@Composable
internal fun TodaySalesCard(today: LocalDate, sales: List<DailySales>, onWrite: () -> Unit, modifier: Modifier = Modifier) {
    val cal = LocalCalendarColors.current
    val now = remember(sales, today) { sales.firstOrNull { it.date == today } }
    val weekAgo = remember(sales, today) { sales.firstOrNull { it.date == today.minusWeeks(1) } }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text("오늘 매출", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(now?.let { Fmt.won(it.sales) } ?: "아직 안 적었어요",
                    style = if (now != null) MaterialTheme.typography.titleLarge.merge(NumberStyle) else MaterialTheme.typography.titleMedium,
                    color = if (now != null) cal.income else MaterialTheme.colorScheme.onSurface)
                val compare = when {
                    now != null && weekAgo != null && weekAgo.sales > 0 -> {
                        val diff = now.sales - weekAgo.sales
                        "지난주 ${Fmt.dayOfWeekShort(today.dayOfWeek)}요일보다 " + if (diff >= 0) "${Fmt.wonShort(diff)} 많아요" else "${Fmt.wonShort(-diff)} 적어요"
                    }
                    weekAgo != null -> "지난주 ${Fmt.dayOfWeekShort(today.dayOfWeek)}요일 ${Fmt.wonShort(weekAgo.sales)}"
                    else -> null
                }
                compare?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (now == null) Button(onClick = onWrite) { Text("매출 적기") } else OutlinedButton(onClick = onWrite) { Text("고치기") }
        }
    }
}

/** [CAL-13] The added calendar on screen does not ring; one line says so and turns it back on. */
@Composable
internal fun CalendarAlarmsOffLine(name: String, onTurnOn: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onTurnOn,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AlarmOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("‘$name’ 달력은 알람이 꺼져 있어요", style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("켜기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
