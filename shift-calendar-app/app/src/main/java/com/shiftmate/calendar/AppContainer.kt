package com.shiftmate.calendar

import android.content.Context
import com.shiftmate.calendar.alarm.AlarmScheduler
import com.shiftmate.calendar.data.BackupManager
import com.shiftmate.calendar.data.MemoRepository
import com.shiftmate.calendar.data.ScheduleRepository
import com.shiftmate.calendar.data.SettingsRepository
import com.shiftmate.calendar.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/** Manual dependency container, created once in [ShiftMateApp]. */
class AppContainer(context: Context) {
    /** Work that must outlive any screen, e.g. saving a memo while leaving the editor. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.create(context)
    val scheduleRepository = ScheduleRepository(database)
    val memoRepository = MemoRepository(database)
    val settingsRepository = SettingsRepository(context)
    val alarmScheduler = AlarmScheduler(context.applicationContext)
    val backupManager = BackupManager(database, settingsRepository)

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
                .collect { (snapshot, on) -> alarmScheduler.reschedule(snapshot, on) }
        }
    }
}
