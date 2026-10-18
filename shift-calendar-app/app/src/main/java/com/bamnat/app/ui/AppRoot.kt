package com.bamnat.app.ui

import com.bamnat.app.HomeRequests
import com.bamnat.app.NoticeLinks
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import android.os.Build
import androidx.compose.foundation.layout.Column
import com.bamnat.app.alarm.AlarmService
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bamnat.app.AppContainer
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.ui.calendar.CalendarScreen
import com.bamnat.app.ui.calendar.CalendarViewModel
import com.bamnat.app.ui.components.AdSlot
import com.bamnat.app.ui.diag.DiagnosticsScreen
import com.bamnat.app.ui.diag.DiagnosticsViewModel
import com.bamnat.core.diag.DiagEvents
import com.bamnat.app.ui.pay.PayScreen
import com.bamnat.app.ui.pay.PayViewModel
import com.bamnat.app.ui.memo.MemoEditorScreen
import com.bamnat.app.ui.memo.MemoEditorViewModel
import com.bamnat.app.ui.memo.MemoListScreen
import com.bamnat.app.ui.memo.MemoListViewModel
import com.bamnat.app.ui.memo.MemoTrashScreen
import com.bamnat.app.ui.memo.MemoTrashViewModel
import com.bamnat.app.ui.settings.ExtraKindsScreen
import com.bamnat.app.ui.settings.ExtraKindsViewModel
import com.bamnat.app.ui.settings.InquiryScreen
import com.bamnat.app.ui.settings.InquiryViewModel
import com.bamnat.app.ui.settings.SettingsScreen
import com.bamnat.app.ui.settings.SettingsViewModel
import com.bamnat.app.ui.shifts.PatternScreen
import com.bamnat.app.ui.shifts.ShiftTypesScreen
import com.bamnat.app.ui.shifts.ShiftsViewModel
import com.bamnat.app.ui.stats.StatsScreen
import com.bamnat.app.ui.stats.StatsViewModel
import com.bamnat.app.ui.money.MoneyScreen
import com.bamnat.app.ui.money.MoneyViewModel
import com.bamnat.app.ui.work.WorkStyleScreen
import com.bamnat.app.ui.setup.SetupHost
import com.bamnat.app.ui.onboarding.ConsentPages
import com.bamnat.app.ui.onboarding.NewConsentPrompt
import com.bamnat.app.ui.setup.SetupViewModel
import com.bamnat.app.ui.setup.SetupPaySheet
import com.bamnat.app.ui.company.CompanyDetailScreen
import com.bamnat.app.ui.company.CompanyDetailViewModel
import com.bamnat.app.ui.company.CompanyFindScreen
import com.bamnat.app.ui.company.CompanyFindViewModel
import com.bamnat.app.ui.company.CompanyNudgeCards
import com.bamnat.app.ui.company.CompanyNudgeViewModel
import com.bamnat.app.ui.company.CompanyPayScreen
import com.bamnat.app.ui.company.CompanyPayViewModel
import com.bamnat.app.ui.company.CompanyReportScreen
import com.bamnat.app.ui.company.CompanyReportViewModel
import com.bamnat.app.ui.company.CompanySentScreen
import com.bamnat.app.ui.company.CompanySettingsRows
import com.bamnat.app.ui.company.FixPlace
import com.bamnat.app.ui.company.InviteSheet
import androidx.compose.runtime.rememberCoroutineScope
import com.bamnat.core.work.WorkStyle
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class TopLevel(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    CALENDAR("calendar", "달력", Icons.Outlined.CalendarMonth, Icons.Filled.CalendarMonth),
    /** [WORK-06] Income, spending and what is left, for every kind of work. */
    MONEY("money", "돈", Icons.Outlined.AccountBalanceWallet, Icons.Filled.AccountBalanceWallet),
    MEMOS("memos", "메모", Icons.AutoMirrored.Outlined.StickyNote2, Icons.AutoMirrored.Filled.StickyNote2),
    /** [NAV-01] Hours and pay of the month together; field and shop work, paid in 돈, keep 통계. */
    STATS("stats", "근무·급여", Icons.Outlined.Insights, Icons.Filled.Insights),
    SETTINGS("settings", "설정", Icons.Outlined.Settings, Icons.Filled.Settings),
    ;

    fun labelFor(style: WorkStyle?): String = if (this == STATS && style?.usesPayEngine == false) "통계" else label
}

