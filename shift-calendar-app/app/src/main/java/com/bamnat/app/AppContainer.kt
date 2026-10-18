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
import com.bamnat.app.pay.PayStore
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.diag.Telemetry
import com.bamnat.app.widget.WidgetRefresher
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
    /** [COMM-00] Company settings as a file for coworkers. */
    val companyShare by lazy { com.bamnat.app.data.CompanyShare(scheduleRepository, payStore, settingsRepository) }
    /** [COMM-04] The company rules server; screenshots and tests put a fake in its place. */
    var companyApi: com.bamnat.app.company.CompanyApi = com.bamnat.app.company.CompanyClient()
    val companyStore by lazy { com.bamnat.app.company.CompanyStore(appContext) }
    /** [MKT-09][SHR-02] The link server; screenshots and tests put a fake in its place. */
    var shareApi: com.bamnat.app.share.ShareApi = com.bamnat.app.share.ShareLinkClient()
    /** [SHR-02] Schedules others shared, kept beside the user's own and never backed up. */
    val received by lazy { com.bamnat.app.share.ReceivedCalendarStore(java.io.File(appContext.noBackupFilesDir, "received_calendars.json")) }
    val featureGate = FeatureGate(settingsRepository, appScope)

    suspend fun rescheduleAlarms(after: LocalDateTime = LocalDateTime.now()) {
        alarmScheduler.reschedule(scheduleRepository.snapshot.first(), settingsRepository.current.alarmsEnabled, after)
    }

    @OptIn(FlowPreview::class)
    fun start() {
        appScope.launch {
            scheduleRepository.ensureDefaults()
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
        appScope.launch {
            moneyRepository.book.debounce(500).collect { JobReminderScheduler.reschedule(appContext, it) }
        }
        // [SLP-04][SLP-07] Sleep reminders follow the schedule and their settings.
        appScope.launch {
            val sleep = settingsRepository.settings.map { it.sleepReminders to it.sleepTargetMinutes }.distinctUntilChanged()
            combine(scheduleRepository.snapshot, sleep) { snapshot, _ -> snapshot }
                .debounce(500)
                .collect { com.bamnat.app.sleep.SleepReminder.reschedule(appContext, it, settingsRepository.current) }
        }
        // [PAY-12][REL-08] 연차 notices follow whether the user entered their days.
        appScope.launch {
            payStore.profile.map { it?.rules?.annualLeaveDays != null }.distinctUntilChanged()
                .collect { com.bamnat.app.pay.LeaveReminder.reschedule(appContext, payStore.profile.value) }
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
