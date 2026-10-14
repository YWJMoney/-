package com.shiftmate.calendar.ui.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.shiftmate.calendar.alarm.Notifications
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shiftmate.calendar.data.AppSettings
import com.shiftmate.calendar.data.ScheduleSnapshot
import com.shiftmate.calendar.ui.Fmt
import com.shiftmate.calendar.ui.components.ShiftBadge
import com.shiftmate.calendar.ui.components.StatTile
import com.shiftmate.calendar.ui.components.WarningBanner
import com.shiftmate.core.hours.HoursFormat
import com.shiftmate.core.hours.WorkHoursCalculator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private const val PAGE_COUNT = 2400
private const val CENTER_PAGE = PAGE_COUNT / 2

/** Fixed, so a saved pager page always maps back to the same month (even after a restart). */
private val BASE_MONTH: YearMonth = YearMonth.of(2026, 1)

private fun pageToMonth(base: YearMonth, page: Int): YearMonth = base.plusMonths((page - CENTER_PAGE).toLong())
private fun monthToPage(base: YearMonth, month: YearMonth): Int =
    (CENTER_PAGE + ChronoUnit.MONTHS.between(base, month)).toInt().coerceIn(0, PAGE_COUNT - 1)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(
    vm: CalendarViewModel,
    wide: Boolean,
    snackbarHostState: SnackbarHostState,
    onOpenMemo: (Long) -> Unit,
    onNewMemo: (LocalDate) -> Unit,
    onOpenPattern: () -> Unit,
    onOpenShiftTypes: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    // Re-checked on every return to the app, since the user may change it in system settings.
    var notificationsAllowed by remember { mutableStateOf(Notifications.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsAllowed = Notifications.canPost(context) }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val paint by vm.paint.collectAsStateWithLifecycle()
    val memoDates by vm.memoDates.collectAsStateWithLifecycle()

    // Re-read the date every minute so "today" moves at midnight while the app is open.
    val today by produceState(LocalDate.now()) {
        while (true) {
            delay(60_000)
            value = LocalDate.now()
        }
    }

    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            val result = snackbarHostState.showSnackbar(
                message = msg.message,
                actionLabel = if (msg.undo != null) "되돌리기" else null,
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.runUndo(msg)
        }
    }

    val snap = snapshot
    if (snap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val calculator = remember(snap, settings.weekStart, settings.limits) {
        WorkHoursCalculator(snap.resolver, settings.weekStart, settings.limits)
    }

    val baseMonth = BASE_MONTH
    val pagerState = rememberPagerState(initialPage = monthToPage(baseMonth, YearMonth.from(selected))) { PAGE_COUNT }
    val visibleMonth = pageToMonth(baseMonth, pagerState.currentPage)
    val scope = rememberCoroutineScope()
    val goToMonth: (YearMonth) -> Unit = { m -> scope.launch { pagerState.animateScrollToPage(monthToPage(baseMonth, m)) } }

    // After swiping to another month, select today (if shown) or the 1st so the detail follows.
    LaunchedEffect(pagerState, baseMonth) {
        androidx.compose.runtime.snapshotFlow { pagerState.settledPage }.collect { page ->
            val m = pageToMonth(baseMonth, page)
            val sel = vm.selected.value
            if (YearMonth.from(sel) != m) {
                val now = LocalDate.now()
                vm.select(if (YearMonth.from(now) == m) now else m.atDay(1))
            }
        }
    }

    val onDayClick: (LocalDate) -> Unit = { date ->
        vm.onDayTapped(date)
        val m = YearMonth.from(date)
        if (m != visibleMonth) goToMonth(m)
    }

    var showMonthPicker by remember { mutableStateOf(false) }

    val calendarPart: @Composable () -> Unit = {
        CalendarHeader(
            month = visibleMonth,
            onPrev = { goToMonth(visibleMonth.minusMonths(1)) },
            onNext = { goToMonth(visibleMonth.plusMonths(1)) },
            onToday = {
                vm.select(LocalDate.now())
                goToMonth(YearMonth.now())
            },
            onPickMonth = { showMonthPicker = true },
            onOpenPattern = onOpenPattern,
        )
        if (snap.isEmpty) {
            EmptyPatternCard(onOpenPattern)
        }
        if (!notificationsAllowed && snap.shiftTypes.any { it.alarmEnabled }) {
            WarningBanner(
                title = "알림이 꺼져 있어 근무 알람이 보이지 않아요",
                message = "눌러서 설정에서 알림을 허용해 주세요.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onOpenSettings),
            )
        }
        WeekdayHeader(settings.weekStart, Modifier.padding(horizontal = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val cellHeight = (maxWidth / 7 * 1.08f).coerceIn(54.dp, 92.dp)
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(cellHeight * GRID_ROWS),
                beyondViewportPageCount = 1,
                key = { it },
            ) { page ->
                MonthGrid(
                    month = pageToMonth(baseMonth, page),
                    snapshot = snap,
                    calculator = calculator,
                    selected = selected,
                    today = today,
                    memoDates = memoDates,
                    showHours = settings.showHoursInCells,
                    cellHeight = cellHeight,
                    onDayClick = onDayClick,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        HoursSummary(
            calculator = calculator,
            settings = settings,
            selected = selected,
            month = visibleMonth,
            onOpenStats = onOpenStats,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    val detailPart: @Composable () -> Unit = {
        DayDetail(
            date = selected,
            today = today,
            snapshot = snap,
            vm = vm,
            onOpenMemo = onOpenMemo,
            onNewMemo = onNewMemo,
            onOpenShiftTypes = onOpenShiftTypes,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }

    val bottomSpace = if (paint != null) 132.dp else 88.dp
    Box(Modifier.fillMaxSize()) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = bottomSpace),
                ) { calendarPart() }
                VerticalDivider()
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 12.dp, bottom = bottomSpace),
                ) { detailPart() }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = bottomSpace),
            ) {
                calendarPart()
                Spacer(Modifier.height(16.dp))
                detailPart()
            }
        }

        AnimatedVisibility(
            visible = paint == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            val first = snap.shiftTypes.firstOrNull()
            ExtendedFloatingActionButton(
                onClick = { vm.setPaint(first?.let { PaintTool.Shift(it.id) } ?: PaintTool.Clear) },
                icon = { Icon(Icons.Filled.EditCalendar, contentDescription = null) },
                text = { Text("빠른 입력") },
            )
        }
        AnimatedVisibility(
            visible = paint != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PaintBar(snapshot = snap, tool = paint, onTool = vm::setPaint)
        }
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            initial = visibleMonth,
            onDismiss = { showMonthPicker = false },
            onPick = { m ->
                showMonthPicker = false
                goToMonth(m)
            },
        )
    }
}