private object Routes {
    const val SHIFT_TYPES = "shift_types"
    const val PATTERN = "pattern?weekdays={weekdays}&setup={setup}"
    const val WORK_STYLE = "work_style"

    fun pattern(weekdays: Boolean = false, setup: Boolean = false) = "pattern?weekdays=$weekdays&setup=$setup"
    const val MEMO_TRASH = "memo_trash"
    const val DIAGNOSTICS = "diagnostics?testing={testing}"

    fun diagnostics(testing: Boolean) = "diagnostics?testing=$testing"
    const val PAY = "pay"
    const val EXTRA_KINDS = "extra_kinds"
    const val MEMO = "memo/{id}?date={date}&checklist={checklist}"

    fun memo(id: Long, date: LocalDate? = null, checklist: Boolean = false) =
        "memo/$id?date=${date?.toString() ?: ""}&checklist=$checklist"

    // [COMM-01][COMM-02] The company rules server: find a company, share a setup, follow what was sent.
    const val COMPANY_FIND = "company_find?setup={setup}&weekdays={weekdays}"
    const val COMPANY = "company/{id}?setup={setup}&weekdays={weekdays}"
    const val COMPANY_REPORT = "company_report"
    const val COMPANY_SENT = "company_sent"
    /** [ADMIN-02] 설정 > 문의하기. */
    const val INQUIRY = "inquiry"

    // [SLP-16][RECV-02][IMPORT-01] The sleep log, money owed, and bringing in the phone's calendar.
    const val SLEEP = "sleep"
    const val OWED = "owed"
    const val CALENDAR_IMPORT = "calendar_import"

    fun companyFind(setup: Boolean = false, weekdays: Boolean = false) = "company_find?setup=$setup&weekdays=$weekdays"
    fun company(id: String, setup: Boolean, weekdays: Boolean) = "company/$id?setup=$setup&weekdays=$weekdays"
}

