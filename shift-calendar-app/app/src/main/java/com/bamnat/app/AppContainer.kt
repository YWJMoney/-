package com.bamnat.app

import android.content.Context
import com.bamnat.app.alarm.AlarmScheduler
import com.bamnat.app.data.BackupManager
import com.bamnat.app.data.MemoRepository
import com.bamnat.app.data.MoneyRepository
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
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
    private val appContext: Context = context.applicationContext

    /** Work that must outlive any screen, e.g. saving a memo while leaving the editor. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.create(context)
    val settingsRepository = SettingsRepository(context)
    val scheduleRepository = ScheduleRepository(database, kinds = settingsRepository.settings.map { it.extraKinds },
        extraName = { settingsRepository.current.extraKinds.name(it) })
    val memoRepository = MemoRepository(database)
    val sleepRepository = com.bamnat.app.data.SleepRepository(database)
    /** [SLP-08] Sleep measured by a watch or the phone, read from Health Connect. */
    val healthSleep = com.bamnat.app.sleep.HealthSleep(context)
    val moneyRepository = MoneyRepository(database, com.bamnat.app.data.CaptureStore(java.io.File(appContext.noBackupFilesDir, "captures.json")))
    val telemetry = Telemetry(EventLog(context.applicationContext), settingsRepository)
    val alarmScheduler = AlarmScheduler(context.applicationContext, telemetry) {
        com.bamnat.app.alarm.BootAlarms.RingSettings.of(settingsRepository.current)
    }
    val payStore = PayStore(appContext)
    val backupManager = BackupManager(database, settingsRepository, payStore = payStore)
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
    /** [SHR-02] Schedules others shared, kept beside the user's own and never backed up. */
    val received by lazy { com.bamnat.app.share.ReceivedCalendarStore(java.io.File(appContext.noBackupFilesDir, "received_calendars.json")) }
    val featureGate = FeatureGate(settingsRepository, appScope)
    /** [NAV-01] The month the calendar, 돈 and 근무·급여 show together. */
    val shownMonth = com.bamnat.app.ui.ShownMonth()

    private companion object {
        /** A shorter sleep (a tap and an undo, a doze) gets no "잤어요" line. */
        const val WOKE_SUMMARY_MINUTES = 20L
    }

    suspend fun rescheduleAlarms(after: LocalDateTime = LocalDateTime.now()) {
        alarmScheduler.reschedule(scheduleRepository.snapshot.first(), settingsRepository.current.alarmsEnabled, after)
    }

    @OptIn(FlowPreview::class)
    fun start() {
        appScope.launch {
            scheduleRepository.ensureDefaults()
            // [SETUP-01] A first setup the app was closed in the middle of saving is finished first.
            runCatching { firstSetup.finishInterrupted() }
            memoRepository.purgeOldTrash()
            moneyRepository.purgeCaptures()
            // Any edit to shift types, the pattern, a day or the master switch re-plans the next alarm.
            val enabled = settingsRepository.settings.map { it.alarmsEnabled }.distinctUntilChanged()
            combine(scheduleRepository.snapshot, enabled) { snapshot, on -> snapshot to on }
                .debounce(300)
                .collect { (snapshot, on) ->
                    alarmScheduler.reschedule(snapshot, on)
                    WidgetRefresher.refresh(appContext)
                }
        }
        // [WORK-02] Any change to the booked jobs re-plans the next job reminder.
        // [SLP-15] So does the 나갈 돈·받을 돈 switch.
        appScope.launch {
            val on = settingsRepository.settings.map { it.moneyNotices }.distinctUntilChanged()
            combine(moneyRepository.book, on) { book, _ -> book }.debounce(500)
                .collect { JobReminderScheduler.reschedule(appContext, it, settingsRepository.current) }
        }
        // [SLP-15] 하루 브리핑, 퇴근길 and 잘 준비 follow the schedule, the alarms and their settings.
        appScope.launch {
            val reads = settingsRepository.settings.map {
                listOf(it.briefing, it.commuteNotice, it.bedtimeNotice, it.sleepTargetMinutes, it.commuteMinutes, it.alarmsEnabled, it.workStyle)
            }.distinctUntilChanged()
            combine(scheduleRepository.snapshot, reads) { snapshot, _ -> snapshot }
                .debounce(500)
                .collect { DayNoticeScheduler.reschedule(appContext, it, settingsRepository.current) }
        }
        // [PAY-12][REL-08] 연차 notices follow whether the user entered their days, and their switch.
        appScope.launch {
            val entered = payStore.profile.map { it?.rules?.annualLeaveDays != null }.distinctUntilChanged()
            val on = settingsRepository.settings.map { it.leaveNotice }.distinctUntilChanged()
            combine(entered, on) { a, b -> a to b }
                .collect { LeaveReminder.reschedule(appContext, payStore.profile.value, settingsRepository.current) }
        }
        // [SLP-15] 자는 중 follows the sleep log, and a sleep that just ended says how long it was.
        // What waited while the user slept is planned again from the log.
        appScope.launch {
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
        appScope.launch {
            settingsRepository.settings.map { com.bamnat.app.alarm.BootAlarms.RingSettings.of(it) }.distinctUntilChanged().drop(1)
                .collect { alarmScheduler.saveBootCopy() }
        }
        // Widgets lay out weeks from the user's first day of the week.
        appScope.launch {
            settingsRepository.settings.map { it.weekStart }.distinctUntilChanged().drop(1)
                .collect { WidgetRefresher.refresh(appContext) }
        }
        // [SLP-14] The today widget's line of 오늘의 흐름 follows the sleep log and the settings it reads.
        appScope.launch {
            val reads = settingsRepository.settings.map {
                listOf(it.sleepTips, it.sleepTargetMinutes, it.commuteMinutes, it.restCheck, it.strictRest, it.workStyle)
            }.distinctUntilChanged()
            combine(sleepRepository.recent(days = 2), reads) { log, r -> log to r }
                .drop(1).debounce(500)
                .collect { WidgetRefresher.refresh(appContext) }
        }
    }
}