@Composable
private fun CalendarHeader(
    month: YearMonth,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onPickMonth: () -> Unit,
    onOpenPattern: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
        Text(
            Fmt.month(month),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onPickMonth)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToday) { Text("오늘") }
        IconButton(onClick = onOpenPattern) { Icon(Icons.Filled.EventRepeat, "근무 패턴 설정") }
    }
}

@Composable
private fun EmptyPatternCard(onOpenPattern: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("근무 패턴을 등록해 보세요", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "주주야야비휴 같은 교대 순서나 요일별 근무를 한 번만 입력하면 달력이 자동으로 채워져요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = onOpenPattern) {
                Icon(Icons.Filled.Autorenew, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("패턴 설정하기")
            }
        }
    }
}

@Composable
private fun HoursSummary(
    calculator: WorkHoursCalculator,
    settings: AppSettings,
    selected: LocalDate,
    month: YearMonth,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val week = remember(calculator, selected) { calculator.week(selected) }
    val monthSummary = remember(calculator, month) { calculator.month(month) }
    val three = remember(calculator, month) { calculator.threeMonths(month) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                label = "선택한 주",
                value = HoursFormat.decimalHours(week.totalMinutes.toDouble()) + "시간",
                caption = "${Fmt.shortDate(week.start)}–${Fmt.shortDate(week.end)}",
                warning = week.overLimit,
                onClick = onOpenStats,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "${month.monthValue}월 합계",
                value = HoursFormat.decimalHours(monthSummary.totalMinutes.toDouble()) + "시간",
                caption = "근무 ${monthSummary.workDays}일",
                onClick = onOpenStats,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "3개월 주평균",
                value = HoursFormat.decimalHours(three.averageWeeklyMinutes) + "시간",
                caption = "${three.firstMonth.monthValue}–${three.lastMonth.monthValue}월",
                warning = three.overLimit,
                onClick = onOpenStats,
                modifier = Modifier.weight(1f),
            )
        }
        if (week.overLimit) {
            WarningBanner(
                title = "주 ${settings.weeklyLimitHours}시간 초과",
                message = "${Fmt.shortDate(week.start)}–${Fmt.shortDate(week.end)} 근무가 " +
                    "${HoursFormat.korean(week.totalMinutes)}이에요.",
            )
        } else {
            val others = monthSummary.weeksOverLimit
            if (others.isNotEmpty()) {
                WarningBanner(
                    title = "주 ${settings.weeklyLimitHours}시간 초과 주가 있어요",
                    message = others.joinToString(", ") { "${Fmt.shortDate(it.start)}주 ${HoursFormat.decimalHours(it.totalMinutes.toDouble())}시간" },
                )
            }
        }
        if (three.overLimit) {
            WarningBanner(
                title = "3개월 평균 주 ${settings.averageLimitHours}시간 초과",
                message = "${three.firstMonth.monthValue}–${three.lastMonth.monthValue}월 평균이 " +
                    "주 ${HoursFormat.decimalHours(three.averageWeeklyMinutes)}시간이에요.",
            )
        }
    }
}

