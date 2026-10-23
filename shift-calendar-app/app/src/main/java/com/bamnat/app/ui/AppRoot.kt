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
import com.bamnat.core.work.words
import com.bamnat.app.AppContainer
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.ui.calendar.CalendarScreen
import com.bamnat.app.ui.calendar.CalendarViewModel
import com.bamnat.app.ui.calendar.CalendarsScreen
import com.bamnat.app.ui.calendar.CalendarsViewModel
import com.bamnat.app.ui.calendar.rememberAddCalendar
import com.bamnat.app.data.PersonalCalendar
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
import com.bamnat.app.ui.settings.SettingsGo
import com.bamnat.app.ui.settings.SettingsLink
import com.bamnat.app.ui.settings.SettingsLinks
import androidx.compose.material3.BadgedBox
import com.bamnat.app.ui.settings.SettingsViewModel
import com.bamnat.app.ui.shifts.PatternScreen
import com.bamnat.app.ui.shifts.ShiftTypesScreen
import com.bamnat.app.ui.shifts.ShiftsViewModel
import com.bamnat.app.ui.stats.StatsScreen
import com.bamnat.app.ui.stats.StatsViewModel
import com.bamnat.app.ui.money.MoneyScreen
import com.bamnat.app.ui.money.MoneyViewModel
import com.bamnat.app.ui.work.WorkStyleScreen
import com.bamnat.app.ui.work.changedMessage
import com.bamnat.app.ui.setup.SetupHost
import com.bamnat.app.ui.onboarding.ConsentPages
import com.bamnat.app.ui.onboarding.NewConsentPrompt
import com.bamnat.app.ui.setup.SetupViewModel
import com.bamnat.app.ui.setup.SetupPaySheet
import com.bamnat.app.ui.company.CompanyBoardScreen
import com.bamnat.app.ui.company.CompanyBoardViewModel
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
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.guide.Place
import com.bamnat.app.ui.guide.settingsPage
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

    /** [TYPE-02] The hours-and-money tab is named for the work of the calendar on screen. */
    fun labelFor(style: WorkStyle?): String = if (this == STATS) style.words().tab else label
}

/** [MON-07] Where the banner may go: under 근무·급여 and 돈 only ([com.bamnat.core.tier.AdRules.banner]). */
private fun TopLevel?.adPlace(): com.bamnat.core.tier.AdRules.Place = when (this) {
    TopLevel.MONEY -> com.bamnat.core.tier.AdRules.Place.MONEY
    TopLevel.STATS -> com.bamnat.core.tier.AdRules.Place.STATS
    TopLevel.CALENDAR -> com.bamnat.core.tier.AdRules.Place.CALENDAR
    TopLevel.MEMOS -> com.bamnat.core.tier.AdRules.Place.MEMOS
    TopLevel.SETTINGS -> com.bamnat.core.tier.AdRules.Place.SETTINGS
    null -> com.bamnat.core.tier.AdRules.Place.OTHER
}

private object Routes {
    // [MCAL-01] Shifts and the order are edited for one calendar: the main one unless one is named.
    const val SHIFT_TYPES = "shift_types?calendar={calendar}&edit={edit}"
    const val PATTERN = "pattern?weekdays={weekdays}&setup={setup}&calendar={calendar}"
    // [CALS-02] The kind of work of one calendar: the main one unless one is named.
    const val WORK_STYLE = "work_style?calendar={calendar}"
    fun workStyle(calendar: String = PersonalCalendar.MAIN) = "work_style?calendar=$calendar"
    // [CALS-01] One calendar's own page: name, colour, kind of work and everything set for it.
    const val CALENDAR_EDIT = "calendar_edit/{id}"
    fun calendarEdit(id: String) = "calendar_edit/$id"
    // [TYPE-03] A shop calendar's hours and regular days off.
    const val SHOP_DAYS = "shop_days?calendar={calendar}"
    fun shopDays(calendar: String = PersonalCalendar.MAIN) = "shop_days?calendar=$calendar"

    /** [DAILY-08] [edit] opens that shift type's editor at once (the day's shift, from its sheet). */
    fun shiftTypes(calendar: String = PersonalCalendar.MAIN, edit: Long = 0L) = "shift_types?calendar=$calendar&edit=$edit"
    fun pattern(weekdays: Boolean = false, setup: Boolean = false, calendar: String = PersonalCalendar.MAIN) =
        "pattern?weekdays=$weekdays&setup=$setup&calendar=$calendar"

    // [MCAL-02][MCAL-04] Setting up a new calendar, and the calendar list.
    const val CALENDAR_SETUP = "calendar_setup/{id}"
    const val CALENDARS = "calendars"
    fun calendarSetup(id: String) = "calendar_setup/$id"
    const val MEMO_TRASH = "memo_trash"
    const val DIAGNOSTICS = "diagnostics?testing={testing}"

    fun diagnostics(testing: Boolean) = "diagnostics?testing=$testing"
    /** [MCAL-06] The pay of one of the user's calendars; without one, the main calendar's. */
    const val PAY = "pay?calendar={calendar}"
    fun pay(calendar: String = PersonalCalendar.MAIN) = "pay?calendar=$calendar"
    /** [LEAVE-06] One calendar's 연차 원장; without one, the main calendar's. */
    const val LEAVE = "leave?calendar={calendar}"
    fun leave(calendar: String = PersonalCalendar.MAIN) = "leave?calendar=$calendar"
    /** [MCAL-06] How extra work is paid, in one calendar's pay; without one, the main calendar's. */
    const val EXTRA_KINDS = "extra_kinds?calendar={calendar}"
    fun extraKinds(calendar: String = PersonalCalendar.MAIN) = "extra_kinds?calendar=$calendar"
    const val MEMO = "memo/{id}?date={date}&checklist={checklist}&notebook={notebook}"

    /** [notebook]: opened from the memo tab, so a new memo goes into the notebook shown there. */
    fun memo(id: Long, date: LocalDate? = null, checklist: Boolean = false, notebook: Boolean = false) =
        "memo/$id?date=${date?.toString() ?: ""}&checklist=$checklist&notebook=$notebook"

    // [COMM-01][COMM-02] The company rules server: find a company, share a setup, follow what was sent.
    const val COMPANY_FIND = "company_find?setup={setup}&weekdays={weekdays}"
    const val COMPANY = "company/{id}?setup={setup}&weekdays={weekdays}"
    const val COMPANY_REPORT = "company_report"
    const val COMPANY_SENT = "company_sent"
    // [COMM-07] 회사 규칙 광장: every company's rule sets, to vote on, talk over and use.
    const val COMPANY_BOARD = "company_board"
    const val PLAZA_RATES = "plaza_rates"
    /** [ADMIN-02] 설정 > 문의하기. */
    const val INQUIRY = "inquiry"
    /** [SHR-14] 우리 조 모아 보기: links put together under a 6-letter code. */
    const val SHARE_GROUP = "share_group"

