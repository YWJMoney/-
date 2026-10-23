package com.bamnat.app.ui.calendar

import com.bamnat.app.ui.components.labelWidth
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.AssistChip
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.schedule.ResolvedDay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditCalendar
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import com.bamnat.app.appContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WarningAmber
import com.bamnat.app.share.MonthImage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bamnat.app.alarm.Notifications
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.rules
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.appViewModel
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.SystemSettings
import com.bamnat.app.ui.components.rememberNotificationAsk
import com.bamnat.app.NoticeLinks
import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.app.review.InAppReview
import com.bamnat.app.review.InAppReview.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.filled.AlarmOff
import com.bamnat.core.work.calendarStart
import java.time.LocalDate
import com.bamnat.app.ui.theme.LocalCalendarColors
import java.time.YearMonth
import com.bamnat.core.model.ShiftOverride
import java.time.temporal.ChronoUnit
import java.time.DayOfWeek
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.bamnat.app.data.PhoneDayView

internal const val PAGE_COUNT = 2400
/** [CAL-01] Alarm warnings show while an alarm is due within this many days. */
private const val ALARM_WARNING_DAYS = 7L
internal const val CENTER_PAGE = PAGE_COUNT / 2
/** [VIS-02] A month slides this long after ‹ ›. */
private const val MONTH_SLIDE_MILLIS = 220
/** [CAL-03] How far a finger goes sideways on the week before it turns. */
private val WEEK_SWIPE = 48.dp
/** [CAL-01] The unfolded day panel: room for "10월 18일 (일) 오늘" and its arrows, never most of the screen. */
private val DAY_PANEL_MIN_WIDTH = 280.dp
private val DAY_PANEL_MAX_WIDTH = 420.dp
/** [CAL-15] The floating button's height and the room it keeps from the screen's edge. */
private val FAB_HEIGHT = 56.dp
private val FAB_MARGIN = 16.dp
/** [CAL-15] Between the month's last row and the button riding under it. */
private val FAB_DOCK_GAP = 8.dp
/** [CAL-15] Room under the month for the button: the gap above it, its height and the same gap below. */
private val FAB_ROOM = FAB_DOCK_GAP + FAB_HEIGHT + FAB_DOCK_GAP
/** [DAILY-09] Above the month in a half-open fold's upper half: its 2026년 10월 row and weekday names. */
private val MONTH_ROW_ROOM = 96.dp
private const val FAB_STEP_MILLIS = 180
/** [CAL-17] The tip comes just after the tap has shown its day, so it is read as being about the tap. */
private const val TIP_DELAY_MILLIS = 400L

/** Fixed, so a saved pager page always maps back to the same month (even after a restart). */
internal val BASE_MONTH: YearMonth = YearMonth.of(2026, 1)