@Composable
private fun PaintBar(snapshot: ScheduleSnapshot, tool: PaintTool?, onTool: (PaintTool?) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("빠른 입력", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "날짜를 누르면 바로 들어가요. 같은 근무를 한 번 더 누르면 취소돼요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onTool(null) }) { Icon(Icons.Filled.Close, "빠른 입력 끝내기") }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (type in snapshot.shiftTypes) {
                    val t = PaintTool.Shift(type.id)
                    FilterChip(
                        selected = tool == t,
                        onClick = { onTool(t) },
                        label = { Text(type.name) },
                        leadingIcon = { ShiftBadge(type, size = 18.dp, outlined = type.isOff) },
                    )
                }
                if (snapshot.shiftTypes.none { it.isOff }) {
                    FilterChip(
                        selected = tool == PaintTool.Off,
                        onClick = { onTool(PaintTool.Off) },
                        label = { Text("쉬는 날") },
                    )
                }
                FilterChip(
                    selected = tool == PaintTool.Clear,
                    onClick = { onTool(PaintTool.Clear) },
                    label = { Text("패턴대로") },
                    leadingIcon = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun MonthPickerDialog(initial: YearMonth, onDismiss: () -> Unit, onPick: (YearMonth) -> Unit) {
    var year by remember { mutableStateOf(initial.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 해") }
                Text("${year}년", modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                IconButton(onClick = { year++ }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 해") }
            }
        },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.widthIn(max = 360.dp).height(180.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items((1..12).toList()) { m ->
                    val ym = YearMonth.of(year, m)
                    val isCurrent = ym == initial
                    Surface(
                        onClick = { onPick(ym) },
                        shape = MaterialTheme.shapes.medium,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.height(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${m}월", fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(YearMonth.now()) }) { Text("이번 달") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("닫기") } },
    )
}