    // [SLP-16][RECV-02][IMPORT-01] The sleep log, money owed, and bringing in the phone's calendar.
    const val SLEEP = "sleep"
    const val OWED = "owed"
    const val CALENDAR_IMPORT = "calendar_import"
    /** [COMN-01] 모든 달력 합쳐 보기. */
    const val COMBINED = "combined"
    // [SHFT-06][SHFT-07][DAYW-06][DAYW-07] Shift and day work's own tools, for the calendar on screen.
    const val DUTY_ROSTER = "duty_roster"
    const val HEALTH_REPORT = "health_report"
    const val TEAMS = "teams"
    const val YEAR = "year"
    const val ATTENDANCE_IMPORT = "attendance_import"
    const val COMMUTE = "commute"
    /** [GUIDE-05] 설정 › 기능 켜고 끄기. */
    const val FEATURES = "features"
    /** [MON-04] 프로: what it adds, the plans, passes and the admin link. */
    const val PRO = "pro"
    /** [ONBOARD-04] 추천 기능 한 번에 켜기, from settings and the 1.22 update notice. */
    const val RECOMMENDED = "recommended"

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
    // [CALS-02] The calendar on screen and its kind of work, which names the tabs and opens its own settings.
    val activeCalendarId by container.calendars.active.collectAsStateWithLifecycle()
    val calendarList by container.calendars.calendars.collectAsStateWithLifecycle()
    val activeStyle = calendarList.firstOrNull { it.id == activeCalendarId }?.takeIf { !it.isMain }?.style ?: settingsNow.workStyle
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
        // [GUIDE-04] A screen that holds a feature ticks it in the guide; kept on this phone only.
        route?.let { com.bamnat.app.ui.guide.GuideUse.screen(com.bamnat.app.data.GuideStore.get(container.appContext), it.substringBefore('/').substringBefore('?')) }
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
            // [SHR-12] The calendar opens its share sheet itself.
            NoticeLinks.Target.TODAY, NoticeLinks.Target.SHARE, NoticeLinks.Target.RECEIVE, NoticeLinks.Target.DAY -> {
                val current = nav.currentBackStackEntry?.destination?.route
                when {
                    current == TopLevel.CALENDAR.route -> Unit
                    TopLevel.entries.any { it.route == current } -> nav.navigateTopLevel(TopLevel.CALENDAR.route)
                    else -> nav.navigate(TopLevel.CALENDAR.route) { launchSingleTop = true }
                }
            }
            NoticeLinks.Target.MONEY -> { nav.navigateTopLevel(TopLevel.MONEY.route); NoticeLinks.consume(target) }
            // [INV-08] An investor notice opens its calendar's 투자 tab, which opens the trade itself.
            NoticeLinks.Target.INVEST, NoticeLinks.Target.INVEST_TRADE -> {
                // [MCAL-03] No calendar named is the main one; its 투자 tab takes the notice only once it is shown.
                val cal = NoticeLinks.calendar.value ?: com.bamnat.app.data.PersonalCalendar.MAIN
                val gone = cal != com.bamnat.app.data.PersonalCalendar.MAIN && container.calendars.get(cal) == null
                container.calendars.setActive(cal)
                nav.navigateTopLevel(TopLevel.STATS.route)
                // The trade sheet waits for the 투자 tab to read it; a plain notice, or one of a calendar since removed, is done here.
                if (target == NoticeLinks.Target.INVEST || gone) NoticeLinks.consume(target)
            }
            // [LEDG-16] The money tab takes this one itself, to open 자동 기록.
            NoticeLinks.Target.AUTO_RECORD -> nav.navigateTopLevel(TopLevel.MONEY.route)
            NoticeLinks.Target.MEMOS -> { nav.navigateTopLevel(TopLevel.MEMOS.route); NoticeLinks.consume(target) }
            NoticeLinks.Target.LEAVE -> { nav.navigate(Routes.leave()) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.SLEEP -> { nav.navigate(Routes.SLEEP) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.OWED -> { nav.navigate(Routes.OWED) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.CALENDAR_IMPORT -> { nav.navigate(Routes.CALENDAR_IMPORT) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.DUTY_ROSTER -> { nav.navigate(Routes.DUTY_ROSTER) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.HEALTH_REPORT -> { nav.navigate(Routes.HEALTH_REPORT) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.TEAMS -> { nav.navigate(Routes.TEAMS) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.YEAR -> { nav.navigate(Routes.YEAR) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.ATTENDANCE_IMPORT -> { nav.navigate(Routes.ATTENDANCE_IMPORT) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.COMMUTE -> { nav.navigate(Routes.COMMUTE) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.PRO -> { nav.navigate(Routes.PRO) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.PLAZA -> { nav.navigate(Routes.COMPANY_BOARD) { launchSingleTop = true }; NoticeLinks.consume(target) }
            // [INQ-05] 밤낮 소식's answer line opens 내 문의 (문의하기 opens there when an answer is new).
            NoticeLinks.Target.INQUIRY -> { nav.navigate(Routes.INQUIRY) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.GROUP -> { nav.navigate(Routes.SHARE_GROUP) { launchSingleTop = true }; NoticeLinks.consume(target) }
            NoticeLinks.Target.PHONE_CALENDAR -> {
                com.bamnat.app.ui.settings.SettingsLinks.open(com.bamnat.app.ui.settings.SettingsPage.DATA, com.bamnat.app.ui.settings.PHONE_CALENDAR_ROW)
                NoticeLinks.consume(target)
            }
            // [SRV-09] Shown by the update's own dialog below, which takes the target.
            NoticeLinks.Target.WHATS_NEW -> Unit
            NoticeLinks.Target.BACKUP -> {
                com.bamnat.app.ui.settings.SettingsLinks.open(com.bamnat.app.ui.settings.SettingsPage.DATA, com.bamnat.app.ui.settings.AUTO_BACKUP_ROW)
                NoticeLinks.consume(target)
            }
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

    // [UX-04] A help answer, a search result or a screen's own "설정" opens the setting or screen it names.
    // A group of settings opens over an inner screen, so back returns there; a screen opens on top.
    val settingsLink by SettingsLinks.target.collectAsStateWithLifecycle()
    LaunchedEffect(settingsLink) {
        when (val link = settingsLink ?: return@LaunchedEffect) {
            is SettingsLink.Page -> {
                val current = nav.currentBackStackEntry?.destination?.route
                when {
                    current == TopLevel.SETTINGS.route -> Unit
                    TopLevel.entries.any { it.route == current } -> nav.navigateTopLevel(TopLevel.SETTINGS.route)
                    else -> nav.navigate(TopLevel.SETTINGS.route) { launchSingleTop = true }
                }
            }
            is SettingsLink.Go -> {
                SettingsLinks.consume(link)
                link.go.route()?.let { r -> nav.navigate(r) { launchSingleTop = true } }
            }
        }
    }
    // [GUIDE-01] The checklist, a tip, the help search or 기능 켜고 끄기 opens a feature where it lives,
    // for the calendar on screen.
    val featureLink by com.bamnat.app.ui.guide.FeatureLinks.target.collectAsStateWithLifecycle()
    LaunchedEffect(featureLink) {
        val link = featureLink ?: return@LaunchedEffect
        com.bamnat.app.ui.guide.FeatureLinks.consume(link)
        nav.openFeature(container, link)
    }
    // [GUIDE-06] A feature turned off from its ⋮: where it comes back, one tap away.
    LaunchedEffect(Unit) {
        com.bamnat.app.ui.guide.FeatureLinks.turnedOff.collect { name ->
            val result = snackbar.showSnackbar("‘$name’ 꺼졌어요. 설정 › 기능 켜고 끄기에서 다시 켤 수 있어요",
                actionLabel = "바로 가기", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) nav.navigate(Routes.FEATURES) { launchSingleTop = true }
        }
    }
    // [UX-05] A new answer to the user's question shows on the 설정 tab from the moment the app opens.
    val inquiryUnread by container.inquiryStore.unread.collectAsStateWithLifecycle()
    LaunchedEffect(homeRequests) {
        if (!container.inquiryApi.enabled || !container.inquiryStore.asked) return@LaunchedEffect
        runCatching { container.inquiryApi.mine(container.companyStore.device) }.onSuccess { container.inquiryStore.update(it) }
    }

    // [GUIDE-08] Once after an update: what it brought, each with a way to open it.
    val whatsNew = remember {
        val ctx = container.appContext
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val fresh = info == null || info.firstInstallTime == info.lastUpdateTime
        mutableStateOf(com.bamnat.app.data.GuideStore.get(ctx).whatsNew(com.bamnat.app.BuildConfig.VERSION_CODE, fresh))
    }
    // [ONBOARD-04] A phone set up before 1.22 keeps its own values; the update notice offers the rest once.
    val recommendOff = remember {
        val ctx = container.appContext
        val info = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val fresh = info == null || info.firstInstallTime == info.lastUpdateTime
        val on = com.bamnat.app.data.RecommendedSettings.on(container.settingsRepository.current)
        if (com.bamnat.core.consent.RecommendedPlan.offerOnce(fresh, container.consentStore.recommendOffered, on))
            com.bamnat.core.consent.RecommendedPlan.offNow(on).size else 0
    }
    // [SRV-09] A notice's 새 기능 보기 button: this version's lines again, from wherever.
    LaunchedEffect(noticeTarget) {
        if (noticeTarget != NoticeLinks.Target.WHATS_NEW) return@LaunchedEffect
        NoticeLinks.consume(NoticeLinks.Target.WHATS_NEW)
        whatsNew.value = com.bamnat.core.guide.WhatsNew.releases.filter { it.versionCode <= com.bamnat.app.BuildConfig.VERSION_CODE }.take(1)
    }
    if (whatsNew.value.isNotEmpty()) {
        val closeNew = {
            com.bamnat.app.data.GuideStore.get(container.appContext).whatsNewSeen(com.bamnat.app.BuildConfig.VERSION_CODE)
            if (recommendOff > 0) container.consentStore.markRecommendOffered()
            whatsNew.value = emptyList()
        }
        com.bamnat.app.ui.guide.WhatsNewDialog(
            releases = whatsNew.value,
            recommendOff = recommendOff,
            onRecommend = { closeNew(); SettingsLinks.go(SettingsGo.RECOMMENDED) },
            onOpen = { id -> closeNew(); com.bamnat.core.guide.FeatureRegistry.get(id)?.let(com.bamnat.app.ui.guide.FeatureLinks::open) },
            onAsk = { closeNew(); com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(about = "업데이트")) },
            onDismiss = closeNew,
        )
    } else {
        // [MON-06][MON-10] One at a time, after what the update brought.
        com.bamnat.app.ui.pro.TrialConsentPrompt(container)
        com.bamnat.app.ui.pro.LaunchThanks(container)
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

    // [UNDO-01][UNDO-02][UNDO-03] A kind of work changed, records cleared or a calendar removed: 되돌리기 at
    // once, wherever it was done, and 설정 › 데이터 › 최근 지운 기록 for 7 days after.
    val styleExtras = remember { mutableStateOf<StyleExtras?>(null) }
    LaunchedEffect(Unit) {
        container.recordsTrash.offers.collect { offer ->
            val result = snackbar.showSnackbar(offer.message, actionLabel = "되돌리기", duration = SnackbarDuration.Long)
            // Read once the snackbar is answered: the change sets it right after it is offered.
            val extras = styleExtras.value?.takeIf { it.entryId == offer.entry.id }
            if (extras != null) styleExtras.value = null
            if (result == SnackbarResult.ActionPerformed) {
                val ok = undoOffer(container, offer.entry.id, extras)
                // [UNDO-01] Its next step closes too.
                if (ok) extras?.opened?.let { nav.popFrom(it) }
                snackbar.showSnackbar(if (ok) "되돌렸어요" else "되돌리지 못했어요. 설정 › 데이터 › 최근 지운 기록에서 다시 해 주세요")
            }
        }
    }

    val navigateTop: (TopLevel) -> Unit = { dest -> nav.navigateTopLevel(dest.route) }

    // [DAILY-09] Half open like a laptop (Flex): the calendar splits at the hinge.
    val tabletop = rememberTabletop()
    androidx.compose.runtime.CompositionLocalProvider(LocalTabletop provides tabletop) {
        Row(Modifier.fillMaxSize()) {
            if (wide && showNav) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Spacer(Modifier.height(12.dp))
                    for (dest in TopLevel.entries) {
                        val selected = dest == topLevel
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTop(dest) },
                            icon = { TabIcon(dest, selected, if (dest == TopLevel.SETTINGS) inquiryUnread else 0) },
                            label = { TabLabel(dest.labelFor(activeStyle)) },
                            // [VIS-03] The chosen tab in the same blue as every other chosen thing.
                            colors = com.bamnat.app.ui.components.Selection.navRailItemColors(),
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
                            AdSlot(topLevel.adPlace())
                            NavigationBar {
                                for (dest in TopLevel.entries) {
                                    val selected = dest == topLevel
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { navigateTop(dest) },
                                        icon = { TabIcon(dest, selected, if (dest == TopLevel.SETTINGS) inquiryUnread else 0) },
                                        label = { TabLabel(dest.labelFor(activeStyle)) },
                                        colors = com.bamnat.app.ui.components.Selection.navBarItemColors(),
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
                        styleExtras = styleExtras,
                        modifier = Modifier.weight(1f),
                    )
                    if (wide && showNav) AdSlot(topLevel.adPlace())
                }
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
                    nav.navigate(Routes.pay()) { launchSingleTop = true }
                },
                onLater = { container.firstSetup.consumeSaved() },
            )
        }
    }
}

/** [UNDO-01] A kind-of-work change's own extras, for its 되돌리기: the 시급 types it added and the next step it opened (a route pattern). */
internal class StyleExtras(val entryId: String, val calendar: String, val addedTypes: List<Long>, val opened: String?)

/** [UNDO-01] Adds what [style] brings to calendar [calendar] (시급 types for hourly work); the ids added, for 되돌리기. */
internal suspend fun addStyleTypes(container: AppContainer, calendar: String, style: WorkStyle): List<Long> {
    if (style != WorkStyle.HOURLY) return emptyList()
    val schedule = container.calendars.repository(calendar)
    val typesBefore = schedule.shiftTypes.first().map { it.id }.toSet()
    schedule.addHourlyTypes()
    return schedule.shiftTypes.first().map { it.id }.filter { it !in typesBefore }
}

/** [UNDO-01] 되돌리기 of trash entry [entryId]; with a kind-of-work change's [extras] the 시급 types it added go too. */
internal suspend fun undoOffer(container: AppContainer, entryId: String, extras: StyleExtras?): Boolean {
    val ok = container.recordsTrash.restore(entryId)
    if (ok && extras != null && extras.entryId == entryId) {
        val schedule = container.calendars.repository(extras.calendar)
        extras.addedTypes.forEach { schedule.deleteShiftType(it) }
    }
    return ok
}

@Composable
private fun AppNavHost(
    nav: NavHostController,
    container: AppContainer,
    wide: Boolean,
    snackbar: SnackbarHostState,
    styleExtras: androidx.compose.runtime.MutableState<StyleExtras?>,
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
                // [MCAL-01] The calendar on screen is the one these edit.
                onOpenPattern = { nav.openPattern(container, container.calendars.active.value) },
                onOpenShiftTypes = { nav.navigate(Routes.shiftTypes(container.calendars.active.value)) { launchSingleTop = true } },
                onOpenStats = { nav.navigateTopLevel(TopLevel.STATS.route) },
                onOpenMoney = { nav.navigateTopLevel(TopLevel.MONEY.route) },
                onOpenPay = { nav.navigate(Routes.pay(container.calendars.active.value)) { launchSingleTop = true } },
                onOpenSettings = { nav.navigateTopLevel(TopLevel.SETTINGS.route) },
                onOpenExtraKinds = { nav.navigate(Routes.extraKinds(container.calendars.active.value)) { launchSingleTop = true } },
                onSetupCalendar = { id -> nav.navigate(Routes.calendarSetup(id)) { launchSingleTop = true } },
                onManageCalendars = { nav.navigate(Routes.CALENDARS) { launchSingleTop = true } },
                onEditCalendar = { id -> nav.navigate(Routes.calendarEdit(id)) { launchSingleTop = true } },
                onOpenCombined = { nav.navigate(Routes.COMBINED) { launchSingleTop = true } },
            )
        }
        composable(Routes.COMBINED) { com.bamnat.app.ui.calendar.CombinedScreen(onBack = { nav.popFrom(Routes.COMBINED) }) }
        composable(Routes.CALENDAR_SETUP, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val vm = appViewModel(key = "calendar_setup_$id") { SetupViewModel(it, calendarId = id) }
            val done by vm.done.collectAsStateWithLifecycle()
            val profile by remember(id) { container.calendars.pay(id).profile }.collectAsStateWithLifecycle()
            // [MCAL-06] As after the first setup: work paid by the hour or month is offered its pay next.
            val asksPay = done && container.calendars.get(id)?.let { it.ready && it.style?.usesPayEngine == true } == true &&
                profile == null
            LaunchedEffect(done) { if (done && !asksPay) nav.popFrom(Routes.CALENDAR_SETUP) }
            SetupHost(vm)
            if (asksPay) SetupPaySheet(
                companyPay = false,
                onNow = { nav.navigate(Routes.pay(id)) { popUpTo(Routes.CALENDAR_SETUP) { inclusive = true }; launchSingleTop = true } },
                onLater = { nav.popFrom(Routes.CALENDAR_SETUP) },
            )
        }
        composable(Routes.CALENDARS) {
            val vm = appViewModel { CalendarsViewModel(it) }
            val add = rememberAddCalendar(container) { id -> nav.navigate(Routes.calendarSetup(id)) { launchSingleTop = true } }
            // [UX-03] 공유 on a calendar of the list: that calendar, on the calendar tab, with its share sheet.
            CalendarsScreen(vm, onBack = { nav.popFrom(Routes.CALENDARS) }, onAddCalendar = add, onShare = { id ->
                nav.navigateTopLevel(TopLevel.CALENDAR.route)
                NoticeLinks.open(NoticeLinks.Target.SHARE, id)
            }, onEdit = { id -> nav.navigate(Routes.calendarEdit(id)) { launchSingleTop = true } })
        }
        composable(Routes.CALENDAR_EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id") ?: PersonalCalendar.MAIN
            val vm = appViewModel(key = "calendar_edit_$id") { com.bamnat.app.ui.calendar.CalendarEditViewModel(it, id) }
            com.bamnat.app.ui.calendar.CalendarEditScreen(
                vm = vm,
                onBack = { nav.popFrom(Routes.CALENDAR_EDIT) },
                onWorkStyle = { nav.navigate(Routes.workStyle(id)) { launchSingleTop = true } },
                onPattern = { nav.openPattern(container, id) },
                onShiftTypes = { nav.navigate(Routes.shiftTypes(id)) { launchSingleTop = true } },
                onPay = { nav.navigate(Routes.pay(id)) { launchSingleTop = true } },
                onMoney = { container.calendars.setActive(id); nav.navigateTopLevel(TopLevel.MONEY.route) },
                onShare = {
                    container.calendars.setActive(id)
                    nav.navigateTopLevel(TopLevel.CALENDAR.route)
                    NoticeLinks.open(NoticeLinks.Target.SHARE, id)
                },
                onDeleted = { nav.popFrom(Routes.CALENDAR_EDIT) },
            )
        }
        composable(Routes.SHOP_DAYS, arguments = listOf(navArgument("calendar") { type = NavType.StringType; defaultValue = PersonalCalendar.MAIN })) { entry ->
            val calendar = entry.arguments?.getString("calendar") ?: PersonalCalendar.MAIN
            val vm = appViewModel(key = "shop_$calendar") { com.bamnat.app.ui.work.ShopDaysViewModel(it, calendar) }
            com.bamnat.app.ui.work.ShopDaysScreen(vm = vm, onBack = { nav.popFrom(Routes.SHOP_DAYS) }, onSaved = { msg ->
                nav.popFrom(Routes.SHOP_DAYS)
                scope.launch { snackbar.showSnackbar(msg) }
            })
        }
        composable(TopLevel.MONEY.route) {
            // [MCAL-06] The money of the calendar on the calendar tab, a view of its own per calendar.
            val calendar by container.calendars.active.collectAsStateWithLifecycle()
            val vm = appViewModel(key = "money_$calendar") { MoneyViewModel(it, calendar) }
            MoneyScreen(vm = vm, wide = wide, snackbar = snackbar, onOpenPay = { nav.navigate(Routes.pay(vm.calendarId)) { launchSingleTop = true } })
        }
        composable(Routes.WORK_STYLE, arguments = listOf(navArgument("calendar") { type = NavType.StringType; defaultValue = PersonalCalendar.MAIN })) { entry ->
            val calendar = entry.arguments?.getString("calendar") ?: PersonalCalendar.MAIN
            val settings by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val list by container.calendars.calendars.collectAsStateWithLifecycle()
            val cal = list.firstOrNull { it.id == calendar }
            val isMain = cal?.isMain != false
            val current = if (isMain) settings.workStyle else cal?.style ?: settings.workStyle
            // [UNDO-01] A calendar with records is asked first whether they stay; the answer can be taken back for 7 days.
            var asking by remember { mutableStateOf<Pair<WorkStyle, com.bamnat.app.data.RecordsTrash.Records>?>(null) }
            // clear: null when nothing is changed from a kind of work (the first one, or the same again).
            val apply: (WorkStyle, Boolean?) -> Unit = { style, clear ->
                val before = current
                // [WORK-07] A new kind of work goes straight on to what it still needs, as its card said
                // ("다음: …"): the order for shift work, the days for weekday work, the wage for hourly work.
                // What it already has is kept, so switching back and forth never asks twice.
                scope.launch {
                    if (clear == null) {
                        // [CALS-02] Each calendar keeps its own kind of work: the main one in settings, an added one in its entry.
                        if (isMain) container.settingsRepository.update { it.copy(workStyle = style) } else container.calendars.setStyle(calendar, style)
                    }
                    // [UNDO-01] Kept or cleared, the change is offered back (되돌리기) and listed for 7 days.
                    val entry = if (clear == null) null else container.recordsTrash.changeStyle(
                        calendar, style, clear,
                        if (clear) "기록을 지우고 ${style.label}${com.bamnat.app.ui.Fmt.ro(style.label)} 새로 시작해요" else style.changedMessage(),
                    )
                    val schedule = container.calendars.repository(calendar)
                    val added = addStyleTypes(container, calendar, style)
                    val patterns = schedule.patterns.first()
                    val pay = container.calendars.pay(calendar).profile.value != null
                    var opened: String? = null
                    fun go(route: String, pattern: String) {
                        opened = pattern
                        nav.navigate(route) { popUpTo(Routes.WORK_STYLE) { inclusive = true }; launchSingleTop = true }
                    }
                    when {
                        style == before -> nav.popFrom(Routes.WORK_STYLE)
                        style == WorkStyle.SHIFT && patterns.none { it is WorkPattern.Rotation } -> go(Routes.pattern(weekdays = false, setup = !pay, calendar = calendar), Routes.PATTERN)
                        style == WorkStyle.DAY && patterns.none { it is WorkPattern.Weekly } -> go(Routes.pattern(weekdays = true, setup = !pay, calendar = calendar), Routes.PATTERN)
                        style == WorkStyle.HOURLY && !pay -> go(Routes.pay(calendar), Routes.PAY)
                        // [TYPE-03] A shop sets its hours and regular days off next.
                        style == WorkStyle.BUSINESS -> go(Routes.shopDays(calendar), Routes.SHOP_DAYS)
                        else -> {
                            nav.popFrom(Routes.WORK_STYLE)
                            if (clear == null) snackbar.showSnackbar(style.changedMessage())
                        }
                    }
                    // [UNDO-01] What 되돌리기 takes back besides the trash entry: the types added and the step opened.
                    if (entry != null) styleExtras.value = StyleExtras(entry.id, calendar, added, opened)
                }
            }
            WorkStyleScreen(current = current, title = if (isMain) "근무 형태 바꾸기" else "‘${cal?.name}’ 근무 형태", onBack = { nav.popFrom(Routes.WORK_STYLE) }, onPick = { style ->
                if (current == null || style == current) apply(style, null)
                else scope.launch {
                    val records = container.recordsTrash.records(calendar)
                    if (records.isEmpty) apply(style, false) else asking = style to records
                }
            })
            asking?.let { (style, records) ->
                com.bamnat.app.ui.work.KeepRecordsDialog(
                    calendarName = cal?.name ?: PersonalCalendar.MAIN_NAME,
                    from = current ?: style,
                    to = style,
                    records = records,
                    onKeep = { asking = null; apply(style, false) },
                    onClear = { asking = null; apply(style, true) },
                    onDismiss = { asking = null },
                )
            }
        }
        composable(TopLevel.MEMOS.route) {
            val vm = appViewModel { MemoListViewModel(it) }
            // [DAILY-15] Unfolded, a memo opens in the right pane (list 40%, memo 60%) instead of a screen of its own.
            var pane by rememberSaveable(stateSaver = com.bamnat.app.ui.memo.MemoPane.Saver) { mutableStateOf<com.bamnat.app.ui.memo.MemoPane?>(null) }
            fun next(id: Long, checklist: Boolean = false, date: LocalDate? = null) =
                com.bamnat.app.ui.memo.MemoPane(id, checklist, date, (pane?.n ?: 0) + 1)
            val list = @Composable {
                MemoListScreen(
                    vm = vm,
                    onOpen = { id -> if (wide) pane = next(id) else nav.navigate(Routes.memo(id)) },
                    onNew = { checklist, date -> if (wide) pane = next(0, checklist, date) else nav.navigate(Routes.memo(0, date, checklist, notebook = true)) },
                    onOpenTrash = { nav.navigate(Routes.MEMO_TRASH) { launchSingleTop = true } },
                )
            }
            if (wide) com.bamnat.app.ui.memo.MemoPanes(pane, onOpenChange = { pane = it }, onNew = { pane = next(0) }) { list() }
            else {
                // Folded again with a memo open: it carries on full screen (a new one not yet saved is in the list).
                LaunchedEffect(Unit) {
                    val p = pane ?: return@LaunchedEffect
                    pane = null
                    if (p.id != 0L) nav.navigate(Routes.memo(p.id))
                }
                list()
            }
        }
        composable(Routes.MEMO_TRASH) {
            val vm = appViewModel { MemoTrashViewModel(it) }
            MemoTrashScreen(vm = vm, onBack = { nav.popFrom(Routes.MEMO_TRASH) })
        }
        composable(TopLevel.STATS.route) {
            val calendar by container.calendars.active.collectAsStateWithLifecycle()
            // [INV-01] An investor calendar's tab is its own: 투자·수익.
            val cals by container.calendars.calendars.collectAsStateWithLifecycle()
            val mainStyle by container.settingsRepository.settings.collectAsStateWithLifecycle()
            val tabStyle = cals.firstOrNull { it.id == calendar }?.takeIf { !it.isMain }?.style ?: mainStyle.workStyle
            if (tabStyle == WorkStyle.INVEST) {
                com.bamnat.app.ui.invest.InvestScreen(vm = com.bamnat.app.ui.invest.investViewModel(calendar), wide = wide, snackbar = snackbar)
                return@composable
            }
            val vm = appViewModel(key = "stats_$calendar") { StatsViewModel(it, calendar) }
            StatsScreen(vm = vm, wide = wide, onOpenPay = { nav.navigate(Routes.pay(vm.calendarId)) { launchSingleTop = true } })
        }
        composable(TopLevel.SETTINGS.route) {
            val vm = appViewModel { SettingsViewModel(it) }
            val nudges = appViewModel { CompanyNudgeViewModel(it) }
            SettingsScreen(
                vm = vm,
                // [CALS-02] 내 근무 and 급여 set up the calendar chosen at their top: each calendar its own.
                onOpenShiftTypes = { nav.navigate(Routes.shiftTypes(container.calendars.active.value)) { launchSingleTop = true } },
                onOpenPattern = { nav.openPattern(container, container.calendars.active.value) },
                onOpenDiagnostics = { testing -> nav.navigate(Routes.diagnostics(testing)) { launchSingleTop = true } },
                onOpenWorkStyle = { nav.navigate(Routes.workStyle(container.calendars.active.value)) { launchSingleTop = true } },
                onOpenExtraKinds = { nav.navigate(Routes.extraKinds(container.calendars.active.value)) { launchSingleTop = true } },
                onOpenPay = { nav.navigate(Routes.pay(container.calendars.active.value)) { launchSingleTop = true } },
                wide = wide,
                snackbar = snackbar,
                company = if (nudges.enabled) ({
                    CompanySettingsRows(
                        nudges,
                        onFind = { nav.navigate(Routes.companyFind()) { launchSingleTop = true } },
                        onBoard = { nav.navigate(Routes.COMPANY_BOARD) { launchSingleTop = true } },
                        onShare = { nav.navigate(Routes.COMPANY_REPORT) { launchSingleTop = true } },
                        onSent = { nav.navigate(Routes.COMPANY_SENT) { launchSingleTop = true } },
                        onFix = { nav.fixCompanySetup(it) },
                    )
                }) else null,
                onOpenInquiry = { nav.navigate(Routes.INQUIRY) { launchSingleTop = true } },
                onOpenBoard = if (nudges.enabled) ({ nav.navigate(Routes.COMPANY_BOARD) { launchSingleTop = true } }) else null,
                onOpenCalendars = { nav.navigate(Routes.CALENDARS) { launchSingleTop = true } },
            )
        }
        composable(Routes.INQUIRY) {
            val vm = appViewModel { InquiryViewModel(it) }
            InquiryScreen(vm = vm, onBack = { nav.popFrom(Routes.INQUIRY) })
        }
        composable(Routes.PAY, arguments = listOf(navArgument("calendar") { type = NavType.StringType; defaultValue = PersonalCalendar.MAIN })) { entry ->
            val calendar = entry.arguments?.getString("calendar") ?: PersonalCalendar.MAIN
            val vm = appViewModel(key = "pay_$calendar") { PayViewModel(it, calendar) }
            val profile by vm.profile.collectAsStateWithLifecycle()
            val pendingPay by container.companyStore.pendingPayFlow.collectAsStateWithLifecycle()
            // [MCAL-06] A coworker's company setup is for the main job's pay.
            if (profile == null && pendingPay != null && calendar == PersonalCalendar.MAIN) {
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
                onOpenExtraKinds = { nav.navigate(Routes.extraKinds(calendar)) { launchSingleTop = true } },
                onOpenLeave = { nav.navigate(Routes.leave(calendar)) { launchSingleTop = true } },
                top = {
                    CompanyNudgeCards(nudges, onShare = { nav.navigate(Routes.COMPANY_REPORT) { launchSingleTop = true } },
                        onFix = { nav.fixCompanySetup(it) })
                },
                // [DAILY-16] 광장 시세 of the user's own job and region next to their pay, compared on the phone.
                underTotal = { com.bamnat.app.ui.company.RateHintLine(profile) { nav.navigate(Routes.PLAZA_RATES) { launchSingleTop = true } } },
                // [ADMIN-02] 계산이 이상해요 is a question in the app; a build without the company server shares it.
                onReport = if (container.inquiryApi.enabled) { check ->
                    container.pendingPayCheck = check
                    nav.navigate(Routes.INQUIRY) { launchSingleTop = true }
                } else null,
                // [PAYS-09] The invitation after a match or an item of the company's own sends to 내 설정 나누기.
                onShareWay = { nav.navigate(Routes.COMPANY_REPORT) { launchSingleTop = true } },
                // [PAYS-10][UX-05] '?' opens the pay answers first, then a question if they don't help.
                onHelp = { com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(about = "급여", help = true)) })
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
                onBoard = if (setup) null else ({ nav.navigate(Routes.COMPANY_BOARD) { launchSingleTop = true } }),
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
                            applied.pendingPay || (setup && !pay) -> nav.navigate(Routes.pay()) {
                                popUpTo(Routes.COMPANY_FIND) { inclusive = true }
                                launchSingleTop = true
                            }
                            else -> {
                                // From 회사 규칙 광장 there is no 회사 찾기 below: back to the board.
                                if (!nav.popBackStack(Routes.COMPANY_FIND, inclusive = true)) nav.popFrom(Routes.COMPANY)
                                snackbar.showSnackbar("‘$name’ 설정을 넣었어요" + (applied.cycleDays?.let { " · ${it}일 순서" } ?: ""))
                            }
                        }
                    }
                },
                onByHand = { nav.afterCompanyByHand(setup, weekdays, ready) },
            )
        }
        composable(Routes.COMPANY_BOARD) {
            val vm = appViewModel { CompanyBoardViewModel(it) }
            CompanyBoardScreen(
                vm = vm,
                onBack = { nav.popFrom(Routes.COMPANY_BOARD) },
                onOpen = { id -> nav.navigate(Routes.company(id, setup = false, weekdays = false)) },
                onShare = { nav.navigate(Routes.COMPANY_REPORT) { launchSingleTop = true } },
                onRates = { nav.navigate(Routes.PLAZA_RATES) { launchSingleTop = true } },
            )
        }
        composable(Routes.PLAZA_RATES) {
            val vm = appViewModel { com.bamnat.app.ui.company.RatesViewModel(it) }
            com.bamnat.app.ui.company.RatesScreen(vm = vm, onBack = { nav.popFrom(Routes.PLAZA_RATES) })
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
            val calendar by container.calendars.active.collectAsStateWithLifecycle()
            val vm = appViewModel(key = "owed_$calendar") { com.bamnat.app.ui.money.OwedViewModel(it, calendar) }
            com.bamnat.app.ui.money.OwedScreen(vm = vm, wide = wide, onBack = { nav.popFrom(Routes.OWED) })
        }
        composable(Routes.CALENDAR_IMPORT) {
            val vm = appViewModel { com.bamnat.app.ui.imports.CalendarImportViewModel(it) }
            com.bamnat.app.ui.imports.CalendarImportScreen(vm = vm, onBack = { nav.popFrom(Routes.CALENDAR_IMPORT) })
        }
        composable(Routes.DUTY_ROSTER) {
            val calendar = remember { container.calendars.active.value }
            val vm = appViewModel(key = "duty_$calendar") { com.bamnat.app.ui.imports.DutyRosterViewModel(it, calendar) }
            com.bamnat.app.ui.imports.DutyRosterScreen(vm = vm, onBack = { nav.popFrom(Routes.DUTY_ROSTER) })
        }
        composable(Routes.TEAMS) {
            val calendar = remember { container.calendars.active.value }
            val vm = appViewModel(key = "teams_$calendar") { com.bamnat.app.ui.calendar.TeamsViewModel(it, calendar) }
            // [CREW-04] Opened from a day's 대근 찾기, the finder starts on that day.
            val startDay = remember { NoticeLinks.payload.value?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() } }
            com.bamnat.app.ui.calendar.TeamsScreen(vm = vm, onBack = { nav.popFrom(Routes.TEAMS) },
                onOpenPattern = { nav.openPattern(container, calendar) }, startDay = startDay)
        }
        composable(Routes.YEAR) {
            val calendar = remember { container.calendars.active.value }
            val vm = appViewModel(key = "year_$calendar") { com.bamnat.app.ui.calendar.YearViewModel(it, calendar) }
            com.bamnat.app.ui.calendar.YearScreen(vm = vm, onBack = { nav.popFrom(Routes.YEAR) })
        }
        composable(Routes.HEALTH_REPORT) {
            val calendar = remember { container.calendars.active.value }
            val vm = appViewModel(key = "health_$calendar") { com.bamnat.app.ui.stats.HealthReportViewModel(it, calendar) }
            com.bamnat.app.ui.stats.HealthReportScreen(vm = vm, onBack = { nav.popFrom(Routes.HEALTH_REPORT) })
        }
        composable(Routes.ATTENDANCE_IMPORT) {
            val calendar = remember { container.calendars.active.value }
            val vm = appViewModel(key = "attendance_$calendar") { com.bamnat.app.ui.imports.AttendanceImportViewModel(it, calendar) }
            com.bamnat.app.ui.imports.AttendanceImportScreen(vm = vm, onBack = { nav.popFrom(Routes.ATTENDANCE_IMPORT) })
        }
        composable(Routes.COMMUTE) {
            val vm = appViewModel { com.bamnat.app.ui.work.CommuteViewModel(it) }
            com.bamnat.app.ui.work.CommuteScreen(vm = vm, onBack = { nav.popFrom(Routes.COMMUTE) })
        }
        composable(Routes.LEAVE, arguments = listOf(navArgument("calendar") { type = NavType.StringType; defaultValue = PersonalCalendar.MAIN })) { entry ->
            val calendar = entry.arguments?.getString("calendar") ?: PersonalCalendar.MAIN
            val vm = appViewModel(key = "leave_$calendar") { com.bamnat.app.ui.leave.LeaveViewModel(it, calendar) }
            com.bamnat.app.ui.leave.LeaveScreen(vm = vm, onBack = { nav.popFrom(Routes.LEAVE) },
                onEditRules = { nav.navigate(Routes.pay(calendar)) { launchSingleTop = true } })
        }
        composable(Routes.FEATURES) { com.bamnat.app.ui.guide.FeatureSwitchesScreen(onBack = { nav.popFrom(Routes.FEATURES) }) }
        composable(Routes.PRO) { com.bamnat.app.ui.pro.ProScreen(onBack = { nav.popFrom(Routes.PRO) }) }
        composable(Routes.RECOMMENDED) {
            com.bamnat.app.ui.onboarding.RecommendedScreen(container, firstStart = false, onDone = { nav.popFrom(Routes.RECOMMENDED) })
        }
        composable(Routes.EXTRA_KINDS, arguments = listOf(navArgument("calendar") { type = NavType.StringType; defaultValue = PersonalCalendar.MAIN })) { entry ->
            val calendar = entry.arguments?.getString("calendar") ?: PersonalCalendar.MAIN
            val vm = appViewModel(key = "extra_kinds_$calendar") { ExtraKindsViewModel(it, calendar) }
            ExtraKindsScreen(vm = vm, onBack = { nav.popFrom(Routes.EXTRA_KINDS) },
                // Back to the pay screen when it opened this one, so the stack does not grow.
                onOpenPay = { if (!nav.popBackStack(Routes.PAY, inclusive = false)) nav.navigate(Routes.pay(calendar)) { launchSingleTop = true } })
        }
        composable(
            Routes.DIAGNOSTICS,
            arguments = listOf(navArgument("testing") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val vm = appViewModel { DiagnosticsViewModel(it) }
            DiagnosticsScreen(vm = vm, testing = entry.arguments?.getBoolean("testing") == true, onBack = { nav.popFrom(Routes.DIAGNOSTICS) })
        }
        composable(Routes.SHIFT_TYPES, arguments = listOf(
            navArgument("calendar") { type = NavType.StringType; defaultValue = PersonalCalendar.MAIN },
            navArgument("edit") { type = NavType.LongType; defaultValue = 0L },
        )) { entry ->
            val calendar = entry.arguments?.getString("calendar") ?: PersonalCalendar.MAIN
            val vm = appViewModel(key = "shifts_$calendar") { ShiftsViewModel(it, calendar) }
            ShiftTypesScreen(vm = vm, onBack = { nav.popFrom(Routes.SHIFT_TYPES) }, editId = entry.arguments?.getLong("edit") ?: 0L)
        }
        composable(
            Routes.PATTERN,
            arguments = listOf(
                navArgument("weekdays") { type = NavType.BoolType; defaultValue = false },
                navArgument("setup") { type = NavType.BoolType; defaultValue = false },
                navArgument("calendar") { type = NavType.StringType; defaultValue = PersonalCalendar.MAIN },
            ),
        ) { entry ->
            val calendar = entry.arguments?.getString("calendar") ?: PersonalCalendar.MAIN
            val vm = appViewModel(key = "shifts_$calendar") { ShiftsViewModel(it, calendar) }
            val setup = entry.arguments?.getBoolean("setup") ?: false
            PatternScreen(
                vm = vm,
                startWeekdays = entry.arguments?.getBoolean("weekdays") ?: false,
                setup = setup,
                onBack = { nav.popFrom(Routes.PATTERN) },
                // [PAYX-13] Right after the first cycle, the pay setup opens in its place.
                onSaved = if (setup) ({
                    nav.navigate(Routes.pay(calendar)) { popUpTo(Routes.PATTERN) { inclusive = true }; launchSingleTop = true }
                }) else null,
                onOpenShiftTypes = { nav.navigate(Routes.shiftTypes(calendar)) { launchSingleTop = true } },
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
                navArgument("notebook") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val args = entry.arguments
            val id = args?.getLong("id") ?: 0L
            val date = args?.getString("date")?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val checklist = args?.getBoolean("checklist") ?: false
            val notebook = args?.getBoolean("notebook") ?: false
            val vm = appViewModel(key = "memo-$id") { MemoEditorViewModel(it, id, date, checklist, notebook) }
            MemoEditorScreen(vm = vm, onBack = { nav.popFrom(Routes.MEMO) })
        }
    }
}

/** [UX-06] A tab's name, whole at every text size: at the largest sizes "근무·급여" was cut to "근무·". */
@Composable
private fun TabLabel(text: String) = FitText(text, MaterialTheme.typography.labelMedium)

/** [UX-05] A tab's icon; 설정 carries how many answers to the user's questions are new. */
@Composable
private fun TabIcon(dest: TopLevel, selected: Boolean, badge: Int) {
    val icon = if (selected) dest.selectedIcon else dest.icon
    if (badge <= 0) {
        Icon(icon, contentDescription = null)
        return
    }
    BadgedBox(badge = { IconCountBadge(badge, "문의에 새 답 ${badge}개", cap = 9) }) { Icon(icon, contentDescription = null) }
}

/** [TYPE-03] 근무 주기 of a calendar; a shop's is its 영업일·정기휴무. */
private fun NavHostController.openPattern(c: com.bamnat.app.AppContainer, calendar: String) {
    val style = c.calendars.styleOf(calendar, c.settingsRepository.current.workStyle)
    navigate(if (style == WorkStyle.BUSINESS) Routes.shopDays(calendar) else Routes.pattern(weekdays = style == WorkStyle.DAY, calendar = calendar)) { launchSingleTop = true }
}

/** [GUIDE-01] Where each [com.bamnat.core.guide.Place] of the feature list is, for the calendar on screen. */
private fun NavHostController.openFeature(c: AppContainer, link: com.bamnat.core.guide.FeatureRoute) {
    val cal = c.calendars.active.value
    fun screen(r: String) = navigate(r) { launchSingleTop = true }
    when (link.place) {
        // [CUST-01] 화면 꾸미기 opens over the month, not over today.
        Place.CALENDAR -> if (link.focus == "customize") navigateTopLevel(TopLevel.CALENDAR.route) else {
            navigateTopLevel(TopLevel.CALENDAR.route)
            com.bamnat.app.ui.guide.FeatureLinks.today(cal)
            NoticeLinks.open(NoticeLinks.Target.TODAY)
        }
        Place.TODAY -> {
            navigateTopLevel(TopLevel.CALENDAR.route)
            com.bamnat.app.ui.guide.FeatureLinks.today(cal)
            NoticeLinks.open(NoticeLinks.Target.TODAY)
        }
        Place.MONEY -> navigateTopLevel(TopLevel.MONEY.route)
        Place.AUTO_RECORD -> NoticeLinks.open(NoticeLinks.Target.AUTO_RECORD)
        Place.MEMOS -> navigateTopLevel(TopLevel.MEMOS.route)
        Place.STATS -> navigateTopLevel(TopLevel.STATS.route)
        Place.PAY -> screen(Routes.pay(cal))
        Place.PATTERN -> openPattern(c, cal)
        Place.SHIFT_TYPES -> screen(Routes.shiftTypes(cal))
        Place.EXTRA_KINDS -> screen(Routes.extraKinds(cal))
        Place.WORK_STYLE -> screen(Routes.workStyle(cal))
        Place.CALENDARS -> screen(Routes.CALENDARS)
        Place.CALENDAR_EDIT -> screen(Routes.calendarEdit(cal))
        Place.SHOP_DAYS -> screen(Routes.shopDays(cal))
        Place.COMBINED -> screen(Routes.COMBINED)
        Place.OWED -> screen(Routes.OWED)
        Place.SLEEP -> screen(Routes.SLEEP)
        Place.DUTY_ROSTER -> screen(Routes.DUTY_ROSTER)
        Place.HEALTH_REPORT -> screen(Routes.HEALTH_REPORT)
        Place.TEAMS -> screen(Routes.TEAMS)
        Place.YEAR -> screen(Routes.YEAR)
        Place.ATTENDANCE_IMPORT -> screen(Routes.ATTENDANCE_IMPORT)
        Place.COMMUTE -> screen(Routes.COMMUTE)
        Place.CALENDAR_IMPORT -> screen(Routes.CALENDAR_IMPORT)
        Place.SHARE -> { navigateTopLevel(TopLevel.CALENDAR.route); NoticeLinks.open(NoticeLinks.Target.SHARE, cal) }
        Place.RECEIVE -> { navigateTopLevel(TopLevel.CALENDAR.route); NoticeLinks.open(NoticeLinks.Target.RECEIVE) }
        Place.DIAGNOSTICS -> screen(Routes.diagnostics(false))
        Place.INQUIRY -> com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(help = true))
        Place.FEATURES -> screen(Routes.FEATURES)
        Place.PRO -> screen(Routes.PRO)
        Place.RECOMMENDED -> screen(Routes.RECOMMENDED)
        Place.LEAVE -> screen(Routes.leave(cal))
        Place.SETTINGS_WORK, Place.SETTINGS_ALARM, Place.SETTINGS_NOTICES, Place.SETTINGS_PAY,
        Place.SETTINGS_CALENDAR, Place.SETTINGS_DATA, Place.SETTINGS_PRIVACY ->
            link.place.settingsPage()?.let { SettingsLinks.open(it, link.focus) }
    }
}

/** [UX-04] The route a [SettingsGo] opens; the calendar's own sheets are opened through [NoticeLinks] instead. */
private fun SettingsGo.route(): String? = when (this) {
    SettingsGo.WORK_STYLE -> Routes.workStyle()
    SettingsGo.PATTERN -> Routes.pattern()
    SettingsGo.SHIFT_TYPES -> Routes.shiftTypes()
    SettingsGo.EXTRA_KINDS -> Routes.extraKinds()
    SettingsGo.PAY -> Routes.pay()
    SettingsGo.CALENDARS -> Routes.CALENDARS
    SettingsGo.DIAGNOSTICS -> Routes.diagnostics(false)
    SettingsGo.INQUIRY -> Routes.INQUIRY
    SettingsGo.BOARD -> Routes.COMPANY_BOARD
    SettingsGo.FEATURES -> Routes.FEATURES
    SettingsGo.PRO -> Routes.PRO
    SettingsGo.RECOMMENDED -> Routes.RECOMMENDED
    SettingsGo.SHARE, SettingsGo.RECEIVE, SettingsGo.SLEEP, SettingsGo.CALENDAR_IMPORT,
    SettingsGo.DUTY_ROSTER, SettingsGo.HEALTH_REPORT, SettingsGo.ATTENDANCE_IMPORT, SettingsGo.COMMUTE -> null
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
            Button(onClick = { AlarmService.askToDismiss(context) }) { Text("끄기") }
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
        FixPlace.PAY -> navigate(Routes.pay()) { launchSingleTop = true }
        FixPlace.SHIFT_TYPES -> navigate(Routes.shiftTypes()) { launchSingleTop = true }
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
