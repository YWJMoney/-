package com.bamnat.app.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.EventRepeat
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.WorkOutline
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.BuildConfig
import com.bamnat.app.NoticeLinks
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.alarm.Notifications
import com.bamnat.app.alarm.UpcomingAlarm
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.BackupException
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.BackupSummary
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.ThemeMode
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.SystemSettings
import com.bamnat.app.ui.components.rememberNotificationAsk
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.IcsWriter
import com.bamnat.core.schedule.PatternTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** [SET-01] The settings in nine groups, in the order people look for them, under the alarm's state. */
enum class SettingsPage(val title: String, val icon: ImageVector) {
    WORK("내 근무", Icons.Outlined.WorkOutline),
    ALARM("알람", Icons.Outlined.Alarm),
    REST("잠과 휴식", Icons.Outlined.Bedtime),
    NOTICES("알림", Icons.Outlined.Notifications),
    PAY("급여와 연차", Icons.Outlined.Payments),
    CALENDAR("달력 화면", Icons.Outlined.CalendarMonth),
    DATA("데이터", Icons.Outlined.SettingsBackupRestore),
    PRIVACY("개인정보와 권한", Icons.Outlined.PrivacyTip),
    ABOUT("앱 정보", Icons.Outlined.Info),
}

/** A result to show once; [undo] offers to take an import back. */
data class SettingsMessage(val text: String, val undo: Boolean = false)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val upcoming: StateFlow<UpcomingAlarm?> = container.alarmScheduler.upcoming
    val snoozeAt: StateFlow<Long?> = container.alarmScheduler.snoozeAt
    /** [MCAL-01] The user's calendars, for 설정 > 달력. */
    val calendars: StateFlow<List<com.bamnat.app.data.PersonalCalendar>> = container.calendars.calendars
    /** [CALS-02] 내 근무 and 급여 follow the calendar picked at their top (the one on the calendar tab). */
    val activeCalendar: StateFlow<String> = container.calendars.active
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val snapshot: StateFlow<ScheduleSnapshot?> = container.calendars.activeSnapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    /** [CALS-02] The settings as the calendar picked sees them: its own kind of work. */
    val workSettings: StateFlow<AppSettings> = kotlinx.coroutines.flow.combine(container.settingsRepository.settings, container.calendars.activeCalendar) { s, cal ->
        com.bamnat.app.data.CalendarScope.run { s.forCalendar(cal) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, container.settingsRepository.current)
    fun cancelSnooze() = container.alarmScheduler.cancelSnooze()
    fun update(transform: (AppSettings) -> AppSettings) = container.settingsRepository.update(transform)
    fun canScheduleExact() = container.alarmScheduler.canScheduleExact()

    private val _missed = MutableStateFlow(0)
    /** [SET-01] Alarms the log planned that never rang, over the last week. */
    val missed: StateFlow<Int> = _missed.asStateFlow()

    /** Reads the log off the main thread; called each time settings comes back to the front. */
    fun recheckMissed(now: Long = System.currentTimeMillis()) {
        viewModelScope.launch(Dispatchers.IO) {
            _missed.value = EventBook.possiblyMissed(container.telemetry.log.read(), now).count { it > now - MISSED_WINDOW_MILLIS }
        }
    }

    /** [SLP-08] Health Connect sleep, turned on only once reading is allowed. */
    fun healthSdkStatus() = container.healthSleep.sdkStatus()
    suspend fun healthGranted() = container.healthSleep.granted()
    fun setHealthSleep(on: Boolean) {
        update { it.copy(healthSleep = on) }
        viewModelScope.launch { container.healthSleep.refresh(on, force = true) }
    }

    fun setAlarmsEnabled(on: Boolean) {
        update { it.copy(alarmsEnabled = on) }
        if (!on) container.alarmScheduler.cancelSnooze()
    }

    private val _message = MutableStateFlow<SettingsMessage?>(null)
    /** Result of the last backup, import or undo, shown once. */
    val message: StateFlow<SettingsMessage?> = _message.asStateFlow()
    fun consumeMessage() {
        _message.value = null
    }

    /** [SET-01] When the data before the last import was kept, while it can still be put back. */
    val importSavedAt: StateFlow<Long?> = container.importUndo.savedAt

    fun undoImport() {
        container.appScope.launch {
            _message.value = SettingsMessage(runCatching {
                container.importUndo.undo()
                "불러오기 전으로 되돌렸어요"
            }.getOrElse { e -> (e as? BackupException)?.message ?: "되돌리지 못했어요. 지금 데이터는 그대로예요" })
        }
    }

    /** A backup read and checked, waiting for the user to confirm the restore. */
    private val _pendingRestore = MutableStateFlow<Pair<String, BackupSummary>?>(null)
    val pendingRestore: StateFlow<Pair<String, BackupSummary>?> = _pendingRestore.asStateFlow()

    /** [FEAT-08] Password for the next export, chosen before the file picker opens; never stored. */
    private var exportPassword: CharArray? = null

    fun prepareExport(password: CharArray?) {
        exportPassword?.fill(' ')
        exportPassword = password
    }

    fun exportTo(context: Context, uri: Uri, sealed: Boolean = false) {
        val password = exportPassword
        exportPassword = null
        if (sealed && password == null) {
            // The app was closed while the picker was open and the password went with it: never fall
            // back to a file without one. The empty file the picker made is removed.
            runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            _message.value = SettingsMessage("암호가 지워져 저장하지 않았어요. 다시 저장해 주세요")
            return
        }
        viewModelScope.launch {
            _message.value = SettingsMessage(runCatching {
                val json = container.backupManager.export()
                val text = if (password == null) json else withContext(Dispatchers.Default) { container.backupManager.seal(json, password) }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) }
                }
                container.telemetry.event(DiagEvents.BACKUP_EXPORT)
                if (password == null) "백업 파일을 저장했어요" else "암호 건 백업을 저장했어요. 암호를 잊으면 열 수 없어요"
            }.getOrElse { "백업을 저장하지 못했어요" })
            password?.fill(' ')
        }
    }

    /** [FEAT-08] A sealed backup waiting for its password. */
    private val _locked = MutableStateFlow<LockedBackup?>(null)
    val locked: StateFlow<LockedBackup?> = _locked.asStateFlow()

    fun unlock(password: CharArray) {
        val current = _locked.value ?: return
        _locked.value = current.copy(working = true, error = null)
        viewModelScope.launch {
            runCatching {
                val json = withContext(Dispatchers.Default) { container.backupManager.unseal(current.text, password) }
                json to container.backupManager.summarize(json)
            }.onSuccess {
                _locked.value = null
                _pendingRestore.value = it
            }.onFailure { e ->
                _locked.value = current.copy(working = false, error = (e as? BackupException)?.message ?: "백업 파일을 읽을 수 없어요")
            }
            password.fill(' ')
        }
    }

    fun cancelUnlock() {
        _locked.value = null
    }

    /** [COMM-00] A company file read and checked, waiting for the user to apply it. */
    private val _pendingCompany = MutableStateFlow<Pair<String, com.bamnat.app.data.CompanySummary>?>(null)
    val pendingCompany: StateFlow<Pair<String, com.bamnat.app.data.CompanySummary>?> = _pendingCompany.asStateFlow()
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val hasPayProfile: StateFlow<com.bamnat.core.pay.PayProfile?> = container.calendars.active
        .flatMapLatest { container.calendars.pay(it).profile }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.calendars.pay(container.calendars.active.value).profile.value)

    /** [ADMIN-02] 문의하기 in the app when the build has the company server, else by mail. */
    val inquiryOn: Boolean get() = container.inquiryApi.enabled
    /** [UX-04] The build can make and take calendar links, so 동료 근무표 받기 is offered. */
    val shareOn: Boolean get() = container.shareApi.enabled
    /** Answers from the developer not yet opened. */
    val inquiryUnread: StateFlow<Int> get() = container.inquiryStore.unread

    /** Looks for new answers, only once this phone has asked something. */
    fun checkInquiries() {
        if (!inquiryOn || !container.inquiryStore.asked) return
        viewModelScope.launch {
            runCatching { container.inquiryApi.mine(container.companyStore.device) }.onSuccess { container.inquiryStore.update(it) }
        }
    }

    /** [COMM-00] Writes the company file to the share cache and opens the share sheet. */
    fun shareCompany(context: Context, company: String, withAmounts: Boolean, team: String = "") {
        viewModelScope.launch {
            runCatching {
                val text = container.companyShare.export(company, withAmounts, team)
                val file = withContext(Dispatchers.IO) {
                    val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    java.io.File(dir, "밤낮-회사설정-${company.trim().ifEmpty { "우리회사" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")}.json").apply { writeText(text) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.share", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = com.bamnat.app.data.CompanyShare.MIME_TYPE
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "밤낮 앱 설정 > 데이터 > 파일 불러오기에서 이 파일을 열면 근무 주기와 급여 규칙이 들어가요.")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "회사 설정 보내기"))
                container.telemetry.event(DiagEvents.COMPANY_SHARE)
            }.onFailure { _message.value = SettingsMessage("회사 설정 파일을 만들지 못했어요") }
        }
    }

    fun cancelCompany() {
        _pendingCompany.value = null
    }

    /** [SETUP-02] The file's order as rows of the coming week, the sender's row marked. */
    suspend fun phasesFor(text: String): com.bamnat.app.ui.company.Phases? =
        runCatching { com.bamnat.app.ui.company.rotationPhases(container, text, fromSender = true) }.getOrNull()

    /** [todayStep]: today's place in the file's order, so another 조 lines up too [SETUP-02]. */
    fun applyCompany(rotation: Boolean, todayStep: Int? = null) {
        val text = _pendingCompany.value?.first ?: return
        _pendingCompany.value = null
        container.appScope.launch {
            _message.value = runCatching {
                // [SET-01] Kept first, so the company's setup can be taken back.
                container.importUndo.save()
                val done = container.companyShare.apply(text, rotation, todayStep)
                // [COMM-01] With no pay setup yet, the pay screen asks only for the user's own amounts.
                if (done.pendingPay) container.companyStore.pendingPay = text
                container.telemetry.event(DiagEvents.COMPANY_IMPORT)
                SettingsMessage(buildList {
                    add("회사 설정을 넣었어요")
                    if (done.addedTypes > 0) add("근무 종류 ${done.addedTypes}개 추가")
                    done.cycleDays?.let { add("${it}일 주기 적용") }
                    if (done.rules) add("급여 규칙 적용")
                    if (done.pendingPay) add("급여 화면에서 내 금액만 넣으면 돼요")
                }.joinToString(" · "), undo = true)
            }.getOrElse { e -> SettingsMessage((e as? BackupException)?.message ?: "회사 설정을 넣지 못했어요") }
        }
    }

    /** Writes work days from three months ago to a year ahead as a calendar file [REL-04]. */
    fun exportIcs(context: Context, uri: Uri) {
        viewModelScope.launch {
            _message.value = SettingsMessage(runCatching {
                val snapshot = container.scheduleRepository.snapshot.first()
                val (from, to) = IcsWriter.defaultRange(LocalDate.now())
                val ics = withContext(Dispatchers.Default) {
                    IcsWriter.write(snapshot.resolver.resolveRange(from, to), ZoneId.systemDefault())
                }
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(ics.toByteArray()) }
                }
                container.telemetry.event(DiagEvents.ICS_EXPORT)
                "캘린더 파일을 저장했어요. 구글·삼성 캘린더에서 가져오기로 불러오세요"
            }.getOrElse { "캘린더 파일을 저장하지 못했어요" })
        }
    }

    /** [SET-01] One way in for both kinds of file: a backup, or a company setup from a coworker. */
    fun readFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
                if (com.bamnat.app.data.CompanyShare.isCompanyFile(json)) {
                    _pendingCompany.value = json to container.companyShare.summarize(json)
                    return@runCatching
                }
                if (container.backupManager.isSealed(json)) _locked.value = LockedBackup(json)
                else _pendingRestore.value = json to container.backupManager.summarize(json)
            }.onFailure { e ->
                _message.value = SettingsMessage((e as? BackupException)?.message ?: "파일을 읽을 수 없어요")
            }
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore() {
        val json = _pendingRestore.value?.first ?: return
        _pendingRestore.value = null
        container.appScope.launch {
            _message.value = runCatching {
                // [SET-01] Kept first: without the copy nothing is replaced.
                container.importUndo.save()
                container.backupManager.restore(json)
                container.telemetry.event(DiagEvents.BACKUP_RESTORE)
                SettingsMessage("백업을 불러왔어요", undo = true)
            }.getOrElse { e -> SettingsMessage((e as? BackupException)?.message ?: "백업을 불러오지 못했어요. 지금 데이터는 그대로예요") }
        }
    }

    private companion object {
        const val MISSED_WINDOW_MILLIS = 7 * 24 * 60 * 60 * 1000L
    }
}

