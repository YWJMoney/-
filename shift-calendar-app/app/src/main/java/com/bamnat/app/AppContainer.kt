package com.bamnat.app

import android.content.Context
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.MemoRepository
import com.bamnat.app.data.MoneyRepository
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.data.ScheduleRepository
import com.bamnat.app.data.SettingsRepository
import com.bamnat.app.data.db.AppDatabase
import com.bamnat.app.diag.EventLog
import com.bamnat.app.money.JobReminderScheduler
import com.bamnat.app.pay.LeaveReminder
import com.bamnat.app.pay.PayStore
import com.bamnat.app.sleep.DayNoticeScheduler
import com.bamnat.app.sleep.QuietHold
import com.bamnat.app.sleep.SleepingNotice
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.diag.Telemetry
import com.bamnat.app.widget.WidgetRefresher
import com.bamnat.core.sleep.SleepDebt
import kotlinx.coroutines.CoroutineScope
import com.bamnat.core.diag.DiagEvents
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime

/** Manual dependency container, created once in [BamnatApp]. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /**
     * Work that must outlive any screen, e.g. saving a memo while leaving the editor. [STAB-01] An error
     * in one job is written to the event log instead of closing the whole app (a full disk, a locked database).
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
        runCatching { telemetry.event(DiagEvents.JOB_FAILED, "scope ${e.javaClass.simpleName}") }
    })

    val database: AppDatabase = AppDatabase.create(context)
    val settingsRepository = SettingsRepository(context)
    val scheduleRepository = ScheduleRepository(database, kinds = settingsRepository.settings.map { it.extraKinds },
        extraName = { settingsRepository.current.extraKinds.name(it) })
    /** [MCAL-01] The user's calendars; the main one is [scheduleRepository]. */
    val calendars = com.bamnat.app.data.CalendarBook(
        java.io.File(appContext.filesDir, "calendars.json"), scheduleRepository, com.bamnat.app.data.CalendarStorage.Files(appContext),
        repositoryOf = { db ->
            ScheduleRepository(db, kinds = settingsRepository.settings.map { it.extraKinds }, extraName = { settingsRepository.current.extraKinds.name(it) })
        },
    )
    /** [MCAL-02] The setup of a calendar being added, kept apart from the app's first setup. */
    val calendarSetupDrafts by lazy { com.bamnat.app.data.SetupDraftStore(java.io.File(appContext.noBackupFilesDir, "calendar_setup_draft.json")) }
    val memoRepository = MemoRepository(database)
    val sleepRepository = com.bamnat.app.data.SleepRepository(database)
    /** [ONBOARD-01] What this phone was told about on the first-start consent pages. */
    val consentStore by lazy { com.bamnat.app.data.ConsentStore(appContext) }
    /** [SLP-08] Sleep measured by a watch or the phone, read from Health Connect. */
    val healthSleep = com.bamnat.app.sleep.HealthSleep(context)
    val moneyRepository = MoneyRepository(database, com.bamnat.app.data.CaptureStore(java.io.File(appContext.noBackupFilesDir, "captures.json")))
    val telemetry = Telemetry(EventLog(context.applicationContext), settingsRepository)
    val alarmScheduler = AlarmScheduler(context.applicationContext, telemetry) {
        com.bamnat.app.alarm.BootAlarms.RingSettings.of(settingsRepository.current)
    }
    val payStore = PayStore(appContext)
    val backupManager = BackupManager(database, settingsRepository, payStore = payStore, calendars = calendars)
    /** [SET-01] The data as it was before the last import, to take the import back. */
    val importUndo by lazy { com.bamnat.app.data.ImportUndo(java.io.File(appContext.noBackupFilesDir, "before_import.json"), backupManager) }
    /** [COMM-00] Company settings as a file for coworkers. */
    val companyShare by lazy { com.bamnat.app.data.CompanyShare(scheduleRepository, payStore, settingsRepository) }
    /** [COMM-04] The company rules server; screenshots and tests put a fake in its place. */
    var companyApi: com.bamnat.app.company.CompanyApi = com.bamnat.app.company.CompanyClient()
    val companyStore by lazy { com.bamnat.app.company.CompanyStore(appContext) }
    /** [ADMIN-02] 설정 > 문의하기 through the company server; screenshots and tests put a fake in its place. */
    var inquiryApi: com.bamnat.app.company.InquiryApi = com.bamnat.app.company.InquiryClient()
    val inquiryStore by lazy { com.bamnat.app.company.InquiryStore(appContext) }
    /** [SETUP-01] The first setup's answers until it saves, on this phone only. */
    val setupDrafts by lazy { com.bamnat.app.data.SetupDraftStore(java.io.File(appContext.noBackupFilesDir, "setup_draft.json")) }
    val firstSetup by lazy {
        com.bamnat.app.data.FirstSetup(scheduleRepository, settingsRepository, setupDrafts, { companyShare }, { companyStore })
    }
    /** [MKT-09][SHR-02] The link server; screenshots and tests put a fake in its place. */
    var shareApi: com.bamnat.app.share.ShareApi = com.bamnat.app.share.ShareLinkClient()
    /** [MKT-09][SHR-12] The one link this phone made, shared by the sheet, the automatic update and the expiry notice. */
    /** [MCAL-02] When this run began: a calendar begun since is being made, not left over. */
    private val startedAt = System.currentTimeMillis()
    val shareLinks by lazy { com.bamnat.app.share.ShareLinkStore(appContext) }
    private val addedShareLinks = java.util.concurrent.ConcurrentHashMap<String, com.bamnat.app.share.ShareLinkStore>()
    /** [MCAL-03] The link of one of the user's calendars; the main one is [shareLinks]. */
    fun shareLinksOf(calendarId: String): com.bamnat.app.share.ShareLinkStore =
        if (calendarId == PersonalCalendar.MAIN) shareLinks
        else addedShareLinks.getOrPut(calendarId) { com.bamnat.app.share.ShareLinkStore(appContext, calendarId) }
    /** [MCAL-03] A removed calendar's link goes with it, its expiry notice too. */
    fun forgetShareLinks(calendarId: String) {
        if (calendarId == PersonalCalendar.MAIN) return
        addedShareLinks.remove(calendarId)?.clear()
        com.bamnat.app.share.ShareLinkStore.forget(appContext, calendarId)
        com.bamnat.app.share.ShareExpiryReminder.reschedule(appContext, calendarId, null, null)
    }
    /** [SHR-12] Sends the link again when the calendar changes. */
    val shareSync by lazy { com.bamnat.app.share.ShareSync(this) }
    /** [SHR-02] Schedules others shared, kept beside the user's own and never backed up. */
    val received by lazy { com.bamnat.app.share.ReceivedCalendarStore(java.io.File(appContext.noBackupFilesDir, "received_calendars.json")) }
    val featureGate = FeatureGate(settingsRepository, appScope)
    /** [IMPORT-01] Samsung Calendar and the phone's other calendars, brought in on request. */
    val calendarImporter by lazy {
        com.bamnat.app.imports.CalendarImporter(appContext, com.bamnat.app.imports.DeviceCalendars(appContext), scheduleRepository,
            memoRepository, database.importedEventDao())
    }
    /** [NAV-01] The month the calendar, 돈 and 근무·급여 show together. */
    val shownMonth = com.bamnat.app.ui.ShownMonth()

    private companion object {
        /** A shorter sleep (a tap and an undo, a doze) gets no "잤어요" line. */
        const val WOKE_SUMMARY_MINUTES = 20L
        const val RETRY_FIRST_MILLIS = 2_000L
        const val RETRY_MAX_MILLIS = 60_000L
    }

    suspend fun rescheduleAlarms(after: LocalDateTime = LocalDateTime.now()) {
        alarmScheduler.reschedule(calendars.ringing.first(), settingsRepository.current.alarmsEnabled, after)
    }

    /**
     * [STAB-01] A job that follows the data for as long as the app runs. When it fails it is logged and
     * started again a little later, so one bad moment does not leave alarms or notices unplanned until
     * the next launch.
     */
    private fun keep(name: String, job: suspend CoroutineScope.() -> Unit) = appScope.launch {
        var failures = 0
        while (isActive) {
            try {
                coroutineScope { job() }
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { telemetry.event(DiagEvents.JOB_FAILED, "$name ${e.javaClass.simpleName}") }
                failures++
                delay(minOf(RETRY_MAX_MILLIS, RETRY_FIRST_MILLIS shl minOf(failures - 1, 5)))
            }
        }
    }

    @OptIn(FlowPreview::class)
    fun start() {
        keep("schedule") {
            scheduleRepository.ensureDefaults()
            // [SETUP-01] A first setup the app was closed in the middle of saving is finished first.
            runCatching { firstSetup.finishInterrupted() }
            memoRepository.purgeOldTrash()
            moneyRepository.purgeCaptures()
            // [MCAL-02] A calendar whose setup was left before saving is not kept.
            if (calendarSetupDrafts.draft.value == null) calendars.dropUnfinished(before = startedAt)
            // Any edit to shift types, the pattern, a day or the master switch re-plans the next alarm.
            // [MCAL-01] So does one in an added calendar whose alarms ring.
            val enabled = settingsRepository.settings.map { it.alarmsEnabled }.distinctUntilChanged()
            combine(calendars.ringing, enabled) { ringing, on -> ringing to on }
                .debounce(300)
                .collect { (ringing, on) ->
                    alarmScheduler.reschedule(ringing, on)
                    WidgetRefresher.refresh(appContext)
                }
        }
        // [WORK-02] Any change to the booked jobs re-plans the next job reminder.
        // [SLP-15] So does the 나갈 돈·받을 돈 switch.
        keep("money") {
            val on = settingsRepository.settings.map { it.moneyNotices }.distinctUntilChanged()
            combine(moneyRepository.book, on) { book, _ -> book }.debounce(500)
                .collect { JobReminderScheduler.reschedule(appContext, it, settingsRepository.current) }
        }
        // [SCHED-01] A memo saved, moved to the trash, restored or brought back from a backup re-plans the next schedule.
        appScope.launch {
            memoRepository.memos.map { list -> list.filter { it.reminder != null } }
                .distinctUntilChanged { a, b -> a.map { it.id to it.reminder } == b.map { it.id to it.reminder } }
                .debounce(300)
                .collect { com.bamnat.app.memo.MemoScheduleAlarm.reschedule(appContext, it) }
        }
        // [SLP-15] 하루 브리핑, 퇴근길 and 잘 준비 follow the schedule, the alarms and their settings.
        keep("notices") {
            val reads = settingsRepository.settings.map {
                listOf(it.briefing, it.commuteNotice, it.bedtimeNotice, it.sleepTargetMinutes, it.commuteMinutes, it.alarmsEnabled, it.workStyle)
            }.distinctUntilChanged()
            combine(scheduleRepository.snapshot, reads) { snapshot, _ -> snapshot }
                .debounce(500)
                .collect { DayNoticeScheduler.reschedule(appContext, it, settingsRepository.current) }
        }
        // [PAY-12][REL-08] 연차 notices follow whether the user entered their days, and their switch.
        keep("leave") {
            val entered = payStore.profile.map { it?.rules?.annualLeaveDays != null }.distinctUntilChanged()
            val on = settingsRepository.settings.map { it.leaveNotice }.distinctUntilChanged()
            combine(entered, on) { a, b -> a to b }
                .collect { LeaveReminder.reschedule(appContext, payStore.profile.value, settingsRepository.current) }
        }
        // [SHR-12] The link this phone made follows the calendar, and its expiry notice follows the link.
        keep("share") { if (shareApi.enabled) shareSync.follow() }
        // [MCAL-03] Each of the user's calendars has its own link and so its own notice.
        keep("shareexpiry") {
            calendars.calendars.map { list -> list.map { it.id } }.distinctUntilChanged().collectLatest { ids ->
                coroutineScope {
                    ids.forEach { id ->
                        launch {
                            val store = shareLinksOf(id)
                            store.link.collect { com.bamnat.app.share.ShareExpiryReminder.reschedule(appContext, id, it, store.noticedFor) }
                        }
                    }
                }
            }
        }
        // [SLP-15] 자는 중 follows the sleep log, and a sleep that just ended says how long it was.
        // What waited while the user slept is planned again from the log.
        keep("sleeping") {
            var openId: Long? = null
            val on = settingsRepository.settings.map { it.sleepingNotice }.distinctUntilChanged()
            combine(sleepRepository.recent(days = 2), on, alarmScheduler.upcoming) { log, show, next -> Triple(log, show, next) }
                .collect { (log, show, next) ->
                    val now = LocalDateTime.now()
                    val open = log.firstOrNull {
                        it.end == null && !it.imported && Duration.between(it.start, now).toMinutes() < SleepDebt.MAX_SLEEP_MINUTES
                    }
                    when {
                        open != null && show ->
                            SleepingNotice.show(appContext, open, next?.let { AlarmScheduler.millisToLocal(it.triggerAtMillis) }, now)
                        openId != null -> {
                            // The sleep shown a moment ago ended (or was deleted).
                            val ended = log.firstOrNull { it.id == openId && it.end != null }
                            if (show && ended != null && (ended.minutes ?: 0) >= WOKE_SUMMARY_MINUTES) {
                                SleepingNotice.woke(appContext, ended, SleepingNotice.nextWork(scheduleRepository.snapshot.first().resolver, now))
                            } else {
                                SleepingNotice.cancel(appContext)
                            }
                        }
                        !show -> SleepingNotice.cancel(appContext)
                    }
                    openId = open?.id
                    QuietHold.release(appContext)
                }
        }
        // [REL-10] The locked-boot copy carries the ringing settings too, so it follows their changes.
        keep("bootcopy") {
            settingsRepository.settings.map { com.bamnat.app.alarm.BootAlarms.RingSettings.of(it) }.distinctUntilChanged().drop(1)
                .collect { alarmScheduler.saveBootCopy() }
        }
        // Widgets lay out weeks from the user's first day of the week.
        keep("widgetweek") {
            settingsRepository.settings.map { it.weekStart }.distinctUntilChanged().drop(1)
                .collect { WidgetRefresher.refresh(appContext) }
        }
        // [SLP-14] The today widget's line of 오늘의 흐름 follows the sleep log and the settings it reads.
        keep("widgetflow") {
            val reads = settingsRepository.settings.map {
                listOf(it.sleepTips, it.sleepTargetMinutes, it.commuteMinutes, it.restCheck, it.strictRest, it.workStyle)
            }.distinctUntilChanged()
            combine(sleepRepository.recent(days = 2), reads) { log, r -> log to r }
                .drop(1).debounce(500)
                .collect { WidgetRefresher.refresh(appContext) }
        }
    }
}
