package com.bamnat.app.ui

import com.bamnat.app.HomeRequests
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    STATS("stats", "통계", Icons.Outlined.Insights, Icons.Filled.Insights),
    SETTINGS("settings", "설정", Icons.Outlined.Settings, Icons.Filled.Settings),
}

private object Routes {
    const val SHIFT_TYPES = "shift_types"
    const val PATTERN = "pattern?weekdays={weekdays}&setup={setup}"
    const val WORK_STYLE = "work_style"

    fun pattern(weekdays: Boolean = false, setup: Boolean = false) = "pattern?weekdays=$weekdays&setup=$setup"
    const val MEMO_TRASH = "memo_trash"
    const val DIAGNOSTICS = "diagnostics"
    const val PAY = "pay"
    const val EXTRA_KINDS = "extra_kinds"
    const val MEMO = "memo/{id}?date={date}&checklist={checklist}"

    fun memo(id: Long, date: LocalDate? = null, checklist: Boolean = false) =
        "memo/$id?date=${date?.toString() ?: ""}&checklist=$checklist"
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

    // [WORK-01] First launch asks how the user works. Phones that already have a pattern were
    // set up before this question existed, so they are shift workers and are not asked.
    val settingsNow by container.settingsRepository.settings.collectAsStateWithLifecycle()
    val needsWorkStyle by produceState<Boolean?>(if (settingsNow.workStyle != null) false else null, settingsNow.workStyle) {
        value = if (settingsNow.workStyle != null) {
            false
        } else if (container.scheduleRepository.patterns.first().isNotEmpty()) {
            container.settingsRepository.update { it.copy(workStyle = WorkStyle.SHIFT) }
            false
        } else {
            true
        }
    }
    // The first setup screen opens once the navigation graph exists.
    var pendingRoute by rememberSaveable { mutableStateOf<String?>(null) }
    when (needsWorkStyle) {
        null -> return
        true -> {
            WorkStyleScreen(current = null, onPick = { style ->
                container.settingsRepository.update { it.copy(workStyle = style) }
                container.telemetry.event("work_style", style.name)
                if (style == WorkStyle.HOURLY) container.appScope.launch { container.scheduleRepository.addHourlyTypes() }
                pendingRoute = when (style) {
                    // [PAYX-13] First setup goes on from the kind of work to its cycle, then to pay.
                    WorkStyle.SHIFT -> Routes.pattern(setup = true)
                    WorkStyle.DAY -> Routes.pattern(weekdays = true, setup = true)
                    WorkStyle.HOURLY -> Routes.PAY
                    WorkStyle.FIELD, WorkStyle.BUSINESS -> null
                }
            })
            return
        }
        false -> Unit
    }
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { r ->
            pendingRoute = null
            nav.navigate(r) { launchSingleTop = true }
        }
    }

    AskNotificationPermissionOnce(container)

    // Which screens get used decides what is worth building next [MEAS-03].
    LaunchedEffect(route) {
        route?.let { container.telemetry.event(DiagEvents.SCREEN, it.substringBefore('/').substringBefore('?')) }
    }

    // Opening the app (again) always lands on the calendar.
    val homeRequests by HomeRequests.count.collectAsStateWithLifecycle()
    var handledHome by rememberSaveable { mutableStateOf(homeRequests) }
    LaunchedEffect(homeRequests) {
        if (homeRequests != handledHome) {
            handledHome = homeRequests
            // Only from another tab: an editor the user left open (pay setup, a pattern) keeps their input.
            val current = nav.currentBackStackEntry?.destination?.route
            if (current != TopLevel.CALENDAR.route && TopLevel.entries.any { it.route == current }) {
                nav.navigate(TopLevel.CALENDAR.route) {
                    popUpTo(nav.graph.findStartDestination().id) { inclusive = false }
                    launchSingleTop = true
                }
            }
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
                        label = { Text(dest.label) },
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
                                    label = { Text(dest.label) },
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
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    container: AppContainer,
    wide: Boolean,
    snackbar: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    // Screens switch at once, without the default cross-fade.
    NavHost(
        navController = nav,
        startDestination = TopLevel.CALENDAR.route,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
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
            SettingsScreen(
                vm = vm,
                onOpenShiftTypes = { nav.navigate(Routes.SHIFT_TYPES) { launchSingleTop = true } },
                onOpenPattern = { nav.navigate(Routes.pattern()) { launchSingleTop = true } },
                onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) { launchSingleTop = true } },
                onOpenWorkStyle = { nav.navigate(Routes.WORK_STYLE) { launchSingleTop = true } },
                onOpenExtraKinds = { nav.navigate(Routes.EXTRA_KINDS) { launchSingleTop = true } },
            )
        }
        composable(Routes.PAY) {
            val vm = appViewModel { PayViewModel(it) }
            PayScreen(vm = vm, onBack = { nav.popFrom(Routes.PAY) },
                onOpenExtraKinds = { nav.navigate(Routes.EXTRA_KINDS) { launchSingleTop = true } })
        }
        // [EXTRA-04] Names, hours and pay of each kind of extra work, from settings, a day or the pay rules.
        composable(Routes.EXTRA_KINDS) {
            val vm = appViewModel { ExtraKindsViewModel(it) }
            ExtraKindsScreen(vm = vm, onBack = { nav.popFrom(Routes.EXTRA_KINDS) },
                // Back to the pay screen when it opened this one, so the stack does not grow.
                onOpenPay = { if (!nav.popBackStack(Routes.PAY, inclusive = false)) nav.navigate(Routes.PAY) { launchSingleTop = true } })
        }
        composable(Routes.DIAGNOSTICS) {
            val vm = appViewModel { DiagnosticsViewModel(it) }
            DiagnosticsScreen(vm = vm, onBack = { nav.popFrom(Routes.DIAGNOSTICS) })
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

/** Asks for notification permission once on first launch so shift alarms can show. */
@Composable
private fun AskNotificationPermissionOnce(container: AppContainer) {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        // Asked once per phone: the flag lives outside the backed-up settings, so a restore onto a
        // new phone still asks there (without it, a ringing alarm has no lock-screen controls).
        val device = context.getSharedPreferences("device", android.content.Context.MODE_PRIVATE)
        if (!device.getBoolean("notification_asked", false) && !Notifications.canPost(context)) {
            device.edit().putBoolean("notification_asked", true).apply()
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            TextButton(onClick = { AlarmService.snooze(context) }) { Text("다시 알림") }
            Button(onClick = { AlarmService.dismiss(context) }) { Text("끄기") }
        }
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