/**
 * [SET-01] Settings: the alarm's state on top, then nine groups. Folded, a group opens over the
 * list; unfolded, the list stays on the left and the group shows beside it.
 */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onOpenShiftTypes: () -> Unit,
    onOpenPattern: () -> Unit,
    /** The alarm check; [testing] adds the testers' switches, reached only by the version taps. */
    onOpenDiagnostics: (testing: Boolean) -> Unit,
    onOpenWorkStyle: () -> Unit = {},
    onOpenExtraKinds: () -> Unit = {},
    onOpenPay: () -> Unit = {},
    wide: Boolean = false,
    snackbar: SnackbarHostState? = null,
    /** [COMM-01][COMM-02] 회사 찾기·내 설정 나누기·내가 보낸 정보, when the build has a company server. */
    company: (@Composable ColumnScope.() -> Unit)? = null,
    /** [ADMIN-02] 문의하기 in the app. */
    onOpenInquiry: () -> Unit = {},
    /** [COMM-07] 회사 규칙 광장, on the first screen when the build has a company server. */
    onOpenBoard: (() -> Unit)? = null,
    /** [MCAL-04] The calendar list. */
    onOpenCalendars: () -> Unit = {},
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Permission states can change in system settings; re-check whenever we come back.
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val askNotifications = rememberNotificationAsk()

    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    val hubScroll = rememberScrollState()
    // [UX-04] What is typed in the search, and the row a search result or another screen asked for.
    var query by rememberSaveable { mutableStateOf("") }
    var focus by remember { mutableStateOf<SettingsFocus?>(null) }
    val openPage: (SettingsPage) -> Unit = { p -> focus = null; page = p }
    val link by SettingsLinks.target.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        val l = link as? SettingsLink.Page ?: return@LaunchedEffect
        SettingsLinks.consume(l)
        query = ""
        page = l.page
        focus = l.row?.let(::SettingsFocus)
    }
    val found: (SettingEntry) -> Unit = { e ->
        when (val place = e.place) {
            is SettingPlace.Row -> { page = place.page; focus = SettingsFocus(place.row) }
            // 자주 묻는 질문 opens on the answers, 문의하기 on the questions.
            is SettingPlace.Screen -> if (place.go == SettingsGo.INQUIRY) InquiryLinks.ask(InquiryStart(help = e.title == HELP_TITLE))
                else SettingsLinks.go(place.go)
        }
    }
    // [SLP-15] The notice about an alarm that may be late opens here, on the alarm's state.
    val noticeTarget by NoticeLinks.target.collectAsStateWithLifecycle()
    LaunchedEffect(noticeTarget) {
        if (noticeTarget != NoticeLinks.Target.ALARM_CHECK) return@LaunchedEffect
        NoticeLinks.consume(NoticeLinks.Target.ALARM_CHECK)
        page = if (wide) SettingsPage.ALARM else null
        hubScroll.scrollTo(0)
    }

    val files = rememberSettingsFiles(vm)
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        vm.consumeMessage()
        if (snackbar == null) {
            android.widget.Toast.makeText(context, m.text, android.widget.Toast.LENGTH_SHORT).show()
            return@LaunchedEffect
        }
        val result = snackbar.showSnackbar(m.text, actionLabel = if (m.undo) "되돌리기" else null,
            duration = if (m.undo) SnackbarDuration.Long else SnackbarDuration.Short)
        if (result == SnackbarResult.ActionPerformed) vm.undoImport()
    }

    val pageContent: @Composable ColumnScope.(SettingsPage) -> Unit = { p ->
        SettingsPageContent(
            page = p, vm = vm, settings = settings, refresh = refresh, files = files, askNotifications = askNotifications,
            onOpenShiftTypes = onOpenShiftTypes, onOpenPattern = onOpenPattern, onOpenDiagnostics = onOpenDiagnostics,
            onOpenWorkStyle = onOpenWorkStyle, onOpenExtraKinds = onOpenExtraKinds, onOpenPay = onOpenPay, company = company,
            onOpenPage = openPage, onOpenInquiry = onOpenInquiry, onOpenCalendars = onOpenCalendars,
        )
    }
    val hub: @Composable (Modifier, SettingsPage?) -> Unit = { modifier, selected ->
        SettingsHub(vm, settings, refresh, askNotifications, selected, hubScroll, modifier,
            onOpen = openPage, onOpenDiagnostics = onOpenDiagnostics, onOpenShiftTypes = onOpenShiftTypes, onOpenBoard = onOpenBoard,
            query = query, onQuery = { query = it }, onFound = found, onOpenInquiry = onOpenInquiry)
    }

    if (wide) {
        val shown = page ?: SettingsPage.WORK
        BoxWithConstraints(Modifier.fillMaxSize()) {
        // The list takes two fifths: its lines are short, the group's explanations are long.
        val hubWidth = (maxWidth * 0.4f).coerceIn(HUB_MIN_WIDTH, HUB_MAX_WIDTH)
        Row(Modifier.fillMaxSize()) {
            hub(Modifier.width(hubWidth).fillMaxHeight(), shown)
            VerticalDivider()
            AnimatedContent(
                targetState = shown,
                transitionSpec = { fadeIn(tween(PAGE_MILLIS)) togetherWith fadeOut(tween(PAGE_MILLIS)) },
                label = "settings-page",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { p -> CompositionLocalProvider(LocalSettingsFocus provides focus) { PageColumn(p, onBack = null) { pageContent(p) } } }
        }
        }
        return
    }
    BackHandler(enabled = page != null) { page = null; focus = null }
    // [UX-04] Back clears a search before it leaves settings.
    BackHandler(enabled = page == null && query.isNotEmpty()) { query = "" }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val opening = targetState != null
            (slideInHorizontally(tween(PAGE_MILLIS)) { w -> if (opening) w / 4 else -w / 4 } + fadeIn(tween(PAGE_MILLIS))) togetherWith
                (slideOutHorizontally(tween(PAGE_MILLIS)) { w -> if (opening) -w / 4 else w / 4 } + fadeOut(tween(PAGE_MILLIS)))
        },
        label = "settings-page",
    ) { p ->
        if (p == null) hub(Modifier.fillMaxSize(), null)
        else CompositionLocalProvider(LocalSettingsFocus provides focus) {
            PageColumn(p, onBack = { page = null; focus = null }) { pageContent(p) }
        }
    }
}