@Composable
fun AppRoot(windowSizeClass: WindowSizeClass, container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val topLevel = TopLevel.entries.firstOrNull { it.route == route }
    val showNav = topLevel != null || route == null
    // Fold unfolded, tablets and landscape get a navigation rail and two-pane calendar.
    val wide = windowSizeClass.widthSizeClass != WindowWidthSizeClass.Compact
    val snackbar = remember { SnackbarHostState() }

    // [SETUP-01] First launch asks how the user works and sets it all up before the calendar. Phones
    // that already have a pattern were set up before this question existed, so they are shift workers
    // and are not asked; a setup on its way (or being saved) is never taken for one of those.
    val settingsNow by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val setupDraft by container.setupDrafts.draft.collectAsStateWithLifecycle()
    val needsSetup by produceState<Boolean?>(if (settingsNow.workStyle != null) false else null, settingsNow.workStyle, setupDraft == null) {
        value = when {
            settingsNow.workStyle != null -> {
                // Set some other way meanwhile (a backup restored in settings): the setup left is not needed.
                if (setupDraft?.committing == false) container.setupDrafts.clear()
                false
            }
            setupDraft == null && container.scheduleRepository.patterns.first().isNotEmpty() -> {
                container.settingsRepository.update { it.copy(workStyle = WorkStyle.SHIFT) }
                false
            }
            else -> true
        }
    }
    // [ONBOARD-01] A new phone sees what the app may ask for, and why, before the first setup.
    val consentSeen by container.consentStore.seen.collectAsStateWithLifecycle()
    when (needsSetup) {
        null -> return
        // "나중에" opens the calendar with a card to go on from where the user stopped.
        true -> if (setupDraft?.later != true) {
            if (consentSeen == null && setupDraft == null) {
                ConsentPages(container, onDone = {})
                return
            }
            SetupHost(appViewModel { SetupViewModel(it) })
            return
        }
        false -> Unit
    }

    // Which screens get used decides what is worth building next [MEAS-03].
    LaunchedEffect(route) {
        route?.let { container.telemetry.event(DiagEvents.SCREEN, it.substringBefore('/').substringBefore('?')) }
    }

    // Opening the app (again) always lands on the calendar.
    val homeRequests by HomeRequests.count.collectAsStateWithLifecycle()
    LaunchedEffect(homeRequests) {
        // Kept per process with the count: a screen rebuilt (a fold, a restore) does not take one twice or miss one.
        if (homeRequests != HomeRequests.handled) {
            HomeRequests.handled = homeRequests
            // Only from another tab: an editor the user left open (pay setup, a pattern) keeps their input.
            // A tapped notice says where to go instead [SLP-15].
            val current = nav.currentBackStackEntry?.destination?.route
            if (NoticeLinks.target.value == null && current != TopLevel.CALENDAR.route && TopLevel.entries.any { it.route == current }) {
                nav.navigate(TopLevel.CALENDAR.route) {
                    popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                    launchSingleTop = true
                }
            }
            // [NAV-01] Away long enough to count as opening the app again: this month, like a fresh start.
            if (NoticeLinks.target.value == null && TopLevel.entries.any { it.route == current }) {
                container.shownMonth.show(java.time.YearMonth.now())
            }
        }
    }

    // [IMPORT-02] When the user asked for it, new events in the chosen calendars are offered on opening the app.
    LaunchedEffect(homeRequests) {
        val count = runCatching { container.calendarImporter.countNew(LocalDate.now()) }.getOrDefault(0)
        if (count > 0 && snackbar.showSnackbar("캘린더에 새 일정 ${count}개가 있어요", actionLabel = "가져오기",
                duration = androidx.compose.material3.SnackbarDuration.Long) == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            NoticeLinks.open(NoticeLinks.Target.CALENDAR_IMPORT)
        }
    }

    // [SLP-15] A tapped notice opens the screen it is about. The calendar finds today itself.
    val noticeTarget by NoticeLinks.target.collectAsStateWithLifecycle()
    LaunchedEffect(noticeTarget) {
        val target = noticeTarget ?: return@LaunchedEffect
        when (target) {
            // Over an editor the calendar goes on top, so the editor's input is still there after back.
            NoticeLinks.Target.TODAY -> {
                val current = nav.currentBackStackEntry?.destination?.route
                when {
                    current == TopLevel.CALENDAR.route -> Unit
                    TopLevel.entries.any { it.route == current } -> nav.navigateTopLevel(TopLevel.CALENDAR.route)
                    else -> nav.navigate(TopLevel.CALENDAR.route) { launchSingleTop = true }
                }
            }
            NoticeLinks.Target.MONEY -> { nav.navigateTopLevel(TopLevel.MONEY.route); NoticeLinks.consume(target) }
            NoticeLinks.Target.MEMOS -> { nav.navigateTopLevel(TopLevel.MEMOS.route); NoticeLinks.consume(target) }
            NoticeLinks.Target.LEAVE -> { nav.navigate(Routes.PAY) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.SLEEP -> { nav.navigate(Routes.SLEEP) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.OWED -> { nav.navigate(Routes.OWED) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.CALENDAR_IMPORT -> { nav.navigate(Routes.CALENDAR_IMPORT) { launchSingleTop = true }; NoticeLinks.consume(target) }
            // [SET-01] Settings takes the target itself, to open on the alarm's state from wherever it was.
            NoticeLinks.Target.ALARM_CHECK -> {
                val current = nav.currentBackStackEntry?.destination?.route
                when {
                    current == TopLevel.SETTINGS.route -> Unit
                    TopLevel.entries.any { it.route == current } -> nav.navigateTopLevel(TopLevel.SETTINGS.route)
                    else -> nav.navigate(TopLevel.SETTINGS.route) { launchSingleTop = true }
                }
            }
        }
    }

    // [SHR-02] A tapped shared calendar link opens on the calendar. Over an editor it goes on top,
    // so the editor's input is still there after back.
    val openingLink by container.received.opening.collectAsStateWithLifecycle()
    LaunchedEffect(openingLink) {
        if (openingLink == null) return@LaunchedEffect
        val current = nav.currentBackStackEntry?.destination?.route
        when {
            current == TopLevel.CALENDAR.route -> Unit
            TopLevel.entries.any { it.route == current } -> nav.navigateTopLevel(TopLevel.CALENDAR.route)
            else -> nav.navigate(TopLevel.CALENDAR.route) { launchSingleTop = true }
        }
    }

    // Undo for memo deletion, wherever the memo was deleted from.
    val lastDeleted by container.memoRepository.lastDeleted.collectAsStateWithLifecycle()
    LaunchedEffect(lastDeleted) {
        val deleted = lastDeleted ?: return@LaunchedEffect
        val title = deleted.label
        val result = snackbar.showSnackbar(
            message = when {
                deleted.memos.size > 1 -> "메모 ${deleted.memos.size}개를 휴지통으로 옮겼어요"
                title.isBlank() -> "메모를 휴지통으로 옮겼어요"
                else -> "‘$title’ 메모를 휴지통으로 옮겼어요"
            },
            actionLabel = "되돌리기",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            container.appScope.launch { container.memoRepository.undoDelete() }
        } else {
            container.memoRepository.consumeLastDeleted()
        }
    }

    val navigateTop: (TopLevel) -> Unit = { dest -> nav.navigateTopLevel(dest.route) }

    Row(Modifier.fillMaxSize()) {
        if (wide && showNav) {
            NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Spacer(Modifier.height(12.dp))
                for (dest in TopLevel.entries) {
                    val selected = dest == topLevel
                    NavigationRailItem(
                        selected = selected,
                        onClick = { navigateTop(dest) },
                        icon = { Icon(if (selected) dest.selectedIcon else dest.icon, contentDescription = null) },
                        label = { Text(dest.labelFor(settingsNow.workStyle), maxLines = 1) },
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f),
            snackbarHost = { SnackbarHost(snackbar) },
            // Inner screens with their own top bar handle insets themselves.
            contentWindowInsets = if (showNav) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!wide && showNav) {
                    Column {
                        AdSlot()
                        NavigationBar {
                            for (dest in TopLevel.entries) {
                                val selected = dest == topLevel
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTop(dest) },
                                    icon = { Icon(if (selected) dest.selectedIcon else dest.icon, contentDescription = null) },
                                    label = { Text(dest.labelFor(settingsNow.workStyle), maxLines = 1) },
                                )
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                RingingBanner()
                AppNavHost(
                    nav = nav,
                    container = container,
                    wide = wide,
                    snackbar = snackbar,
                    modifier = Modifier.weight(1f),
                )
                if (wide && showNav) AdSlot()
            }
        }
    }

    // [ONBOARD-01] A phone set up before the consent pages is asked only about what is new since.
    NewConsentPrompt(container)

    // [SETUP-01] Right after the first setup, once: the pay comes next for work paid by the hour or month.
    val justSaved by container.firstSetup.saved.collectAsStateWithLifecycle()
    val saved = justSaved
    if (saved != null) {
        val payProfile by container.payStore.profile.collectAsStateWithLifecycle()
        if (saved.style?.usesPayEngine != true || payProfile != null) {
            LaunchedEffect(saved) { container.firstSetup.consumeSaved() }
        } else {
            SetupPaySheet(
                companyPay = container.companyStore.pendingPay != null,
                onNow = {
                    container.firstSetup.consumeSaved()
                    nav.navigate(Routes.PAY) { launchSingleTop = true }
                },
                onLater = { container.firstSetup.consumeSaved() },
            )
        }
    }
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    container: AppContainer,
    wide: Boolean,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    // Outlives the screen that starts it, e.g. a message shown after 회사 찾기 closes.
    val scope = rememberCoroutineScope()
    // [VIS-02] Short, quiet motion: tabs fade through each other, a screen opened from one slides in a
    // little and fades. The system's "remove animations" setting turns both off.
    NavHost(
        navController = nav,
        startDestination = TopLevel.CALENDAR.route,
        modifier = modifier,
        enterTransition = {
            if (betweenTabs()) fadeIn(tween(TAB_IN_MILLIS, delayMillis = TAB_OUT_MILLIS))
            else slideInHorizontally(tween(SCREEN_MILLIS, easing = FastOutSlowInEasing)) { it / 4 } + fadeIn(tween(SCREEN_MILLIS))
        },
        exitTransition = {
            if (betweenTabs()) fadeOut(tween(TAB_OUT_MILLIS)) else fadeOut(tween(SCREEN_MILLIS / 2))
        },
        popEnterTransition = {
            if (betweenTabs()) fadeIn(tween(TAB_IN_MILLIS, delayMillis = TAB_OUT_MILLIS)) else fadeIn(tween(SCREEN_MILLIS))
        },
        popExitTransition = {
            if (betweenTabs()) fadeOut(tween(TAB_OUT_MILLIS))
            else slideOutHorizontally(tween(SCREEN_MILLIS, easing = FastOutSlowInEasing)) { it / 4 } + fadeOut(tween(SCREEN_MILLIS))
        },
    ) {
        composable(TopLevel.CALENDAR.route) {
            val vm = appViewModel { CalendarViewModel(it) }
            CalendarScreen(
                vm = vm,
                wide = wide,
                snackbarHostState = snackbar,
                onOpenMemo = { id -> nav.navigate(Routes.memo(id)) },
                onNewMemo = { date -> nav.navigate(Routes.memo(0, date)) },
                onOpenPattern = { nav.navigate(Routes.pattern()) { launchSingleTop = true } },
                onOpenShiftTypes = { nav.navigate(Routes.SHIFT_TYPES) { launchSingleTop = true } },
                onOpenStats = { nav.navigateTopLevel(TopLevel.STATS.route) },
                onOpenMoney = { nav.navigateTopLevel(TopLevel.MONEY.route) },
                onOpenPay = { nav.navigate(Routes.PAY) { launchSingleTop = true } },
                onOpenSettings = { nav.navigateTopLevel(TopLevel.SETTINGS.route) },
                onOpenExtraKinds = { nav.navigate(Routes.EXTRA_KINDS) { launchSingleTop = true } },
            )
        }
        composable(TopLevel.MONEY.route) {
            val vm = appViewModel { MoneyViewModel(it) }
            MoneyScreen(vm = vm, wide = wide, snackbar = snackbar, onOpenPay = { nav.navigate(Routes.PAY) { launchSingleTop = true } })
        }
        composable(Routes.WORK_STYLE) {
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            WorkStyleScreen(current = settings.workStyle, title = "근무 형태 바꾸기", onPick = { style ->
                container.settingsRepository.update { it.copy(workStyle = style) }
                if (style == WorkStyle.HOURLY) container.appScope.launch { container.scheduleRepository.addHourlyTypes() }
                nav.popFrom(Routes.WORK_STYLE)
            })
        }
        composable(TopLevel.MEMOS.route) {
            val vm = appViewModel { MemoListViewModel(it) }
            MemoListScreen(
                vm = vm,
                onOpen = { id -> nav.navigate(Routes.memo(id)) },
                onNew = { checklist, date -> nav.navigate(Routes.memo(0, date, checklist)) },
                onOpenTrash = { nav.navigate(Routes.MEMO_TRASH) { launchSingleTop = true } },
            )
        }
        composable(Routes.MEMO_TRASH) {
            val vm = appViewModel { MemoTrashViewModel(it) }
            MemoTrashScreen(vm = vm, onBack = { nav.popFrom(Routes.MEMO_TRASH) })
        }
        composable(TopLevel.STATS.route) {
            val vm = appViewModel { StatsViewModel(it) }
            StatsScreen(vm = vm, wide = wide, onOpenPay = { nav.navigate(Routes.PAY) { launchSingleTop = true } })
        }
        composable(TopLevel.SETTINGS.route) {
            val vm = appViewModel { SettingsViewModel(it) }
            val nudges = appViewModel { CompanyNudgeViewModel(it) }
            SettingsScreen(
                vm = vm,
                onOpenShiftTypes = { nav.navigate(Routes.SHIFT_TYPES) { launchSingleTop = true } },
                onOpenPattern = { nav.navigate(Routes.pattern()) { launchSingleTop = true } },
                onOpenDiagnostics = { testing -> nav.navigate(Routes.diagnostics(testing)) { launchSingleTop = true } },
                onOpenWorkStyle = { nav.navigate(Routes.WORK_STYLE) { launchSingleTop = true } },
                onOpenExtraKinds = { nav.navigate(Routes.EXTRA_KINDS) { launchSingleTop = true } },
                onOpenPay = { nav.navigate(Routes.PAY) { launchSingleTop = true } },
                wide = wide,
                snackbar = snackbar,
                company = if (nudges.enabled) ({
                    CompanySettingsRows(
                        nudges,
                        onFind = { nav.navigate(Routes.companyFind()) { launchSingleTop = true } },
                        onShare = { nav.navigate(Routes.COMPANY_REPORT) { launchSingleTop = true } },
                        onSent = { nav.navigate(Routes.COMPANY_SENT) { launchSingleTop = true } },
                        onFix = { nav.fixCompanySetup(it) },
                    )
                }) else null,
                onOpenInquiry = { nav.navigate(Routes.INQUIRY) { launchSingleTop = true } },
            )
        }
        composable(Routes.INQUIRY) {
            val vm = appViewModel { InquiryViewModel(it) }
            InquiryScreen(vm = vm, onBack = { nav.popFrom(Routes.INQUIRY) })
        }
        composable(Routes.PAY) {
            val vm = appViewModel { PayViewModel(it) }
            val profile by vm.profile.collectAsStateWithLifecycle()
            val pendingPay by container.companyStore.pendingPayFlow.collectAsStateWithLifecycle()
            if (profile == null && pendingPay != null) {
                // [COMM-01] A coworker's setup came with its pay: only the user's own amounts are left.
                val companyPay = appViewModel(key = "company-pay-${pendingPay.hashCode()}") { CompanyPayViewModel(it) }
                CompanyPayScreen(companyPay, onBack = { nav.popFrom(Routes.PAY) }, onByHand = { container.companyStore.pendingPay = null })
                return@composable
            }
            val nudges = appViewModel { CompanyNudgeViewModel(it) }
            // [COMM-02] Right after a pay setup made by hand under a company's name, the invitation slides up once.
            var wasEmpty by rememberSaveable { mutableStateOf(profile == null) }
            var inviting by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(profile == null) {
                if (profile == null) wasEmpty = true
                else if (wasEmpty) {
                    wasEmpty = false
                    if (nudges.invite.value != null) inviting = true
                }
            }
            PayScreen(vm = vm, onBack = { nav.popFrom(Routes.PAY) },
                onOpenExtraKinds = { nav.navigate(Routes.EXTRA_KINDS) { launchSingleTop = true } },
                top = {
                    CompanyNudgeCards(nudges, onShare = { nav.navigate(Routes.COMPANY_REPORT) { launchSingleTop = true } },
                        onFix = { nav.fixCompanySetup(it) })
                })
            if (inviting) {
                InviteSheet(nudges, onShare = { inviting = false; nav.navigate(Routes.COMPANY_REPORT) { launchSingleTop = true } },
                    onDismiss = { inviting = false })
            }
        }
        composable(
            Routes.COMPANY_FIND,
            arguments = listOf(
                navArgument("setup") { type = NavType.BoolType; defaultValue = false },
                navArgument("weekdays") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val setup = entry.arguments?.getBoolean("setup") ?: false
            val weekdays = entry.arguments?.getBoolean("weekdays") ?: false
            val vm = appViewModel { CompanyFindViewModel(it) }
            val profile by container.payStore.profile.collectAsStateWithLifecycle()
            val ready = !setup && profile != null
            CompanyFindScreen(
                vm = vm,
                setup = setup,
                ready = ready,
                onBack = { nav.popFrom(Routes.COMPANY_FIND) },
                onOpen = { id -> nav.navigate(Routes.company(id, setup, weekdays)) },
                onByHand = { nav.afterCompanyByHand(setup, weekdays, ready) },
            )
        }
        composable(
            Routes.COMPANY,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("setup") { type = NavType.BoolType; defaultValue = false },
                navArgument("weekdays") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val setup = entry.arguments?.getBoolean("setup") ?: false
            val weekdays = entry.arguments?.getBoolean("weekdays") ?: false
            val vm = appViewModel(key = "company-$id") { CompanyDetailViewModel(it, id) }
            val profile by container.payStore.profile.collectAsStateWithLifecycle()
            val ready = !setup && profile != null
            CompanyDetailScreen(
                vm = vm,
                setup = setup,
                ready = ready,
                onBack = { nav.popFrom(Routes.COMPANY) },
                onApplied = { applied, name ->
                    scope.launch {
                        val hasPattern = container.scheduleRepository.patterns.first().isNotEmpty()
                        val pay = container.payStore.profile.value != null
                        when {
                            // A setup without a rotation (요일 고정 회사): the days are still to be set.
                            !hasPattern -> nav.navigate(Routes.pattern(weekdays = weekdays || applied.cycleDays == null, setup = true)) {
                                popUpTo(Routes.COMPANY_FIND) { inclusive = true }
                            }
                            applied.pendingPay || (setup && !pay) -> nav.navigate(Routes.PAY) {
                                popUpTo(Routes.COMPANY_FIND) { inclusive = true }
                                launchSingleTop = true
                            }
                            else -> {
                                nav.popBackStack(Routes.COMPANY_FIND, inclusive = true)
                                snackbar.showSnackbar("‘$name’ 설정을 넣었어요" + (applied.cycleDays?.let { " · ${it}일 순서" } ?: ""))
                            }
                        }
                    }
                },
                onByHand = { nav.afterCompanyByHand(setup, weekdays, ready) },
            )
        }
        composable(Routes.COMPANY_REPORT) {
            val vm = appViewModel { CompanyReportViewModel(it) }
            CompanyReportScreen(
                vm = vm,
                onBack = { nav.popFrom(Routes.COMPANY_REPORT) },
                onDone = { nav.popFrom(Routes.COMPANY_REPORT) },
                onOpenSent = { nav.navigate(Routes.COMPANY_SENT) { popUpTo(Routes.COMPANY_REPORT) { inclusive = true } } },
            )
        }
        composable(Routes.COMPANY_SENT) {
            val vm = appViewModel { CompanyReportViewModel(it) }
            CompanySentScreen(
                vm = vm,
                onBack = { nav.popFrom(Routes.COMPANY_SENT) },
                onShare = { nav.navigate(Routes.COMPANY_REPORT) { popUpTo(Routes.COMPANY_SENT) { inclusive = true } } },
            )
        }
        // [EXTRA-04] Names, hours and pay of each kind of extra work, from settings, a day or the pay rules.
        composable(Routes.SLEEP) {
            val vm = appViewModel { com.bamnat.app.ui.sleep.SleepViewModel(it) }
            com.bamnat.app.ui.sleep.SleepScreen(vm = vm, wide = wide, onBack = { nav.popFrom(Routes.SLEEP) })
        }
        composable(Routes.OWED) {
            val vm = appViewModel { com.bamnat.app.ui.money.OwedViewModel(it) }
            com.bamnat.app.ui.money.OwedScreen(vm = vm, wide = wide, onBack = { nav.popFrom(Routes.OWED) })
        }
        composable(Routes.CALENDAR_IMPORT) {
            val vm = appViewModel { com.bamnat.app.ui.imports.CalendarImportViewModel(it) }
            com.bamnat.app.ui.imports.CalendarImportScreen(vm = vm, onBack = { nav.popFrom(Routes.CALENDAR_IMPORT) })
        }
        composable(Routes.EXTRA_KINDS) {
            val vm = appViewModel { ExtraKindsViewModel(it) }
            ExtraKindsScreen(vm = vm, onBack = { nav.popFrom(Routes.EXTRA_KINDS) },
                // Back to the pay screen when it opened this one, so the stack does not grow.
                onOpenPay = { if (!nav.popBackStack(Routes.PAY, inclusive = false)) nav.navigate(Routes.PAY) { launchSingleTop = true } })
        }
        composable(
            Routes.DIAGNOSTICS,
            arguments = listOf(navArgument("testing") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val vm = appViewModel { DiagnosticsViewModel(it) }
            DiagnosticsScreen(vm = vm, testing = entry.arguments?.getBoolean("testing") == true, onBack = { nav.popFrom(Routes.DIAGNOSTICS) })
        }
        composable(Routes.SHIFT_TYPES) {
            val vm = appViewModel { ShiftsViewModel(it) }
            ShiftTypesScreen(vm = vm, onBack = { nav.popFrom(Routes.SHIFT_TYPES) })
        }
        composable(
            Routes.PATTERN,
            arguments = listOf(
                navArgument("weekdays") { type = NavType.BoolType; defaultValue = false },
                navArgument("setup") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { entry ->
            val vm = appViewModel { ShiftsViewModel(it) }
            val setup = entry.arguments?.getBoolean("setup") ?: false
            PatternScreen(
                vm = vm,
                startWeekdays = entry.arguments?.getBoolean("weekdays") ?: false,
                setup = setup,
                onBack = { nav.popFrom(Routes.PATTERN) },
                // [PAYX-13] Right after the first cycle, the pay setup opens in its place.
                onSaved = if (setup) ({
                    nav.navigate(Routes.PAY) { popUpTo(Routes.PATTERN) { inclusive = true }; launchSingleTop = true }
                }) else null,
                onOpenShiftTypes = { nav.navigate(Routes.SHIFT_TYPES) { launchSingleTop = true } },
            )
        }
        composable(
            Routes.MEMO,
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("date") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("checklist") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val args = entry.arguments
            val id = args?.getLong("id") ?: 0L
            val date = args?.getString("date")?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val checklist = args?.getBoolean("checklist") ?: false
            val vm = appViewModel(key = "memo-$id") { MemoEditorViewModel(it, id, date, checklist) }
            MemoEditorScreen(vm = vm, onBack = { nav.popFrom(Routes.MEMO) })
        }
    }
}

/**
 * Controls for a ringing alarm inside the app. The notification normally offers them, but it can
 * be hidden (notifications turned off), so the app itself must always be able to stop the sound.
 */
@Composable
private fun RingingBanner() {
    val ring by AlarmService.ringing.collectAsStateWithLifecycle()
    val current = ring ?: return
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Alarm, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(current.title, style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                Text(AlarmService.describe(current), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
            TextButton(onClick = { AlarmService.snooze(context) }) { Text("다시 울림") }
            Button(onClick = { AlarmService.dismiss(context) }) { Text("끄기") }
        }
    }
}

/**
 * [COMM-02] The company is not there (or not the user's department): during first setup the work is
 * set up by hand; once set up, the user's own setup is shared under the company's name.
 */
private fun NavHostController.afterCompanyByHand(setup: Boolean, weekdays: Boolean, ready: Boolean) {
    when {
        setup -> navigate(Routes.pattern(weekdays = weekdays, setup = true)) { popUpTo(Routes.COMPANY_FIND) { inclusive = true } }
        ready -> navigate(Routes.COMPANY_REPORT) { popUpTo(Routes.COMPANY_FIND) { inclusive = true } }
        else -> navigate(Routes.pattern(weekdays = weekdays)) { popUpTo(Routes.COMPANY_FIND) { inclusive = true } }
    }
}

/** [COMM-03] After 달라요: where the setup is corrected before the right one is shared. */
private fun NavHostController.fixCompanySetup(place: FixPlace) {
    when (place) {
        FixPlace.PAY -> navigate(Routes.PAY) { launchSingleTop = true }
        FixPlace.SHIFT_TYPES -> navigate(Routes.SHIFT_TYPES) { launchSingleTop = true }
        FixPlace.PATTERN -> navigate(Routes.pattern()) { launchSingleTop = true }
        FixPlace.NONE -> navigate(Routes.COMPANY_REPORT) { launchSingleTop = true }
    }
}

private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Pops only if [route] is still on top, so a double tap on back or save (or a callback that
 * arrives after the user already left) can never pop the screen underneath.
 */
private fun NavHostController.popFrom(route: String) {
    if (currentBackStackEntry?.destination?.route == route) popBackStack()
}

/** [VIS-02] Tabs fade in over 150 ms after the old one fades out over 90 ms; other screens move over 250 ms. */
private const val TAB_IN_MILLIS = 150
private const val TAB_OUT_MILLIS = 90
private const val SCREEN_MILLIS = 250

/** Both ends of the move are tabs, so it is a tab switch, not a screen opened from one. */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs(): Boolean =
    TopLevel.entries.any { it.route == initialState.destination.route } &&
        TopLevel.entries.any { it.route == targetState.destination.route }
