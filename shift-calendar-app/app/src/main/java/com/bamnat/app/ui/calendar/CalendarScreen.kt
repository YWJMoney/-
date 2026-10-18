package com.bamnat.app.ui.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.style.TextOverflow
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.schedule.ResolvedDay
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.BoxScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.bamnat.app.alarm.Notifications
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.appViewModel
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.components.WarningBanner
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.app.review.InAppReview
import com.bamnat.app.review.InAppReview.findActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.bamnat.app.ui.theme.LocalCalendarColors
import java.time.YearMonth
import java.time.temporal.ChronoUnit

internal const val PAGE_COUNT = 2400
internal const val CENTER_PAGE = PAGE_COUNT / 2

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
) {
    val context = LocalContext.current
    // Re-checked on every return to the app, since the user may change it in system settings.
    var notificationsAllowed by remember { mutableStateOf(Notifications.canPost(context)) }
    var fullScreenAllowed by remember { mutableStateOf(Notifications.canUseFullScreen(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed = Notifications.canPost(context)
        fullScreenAllowed = Notifications.canUseFullScreen(context)
    }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val paint by vm.paint.collectAsStateWithLifecycle()
    val memoDates by vm.memoDates.collectAsStateWithLifecycle()
    val moneyBook by vm.moneyBook.collectAsStateWithLifecycle()
    val datedMemos by vm.datedMemos.collectAsStateWithLifecycle()
    // [SHR-02] Calendars others shared, switched to like accounts.
    val received = appViewModel { ReceivedViewModel(it) }
    val receivedList by received.calendars.collectAsStateWithLifecycle()
    val viewingReceived by received.viewing.collectAsStateWithLifecycle()
    val openingLink by received.opening.collectAsStateWithLifecycle()
    var switching by remember { mutableStateOf(false) }
    // A link id to open, "" to paste one, null when closed.
    var adding by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(openingLink) {
        openingLink?.let { id ->
            adding = id
            received.consumeOpening()
        }
    }
    // [FEAT-17] Memo-first cells show the start of the day's first memo.
    val memoTexts = remember(datedMemos, settings.memoFirst) {
        if (!settings.memoFirst) emptyMap()
        else datedMemos.groupBy { it.date!! }.mapValues { (_, l) -> l.first().let { m -> m.title.ifBlank { m.preview } }.trim().take(4) }
            .filterValues { it.isNotEmpty() }
    }
    var quickMoney by remember { mutableStateOf(false) }

    // Re-read the date every minute so "today" moves at midnight while the app is open.
    val today by produceState(LocalDate.now()) {
        while (true) {
            delay(60_000)
            value = LocalDate.now()
        }
    }

    // [OPS-20] Ask for a store review once, shortly after the first pattern was saved.
    LaunchedEffect(settings.reviewDue, settings.reviewAsked) {
        if (settings.reviewDue && !settings.reviewAsked) {
            delay(1500)
            context.findActivity()?.let { InAppReview.ask(it) }
            vm.markReviewAsked()
        }
    }

    // On phones the day opens in a sheet over the calendar.
    var sheetOpen by remember { mutableStateOf(false) }
    // [DAY-03] While the sheet is open its own snackbar says what happened, above the sheet, not hidden under it.
    val sheetSnackbar = remember { SnackbarHostState() }
    val messageHost by rememberUpdatedState(if (sheetOpen && !wide) sheetSnackbar else snackbarHostState)
    LaunchedEffect(vm) {
        vm.messages.collect { msg ->
            val result = messageHost.showSnackbar(
                message = msg.message,
                actionLabel = if (msg.undo != null) msg.actionLabel else null,
                duration = if (msg.undo != null && msg.actionLabel != "되돌리기") SnackbarDuration.Long else SnackbarDuration.Short,
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
    // Jumps without a sliding animation: buttons should feel instant.
    val goToMonth: (YearMonth) -> Unit = { m -> scope.launch { pagerState.scrollToPage(monthToPage(baseMonth, m)) } }

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

    // Unfolded, the day shows beside the calendar; a sheet left open must not pop up again on folding.
    LaunchedEffect(wide) { if (wide) sheetOpen = false }
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
    // [DAY-01] A tap picks the day and shows it under the calendar; a tap on the picked day opens it all.
    val onDayClick: (LocalDate) -> Unit = { date ->
        val painting = paint != null
        val again = date == selected
        vm.onDayTapped(date)
        val m = YearMonth.from(date)
        if (m != visibleMonth) goToMonth(m)
        if (!wide && !painting && again) sheetOpen = true
    }
    // [DAY-01] ‹ › and swipes in the day header move the calendar along.
    val onDateChange: (LocalDate) -> Unit = { date ->
        vm.select(date)
        val m = YearMonth.from(date)
        if (m != visibleMonth) goToMonth(m)
    }
    val groups = rememberDayGroupState()
    var memoFocus by remember { mutableIntStateOf(0) }
    val onMemo: () -> Unit = {
        memoFocus++
        if (!wide) sheetOpen = true
    }
    val onQuickAction: (LocalDate, QuickAction) -> Unit = { date, action ->
        val day = snap.resolver.resolve(date)
        when (action) {
            is QuickAction.Shift -> vm.assignQuick(day, action.override, snap)
            is QuickAction.Extra -> vm.toggleExtra(day, action.choice)
            is QuickAction.SetLeave -> vm.setLeave(date, action.leave, announce = true)
            is QuickAction.Alarm -> vm.setAlarmSkipped(date, action.skipped, announce = true)
            QuickAction.Open -> {
                vm.select(date)
                if (!wide) sheetOpen = true
            }
        }
    }
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
    val shareLinks = remember { com.bamnat.app.share.ShareLinkClient().enabled }
    if (showShare) ShareDialog(
        snapshot = snap, month = visibleMonth,
        onImage = { scope.launch { MonthImage.share(context, snap, visibleMonth, settings.weekStart) }; vm.logShare() },
        onDismiss = { showShare = false },
        onOpenReceived = { showShare = false; adding = "" },
    )
    // [SHR-02] Whose calendar is on screen, once one was received.
    val identityBar: @Composable () -> Unit = {
        if (receivedList.isNotEmpty() && received.enabled) {
            CalendarIdentityBar(
                calendars = receivedList,
                viewing = viewingReceived,
                onOpenSwitcher = { switching = true },
                onView = { id -> vm.setPaint(null); received.view(id) },
            )
        }
    }
    // Back from someone's calendar returns to the user's own, like leaving another account.
    androidx.activity.compose.BackHandler(enabled = viewingReceived != null) { received.view(null) }

    val memoPart: @Composable () -> Unit = {
        if (settings.calendarMemos) {
            Spacer(Modifier.height(12.dp))
            MonthMemoCard(
                month = visibleMonth,
                memos = remember(datedMemos, visibleMonth) { datedMemos.filter { YearMonth.from(it.date!!) == visibleMonth } },
                memoFirst = settings.memoFirst,
                onOpen = onOpenMemo,
                onNew = { onNewMemo(if (YearMonth.from(selected) == visibleMonth) selected else visibleMonth.atDay(1)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    // [DAY-01] The FAB folds to its icon while the page goes on under it (the day card, the month's hours),
    // so what it would cover stays readable and in reach; at the page's end there is room for its label.
    val pageScroll = rememberScrollState()
    val fabExpanded by remember { derivedStateOf { !pageScroll.canScrollForward } }

    val calendarPart: @Composable () -> Unit = {
        identityBar()
        CalendarHeader(
            month = visibleMonth,
            onPrev = { goToMonth(visibleMonth.minusMonths(1)) },
            onNext = { goToMonth(visibleMonth.plusMonths(1)) },
            onToday = {
                vm.select(LocalDate.now())
                goToMonth(YearMonth.now())
            },
            onPickMonth = { showMonthPicker = true },
            onShare = {
                // [MKT-09] With a link server the icon offers picture or link; without one it sends the picture.
                if (shareLinks) showShare = true
                else {
                    scope.launch { MonthImage.share(context, snap, visibleMonth, settings.weekStart) }
                    vm.logShare()
                }
            },
            onOpenPattern = onOpenPattern,
        )
        // [WORK-01] Only work that repeats needs a pattern; field, shop and hourly work are written day by day.
        if (snap.isEmpty && settings.workStyle?.usesPattern != false) {
            EmptyPatternCard(onOpenPattern)
        }
        // [REL-11] Without notifications an alarm still sounds, but nothing on the lock screen stops it.
        if (!notificationsAllowed && settings.alarmsEnabled && snap.shiftTypes.any { it.alarmEnabled }) {
            WarningBanner(
                title = "알림이 꺼져 있어 근무 알람을 잠금 화면에서 끌 수 없어요",
                message = "소리는 울리지만 끄기·다시 울림 버튼이 안 보여요. 눌러서 알림을 허용해 주세요.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable(onClick = onOpenSettings),
            )
        }
        // [REL-12] Android 14+ can block the full-screen alarm; then only a small notification shows.
        if (notificationsAllowed && !fullScreenAllowed && settings.alarmsEnabled && snap.shiftTypes.any { it.alarmEnabled }) {
            WarningBanner(
                title = "잠금 화면에 알람 화면이 뜨지 않게 막혀 있어요",
                message = "알람이 울릴 때 크게 끄기 버튼이 보이도록 눌러서 ‘전체 화면 알림’을 허용해 주세요.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable {
                    if (android.os.Build.VERSION.SDK_INT >= 34) runCatching {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            android.net.Uri.parse("package:${context.packageName}")))
                    }
                },
            )
        }
        // [SLP-14] 오늘의 흐름 while this month is on screen, for work that comes in shifts and hours.
        if (settings.sleepTips && settings.workStyle?.usesPayEngine != false && !snap.isEmpty && visibleMonth == YearMonth.from(today)) {
            TodayFlowSection(vm, snap, settings, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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
        MonthShiftCounts(snap, visibleMonth, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        WeekdayHeader(settings.weekStart, Modifier.padding(horizontal = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val cellHeight = (maxWidth / 7 * 1.3f).coerceIn(66.dp, 100.dp)
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
                    showLunar = settings.showLunar,
                    cellHeight = cellHeight,
                    onDayClick = onDayClick,
                    onQuickAction = onQuickAction,
                    money = moneyBook,
                    showMoney = settings.workStyle?.usesPayEngine == false,
                    memoTexts = memoTexts,
                )
            }
        }
        // [FEAT-17] Memo-first puts the month's memos right under the grid.
        if (settings.memoFirst) memoPart()
        Spacer(Modifier.height(12.dp))
        val moneyFirst = settings.workStyle?.usesPayEngine == false
        if (!wide) {
            if (moneyFirst) {
                MoneyDayCard(
                    date = selected,
                    isToday = selected == today,
                    day = remember(moneyBook, selected) { moneyBook.on(selected) },
                    memoCount = if (selected in memoDates) 1 else 0,
                    onOpen = { sheetOpen = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    memoPreview = remember(datedMemos, selected) { datedMemos.firstOrNull { it.date == selected }?.let { it.title.ifBlank { it.preview } } },
                )
            } else {
                DayPreview(
                    day = remember(snap, selected) { snap.resolver.resolve(selected) },
                    today = today,
                    snapshot = snap,
                    vm = vm,
                    onDateChange = onDateChange,
                    onOpen = { sheetOpen = true },
                    onMemo = onMemo,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        if (moneyFirst) {
            // [WORK-04] Field and shop work care about the month's money, not weekly hour limits.
            MonthMoneyCard(
                month = remember(moneyBook, visibleMonth) { moneyBook.month(visibleMonth) },
                unpaid = remember(moneyBook, today) { com.bamnat.core.money.MoneyCalculator.unpaid(moneyBook.jobs, today).sumOf { it.unpaid } },
                onOpen = onOpenMoney,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            HoursSummary(
                calculator = calculator,
                settings = settings,
                selected = selected,
                month = visibleMonth,
                onOpenStats = onOpenStats,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (settings.restCheck) {
                val rest = remember(snap, visibleMonth, settings.strictRest) { vm.restIssues(snap, visibleMonth, settings.strictRest) }
                MonthRestCard(rest, onPick = vm::select, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
            }
        }
        if (!settings.memoFirst) memoPart()
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
        )
    }

    val bottomSpace = if (paint != null) 132.dp else 88.dp
    val fab: @Composable BoxScope.() -> Unit = {
        val style = settings.workStyle
        val place = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        if (paint == null && style != null && !style.usesPayEngine) {
            // [WORK-02][WORK-03] Field and shop work add the day's job or sales instead of stamping shifts.
            val label = if (style.usesJobs) "일감 적기" else "매출 적기"
            // The extended FAB drops its text from semantics, so the icon carries the label for TalkBack.
            ExtendedFloatingActionButton(
                onClick = { quickMoney = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = label) },
                text = { Text(label) },
                expanded = fabExpanded,
                modifier = place,
            )
        } else if (paint == null) {
            val first = snap.shiftTypes.firstOrNull()
            ExtendedFloatingActionButton(
                onClick = { vm.setPaint(first?.let { PaintTool.Shift(it.id) } ?: PaintTool.Clear) },
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
            )
        }
    } else Box(Modifier.fillMaxSize()) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1.05f).fillMaxHeight()) {
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
                detailPart(Modifier.weight(1f).fillMaxHeight().padding(top = 4.dp), bottomSpace)
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
                PaintBar(snapshot = snap, tool = paint, onTool = vm::setPaint)
            }
        }
    }

    if (quickMoney) {
        if (settings.workStyle?.usesJobs == true) {
            com.bamnat.app.ui.money.JobDialog(selected, null, onSave = { vm.saveJob(it); quickMoney = false }, onDismiss = { quickMoney = false })
        } else {
            com.bamnat.app.ui.money.SalesDialog(selected, moneyBook.on(selected).sales,
                onSave = { vm.saveSales(it); quickMoney = false }, onDismiss = { quickMoney = false })
        }
    }

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
            calendars = receivedList,
            viewingId = viewingReceived?.id,
            onPick = { id -> switching = false; vm.setPaint(null); received.view(id) },
            onAdd = { switching = false; adding = "" },
            onRemove = { cal -> received.remove(cal.id) },
            onDismiss = { switching = false },
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
        )
    }
}

/** [DAY-01][DAY-03] The whole day over the calendar, open at once, with its own snackbar for undo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DaySheet(snackbar: SnackbarHostState, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for ((type, n) in counts.first) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShiftBadge(type, size = 18.dp, outlined = type.isOff)
                Text(" $n", style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
            }
        }
        for ((kind, n) in counts.second) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExtraKindMark(kind, size = 18.dp)
                Text(" ${kind.name} $n", style = MaterialTheme.typography.labelLarge.merge(NumberStyle))
            }
        }
    }
}

@Composable
private fun MoneyDayCard(
    date: LocalDate,
    isToday: Boolean,
    day: com.bamnat.core.money.DayBook,
    memoCount: Int,
    onOpen: () -> Unit,
    modifier: Modifier,
    memoPreview: String? = null,
) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.dayTitle(date) + if (isToday) " · 오늘" else "", style = MaterialTheme.typography.titleMedium)
                val parts = buildList {
                    day.jobs.firstOrNull()?.let { j ->
                        add(j.start?.let { Fmt.time(it) + " " }.orEmpty() + j.title + if (day.jobs.size > 1) " 외 ${day.jobs.size - 1}건" else "")
                    }
                    day.sales?.let { add("매출 ${Fmt.won(it.sales)}") }
                    val spent = day.entries.filter { it.kind == com.bamnat.core.money.MoneyKind.EXPENSE }.sumOf { it.amount }
                    if (spent > 0) add("지출 ${Fmt.won(spent)}")
                    if (memoCount > 0) add(memoPreview?.takeIf { it.isNotBlank() }?.let { "메모: $it" } ?: "메모 있음")
                    if (isEmpty()) add("기록 없음 · 눌러서 적기")
                }
                Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("열기", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MonthMoneyCard(month: com.bamnat.core.money.MonthMoney, unpaid: Long, onOpen: () -> Unit, modifier: Modifier) {
    val cal = LocalCalendarColors.current
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${month.month.monthValue}월 수입", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.won(month.income), style = MaterialTheme.typography.titleLarge, color = cal.income)
            }
            Column(Modifier.weight(1f)) {
                Text("받을 돈", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Fmt.won(unpaid), style = MaterialTheme.typography.titleLarge,
                    color = if (unpaid > 0) cal.warning else MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "돈 탭 열기", tint = MaterialTheme.colorScheme.primary)
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
    onShare: () -> Unit = {},
) {
    // [QA-01] A cover screen (~344dp) cannot fit four icons, the month and 오늘: share and pattern
    // go into a menu there instead of being squeezed off the edge.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 392.dp
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
        Text(
            Fmt.month(month),
            style = if (narrow) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onPickMonth)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        )
        IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToday) { Text("오늘") }
        if (narrow) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "더 보기") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("근무표 공유") }, leadingIcon = { Icon(Icons.Filled.Share, null) },
                        onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("근무 패턴 설정") }, leadingIcon = { Icon(Icons.Filled.EventRepeat, null) },
                        onClick = { menu = false; onOpenPattern() })
                }
            }
        } else {
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "근무표 공유") }
            IconButton(onClick = onOpenPattern) { Icon(Icons.Filled.EventRepeat, "근무 패턴 설정") }
        }
    }
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
                value = HoursFormat.hours(week.totalMinutes.toDouble()) + "시간",
                caption = "${Fmt.shortDate(week.start)}–${Fmt.shortDate(week.end)}",
                warning = week.overLimit,
                onClick = onOpenStats,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "${month.monthValue}월 합계",
                value = HoursFormat.hours(monthSummary.totalMinutes.toDouble()) + "시간",
                caption = "근무 ${monthSummary.workDays}일",
                onClick = onOpenStats,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "3개월 주평균",
                value = HoursFormat.hours(three.averageWeeklyMinutes) + "시간",
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
                    message = others.joinToString(", ") { "${Fmt.shortDate(it.start)}주 ${HoursFormat.hours(it.totalMinutes.toDouble())}시간" },
                )
            }
        }
        if (three.overLimit) {
            WarningBanner(
                title = "3개월 평균 주 ${settings.averageLimitHours}시간 초과",
                message = "${three.firstMonth.monthValue}–${three.lastMonth.monthValue}월 평균이 " +
                    "주 ${HoursFormat.hours(three.averageWeeklyMinutes)}시간이에요.",
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

/**
 * [FEAT-17] This month's memos in short under the calendar, for people who plan by their notes:
 * the date, then the title or first line. Tapping one opens it.
 */
@Composable
private fun MonthMemoCard(
    month: YearMonth,
    memos: List<com.bamnat.core.memo.Memo>,
    memoFirst: Boolean,
    onOpen: (Long) -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (memos.isEmpty() && !memoFirst) return
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
                    Modifier.fillMaxWidth().clickable { onOpen(m.id) }.padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${d.dayOfMonth}일 ${d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)}",
                        style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(56.dp),
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
 * picks that day. Shown only when there is something to say.
 */
@Composable
private fun MonthRestCard(issues: List<com.bamnat.core.schedule.RestCheck.Issue>, onPick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    if (issues.isEmpty()) return
    var all by remember(issues) { mutableStateOf(false) }
    val shown = if (all) issues else issues.take(REST_PREVIEW_ROWS)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text("휴식 점검 ${issues.size}건 (참고)", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            for (issue in shown) {
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(issue.date) }.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${issue.date.dayOfMonth}일", style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(issue.kind.label, style = MaterialTheme.typography.bodyMedium)
                        Text(issue.text, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (issues.size > REST_PREVIEW_ROWS) {
                androidx.compose.material3.TextButton(onClick = { all = !all }, modifier = Modifier.padding(start = 4.dp)) {
                    Text(if (all) "접기" else "${issues.size - REST_PREVIEW_ROWS}개 더 보기")
                }
            }
        }
    }
}

private const val REST_PREVIEW_ROWS = 3