/** [VIS-02] A group opens in this long; the list and the group slide a quarter of the way. */
private const val PAGE_MILLIS = 250
private val HUB_MIN_WIDTH = 260.dp
private val HUB_MAX_WIDTH = 360.dp

/** A group's page: its title (with back when folded), then its sections, scrolling. */
@Composable
private fun PageColumn(page: SettingsPage, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "설정으로") }
                Spacer(Modifier.width(4.dp))
            }
            Text(page.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }
        content()
        // [UX-05] Every group ends with where to ask, with the group named so the answers start there.
        if (page != SettingsPage.ABOUT) {
            TextButton(
                onClick = { InquiryLinks.ask(InquiryStart(about = "설정 > ${page.title}", help = true)) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("${page.title}${com.bamnat.app.ui.Fmt.ro(page.title)} 도움이 필요하면 · 도움말·문의")
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

/** The list of groups, with the alarm's state above it and the version under it. */
@Composable
private fun SettingsHub(
    vm: SettingsViewModel,
    settings: AppSettings,
    refresh: Int,
    askNotifications: () -> Unit,
    selected: SettingsPage?,
    scroll: ScrollState,
    modifier: Modifier,
    onOpen: (SettingsPage) -> Unit,
    onOpenDiagnostics: (Boolean) -> Unit,
    onOpenShiftTypes: () -> Unit,
    onOpenBoard: (() -> Unit)? = null,
    query: String = "",
    onQuery: (String) -> Unit = {},
    onFound: (SettingEntry) -> Unit = {},
    onOpenInquiry: () -> Unit = {},
) {
    val context = LocalContext.current
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val profile by vm.hasPayProfile.collectAsStateWithLifecycle()
    val notificationsOk = remember(refresh) { Notifications.canPost(context) }
    val unread by vm.inquiryUnread.collectAsStateWithLifecycle()
    LaunchedEffect(refresh) { vm.checkInquiries() }
    Column(modifier.verticalScroll(scroll).padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("설정", style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() })
        SettingsSearchField(query, onQuery)
        Spacer(Modifier.size(4.dp))
        if (query.isNotBlank()) {
            val entries = remember(onOpenBoard != null) { SettingsIndex.entries(board = onOpenBoard != null, share = vm.shareOn) }
            SearchResults(query, remember(query, entries) { SettingsIndex.search(query, entries) }, vm.shareOn, onFound)
            return@Column
        }
        // [UX-05] A new answer is said at the top, not only on the row at the end of the list.
        if (unread > 0) NewAnswerLine(unread, onOpenInquiry)
        AlarmReadyCard(vm, settings, refresh, askNotifications, onOpenDiagnostics, onOpenShiftTypes)
        Spacer(Modifier.size(4.dp))
        Shortcuts(onFound)
        Spacer(Modifier.size(4.dp))
        val work by vm.workSettings.collectAsStateWithLifecycle()
        for (p in SettingsPage.entries) {
            GroupRow(p.icon, p.title, summary(p, if (p == SettingsPage.WORK) work else settings, snapshot, profile != null, notificationsOk), selected == p) { onOpen(p) }
        }
        // [COMM-07] Its own row, so it is one tap away rather than inside 급여.
        if (onOpenBoard != null) GroupRow(Icons.Outlined.Forum, "회사 규칙 광장", "회사별 근무표·급여 규칙 찾기 · 맞았어요·달라요 · 토론", false, onClick = onOpenBoard)
        // [UX-05] Help and questions on their own row, with new answers counted on it.
        GroupRow(
            Icons.AutoMirrored.Outlined.HelpOutline, "도움말과 문의",
            when {
                unread > 0 -> "개발자 답이 ${unread}개 왔어요 · 자주 묻는 질문 · 문의하기"
                vm.inquiryOn -> "자주 묻는 질문 · 문의하기 · 내 문의와 답"
                else -> "자주 묻는 질문 · 메일로 문의하기"
            },
            false, badge = unread, onClick = onOpenInquiry,
        )
        VersionLine(onOpenDiagnostics)
    }
}

/** [UX-05] "개발자 답이 왔어요" above everything else in settings, one tap from the answer. */
@Composable
private fun NewAnswerLine(unread: Int, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.secondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .clickable(role = Role.Button, onClickLabel = "답 보기", onClick = onOpen),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Forum, null, tint = colors.onSecondaryContainer)
            Spacer(Modifier.width(12.dp))
            Text("문의에 개발자 답이 ${unread}개 왔어요", style = MaterialTheme.typography.titleSmall,
                color = colors.onSecondaryContainer, modifier = Modifier.weight(1f))
            Text("보기", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}

/** [UX-04] The search over every setting, at the top of the list. */
@Composable
private fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = { onQuery(it.take(40)) },
        // TalkBack names the field once the example has gone.
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "설정 찾기" },
        placeholder = { Text("설정 찾기 (예: 알람 소리, 백업)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Filled.Search, null) },
        trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, "검색어 지우기") } }) else null,
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** [UX-04] What the search found: the setting, and where it lives, so the way there is learned too. */
@Composable
private fun SearchResults(query: String, results: List<SettingEntry>, share: Boolean, onFound: (SettingEntry) -> Unit) {
    Text(if (results.isEmpty()) "‘$query’에 맞는 설정이 없어요" else "찾은 설정 ${results.size}개",
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
    for (e in results) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button) { onFound(e) }.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge)
                Text(if (e.where == "설정") "설정" else "설정 › ${e.where}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
    // [UX-05] The same words find the help answers too: "알람이 안 울려요" is a question more than a setting.
    val answers = remember(query, share) { HelpFaq.search(query, HelpFaq.topics(share), limit = 3) }
    if (answers.isNotEmpty()) {
        Text("도움말의 답 ${answers.size}개", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
        for (t in answers) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button) { InquiryLinks.ask(InquiryStart(help = true, text = query)) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Outlined.HelpOutline, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.question, style = MaterialTheme.typography.bodyLarge)
                    Text("도움말 › ${t.tag}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
    // Nothing found is not a dead end: the same words go to the help answers, and on to a question.
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (results.isEmpty() && answers.isEmpty()) "도움말에서 더 찾거나 개발자에게 물어봐요" else "찾는 게 아니면 도움말에서 더 찾아봐요",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { InquiryLinks.ask(InquiryStart(help = true, text = query)) }) { Text("도움말") }
        }
    }
}