internal fun pageToMonth(base: YearMonth, page: Int): YearMonth = base.plusMonths((page - CENTER_PAGE).toLong())
internal fun monthToPage(base: YearMonth, month: YearMonth): Int =
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
    onOpenMoney: () -> Unit = {},
    onOpenPay: () -> Unit = onOpenStats,
    onOpenSettings: () -> Unit,
    onOpenExtraKinds: () -> Unit = {},
    /** [MCAL-02] Starts the setup of a new calendar with this id. */
    onSetupCalendar: (String) -> Unit = {},
    /** [MCAL-04] The calendar list: order, names, removing one. */
    onManageCalendars: () -> Unit = {},
    /** [CALS-01] One of the user's calendars: its name, colour, kind of work and settings. */
    onEditCalendar: (String) -> Unit = {},
    /** [COMN-01] 모든 달력 합쳐 보기. */
    onOpenCombined: () -> Unit = {},
) {
    // [DAILY-09] Half open like a laptop (Flex): the day sits below the hinge as it sits beside the month when
    // unfolded, so everything a wide screen does (no sheet, the day always shown) holds there too.
    val tabletop = com.bamnat.app.ui.LocalTabletop.current
    @Suppress("NAME_SHADOWING") val wide = wide || tabletop != null
    val context = LocalContext.current
    // Re-checked on every return to the app, since the user may change it in system settings.
    var notificationsAllowed by remember { mutableStateOf(Notifications.canPost(context)) }
    val askNotifications = rememberNotificationAsk()
    var fullScreenAllowed by remember { mutableStateOf(Notifications.canUseFullScreen(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed = Notifications.canPost(context)
        fullScreenAllowed = Notifications.canUseFullScreen(context)
    }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    // [PERF-01] The month is there: Android's "Fully drawn" for this start, and the startup budget's last step.
    androidx.activity.compose.ReportDrawnWhen { snapshot != null }
    LaunchedEffect(snapshot != null) { if (snapshot != null) com.bamnat.app.StartupTrace.mark(com.bamnat.app.StartupTrace.Step.CALENDAR) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    // [CUST-01] The page's parts in the user's order (화면 꾸미기).
    val calendarParts = remember(settings) { settings.calendarParts() }
    fun partShown(p: CalendarPart) = calendarParts.firstOrNull { it.part == p }?.shown != false
    val selected by vm.selected.collectAsStateWithLifecycle()
    val paint by vm.paint.collectAsStateWithLifecycle()
    var typingMonth by remember { mutableStateOf<YearMonth?>(null) }
    // [REST-01] 근무표 점검 기준, opened from the check's card.
    var rulesOpen by remember { mutableStateOf(false) }
    val lastPaint by vm.lastPaint.collectAsStateWithLifecycle()
    val coverAsk by vm.coverAsk.collectAsStateWithLifecycle()
    // [CUST-01] 화면 꾸미기: the page's parts in the user's order, and the sheet that changes them.
    var editingLayout by remember { mutableStateOf(false) }
    // [CUST-01] 화면 꾸미기 opened from the help search or the update notice.
    val featureFocus by com.bamnat.app.ui.guide.FeatureLinks.focus.collectAsStateWithLifecycle()
    LaunchedEffect(featureFocus) {
        if (featureFocus == "customize" && com.bamnat.app.ui.guide.FeatureLinks.take("customize")) editingLayout = true
    }
    val memoDates by vm.memoDates.collectAsStateWithLifecycle()
    val moneyBook by vm.moneyBook.collectAsStateWithLifecycle()
    val datedMemos by vm.datedMemos.collectAsStateWithLifecycle()
    // [CAL-05..CAL-13] What the calendar is about, its view, its alarms and quick input's marks.
    val focus by vm.focus.collectAsStateWithLifecycle()
    val listView by vm.listView.collectAsStateWithLifecycle()
    val calendarAlarmsOn by vm.calendarAlarmsOn.collectAsStateWithLifecycle()
    val mark by vm.mark.collectAsStateWithLifecycle()
    val rangeFrom by vm.rangeFrom.collectAsStateWithLifecycle()
    val holidaysKept by vm.holidaysKept.collectAsStateWithLifecycle()
    val payProfile by vm.payProfile.collectAsStateWithLifecycle()
    var legendOpen by remember { mutableStateOf(false) }
    // [SHR-02] Calendars others shared, switched to like accounts.
    val received = appViewModel { ReceivedViewModel(it) }
    val receivedList by received.calendars.collectAsStateWithLifecycle()
    val viewingReceived by received.viewing.collectAsStateWithLifecycle()
    val openingLink by received.opening.collectAsStateWithLifecycle()
    var switching by remember { mutableStateOf(false) }
    // [MCAL-01] The user's own calendars, the one on screen, and the list's order with the received ones.
    val myCalendars by vm.calendars.collectAsStateWithLifecycle()
    val activeCalendar by vm.activeCalendar.collectAsStateWithLifecycle()
    val calendarOrder by vm.calendarOrder.collectAsStateWithLifecycle()
    val onAddCalendar = rememberAddCalendar(context.appContainer, onSetupCalendar)
    // A link id to open, "" to paste one, null when closed.
    var adding by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openingLink) {
        openingLink?.let { id ->
            adding = id
            received.consumeOpening()
        }
    }
    // [FEAT-17][DAY-04] Cells can show the start of the day's first memo under the date.
    val memoTexts = remember(datedMemos, settings.memoInCells) {
        if (!settings.memoInCells) emptyMap()
        else datedMemos.groupBy { it.date!! }.mapValues { (_, l) -> l.first().let { m -> m.title.ifBlank { m.preview } }.trim().take(4) }
            .filterValues { it.isNotEmpty() }
    }
    var quickMoney by remember { mutableStateOf(false) }
    // [INV-01] An investor calendar's trades, cells and sheets.
    val investVm = if (focus == com.bamnat.core.work.CalendarFocus.INVEST) com.bamnat.app.ui.invest.investViewModel(activeCalendar.id) else null
    val investState = investVm?.state?.collectAsStateWithLifecycle()?.value
    val investCells = remember(investState) { investState?.takeIf { it.loaded }?.let { com.bamnat.app.ui.invest.InvestCells.of(it) } }
    var investSheet by remember { mutableStateOf<com.bamnat.app.ui.invest.InvestSheet?>(null) }

    // Re-read the date every minute so "today" moves at midnight while the app is open.
    val today by produceState(LocalDate.now()) {
        while (true) {
            delay(60_000)
            value = LocalDate.now()
        }
    }

    // [OPS-20] Ask for a store review once, shortly after the first pattern was saved.
    // [DAILY-17] And once more after the first pay month is closed, a month or more after the first.
    val reviewMoment = InAppReview.due(settings, LocalDate.now())
    LaunchedEffect(reviewMoment) {
        if (reviewMoment != null) {
            delay(1500)
            context.findActivity()?.let { InAppReview.ask(it) }
            vm.markReviewAsked(reviewMoment)
        }
    }

    // On phones the day opens in a sheet over the calendar. [285] Saved, so it is still open after a trip to the
    // memo editor, pay or shift types and back.
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    // [CAL-03] On phones set to 그 주만 보기, a tapped date folds the month to its week with the day under it.
    var weekOnly by rememberSaveable { mutableStateOf(false) }
    val selectedNow: () -> LocalDate = { vm.selected.value }
    val dayView = settings.phoneDayView
    // [DAY-03] While the sheet is open its own snackbar says what happened, above the sheet, not hidden under it.
    val sheetSnackbar = remember { SnackbarHostState() }
    val messageHost by rememberUpdatedState(if (sheetOpen && !wide) sheetSnackbar else snackbarHostState)
    LaunchedEffect(vm) {
        // [CAL-10] The newest change replaces a message still showing, so undo always means the last thing done.
        vm.messages.collectLatest { msg ->
            val result = messageHost.showSnackbar(
                message = msg.message,
                actionLabel = if (msg.undo != null) msg.actionLabel else null,
                duration = if (msg.undo != null && msg.actionLabel != "되돌리기") SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) vm.runUndo(msg)
        }
    }

    LaunchedEffect(vm) { vm.openMemo.collect { onOpenMemo(it) } }

    val snap = snapshot
    if (snap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val pendingSetup by vm.setupDraft.collectAsStateWithLifecycle()
    // [WEEK-01] Hours always count Monday to Sunday, the week the law means; 한 주의 시작 only moves the grid.
    val calculator = remember(snap, settings.limits) {
        WorkHoursCalculator(snap.resolver, java.time.DayOfWeek.MONDAY, settings.limits)
    }
    // [CAL-24] A calendar of 교대 근무 whose order repeats can be split into teams: 모든 조 is on the month itself.
    val openTeams: (() -> Unit)? = remember(snap, settings.workStyle, viewingReceived) {
        val rotation = rotationOf(snap, LocalDate.now())
        if (rotation != null && viewingReceived == null && settings.workStyle == com.bamnat.core.work.WorkStyle.SHIFT) {
            { com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.TEAMS) }
        } else null
    }
    // [HRLY-02][HRLY-03] An 알바 calendar's cells show times and pay, and each week its 주휴.
    val hourlyCells = remember(settings.workStyle, payProfile) {
        if (settings.workStyle != com.bamnat.core.work.WorkStyle.HOURLY) null
        else HourlyCells(payProfile, payProfile?.holidayWeekFrom ?: java.time.DayOfWeek.MONDAY)
    }
    // [CAL-14] Stable references, so the cells' painting hooks never change under them.
    val paintDays: (List<LocalDate>) -> Unit = remember(vm) { vm::paintDays }
    val startRange: (LocalDate) -> Unit = remember(vm) { vm::startRange }

    val baseMonth = BASE_MONTH
    val shownMonth by vm.shownMonth.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = monthToPage(baseMonth, shownMonth)) { PAGE_COUNT }
    val visibleMonth = pageToMonth(baseMonth, pagerState.currentPage)
    // [CAL-06] The month on screen in the kind of work's own figures; pay is worked out off the main thread.
    val overview = remember(snap, visibleMonth, focus, moneyBook) {
        com.bamnat.core.work.MonthOverview.of(visibleMonth, focus, snap.resolver, moneyBook.jobs, moneyBook.sales)
    }
    val payEstimate by produceState<Long?>(null, payProfile, snap, visibleMonth, focus) {
        value = null
        if (focus != com.bamnat.core.work.CalendarFocus.SHIFTS || payProfile == null) return@produceState
        delay(150)
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.monthEstimate(payProfile, snap, visibleMonth) }
    }
    val scope = rememberCoroutineScope()
    // A month picked from the list is there at once.
    val goToMonth: (YearMonth) -> Unit = { m -> scope.launch { pagerState.scrollToPage(monthToPage(baseMonth, m)) } }
    // [VIS-02] ‹ ›, 오늘 and a day of the next month slide over 220 ms, so it is clear which way it went.
    // Counted from where a slide is heading, so quick taps on › each move a month.
    val headingMonth = { pageToMonth(baseMonth, pagerState.targetPage) }
    val slideToMonth: (YearMonth) -> Unit = { m ->
        scope.launch {
            pagerState.animateScrollToPage(monthToPage(baseMonth, m), animationSpec = tween(MONTH_SLIDE_MILLIS, easing = FastOutSlowInEasing))
        }
    }

    // After swiping to another month, select today (if shown) or the 1st so the detail follows.
    LaunchedEffect(pagerState, baseMonth) {
        androidx.compose.runtime.snapshotFlow { pagerState.settledPage }.collect { page ->
            val m = pageToMonth(baseMonth, page)
            vm.showMonth(m)
            val sel = vm.selected.value
            if (YearMonth.from(sel) != m) {
                val now = LocalDate.now()
                vm.select(if (YearMonth.from(now) == m) now else m.atDay(1))
            }
        }
    }

    // [NAV-01] Turned in 돈 or 근무·급여, the month is the one shown here on coming back.
    LaunchedEffect(shownMonth) {
        if (pageToMonth(baseMonth, pagerState.settledPage) != shownMonth) pagerState.scrollToPage(monthToPage(baseMonth, shownMonth))
    }
    // Unfolded, the day shows beside the calendar; a sheet left open must not pop up again on folding.
    LaunchedEffect(wide) { if (wide) { sheetOpen = false; weekOnly = false } }
    LaunchedEffect(dayView) { if (dayView != PhoneDayView.WEEK) weekOnly = false }
    // [CAL-03] Back to the month, it opens on the month of the day picked in the week.
    LaunchedEffect(weekOnly) {
        if (!weekOnly) {
            val m = YearMonth.from(vm.selected.value)
            if (pageToMonth(baseMonth, pagerState.currentPage) != m) pagerState.scrollToPage(monthToPage(baseMonth, m))
        }
    }
    // [CAL-03] Back opens the whole month again.
    androidx.activity.compose.BackHandler(enabled = weekOnly && !wide && paint == null) { weekOnly = false }
    // Back ends quick input instead of leaving the app.
    androidx.activity.compose.BackHandler(enabled = paint != null) { vm.setPaint(null) }
    // Left open past midnight, the selection moves on with today when it was on today.
    var lastToday by remember { mutableStateOf(today) }
    LaunchedEffect(today) {
        if (today != lastToday) {
            if (selected == lastToday) vm.select(today)
            lastToday = today
        }
    }
    // [UX-03][CAL-17] Things pointed out once on this phone.
    val hints = remember { HintStore(context) }
    var longPressHintDone by remember { mutableStateOf(hints.done(HintStore.LONG_PRESS)) }
    val longPressSeen: () -> Unit = {
        if (!longPressHintDone) { hints.markDone(HintStore.LONG_PRESS); longPressHintDone = true }
    }
    // [DAY-01] A tap picks the day and shows it under the calendar; a tap on the picked day opens it all.
    val onDayClickNow: (LocalDate) -> Unit = { date ->
        val painting = paint != null
        val again = date == selected
        // [CAL-17] Changing one day in place is a long press away, which nothing on screen shows: the
        // first plain tap on a date says so once.
        if (!painting && mark == null && rangeFrom == null && !longPressHintDone) {
            longPressSeen()
            val tip = focus.longPressTip()
            scope.launch { delay(TIP_DELAY_MILLIS); vm.tip(tip) }
        }
        vm.onDayTapped(date)
        val m = YearMonth.from(date)
        if (m != visibleMonth && !weekOnly) slideToMonth(m)
        // [CAL-20] With the day card on, the first tap shows it and the second opens the day.
        if (!wide && !painting) when {
            dayView == PhoneDayView.WEEK -> weekOnly = true
            settings.showDayCard -> if (again) sheetOpen = true
            else -> sheetOpen = true
        }
    }
    // [DAY-01] ‹ › and swipes in the day header move the calendar along.
    val onDateChange: (LocalDate) -> Unit = { date ->
        vm.select(date)
        val m = YearMonth.from(date)
        // [CAL-03] The month is not on screen in the week view; it is put right when it comes back.
        if (m != visibleMonth && !(weekOnly && !wide)) slideToMonth(m)
    }
    val groups = rememberDayGroupState()
    // [STAB-01] Counted across a font or theme change too, so a memo tap from before does not open the keyboard again.
    var memoFocus by rememberSaveable { mutableIntStateOf(0) }
    // In the week view the whole day is on the page already.
    val showsDay = wide || weekOnly
    val onMemo: () -> Unit = {
        memoFocus++
        if (!showsDay) sheetOpen = true
    }
    val onQuickActionNow: (LocalDate, QuickAction) -> Unit = { date, action ->
        // [CAL-17] Found the long press already: the tip is not needed.
        longPressSeen()
        val day = snap.resolver.resolve(date)
        when (action) {
            is QuickAction.Shift -> vm.assignQuick(day, action.override, snap)
            is QuickAction.Extra -> vm.toggleExtra(day, action.choice)
            is QuickAction.SetLeave -> vm.setLeave(date, action.leave, announce = true)
            is QuickAction.Alarm -> vm.setAlarmSkipped(date, action.skipped, announce = true)
            QuickAction.AddMoney -> {
                vm.select(date)
                quickMoney = true
            }
            QuickAction.Memo -> {
                vm.select(date)
                onMemo()
            }
            QuickAction.Open -> {
                vm.select(date)
                if (!showsDay) sheetOpen = true
            }
        }
    }
    // [CAL-14] The same two lambdas for the whole life of the screen: a tap that changes the selection
    // then redraws only the two cells it touches, not all 42 of every month in the pager.
    val dayClickLatest by rememberUpdatedState(onDayClickNow)
    val quickActionLatest by rememberUpdatedState(onQuickActionNow)
    val onDayClick: (LocalDate) -> Unit = remember { { d: LocalDate -> dayClickLatest(d) } }
    val onQuickAction: (LocalDate, QuickAction) -> Unit = remember { { d: LocalDate, a: QuickAction -> quickActionLatest(d, a) } }
    // [EXTRA-05] Records from before 1.9 that need a look, offered once above the calendar.
    val toReview by vm.extrasToReview.collectAsStateWithLifecycle()
    var reviewing by remember { mutableStateOf(false) }
    val swapCovers = remember(snap, settings.swapCoversAnswered) {
        if (settings.swapCoversAnswered) emptyList()
        else com.bamnat.core.schedule.ExtraReview.shiftSwapCovers(snap.shiftTypes, snap.patterns, snap.entries)
    }
    var explainingSwap by remember { mutableStateOf(false) }

    var showMonthPicker by remember { mutableStateOf(false) }
    var showShare by remember { mutableStateOf(false) }
    // [PROV-04] Which of the calendar's links the sheet opens on: the first, or the one a notice is about.
    var shareSlot by remember { mutableIntStateOf(1) }
    val shareLinks = remember { com.bamnat.app.share.ShareLinkClient().enabled }
    // [UX-03] Pointed out once, the first time there is a schedule worth sending and nothing was sent yet.
    var shareHintDone by remember { mutableStateOf(hints.done(HintStore.SHARE)) }
    val shareCalendar: () -> Unit = {
        if (!shareHintDone) { hints.markDone(HintStore.SHARE); shareHintDone = true }
        shareSlot = 1
        // [MKT-09] With a link server the sheet offers picture or link; without one it sends the picture.
        if (shareLinks) showShare = true
        else {
            scope.launch { MonthImage.share(context, snap, visibleMonth, settings.weekStart) }
            vm.logShare()
        }
    }
    // [MCAL-03] Keyed by the calendar, so a sheet opened as the calendar changes starts from that calendar's link.
    if (showShare) androidx.compose.runtime.key(activeCalendar?.id) {
        ShareDialog(
            snapshot = snap, month = visibleMonth,
            calendar = activeCalendar,
            startSlot = shareSlot,
            onImage = { scope.launch { MonthImage.share(context, snap, visibleMonth, settings.weekStart) }; vm.logShare() },
            onDismiss = { showShare = false },
            onOpenReceived = { showShare = false; adding = "" },
        )
    }
    // [SHR-02][MCAL-01] Whose calendar is on screen, once there is more than one.
    val calendarRows = remember(calendarOrder, myCalendars, receivedList, received.enabled, showShare, switching) {
        CalendarRow.of(calendarOrder, myCalendars, if (received.enabled) receivedList else emptyList()) { id ->
            if (shareLinks) com.bamnat.app.share.ShareLinkKey.all(id).firstNotNullOfOrNull { context.appContainer.shareLinksOf(it).link.value } else null
        }
    }
    val pickCalendar: (CalendarRow) -> Unit = { row ->
        vm.setPaint(null)
        when (row) {
            is CalendarRow.Mine -> { received.view(null); vm.viewCalendar(row.cal.id) }
            is CalendarRow.Got -> received.view(row.cal.id)
        }
    }
    val currentRow: CalendarRow = viewingReceived?.let { CalendarRow.Got(it) }
        ?: calendarRows.firstOrNull { it.key == com.bamnat.app.data.CalendarKey.Mine(activeCalendar.id) } ?: CalendarRow.Mine(activeCalendar, null)
    // [UX-03] Always there: whose calendar this is (a tap opens the switcher, where a coworker's calendar
    // is taken in too) and 공유, on every screen size.
    val identityBar: @Composable () -> Unit = {
        CalendarIdentityBar(
            rows = calendarRows,
            current = currentRow,
            home = activeCalendar,
            onOpenSwitcher = { switching = true },
            onPick = pickCalendar,
            onShare = shareCalendar,
        )
    }
    // Back from someone's calendar returns to the user's own, like leaving another account.
    androidx.activity.compose.BackHandler(enabled = viewingReceived != null) { received.view(null) }

    val memoPart: @Composable () -> Unit = {
        if (settings.calendarMemos) {
            MonthMemoCard(
                month = visibleMonth,
                memos = remember(datedMemos, visibleMonth) { datedMemos.filter { YearMonth.from(it.date!!) == visibleMonth } },
                showEmpty = settings.memoInCells,
                onOpen = onOpenMemo,
                onNew = { onNewMemo(if (YearMonth.from(selected) == visibleMonth) selected else visibleMonth.atDay(1)) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }

    // [CAL-15] No date is ever under the FAB, so a tap meant for a day never opens quick input. There is
    // room for the FAB under the month: while the month's last row (the 30th, the 31st) is lower than
    // where the FAB rests, the FAB rides in that room just under the row, coming up from the screen's
    // edge with it, and it rests in its usual place once the month is above. Read while placing:
    // scrolling moves it without recomposing; a jump (another view, another layout) is animated.
    val pageScroll = rememberScrollState()
    var gridBottom by remember { mutableFloatStateOf(Float.NaN) }
    // [DAILY-09] Where the month starts in the window, so the half above a fold's hinge can open on it.
    var gridTop by remember { mutableFloatStateOf(Float.NaN) }
    var fabAreaBottom by remember { mutableFloatStateOf(Float.NaN) }
    val docking = paint == null && !listView && !(weekOnly && !wide)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fabLift = with(density) { (FAB_MARGIN + FAB_HEIGHT).toPx() }
    val fabHeight = with(density) { FAB_HEIGHT.toPx() }
    val dockGap = with(density) { FAB_DOCK_GAP.toPx() }
    val fabJump = with(density) { 8.dp.toPx() }
    val fabTarget: () -> Float = {
        if (!docking || gridBottom.isNaN() || fabAreaBottom.isNaN()) 0f
        else (gridBottom + dockGap - (fabAreaBottom - fabLift)).coerceIn(0f, fabLift)
    }
    val fabDrop = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(docking, fabLift) {
        androidx.compose.runtime.snapshotFlow { fabTarget() }.collectLatest { t ->
            if (kotlin.math.abs(t - fabDrop.value) > fabJump) fabDrop.animateTo(t, tween(FAB_STEP_MILLIS, easing = FastOutSlowInEasing))
            else fabDrop.snapTo(t)
        }
    }
    // [DAY-01] The FAB folds to its icon while the page goes on under it (the day card, the month's hours),
    // so what it would cover stays readable and in reach; at the page's end there is room for its label.
    // [CAL-15] It says "빠른 입력" at the top of the page and while it rides under the month, so the
    // first look tells what it is.
    val fabExpanded by remember { derivedStateOf { fabDrop.value > 0.5f || pageScroll.value == 0 || !pageScroll.canScrollForward } }
    // Below the screen's edge with the month, it is not there for TalkBack either.
    val fabGone by remember(fabLift) { derivedStateOf { fabDrop.value >= fabLift - 1f } }

    // [SLP-15] A tapped day notice opens on today: the user's own calendar, this month, the page's top.
    val noticeTarget by NoticeLinks.target.collectAsStateWithLifecycle()
    LaunchedEffect(noticeTarget) {
        if (noticeTarget != NoticeLinks.Target.TODAY) return@LaunchedEffect
        NoticeLinks.consume(NoticeLinks.Target.TODAY)
        received.view(null)
        // [MCAL-01] Day notices follow the main calendar; [GUIDE-01] a feature of today opens on its own calendar, with the day open.
        val feature = com.bamnat.app.ui.guide.FeatureLinks.takeToday()
        vm.viewCalendar(feature ?: com.bamnat.app.data.PersonalCalendar.MAIN)
        vm.setPaint(null)
        sheetOpen = feature != null && !showsDay
        vm.select(LocalDate.now())
        pagerState.scrollToPage(monthToPage(baseMonth, YearMonth.now()))
        pageScroll.scrollTo(0)
    }
    // [DAILY-06] A widget's date opens that day on 내 달력, as tapping it on the calendar does.
    LaunchedEffect(noticeTarget) {
        if (noticeTarget != NoticeLinks.Target.DAY) return@LaunchedEffect
        NoticeLinks.consume(NoticeLinks.Target.DAY)
        val day = NoticeLinks.payload.value?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
        received.view(null)
        // [DAILY-07] 근무·급여's week bar names its calendar; a widget's date is always 내 달력.
        vm.viewCalendar(NoticeLinks.calendar.value ?: com.bamnat.app.data.PersonalCalendar.MAIN)
        vm.setPaint(null)
        vm.select(day)
        pagerState.scrollToPage(monthToPage(baseMonth, YearMonth.from(day)))
        sheetOpen = !showsDay
    }
    // [SHR-12] The link's expiry notice opens the share sheet on the user's own calendar, to 늘리기.
    LaunchedEffect(noticeTarget) {
        if (noticeTarget != NoticeLinks.Target.SHARE) return@LaunchedEffect
        NoticeLinks.consume(NoticeLinks.Target.SHARE)
        received.view(null)
        // [MCAL-03] An added calendar's notice opens that calendar's sheet.
        // [PROV-04] A notice about a second link names it too, and the sheet opens on it.
        val key = NoticeLinks.calendar.value ?: com.bamnat.app.data.PersonalCalendar.MAIN
        vm.viewCalendar(com.bamnat.app.share.ShareLinkKey.calendar(key))
        shareSlot = com.bamnat.app.share.ShareLinkKey.slot(key)
        if (shareLinks) showShare = true
        else {
            scope.launch { MonthImage.share(context, snap, visibleMonth, settings.weekStart) }
            vm.logShare()
        }
    }
    // [UX-03] 동료 근무표 받기 from settings or a help answer: the sheet that takes a received link.
    LaunchedEffect(noticeTarget) {
        if (noticeTarget != NoticeLinks.Target.RECEIVE) return@LaunchedEffect
        NoticeLinks.consume(NoticeLinks.Target.RECEIVE)
        received.view(null)
        if (received.enabled) adding = ""
    }
    // [CAL-01] The picked day's week over the hour limit, said in one line under the calendar.
    val weekNote = remember(calculator, selected, settings.weeklyLimitHours) {
        calculator.week(selected).takeIf { it.overLimit }?.let {
            "이 주 ${HoursFormat.hours(it.totalMinutes.toDouble())}시간 · ${settings.weeklyLimitHours}시간 넘음"
        }
    }
    // [CAL-01] An alarm due within a week, whether or not the master switch lets it ring.
    val alarmSoon = remember(snap, today) {
        AlarmPlanner.nextAlarm(snap.resolver, java.time.LocalDateTime.now(), lookaheadDays = ALARM_WARNING_DAYS) != null
    }

    // [CAL-03] The tapped date's week, Monday to Sunday, and the whole day under it: no sheet over the
    // calendar, and the rest of the week a tap away. A sideways swipe goes a week back or on.
    val weekPart: @Composable () -> Unit = {
        val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${Fmt.monthDay(monday)} ~ ${Fmt.monthDay(monday.plusDays(6))}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { weekOnly = false }) { Text("달 전체 보기") }
        }
        WeekdayHeader(DayOfWeek.MONDAY, Modifier.padding(start = 8.dp + WEEK_BAR_SPACE, end = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val cellHeight = (maxWidth / 7 * 1.3f).coerceIn(66.dp, 100.dp) + cellTextGrowth()
            val swipe = with(androidx.compose.ui.platform.LocalDensity.current) { WEEK_SWIPE.toPx() }
            val turnTo by rememberUpdatedState(onDateChange)
            WeekStrip(
                monday = monday,
                snapshot = snap,
                selected = selected,
                today = today,
                memoDates = memoDates,
                showHours = settings.showHoursInCells,
                showLunar = settings.showLunar,
                cellHeight = cellHeight,
                onDayClick = onDayClick,
                onQuickAction = onQuickAction,
                money = moneyBook,
                showMoney = focus.moneyFirst,
                memoTexts = memoTexts,
                focus = focus,
                hourly = hourlyCells,
                invest = investCells,
                // [DAYW-01] A day calendar shows its exceptions only.
                quietWorkDays = settings.workStyle == com.bamnat.core.work.WorkStyle.DAY,
                modifier = Modifier.pointerInput(Unit) {
                    var dragged = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            if (dragged > swipe) turnTo(selectedNow().minusWeeks(1))
                            else if (dragged < -swipe) turnTo(selectedNow().plusWeeks(1))
                        },
                    ) { change, amount ->
                        change.consume()
                        dragged += amount
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        DayPanel(
            date = selected,
            today = today,
            snapshot = snap,
            vm = vm,
            groups = groups,
            onDateChange = onDateChange,
            onOpenMemo = onOpenMemo,
            onNewMemo = onNewMemo,
            onOpenShiftTypes = onOpenShiftTypes,
            onOpenPay = onOpenPay,
            onOpenExtraKinds = onOpenExtraKinds,
            memoFocus = memoFocus,
            onMemo = onMemo,
            bottomPadding = 12.dp,
            weekNote = weekNote,
            scrollGroups = false,
        )
    }

    val calendarPart: @Composable () -> Unit = {
        identityBar()
        // [SRV-04] The developer's notice, one line, only while it runs.
        AppNoticeLine(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
        val linkMade = remember(activeCalendar.id, showShare) {
            shareLinks && context.appContainer.hasShareLink(activeCalendar.id)
        }
        if (!shareHintDone && !linkMade && !snap.isEmpty && paint == null && pendingSetup?.later != true) {
            ShareHintCard(
                onShare = shareCalendar,
                onClose = { hints.markDone(HintStore.SHARE); shareHintDone = true },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        CalendarHeader(
            month = if (weekOnly && !wide) YearMonth.from(selected) else visibleMonth,
            // [CAL-03] With one week on screen, ‹ › go a week back or on.
            onPrev = { if (weekOnly) onDateChange(selected.minusWeeks(1)) else slideToMonth(headingMonth().minusMonths(1)) },
            onNext = { if (weekOnly) onDateChange(selected.plusWeeks(1)) else slideToMonth(headingMonth().plusMonths(1)) },
            onToday = {
                vm.select(LocalDate.now())
                slideToMonth(YearMonth.now())
            },
            // [285] Already on today: the button stays where it is, greyed, so the bar does not jump.
            atToday = selected == LocalDate.now() && (if (weekOnly && !wide) YearMonth.from(selected) else visibleMonth) == YearMonth.now(),
            onPickMonth = { showMonthPicker = true },
            byWeek = weekOnly,
            onShare = shareCalendar,
            onOpenPattern = onOpenPattern,
            onAddCalendar = onAddCalendar,
            onReceive = if (received.enabled) ({ adding = "" }) else null,
            // [CALS-01] The calendar on screen is changed from its own page: name, kind of work, settings.
            onEditCalendar = if (viewingReceived == null) ({ onEditCalendar(activeCalendar.id) }) else null,
            onCustomize = if (viewingReceived == null) ({ editingLayout = true }) else null,
            calendarName = activeCalendar.name,
            onOpenTeams = openTeams,
            // [NURS-01] A month's duty typed one day after another, for rosters that come as a sheet.
            onTypeMonth = if (viewingReceived == null && snap.shiftTypes.isNotEmpty()) ({ typingMonth = visibleMonth }) else null,
        )
        // [SETUP-01] A first setup left for later comes first; it fills the calendar.
        val waiting = pendingSetup?.takeIf { it.later && settings.workStyle == null }
        if (waiting != null) {
            SetupResumeCard(waiting, onResume = vm::resumeSetup)
        } else if (paint == null && !settings.rosterMonthly) {
            // [WORK-01][CAL-05] Only work that repeats needs a pattern; field, shop and hourly work start from
            // a first record, each in its own words.
            val style = settings.workStyle
            val empty = when {
                style?.usesPattern != false -> snap.isEmpty
                style == com.bamnat.core.work.WorkStyle.HOURLY -> overview.isEmpty && !snap.hasPattern
                // [INV-01] An investor calendar starts from its first deposit.
                style == com.bamnat.core.work.WorkStyle.INVEST -> investState?.loaded == true && investState.full.isEmpty
                else -> overview.isEmpty && YearMonth.from(today) == visibleMonth
            }
            if (empty) CalendarStartCard(
                start = style.calendarStart(),
                onAction = {
                    when {
                        style?.usesPattern != false -> onOpenPattern()
                        style == com.bamnat.core.work.WorkStyle.HOURLY -> vm.setPaint(PaintTool.Pick)
                        style == com.bamnat.core.work.WorkStyle.INVEST ->
                            investSheet = com.bamnat.app.ui.invest.InvestSheet.CashEdit(com.bamnat.core.invest.CashKind.DEPOSIT)
                        else -> {
                            if (YearMonth.from(today) == visibleMonth) vm.select(today)
                            quickMoney = true
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // [GUIDE-02][GUIDE-03] What this calendar can do on its first days, then one tip at the right moment.
        if (waiting == null && paint == null && viewingReceived == null && visibleMonth == YearMonth.from(today)) {
            com.bamnat.app.ui.guide.GuideArea(activeCalendar.id, settings.workStyle, snap, moneyBook, payProfile, settings.weekStart,
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), trades = investState?.full?.trades?.size ?: 0)
        }
        // [CAL-07] Weekday work set by the week, on this month's public holidays: asked once.
        val holidayDays = remember(snap, visibleMonth, holidaysKept, settings.workStyle, activeCalendar.id) { vm.holidayWork(snap, visibleMonth) }
        if (holidayDays.isNotEmpty() && paint == null) {
            HolidayWorkCard(
                month = visibleMonth, dates = holidayDays,
                onRest = { vm.restHolidays(holidayDays) },
                onKeep = { vm.keepHolidayWork(visibleMonth) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // [SETUP-02] A roster that comes every month: this month's or next month's, until a day of it is painted.
        if (settings.rosterMonthly && paint == null && waiting == null) {
            val thisMonth = YearMonth.from(today)
            val unpainted = (visibleMonth == thisMonth || visibleMonth == thisMonth.plusMonths(1)) &&
                snap.entries.none { YearMonth.from(it.date) == visibleMonth && it.override != ShiftOverride.None }
            if (unpainted) RosterCard(visibleMonth, onPaint = { vm.setPaint(PaintTool.Pick) })
        }
        // [CAL-01] What keeps an alarm from working, the most serious one in a single line, only while
        // one is due within a week. The whole line is the button that fixes it.
        val linePlace = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        if (alarmSoon && !calendarAlarmsOn) {
            // [CAL-13] An added calendar kept from ringing: the master switch and permissions do not matter here.
            CalendarAlarmsOffLine(activeCalendar.name, onTurnOn = { vm.setCalendarAlarms(true) }, modifier = linePlace)
        } else if (alarmSoon) when {
            // [SET-01] With the master switch off nothing rings: said where the user looks first.
            !settings.alarmsEnabled -> AlarmWarningLine("알람이 모두 꺼져 있어요", "켜기", { vm.setAlarmsEnabled(true) }, linePlace)
            // [REL-11][SLP-15] Without notifications an alarm still sounds, but nothing on the lock
            // screen stops it. Asked here, where it is needed, instead of on the first start.
            !notificationsAllowed -> AlarmWarningLine("알림을 허용해야 알람을 끌 수 있어요", "허용", askNotifications, linePlace)
            // [REL-12] Android 14+ can block the full-screen alarm; then only a small notification shows.
            !fullScreenAllowed -> AlarmWarningLine("잠금 화면에 알람 화면이 안 떠요", "허용", {
                if (android.os.Build.VERSION.SDK_INT >= 34) SystemSettings.open(context,
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                        android.net.Uri.parse("package:${context.packageName}")))
            }, linePlace)
        }
        // [CUST-01] The parts above the month in the user's order (화면 꾸미기).
        calendarParts.filter { it.part.above && it.part.movable && it.shown }.forEach { placed ->
            when (placed.part) {
                CalendarPart.TODAY_FLOW -> {
                // [SLP-14] 오늘의 흐름 while this month is on screen, for work that comes in shifts and hours.
                if (settings.sleepTips && settings.workStyle?.usesPayEngine != false && !snap.isEmpty && visibleMonth == YearMonth.from(today)) {
                    TodayFlowSection(vm, snap, settings, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
                }
                CalendarPart.TODAY_WORK -> {
                // [CAL-05] Today for field and shop work, where 오늘의 흐름 shows shifts.
                if (visibleMonth == YearMonth.from(today) && paint == null) when (focus) {
                    com.bamnat.core.work.CalendarFocus.JOBS -> if (moneyBook.jobs.isNotEmpty()) TodayJobsCard(
                        today = today, jobs = moneyBook.jobs,
                        onOpen = { d -> onDateChange(d); if (!showsDay) sheetOpen = true },
                        onAdd = { vm.select(today); quickMoney = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    com.bamnat.core.work.CalendarFocus.SALES -> if (moneyBook.sales.isNotEmpty()) TodaySalesCard(
                        today = today, sales = moneyBook.sales,
                        onWrite = { vm.select(today); quickMoney = true },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // [INV-01] Today's result, 총자산 and 수익률, and the next alarm.
                    com.bamnat.core.work.CalendarFocus.INVEST -> if (investVm != null) com.bamnat.app.ui.invest.TodayInvestCard(
                        vm = investVm, today = today, onTrade = { vm.select(today); quickMoney = true }, onOpen = onOpenStats,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    else -> Unit
                }
                }
                else -> Unit
            }
        }
        if (swapCovers.isNotEmpty()) {
            ExtraReviewCard(
                title = "쉬는 날 대근 ${swapCovers.size}일을 고쳐 주세요",
                message = "근무를 바꿔서 적은 대근은 쉬는 날 근무로 계산되지 않아요. 한 번에 대근으로 고칠 수 있어요.",
                action = "자세히",
                onAction = { explainingSwap = true },
                onLater = vm::keepSwapCovers,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else if (toReview > 0) {
            ExtraReviewCard(
                title = "옮겨 온 추가 근무 ${toReview}건을 확인해 주세요",
                message = "이제 잔업·특근·대근마다 시각이 있어요. 예전 기록의 시간이 맞는지 한 번만 봐 주세요.",
                action = "확인하기",
                onAction = { reviewing = true },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        // [CAL-03] One week and the whole day under it, or the month.
        if (weekOnly && !wide) {
            weekPart()
        } else {
            // [CAL-06] The month in the kind of work's own three figures.
            if (!partShown(CalendarPart.SUMMARY)) Unit
            else if (focus == com.bamnat.core.work.CalendarFocus.INVEST && investVm != null) {
                // [INV-06] The month in the investor's own figures.
                com.bamnat.app.ui.invest.InvestMonthBar(investVm, visibleMonth, onOpen = onOpenStats, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            } else if (!(focus == com.bamnat.core.work.CalendarFocus.SHIFTS && overview.isEmpty && payEstimate == null)) {
                MonthSummaryBar(
                    overview = overview,
                    payEstimate = payEstimate,
                    onOpen = if (focus == com.bamnat.core.work.CalendarFocus.SHIFTS) onOpenPay else onOpenMoney,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // [CAL-06][CAL-08] The month's shifts at a glance, what the marks mean, and grid or list.
            val showList = listView && paint == null
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                // The counts take what is left, so the two buttons always sit at the right edge.
                // [CAL-24] 모든 조 보기 one tap from the month, before the counts, on every screen width.
                openTeams?.let { open ->
                    androidx.compose.material3.AssistChip(
                        onClick = open,
                        label = { Text("모든 조", maxLines = 1, softWrap = false) },
                        leadingIcon = { Icon(Icons.Outlined.Groups, null, Modifier.size(18.dp)) },
                        modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "모든 조 보기와 대근 찾기" },
                    )
                }
                Box(Modifier.weight(1f)) { if (partShown(CalendarPart.COUNTS)) MonthShiftCounts(snap, visibleMonth) }
                CalendarViewSwitch(listView = showList, onListView = { if (paint == null) vm.setListView(it) }, onLegend = { legendOpen = true })
            }
            if (!showList) WeekdayHeader(settings.weekStart, Modifier.padding(start = 8.dp + WEEK_BAR_SPACE, end = 8.dp))
            BoxWithConstraints(
                Modifier.fillMaxWidth().padding(horizontal = if (showList) 0.dp else 8.dp)
                    // [CAL-15] Where the month's last row ends, for the FAB to keep under it.
                    .onGloballyPositioned { c -> gridTop = c.positionInWindow().y; gridBottom = gridTop + c.size.height },
            ) {
                val cellHeight = (maxWidth / 7 * 1.3f).coerceIn(66.dp, 100.dp) + cellTextGrowth()
                // [CAL-09] With something to stamp, a drag paints and a long press starts a range.
                val stamping = mark != null || (paint != null && paint != PaintTool.Pick)
                val memoCounts = remember(datedMemos) { datedMemos.groupingBy { it.date!! }.eachCount() }
                HorizontalPager(
                    state = pagerState,
                    // [CAL-08] The list is as tall as its month; the grid is always six weeks.
                    modifier = Modifier.fillMaxWidth().then(if (showList) Modifier.animateContentSize() else Modifier.height(cellHeight * GRID_ROWS)),
                    beyondViewportPageCount = if (showList) 0 else 1,
                    key = { it },
                    verticalAlignment = Alignment.Top,
                    // [CAL-01] With a stamp chosen, a sideways drag paints days instead of turning the month.
                    userScrollEnabled = !stamping,
                ) { page ->
                    if (showList) {
                        AgendaList(
                            month = pageToMonth(baseMonth, page),
                            focus = focus,
                            snapshot = snap,
                            money = moneyBook,
                            memoCounts = memoCounts,
                            weekStart = settings.weekStart,
                            today = today,
                            selected = selected,
                            onDay = onDayClick,
                            onQuickAction = onQuickAction,
                        )
                    } else MonthGrid(
                        month = pageToMonth(baseMonth, page),
                        snapshot = snap,
                        calculator = calculator,
                        weekStart = settings.weekStart,
                        selected = selected,
                        today = today,
                        memoDates = memoDates,
                        showHours = settings.showHoursInCells,
                        showLunar = settings.showLunar,
                        cellHeight = cellHeight,
                        onDayClick = onDayClick,
                        onQuickAction = onQuickAction,
                        money = moneyBook,
                        showMoney = focus.moneyFirst,
                        memoTexts = memoTexts,
                        onPaint = if (stamping) paintDays else null,
                        weeklyLimitHours = settings.weeklyLimitHours,
                        focus = focus,
                        onLongPress = if (stamping) startRange else null,
                        rangeFrom = rangeFrom,
                        hourly = hourlyCells,
                        invest = investCells,
                        // [DAYW-01] A day calendar shows its exceptions only; painting shows every day.
                        quietWorkDays = settings.workStyle == com.bamnat.core.work.WorkStyle.DAY && !stamping,
                    )
                }
            }
            // [CAL-15] Room for the floating button under the month, so its last row (the 30th, the 31st)
            // can always be scrolled clear of it.
            Spacer(Modifier.height(if (paint == null) FAB_ROOM else 12.dp))
            // [CUST-01] The parts under the month in the user's order (화면 꾸미기); each keeps its own switch.
            calendarParts.filter { !it.part.above && it.shown }.forEach { placed ->
                when (placed.part) {
                    CalendarPart.DAY_CARD -> {
                        if (!wide) {
                            // [CAL-05] One day card for every kind of work: its jobs, sales or shift, with the chips that fit.
                            DayPreview(
                                day = remember(snap, selected) { snap.resolver.resolve(selected) },
                                today = today,
                                snapshot = snap,
                                vm = vm,
                                onDateChange = onDateChange,
                                onOpen = { sheetOpen = true },
                                onMemo = onMemo,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                weekNote = weekNote,
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    CalendarPart.HOURS -> if (focus == com.bamnat.core.work.CalendarFocus.SHIFTS) {
                        HoursLine(
                            calculator = calculator,
                            limits = settings.limits,
                            selected = selected,
                            month = visibleMonth,
                            onOpenStats = onOpenStats,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                    CalendarPart.REST -> if (focus == com.bamnat.core.work.CalendarFocus.SHIFTS) {
                        val rules = settings.rules
                        val rest = remember(snap, visibleMonth, rules) { vm.restIssues(snap, visibleMonth, rules) }
                        // [NURS-05][JOB-03] Nights, night pay and a 공무원's 시간외, worked out off the main thread.
                        val nights by produceState<CalendarViewModel.MonthNights?>(null, payProfile, snap, visibleMonth) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.monthNights(payProfile, snap, visibleMonth) }
                        }
                        MonthRestCard(rest, nights, onPick = vm::select, onRules = { rulesOpen = true },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp))
                    }
                    CalendarPart.MEMOS -> memoPart()
                    else -> Unit
                }
            }
        }
    }

    val detailPart: @Composable (Modifier, androidx.compose.ui.unit.Dp) -> Unit = { m, bottom ->
        DayPanel(
            date = selected,
            today = today,
            snapshot = snap,
            vm = vm,
            groups = groups,
            onDateChange = onDateChange,
            onOpenMemo = onOpenMemo,
            onNewMemo = onNewMemo,
            onOpenShiftTypes = onOpenShiftTypes,
            onOpenPay = onOpenPay,
            onOpenExtraKinds = onOpenExtraKinds,
            modifier = m,
            memoFocus = memoFocus,
            onMemo = onMemo,
            bottomPadding = bottom,
            weekNote = weekNote,
            compact = wide,
        )
    }

    val bottomSpace = if (paint != null) 132.dp else 88.dp
    val fab: @Composable BoxScope.() -> Unit = {
        // [CAL-15] Where the FAB rests: the bottom of the area it floats in.
        Spacer(Modifier.matchParentSize().onGloballyPositioned { c -> fabAreaBottom = c.positionInWindow().y + c.size.height })
        val style = settings.workStyle
        val place = Modifier.align(Alignment.BottomEnd).padding(FAB_MARGIN).offset { IntOffset(0, fabDrop.value.roundToInt()) }
            // Coming up from the edge it fades in with how much of it shows, so a sliver is not a stray shape.
            .graphicsLayer { alpha = ((fabLift - fabDrop.value) / fabHeight).coerceIn(0f, 1f) }
        if (!fabGone && paint == null && style != null && !style.usesPayEngine) {
            // [WORK-02][WORK-03] Field and shop work add the day's job or sales instead of stamping shifts.
            val label = if (style.usesJobs) "일감 적기" else if (style.usesTrades) "매매 기록" else "매출 적기"
            // The extended FAB drops its text from semantics, so the icon carries the label for TalkBack.
            ExtendedFloatingActionButton(
                onClick = { quickMoney = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = label) },
                text = { Text(label) },
                expanded = fabExpanded,
                modifier = place,
            )
        } else if (!fabGone && paint == null) {
            ExtendedFloatingActionButton(
                // [CAL-01] Nothing is chosen yet, so the first tap cannot paint a day by surprise.
                onClick = { vm.setPaint(PaintTool.Pick) },
                icon = { Icon(Icons.Filled.EditCalendar, contentDescription = "빠른 입력") },
                text = { Text("빠른 입력") },
                expanded = fabExpanded,
                modifier = place,
            )
        }
    }
    val them = viewingReceived
    if (them != null) {
        // Each received calendar opens on its own month and day, not the last one's.
        key(them.id) {
            ReceivedCalendarView(
                vm = received,
                cal = them,
                mine = snap,
                weekStart = settings.weekStart,
                wide = wide,
                today = today,
                startMonth = visibleMonth,
                identityBar = identityBar,
                rules = settings.rules,
            )
        }
    } else Box(Modifier.fillMaxSize().clipToBounds()) {
        if (tabletop != null) {
            // [DAILY-09] The month above the hinge, the day panel below it; nothing sits on the fold itself.
            var areaTop by remember { mutableIntStateOf(0) }
            // The month, not the cards above it, is what the half above the hinge is for: it opens there once.
            LaunchedEffect(tabletop, gridTop.isNaN()) {
                if (!gridTop.isNaN()) {
                    val room = with(density) { MONTH_ROW_ROOM.toPx() }
                    pageScroll.scrollTo((pageScroll.value + gridTop - areaTop - room).roundToInt().coerceAtLeast(0))
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { areaTop = it.positionInWindow().y.roundToInt() }) {
                val upper = with(density) { tabletop.upperHeight(areaTop, constraints.maxHeight).toDp() }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(upper).clipToBounds()) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(pageScroll)
                                .padding(bottom = bottomSpace),
                        ) { calendarPart() }
                        fab()
                    }
                    Spacer(Modifier.height(with(density) { tabletop.hinge.toDp() }))
                    HorizontalDivider()
                    detailPart(Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp), 16.dp)
                }
            }
        } else if (wide) {
            // [CAL-01] About 3:2, so the unfolded calendar's days are as big as on the cover screen; the
            // day panel keeps the room its title and switches need.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val panelWidth = (maxWidth * 0.4f).coerceIn(DAY_PANEL_MIN_WIDTH, DAY_PANEL_MAX_WIDTH)
                Row(Modifier.fillMaxSize()) {
                    // [CAL-15] Clipped, so the FAB riding under the month slides out under the pane's edge.
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(pageScroll)
                                .padding(bottom = bottomSpace),
                        ) { calendarPart() }
                        fab()
                    }
                    VerticalDivider()
                    // [DAY-01] The same day panel as the sheet: the header stays, the groups scroll.
                    detailPart(Modifier.width(panelWidth).fillMaxHeight().padding(top = 4.dp), bottomSpace)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(pageScroll)
                    .padding(bottom = bottomSpace),
            ) {
                calendarPart()
            }
        }

        if (!wide) fab()
        if (paint != null) {
            Box(Modifier.align(Alignment.BottomCenter)) {
                PaintBar(snapshot = snap, tool = paint, last = lastPaint, onTool = vm::setPaint, onUndo = vm::undoLastPaint,
                    mark = mark, onMark = vm::setMark, focus = focus, onTypeMonth = { typingMonth = visibleMonth })
            }
        }
    }

    typingMonth?.let { m ->
        DutyTypingSheet(snap, m, onDismiss = { typingMonth = null }, onSave = { changes -> vm.applyDuty(changes); typingMonth = null },
            rules = vm.rulesFor(snap, m.atDay(1), settings.rules), onRules = { rulesOpen = true })
    }
    if (rulesOpen) {
        RestRulesSheet(settings.rules, vm.rotation(snap, visibleMonth.atDay(1)), onDismiss = { rulesOpen = false }) { r ->
            vm.setRestRules(r); rulesOpen = false
        }
    }

    if (investVm != null) {
        com.bamnat.app.ui.invest.InvestMessages(investVm, snackbarHostState)
        investSheet?.let { s -> com.bamnat.app.ui.invest.InvestSheetHost(investVm, s) { investSheet = it } }
    }
    if (quickMoney && investVm != null) {
        // [INV-03] The trade sheet for the picked day.
        LaunchedEffect(Unit) {
            investSheet = com.bamnat.app.ui.invest.InvestSheet.TradeEdit(investVm.draftFor(null, selected))
            quickMoney = false
        }
    } else if (quickMoney) {
        if (settings.workStyle?.usesJobs == true) {
            com.bamnat.app.ui.money.JobDialog(selected, null, onSave = { vm.saveJob(it); quickMoney = false }, onDismiss = { quickMoney = false })
        } else {
            com.bamnat.app.ui.money.SalesDialog(selected, moneyBook.on(selected).sales,
                onSave = { vm.saveSales(it); quickMoney = false }, onDismiss = { quickMoney = false })
        }
    }

    if (editingLayout) {
        CalendarEditSheet(settings, update = vm::updateSettings, onDismiss = { editingLayout = false })
    }
    coverAsk?.let { ask -> CoverAskDialog(ask, onAnswer = { asCover, keep -> vm.answerCover(ask, asCover, keep) }, onDismiss = vm::dismissCover) }

    if (sheetOpen && !wide) {
        DaySheet(snackbar = sheetSnackbar, onDismiss = { sheetOpen = false }) { detailPart(Modifier.fillMaxWidth(), 24.dp) }
    }
    if (reviewing) {
        ExtraReviewDialog(snap, vm, onOpenDay = { d ->
            reviewing = false
            onDateChange(d)
            if (!wide) sheetOpen = true
        }, onDismiss = { reviewing = false })
    }
    if (explainingSwap) {
        SwapCoverDialog(
            days = swapCovers,
            snapshot = snap,
            onConvert = { explainingSwap = false; vm.convertSwapCovers(swapCovers) },
            onKeep = { explainingSwap = false; vm.keepSwapCovers() },
            onDismiss = { explainingSwap = false },
        )
    }

    if (switching) {
        CalendarSwitcherSheet(
            rows = calendarRows,
            currentKey = currentRow.key,
            onPick = { row -> switching = false; pickCalendar(row) },
            onAddCalendar = { switching = false; onAddCalendar() },
            onAddReceived = if (received.enabled) ({ switching = false; adding = "" }) else null,
            onManage = { switching = false; onManageCalendars() },
            onEdit = { cal -> switching = false; onEditCalendar(cal.id) },
            onRemove = { cal -> received.remove(cal.id) },
            onDismiss = { switching = false },
            onCombined = { switching = false; onOpenCombined() },
            // [UX-03] Each of the user's calendars shares from here, as that calendar.
            onShare = { cal ->
                switching = false
                received.view(null)
                vm.setPaint(null)
                vm.viewCalendar(cal.id)
                // The sheet is keyed by the calendar on screen, so it opens as this one.
                shareCalendar()
            },
        )
    }
    adding?.let { start ->
        AddReceivedSheet(
            vm = received,
            startId = start.ifEmpty { null },
            today = today,
            onDismiss = { adding = null },
            onAdded = { adding = null; vm.setPaint(null) },
        )
    }

    if (showMonthPicker) {
        MonthPickerDialog(
            initial = visibleMonth,
            onDismiss = { showMonthPicker = false },
            onPick = { m ->
                showMonthPicker = false
                goToMonth(m)
            },
            selected = selected,
            onPickDate = { d ->
                showMonthPicker = false
                vm.select(d)
                goToMonth(YearMonth.from(d))
            },
        )
    }
    if (legendOpen) CalendarLegendSheet(focus, snap, onDismiss = { legendOpen = false })
}

/** [DAY-01][DAY-03] The whole day over the calendar, open at once, with its own snackbar for undo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySheet(snackbar: SnackbarHostState, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // [STAB-01] A message still showing here when the sheet closes is closed with it: otherwise it waits for
    // a host that is gone, and every later message (되돌리기 and the rest) stops appearing.
    DisposableEffect(snackbar) { onDispose { snackbar.currentSnackbarData?.dismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Box(Modifier.fillMaxWidth()) {
            content()
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
    }
}

/** How many of each shift this month, e.g. 주 10 · 야 10 · 휴 6 · 특근 2. */
@Composable
private fun MonthShiftCounts(snapshot: ScheduleSnapshot, month: YearMonth, modifier: Modifier = Modifier) {
    val counts = remember(snapshot, month) {
        val days = snapshot.resolver.resolveRange(month.atDay(1), month.atEndOfMonth())
        val byType = days.mapNotNull { it.shiftType }.groupingBy { it.id }.eachCount()
        // [EXTRA-01] Pieces of extra work by kind, e.g. 대근 3.
        val extras = days.flatMap { it.extras }.groupingBy { it.kind }.eachCount()
        Pair(snapshot.shiftTypes.filter { (byType[it.id] ?: 0) > 0 }.map { it to byType.getValue(it.id) }, extras)
    }
    if (counts.first.isEmpty() && counts.second.isEmpty()) return
    // [CAL-06] Beside the view buttons a narrow screen drops the badges' icons, and the row fades out
    // at the edge so what is left to scroll to shows.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val icons = maxWidth >= 320.dp
        val scroll = rememberScrollState()
        Row(
            Modifier.fillMaxWidth().fadingEdges(back = { scroll.canScrollBackward }, forward = { scroll.canScrollForward }).horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((type, n) in counts.first) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShiftBadge(type, size = 18.dp, outlined = type.isOff, showIcon = icons)
                    Text(" $n", style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
                }
            }
            for ((kind, n) in counts.second) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ExtraKindMark(kind, size = 18.dp)
                    Text(" ${kind.name} $n", style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
                }
            }
            Spacer(Modifier.width(20.dp))
        }
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
    atToday: Boolean = false,
    onShare: () -> Unit = {},
    /** [CAL-03] ‹ › step a week while one week is on screen. */
    byWeek: Boolean = false,
    /** [MCAL-02] Adds a calendar of the user's own. */
    onAddCalendar: () -> Unit = {},
    /** [UX-03] Opens the sheet that takes a coworker's link; null without a share server. */
    onReceive: (() -> Unit)? = null,
    /** [CALS-01] The calendar on screen's own page; null while a received calendar is shown. */
    onEditCalendar: (() -> Unit)? = null,
    calendarName: String = "",
    /** [CUST-01] 화면 꾸미기 for the calendar page; null while a received calendar is shown. */
    onCustomize: (() -> Unit)? = null,
    /** [CAL-24] 모든 조 보기; null when the calendar has no rotation to split into teams. */
    onOpenTeams: (() -> Unit)? = null,
    onTypeMonth: (() -> Unit)? = null,
) {
    // [QA-01] A cover screen (~344dp) cannot fit four icons, the month and 오늘: pattern and the new
    // calendar go into ⋮ there. [UX-03] 공유 is in the bar above on every screen; ⋮ keeps it too, where
    // people who learned it there look, with the coworker's calendar, the calendar's look and help.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 392.dp
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, if (byWeek) "이전 주" else "이전 달") }
        // [UX-06] The month and › take only what is left, the month's text shrinking at the largest text
        // sizes, so 오늘 and ⋮ (with 공유) stay on screen instead of being pushed off it.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // [VIS-02] The month opens the month list: 48dp tall, with ▾ to show it can be tapped.
            Row(
                Modifier
                    .weight(1f, fill = false)
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClickLabel = "달 고르기", role = Role.Button, onClick = onPickMonth)
                    .padding(start = 6.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.bamnat.app.ui.FitText(Fmt.month(month), if (narrow) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    Modifier.weight(1f, fill = false))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, if (byWeek) "다음 주" else "다음 달") }
        }
        TextButton(onClick = onToday, enabled = !atToday) { Text("오늘", maxLines = 1, softWrap = false) }
        if (!narrow) {
            IconButton(onClick = onOpenPattern) { Icon(Icons.Filled.EventRepeat, "근무 주기 설정") }
            IconButton(onClick = onAddCalendar) { Icon(Icons.Outlined.LibraryAdd, "새 달력 만들기") }
        }
        // [CAL-23] A button with its name instead of ⋮: people did not know three dots held the tools.
        androidx.compose.material3.FilledTonalButton(
            onClick = { menu = true },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.heightIn(min = 40.dp).padding(end = 4.dp),
        ) {
            // [A11Y-03] On a small phone (320dp) or at the largest text sizes the word squeezed the month to a
            // few pixels; there only the icon shows, and TalkBack still reads its name.
            if (maxWidth < 340.dp || narrow && LocalDensity.current.fontScale >= 1.5f) {
                Icon(Icons.Outlined.Apps, "도구", Modifier.size(18.dp))
            } else {
                Icon(Icons.Outlined.Apps, null, Modifier.size(18.dp))
                Text(" 도구", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (menu) {
        CalendarToolsSheet(
            onDismiss = { menu = false },
            tools = buildList {
                onEditCalendar?.let { add(CalendarTool("‘$calendarName’ 고치기", Icons.Outlined.Edit, it)) }
                // [CAL-24] 모든 조 is also on the calendar itself; here it stays where people learned it.
                onOpenTeams?.let { add(CalendarTool("모든 조 보기", Icons.Outlined.Groups, it)) }
                onTypeMonth?.let { add(CalendarTool("한 달 근무 연달아 넣기", Icons.Outlined.Keyboard, it)) }
                add(CalendarTool("근무표 공유", Icons.Filled.Share, onShare))
                add(CalendarTool("1년 달력", Icons.Outlined.CalendarViewMonth) { com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.YEAR) })
                onReceive?.let { add(CalendarTool("동료 근무표 받기", Icons.Outlined.GroupAdd, it)) }
                add(CalendarTool("근무 주기 설정", Icons.Filled.EventRepeat, onOpenPattern))
                add(CalendarTool("새 달력 만들기", Icons.Outlined.LibraryAdd, onAddCalendar))
                onCustomize?.let { add(CalendarTool("화면 꾸미기", Icons.Outlined.Dashboard, it)) }
                add(CalendarTool("달력 보기 설정", Icons.Outlined.Tune) {
                    com.bamnat.app.ui.settings.SettingsLinks.open(com.bamnat.app.ui.settings.SettingsPage.CALENDAR)
                })
                add(CalendarTool("도움말·문의", Icons.AutoMirrored.Outlined.HelpOutline) {
                    com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(about = "달력", help = true))
                })
            },
        )
    }
    }
}

/** [CAL-23] One tool of the calendar's tool sheet: a name people can read, not a hidden menu row. */
internal data class CalendarTool(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)

/** [CAL-23] The calendar's tools as big named tiles, three or four to a row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CalendarToolsSheet(tools: List<CalendarTool>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CalendarToolsGrid(tools, onPicked = onDismiss)
    }
}

@Composable
internal fun CalendarToolsGrid(tools: List<CalendarTool>, onPicked: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).navigationBarsPadding()) {
        Text("달력 도구", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = if (maxWidth >= 480.dp) 4 else 3
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tools.chunked(perRow).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { tool ->
                            Surface(
                                onClick = { onPicked(); tool.onClick() },
                                shape = MaterialTheme.shapes.large,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.weight(1f).heightIn(min = 84.dp),
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(tool.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2)
                                }
                            }
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupResumeCard(d: com.bamnat.core.setup.SetupDraft, onResume: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("근무 설정을 마저 해 주세요", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                if (d.style == null) "어떻게 일하는지만 고르면 나머지는 하나씩 물어볼게요."
                else "${d.steps.size}단계 중 ${d.position}단계까지 했어요. 고른 답은 그대로 있어요.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = onResume) { Text("이어서 하기") }
        }
    }
}

/** [SETUP-02] The month's roster is not painted yet: one tap opens quick input to paint it. */
@Composable
private fun RosterCard(month: YearMonth, onPaint: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${month.monthValue}월 근무표를 칠해 주세요", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("근무를 고르고 날짜를 쓸어 칠하면 돼요", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Button(onClick = onPaint) { Text("칠하기") }
        }
    }
}

/**
 * [CAL-01] The hours in one line under the calendar: the picked week and the three-month average
 * ([CAL-06] the month's hours are in the summary over the calendar). A number over its limit is marked in words and color; the week's own warning is the bar
 * beside it and the line under the day. A tap opens 근무·급여.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HoursLine(
    calculator: WorkHoursCalculator,
    limits: com.bamnat.core.hours.HourLimits,
    selected: LocalDate,
    month: YearMonth,
    onOpenStats: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val week = remember(calculator, selected) { calculator.week(selected) }
    val three = remember(calculator, month) { calculator.threeMonths(month) }
    val cal = LocalCalendarColors.current
    Surface(
        onClick = onOpenStats,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                HoursPart("이 주", HoursFormat.hours(week.totalMinutes.toDouble()) + "시간",
                    limits.weeklyLimitHours.takeIf { week.overLimit }, cal.warning)
                // Its own limit (52), not the week's (64), so the number it is over is said.
                HoursPart("3개월 주평균", HoursFormat.hours(three.averageWeeklyMinutes) + "시간",
                    limits.threeMonthAverageLimitHours.takeIf { three.overLimit }, cal.warning)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "근무·급여 열기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** "이 주 73.5시간", and "· 64시간 넘음" in the warning color when it is over [overLimit] hours. */
@Composable
private fun HoursPart(label: String, value: String, overLimit: Int?, warning: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall.merge(NumberStyle),
            color = if (overLimit != null) warning else MaterialTheme.colorScheme.onSurface)
        if (overLimit != null) {
            Text(" · ${overLimit}시간 넘음", style = MaterialTheme.typography.labelMedium, color = warning, fontWeight = FontWeight.Bold)
        }
    }
}

/** [CAL-01] One line about what keeps an alarm due this week from working; the whole line fixes it. */
@Composable
private fun AlarmWarningLine(text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val cal = LocalCalendarColors.current
    Surface(
        onClick = onAction,
        shape = MaterialTheme.shapes.medium,
        color = cal.warningContainer,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, null, tint = cal.warning, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PaintBar(
    snapshot: ScheduleSnapshot,
    tool: PaintTool?,
    last: CalendarViewModel.PaintUndo?,
    onTool: (PaintTool?) -> Unit,
    onUndo: () -> Unit,
    mark: DayMark? = null,
    onMark: (DayMark?) -> Unit = {},
    focus: com.bamnat.core.work.CalendarFocus = com.bamnat.core.work.CalendarFocus.SHIFTS,
    onTypeMonth: (() -> Unit)? = null,
) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
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
                    // [CAL-01] What to do now, or what the last drag did. [CAL-09] Two lines, so the range hint fits.
                    Text(
                        last?.text ?: when {
                            tool == PaintTool.Pick && mark == null -> "고르고 날짜를 누르거나 끌어서 칠하세요"
                            mark == DayMark.LEAVE -> "근무일을 누르거나 끌면 연차가 들어가요 · 길게 누르면 기간"
                            mark == DayMark.ALARM_OFF -> "누르거나 끌면 그날 알람이 꺼져요 · 길게 누르면 기간"
                            else -> "누르거나 끌어서 칠해요 · 다시 누르면 취소 · 길게 누르면 기간"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (last != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                if (last?.undo != null) TextButton(onClick = onUndo) { Text("되돌리기") }
                IconButton(onClick = { onTool(null) }) { Icon(Icons.Filled.Close, "빠른 입력 끝내기") }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // The chips say what a tap on the calendar will do, for TalkBack.
                for (type in snapshot.shiftTypes) {
                    val t = PaintTool.Shift(type.id)
                    FilterChip(
                        selected = tool == t && mark == null,
                        onClick = { onTool(if (tool == t) PaintTool.Pick else t) },
                        label = { Text(type.name) },
                        leadingIcon = { ShiftBadge(type, size = 18.dp, outlined = type.isOff) },
                        modifier = Modifier.semantics { contentDescription = "${type.name} 칠하기" },
                    )
                }
                if (snapshot.shiftTypes.none { it.isOff }) {
                    FilterChip(
                        selected = tool == PaintTool.Off && mark == null,
                        onClick = { onTool(if (tool == PaintTool.Off) PaintTool.Pick else PaintTool.Off) },
                        label = { Text("쉬는 날") },
                        modifier = Modifier.semantics { contentDescription = "쉬는 날 칠하기" },
                    )
                }
                FilterChip(
                    selected = tool == PaintTool.Clear,
                    onClick = { onTool(if (tool == PaintTool.Clear) PaintTool.Pick else PaintTool.Clear) },
                    label = { Text("패턴대로") },
                    leadingIcon = { Icon(Icons.Filled.Autorenew, contentDescription = null) },
                    modifier = Modifier.semantics { contentDescription = "패턴대로 되돌리기" },
                )
                // [CAL-09] Leave and 알람 끄기 painted like shifts, for work kept in shifts.
                if (focus == com.bamnat.core.work.CalendarFocus.SHIFTS || snapshot.hasPattern) {
                    VerticalDivider(Modifier.height(32.dp).align(Alignment.CenterVertically))
                    for (m in DayMark.entries) {
                        FilterChip(
                            selected = mark == m,
                            onClick = {
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.SegmentTick)
                                onMark(if (mark == m) null else m)
                            },
                            label = { Text(m.label) },
                            leadingIcon = {
                                if (m == DayMark.LEAVE) com.bamnat.app.ui.components.LeaveMark(com.bamnat.core.model.Leave(com.bamnat.core.model.LeaveKind.ANNUAL, 4), size = 18.dp)
                                else Icon(Icons.Filled.AlarmOff, contentDescription = null, Modifier.size(18.dp))
                            },
                            modifier = Modifier.semantics { contentDescription = "${m.label} 칠하기" },
                        )
                    }
                }
                // [NURS-01] Day after day from a keypad, instead of picking and tapping each one; last, after
                // the paint chips, since it opens a sheet rather than painting.
                onTypeMonth?.let { open ->
                    AssistChip(onClick = open, label = { Text("한 달 연달아") },
                        leadingIcon = { Icon(Icons.Outlined.Keyboard, contentDescription = null, Modifier.size(18.dp)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonthPickerDialog(
    initial: YearMonth,
    onDismiss: () -> Unit,
    onPick: (YearMonth) -> Unit,
    selected: LocalDate = initial.atDay(1),
    /** [CAL-06] A date far away, picked on a full calendar instead of month by month. */
    onPickDate: ((LocalDate) -> Unit)? = null,
) {
    var year by remember { mutableStateOf(initial.year) }
    var pickingDate by remember { mutableStateOf(false) }
    if (pickingDate && onPickDate != null) {
        val utc = java.time.ZoneOffset.UTC
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = selected.atStartOfDay(utc).toInstant().toEpochMilli(),
        )
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPickDate(java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate()) }
                    pickingDate = false
                }) { Text("이 날로 가기") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("취소") } },
        ) {
            // The title and the picked day in the app's own words, whatever the phone's language.
            androidx.compose.material3.DatePicker(
                state = state,
                title = { Text("갈 날짜 고르기", Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp)) },
                headline = {
                    Text(
                        state.selectedDateMillis?.let { Fmt.dayTitle(java.time.Instant.ofEpochMilli(it).atZone(utc).toLocalDate()) } ?: "날짜를 눌러 주세요",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(start = 24.dp, end = 12.dp, bottom = 12.dp),
                    )
                },
            )
        }
        return
    }
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
                        modifier = Modifier.heightIn(min = 40.dp),
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
        dismissButton = {
            Row {
                if (onPickDate != null) TextButton(onClick = { pickingDate = true }) { Text("날짜 고르기") }
                TextButton(onClick = onDismiss) { Text("닫기") }
            }
        },
    )
}

/**
 * [FEAT-17] This month's memos in short under the calendar, for people who plan by their notes:
 * the date, then the title or first line. Tapping one opens it.
 */
@Composable
private fun MonthMemoCard(
    month: YearMonth,
    memos: List<com.bamnat.core.memo.Memo>,
    /** [DAY-04] With memos under the dates, the card stays to say where the month's memos gather. */
    showEmpty: Boolean,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (memos.isEmpty() && !showEmpty) return
    var all by remember(month) { mutableStateOf(false) }
    val shown = if (all) memos else memos.take(MEMO_PREVIEW_ROWS)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${month.monthValue}월 메모" + if (memos.isNotEmpty()) " ${memos.size}" else "",
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onNew) { Text("메모 쓰기") }
            }
            if (memos.isEmpty()) {
                Text("이번 달 메모가 없어요. 날짜를 누르고 메모를 쓰면 여기 모여요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            for (m in shown) {
                val d = m.date!!
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(m.id) }.padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)}",
                        style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.labelWidth(56.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        val title = m.title.ifBlank { m.preview }
                        Text(title.ifBlank { "빈 메모" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val rest = if (m.title.isNotBlank()) m.preview else ""
                        if (rest.isNotBlank()) Text(rest, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (m.isChecklist && m.itemCount > 0) {
                        Text("${m.checkedCount}/${m.itemCount}", style = MaterialTheme.typography.labelMedium.merge(NumberStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (memos.size > MEMO_PREVIEW_ROWS) {
                androidx.compose.material3.TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 4.dp)) {
                    Text(if (all) "접기" else "${memos.size - MEMO_PREVIEW_ROWS}개 더 보기")
                }
            }
        }
    }
}

private const val MEMO_PREVIEW_ROWS = 4

/**
 * [SLP-01][MKT-07] The month's rest-gap signs under the hours summary: short rest between
 * shifts, a day shift soon after a night, backward rotation, long night runs. Tapping a row
 * picks that day. Shown only when there is something to say. [NURS-05][JOB-03] Its first line is the month's
 * nights (and night pay) and a 공무원's 시간외 against the 57-hour cap; [REST-01] '기준 바꾸기' opens the limits,
 * and each sign says where its limit comes from [REST-02].
 */
@Composable
private fun MonthRestCard(
    issues: List<com.bamnat.core.schedule.RestCheck.Issue>,
    nights: CalendarViewModel.MonthNights?,
    onPick: (LocalDate) -> Unit,
    onRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val count = nights?.nights ?: 0
    val overtime = nights?.overtime
    if (issues.isEmpty() && count == 0 && overtime == null) return
    var all by remember(issues) { mutableStateOf(false) }
    // [REST-02] Folded, the same sign over the month is one line ("연속 야간 3번 · 2·10·18일"); 근거 opens each day.
    val groups = remember(issues) { com.bamnat.core.schedule.RestCheck.group(issues) }
    val shown = if (all) issues.map { com.bamnat.core.schedule.RestCheck.Group(it.kind, it.pattern, listOf(it)) } else groups.take(REST_PREVIEW_ROWS)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (issues.isEmpty()) "근무표 점검" else "휴식 점검 ${issues.size}건 (참고)", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onRules) { Text("기준 바꾸기") }
            }
            if (count > 0 || overtime != null) {
                val parts = listOfNotNull(
                    count.takeIf { it > 0 }?.let { com.bamnat.core.pay.NightSummary.line(it, nights?.nightPay) },
                    overtime?.let { "이달 시간외 ${it.text}" },
                )
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                    color = if (overtime?.over == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                if (overtime?.over == true) Text("시간외는 한 달 ${com.bamnat.core.pay.PublicPayTable.MONTHLY_CAP}시간까지만 수당이 나와요 (공무원수당 등에 관한 규정 제15조)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            for (group in shown) {
                val issue = group.issues.first()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(group.date) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${group.date.dayOfMonth}일", style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.labelWidth(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.title, style = MaterialTheme.typography.bodyMedium)
                        Text(group.text, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (all && issue.source.isNotEmpty()) Text("근거: ${issue.source}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            if (issues.isNotEmpty()) {
                androidx.compose.material3.TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 4.dp)) {
                    Text(when {
                        all -> "접기"
                        groups.size > REST_PREVIEW_ROWS -> "${groups.size - REST_PREVIEW_ROWS}가지 더 · 날짜별로 보기 · 근거"
                        else -> "날짜별로 보기 · 근거"
                    })
                }
            }
        }
    }
}

private const val REST_PREVIEW_ROWS = 3

/** [CAL-17] The one-time tip on the first tap of a date, in the words of the kind of work. */
private fun com.bamnat.core.work.CalendarFocus.longPressTip(): String = "날짜를 길게 누르면 빠른 설정이 열려요. " + when (this) {
    com.bamnat.core.work.CalendarFocus.SHIFTS -> "근무·연차를 바로 바꿀 수 있어요"
    com.bamnat.core.work.CalendarFocus.JOBS -> "일감을 바로 적을 수 있어요"
    com.bamnat.core.work.CalendarFocus.SALES -> "매출을 바로 적을 수 있어요"
    com.bamnat.core.work.CalendarFocus.INVEST -> "매매를 바로 적을 수 있어요"
}

/**
 * [CAL-19] "근무일이 아니에요": a working shift stamped on days off. 대근 keeps the roster and adds the
 * shift's hours as a 대근; the other answer makes them working days. Closing it writes nothing.
 */
@Composable
private fun CoverAskDialog(ask: CalendarViewModel.CoverAsk, onAnswer: (asCover: Boolean, keep: Boolean) -> Unit, onDismiss: () -> Unit) {
    var keep by remember { mutableStateOf(false) }
    val name = ask.type.name
    val days = if (ask.offDays.size == 1) "${Fmt.monthDay(ask.offDays.first())}은 근무일이 아니에요" else "고른 날 중 ${ask.offDays.size}일은 근무일이 아니에요"
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.WarningAmber, null) },
        title = { Text(days) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$name${Fmt.obj(name)} 대근으로 넣을까요?", style = MaterialTheme.typography.bodyLarge)
                Text("대근으로 넣으면 근무표는 쉬는 날 그대로 두고, $name 시간이 대근(휴일 근무)으로 따로 잡혀 수당에 들어가요. " +
                    "근무표가 바뀐 거라면 ‘근무일로 바꾸기’를 고르세요.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { keep = !keep },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(checked = keep, onCheckedChange = { keep = it })
                    Text("이번 빠른 입력 동안 다시 묻지 않기", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { Button(onClick = { onAnswer(true, keep) }) { Text("대근으로") } },
        dismissButton = { TextButton(onClick = { onAnswer(false, keep) }) { Text("근무일로 바꾸기") } },
    )
}
