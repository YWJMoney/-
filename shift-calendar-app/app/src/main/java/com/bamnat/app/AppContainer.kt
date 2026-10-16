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
    val scheduleRepository = ScheduleRepository(database)
    val memoRepository = MemoRepository(database)
    val moneyRepository = MoneyRepository(database, com.bamnat.app.data.CaptureStore(java.io.File(appContext.noBackupFilesDir, "captures.json")))
    val settingsRepository = SettingsRepository(context)
    val telemetry = Telemetry(EventLog(context.applicationContext), settingsRepository)
    val alarmScheduler = AlarmScheduler(context.applicationContext, telemetry)
    val backupManager = BackupManager(database, settingsRepository)
    val payStore = PayStore(appContext)
    val featureGate = FeatureGate(settingsRepository, appScope)

    suspend fun rescheduleAlarms(after: LocalDateTime = LocalDateTime.now()) {
        alarmScheduler.reschedule(scheduleRepository.snapshot.first(), settingsRepository.current.alarmsEnabled, after)
    }

    @OptIn(FlowPreview::class)
    fun start() {
        appScope.launch {
            scheduleRepository.ensureDefaults()
            memoRepository.purgeOldTrash()
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
        // Widgets lay out weeks from the user's first day of the week.
        appScope.launch {
            settingsRepository.settings.map { it.weekStart }.distinctUntilChanged().drop(1)
                .collect { WidgetRefresher.refresh(appContext) }
        }
    }
}