/** [UX-04] What people open most, one tap from the top: work, pay, sharing, the alarm's sound, a backup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Shortcuts(onFound: (SettingEntry) -> Unit) {
    val entries = remember { SettingsIndex.entries().associateBy { it.title } }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text("자주 찾는 것", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((title, label, icon) in SHORTCUTS) {
                val e = entries[title] ?: continue
                AssistChip(onClick = { onFound(e) }, label = { Text(label) },
                    leadingIcon = { Icon(icon, null, Modifier.size(AssistChipDefaults.IconSize)) })
            }
        }
    }
}

private val SHORTCUTS = listOf(
    Triple("근무 종류와 시간", "근무 시간", Icons.Outlined.Schedule),
    Triple("근무 주기", "근무 주기", Icons.Outlined.EventRepeat),
    Triple("급여 계산", "급여", Icons.Outlined.Payments),
    Triple("근무표 공유", "근무표 공유", Icons.Outlined.Share),
    Triple("알람음", "알람 소리", Icons.Outlined.MusicNote),
    Triple("백업 저장", "백업", Icons.Outlined.SettingsBackupRestore),
)

/** [UX-04] The search result that opens the help answers rather than the question form. */
internal const val HELP_TITLE = "자주 묻는 질문"

/** How each group stands now, in a few words. */
private fun summary(page: SettingsPage, s: AppSettings, snapshot: ScheduleSnapshot?, hasPay: Boolean, notificationsOk: Boolean): String = when (page) {
    SettingsPage.WORK -> listOfNotNull(
        s.workStyle?.label ?: "일하는 방식 정하기",
        snapshot?.shiftTypes?.count { !it.isOff }?.takeIf { it > 0 }?.let { "근무 ${it}가지" },
    ).joinToString(" · ")
    SettingsPage.ALARM -> if (s.alarmsEnabled) "켜짐 · 다시 울림 ${s.snoozeMinutes}분 · ${s.ringMinutes}분 뒤 꺼짐" else "모두 꺼짐"
    SettingsPage.REST -> listOfNotNull(
        "오늘의 흐름".takeIf { s.sleepTips },
        "목표 ${sleepHours(s.sleepTargetMinutes)}",
        "휴식 점검".takeIf { s.restCheck },
    ).joinToString(" · ")
    SettingsPage.NOTICES -> if (!notificationsOk) "알림이 꺼져 있어요" else listOfNotNull(
        "하루 브리핑".takeIf { s.briefing }, "퇴근길 안전".takeIf { s.commuteNotice }, "잘 준비".takeIf { s.bedtimeNotice },
        "자는 중".takeIf { s.sleepingNotice }, "돈".takeIf { s.moneyNotices }, "연차".takeIf { s.leaveNotice },
    ).ifEmpty { listOf("근무 알람만 와요") }.joinToString(" · ")
    SettingsPage.PAY -> if (hasPay) "급여 계산 · 연차 · 회사 설정 주고받기" else "급여 계산 시작하기 · 회사 설정 주고받기"
    SettingsPage.CALENDAR -> listOf(
        when (s.themeMode) { ThemeMode.SYSTEM -> "휴대폰 테마"; ThemeMode.LIGHT -> "밝게"; ThemeMode.DARK -> "어둡게"; ThemeMode.NIGHT_SHIFT -> "야간 근무 중 어둡게" },
        "날짜 칸 아래 " + when { s.showLunar -> "음력"; s.showHoursInCells -> "근무시간"; else -> "비움" },
    ).joinToString(" · ")
    SettingsPage.DATA -> "백업 저장 · 불러오기 · 캘린더 가져오기·내보내기"
    SettingsPage.PRIVACY -> "개인정보 처리방침 · 권한 확인"
    SettingsPage.ABOUT -> "밤낮 ${BuildConfig.VERSION_NAME} · 알람 점검 · 처리방침"
}

/** "7시간", "6시간 반". */
internal fun sleepHours(m: Int): String = if (m % 60 == 0) "${m / 60}시간" else if (m % 60 == 30) "${m / 60}시간 반" else "${m / 60}시간 ${m % 60}분"

/** Seven taps on the version open the alarm check with the testers' switches [MEAS-04]. */
@Composable
private fun VersionLine(onOpenDiagnostics: (Boolean) -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    Text(
        "밤낮 ${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                taps++
                if (taps >= 7) {
                    taps = 0
                    onOpenDiagnostics(true)
                }
            }
            .padding(top = 16.dp, bottom = 24.dp),
    )
}

/**
 * [SET-01] 알람 준비: when the next alarm rings, and anything that would keep it from ringing on time
 * or from being stopped on the lock screen, each with the one button that fixes it. All fine, it is
 * one line.
 */
@Composable
private fun AlarmReadyCard(
    vm: SettingsViewModel,
    settings: AppSettings,
    refresh: Int,
    askNotifications: () -> Unit,
    onOpenDiagnostics: (Boolean) -> Unit,
    onOpenShiftTypes: () -> Unit,
) {
    val context = LocalContext.current
    val upcoming by vm.upcoming.collectAsStateWithLifecycle()
    val snoozeAt by vm.snoozeAt.collectAsStateWithLifecycle()
    val missed by vm.missed.collectAsStateWithLifecycle()
    LaunchedEffect(refresh) { vm.recheckMissed() }
    val fixes = remember(refresh, settings.alarmsEnabled, missed) { alarmFixes(context, vm, settings, missed, askNotifications, onOpenDiagnostics) }
    val cal = LocalCalendarColors.current
    val next = upcoming
    val (icon, headline, tint) = when {
        !settings.alarmsEnabled -> Triple(Icons.Filled.AlarmOff, "알람이 모두 꺼져 있어요", MaterialTheme.colorScheme.error)
        fixes.isNotEmpty() -> Triple(Icons.Filled.WarningAmber, "알람 확인이 필요해요", cal.warning)
        next == null -> Triple(Icons.Outlined.Alarm, "울릴 알람이 없어요", MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Triple(Icons.Filled.CheckCircle, "알람 준비 완료", MaterialTheme.colorScheme.secondary)
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (fixes.isNotEmpty() || !settings.alarmsEnabled) cal.warningContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(headline, style = MaterialTheme.typography.titleMedium)
                    val line = when {
                        !settings.alarmsEnabled -> "근무 알람도 이 날 알람도 울리지 않아요"
                        next == null -> "근무 종류에서 근무 전 알람을 켜면 울려요"
                        else -> AlarmScheduler.millisToLocal(next.triggerAtMillis).let { at ->
                            "다음 알람 ${Fmt.dayTitle(at.toLocalDate())} ${Fmt.time(at.toLocalTime())} · " + next.plan.shiftName.ifBlank { "이 날 알람" }
                        }
                    }
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    !settings.alarmsEnabled -> TextButton(onClick = { vm.setAlarmsEnabled(true) }) { Text("켜기") }
                    next == null && fixes.isEmpty() -> TextButton(onClick = onOpenShiftTypes) { Text("근무 종류") }
                }
            }
            snoozeAt?.let { at ->
                Row(Modifier.padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${Fmt.time(AlarmScheduler.millisToLocal(at).toLocalTime())}에 다시 울려요",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::cancelSnooze) { Text("지금 끄기") }
                }
            }
            for (fix in fixes) {
                Row(Modifier.padding(start = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(fix.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = fix.onFix) { Text(fix.label) }
                }
            }
        }
    }
}

private class AlarmFix(val text: String, val label: String, val onFix: () -> Unit)

/** What keeps the alarm from working, most serious first. Nothing while every alarm is off on purpose. */
private fun alarmFixes(
    context: Context,
    vm: SettingsViewModel,
    settings: AppSettings,
    missed: Int,
    askNotifications: () -> Unit,
    onOpenDiagnostics: (Boolean) -> Unit,
): List<AlarmFix> {
    if (!settings.alarmsEnabled) return emptyList()
    val pkg = Uri.parse("package:${context.packageName}")
    val canPost = Notifications.canPost(context)
    return buildList {
        if (!canPost) add(AlarmFix("알림이 꺼져 있어 잠금 화면에서 알람을 끌 수 없어요", "허용", askNotifications))
        else if (!Notifications.channelOn(context, Notifications.ALARM_CHANNEL_ID)) {
            add(AlarmFix("휴대폰 설정에서 근무 알람 알림이 꺼져 있어요", "켜기") { SystemSettings.channel(context, Notifications.ALARM_CHANNEL_ID) })
        }
        if (canPost && !Notifications.canUseFullScreen(context) && Build.VERSION.SDK_INT >= 34) {
            add(AlarmFix("잠금 화면에 알람 화면이 뜨지 않아요", "허용") {
                SystemSettings.open(context, Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
            })
        }
        if (!vm.canScheduleExact() && Build.VERSION.SDK_INT >= 31) {
            add(AlarmFix("알람이 몇 분 늦게 울릴 수 있어요", "허용") {
                SystemSettings.open(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
            })
        }
        if (Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true) {
            add(AlarmFix("배터리 설정이 밤낮을 막고 있어 알람이 늦을 수 있어요", "풀기") {
                SystemSettings.open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
            })
        }
        if (missed > 0) add(AlarmFix("지난 7일에 울리지 않았을 수 있는 알람이 ${missed}번 있어요", "보기") { onOpenDiagnostics(false) })
    }
}

/** The file pickers and the dialogs around backups and company files, kept while the user moves between groups. */
internal class SettingsFiles(
    val saveBackup: () -> Unit,
    val openFile: () -> Unit,
    val exportIcs: () -> Unit,
    val shareCompany: () -> Unit,
)

@Composable
private fun rememberSettingsFiles(vm: SettingsViewModel): SettingsFiles {
    val context = LocalContext.current
    // Survives the app being closed behind the file picker, unlike the password itself.
    var sealedExport by rememberSaveable { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME_TYPE)) { uri ->
        if (uri != null) vm.exportTo(context, uri, sealedExport)
    }
    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) vm.exportIcs(context, uri)
    }
    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readFile(context, uri)
    }
    var askingPassword by remember { mutableStateOf(false) }
    if (askingPassword) {
        ExportPasswordDialog(
            onDismiss = { askingPassword = false },
            onExport = { password ->
                askingPassword = false
                vm.prepareExport(password)
                sealedExport = password != null
                exportLauncher.launch("밤낮-백업-${LocalDate.now()}.json")
            },
        )
    }
    var sharingCompany by remember { mutableStateOf(false) }
    val profile by vm.hasPayProfile.collectAsStateWithLifecycle()
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    if (sharingCompany) {
        CompanyShareDialog(
            hasPay = profile != null,
            hasRotation = snapshot?.let { PatternTimeline.current(it.patterns, LocalDate.now()) is WorkPattern.Rotation } == true,
            onDismiss = { sharingCompany = false },
            onShare = { name, amounts, team -> sharingCompany = false; vm.shareCompany(context, name, amounts, team) },
        )
    }
    val pendingCompany by vm.pendingCompany.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // [SETUP-02] With the order on, today's row is asked next: the sender's 조 is only where it starts.
    var phases by remember(pendingCompany) { mutableStateOf<com.bamnat.app.ui.company.Phases?>(null) }
    pendingCompany?.let { (text, summary) ->
        val rows = phases
        if (rows == null) {
            CompanyImportDialog(summary, hasPay = profile != null, onDismiss = vm::cancelCompany, onApply = { rotation ->
                if (!rotation) vm.applyCompany(false)
                else scope.launch {
                    val p = vm.phasesFor(text)
                    if (p != null && p.options.size > 1) phases = p else vm.applyCompany(true, if (p != null) 0 else null)
                }
            })
        } else {
            com.bamnat.app.ui.company.PhaseSheet(rows, replacing = rows.hasPattern, busy = false, onDismiss = { phases = null },
                onPick = { step -> vm.applyCompany(true, step) })
        }
    }
    val locked by vm.locked.collectAsStateWithLifecycle()
    locked?.let { UnlockBackupDialog(it, onUnlock = vm::unlock, onDismiss = vm::cancelUnlock) }
    val pendingRestore by vm.pendingRestore.collectAsStateWithLifecycle()
    pendingRestore?.let { (_, summary) ->
        ConfirmDialog(
            title = "백업을 불러올까요?",
            message = restoreMessage(summary),
            confirmLabel = "불러오기",
            destructive = true,
            onDismiss = vm::cancelRestore,
            onConfirm = vm::confirmRestore,
        )
    }
    return remember(exportLauncher, icsLauncher, openLauncher) {
        SettingsFiles(
            saveBackup = { askingPassword = true },
            openFile = { openLauncher.launch(arrayOf(BackupManager.MIME_TYPE, com.bamnat.app.data.CompanyShare.MIME_TYPE, "text/plain", "application/octet-stream")) },
            exportIcs = { icsLauncher.launch("밤낮-근무-${LocalDate.now()}.ics") },
            shareCompany = { sharingCompany = true },
        )
    }
}

/** [SET-01] Everything a restore replaces, and that the data before it is kept. */
internal fun restoreMessage(s: BackupSummary): String {
    val parts = buildList {
        add("근무 종류 ${s.shiftTypes}개")
        add("바꾼 날짜 ${s.days}일")
        add("메모 ${s.memos}개")
        if (s.moneyLines > 0) add("돈 기록 ${s.moneyLines}건")
        if (s.sleeps > 0) add("수면 기록 ${s.sleeps}개")
        if (s.hasPay) add("급여 설정")
        if (s.calendars > 0) add("추가한 달력 ${s.calendars}개")
    }
    val saved = if (s.exportedAtMillis > 0) " (${Fmt.dayTitle(AlarmScheduler.millisToLocal(s.exportedAtMillis).toLocalDate())} 저장)" else ""
    return "들어 있는 것: ${parts.joinToString(", ")}$saved.\n" +
        "지금 앱의 데이터가 이 백업으로 바뀌어요. 바뀌기 전 데이터는 남겨 두어 ${com.bamnat.app.data.ImportUndo.KEEP_DAYS}일 안에 되돌릴 수 있어요."
}
