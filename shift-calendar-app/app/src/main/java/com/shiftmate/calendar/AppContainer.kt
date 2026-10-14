package com.shiftmate.calendar

import android.content.Context
import com.shiftmate.calendar.alarm.AlarmScheduler
import com.shiftmate.calendar.data.MemoRepository
import com.shiftmate.calendar.data.ScheduleRepository
import com.shiftmate.calendar.data.SettingsRepository
import com.shiftmate.calendar.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
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

    suspend fun rescheduleAlarms(after: LocalDateTime = LocalDateTime.now()) {
        alarmScheduler.reschedule(scheduleRepository.snapshot.first(), after)
    }

    @OptIn(FlowPreview::class)
    fun start() {
        appScope.launch {
            scheduleRepository.ensureDefaults()
            // Any edit to shift types, the pattern or a day re-plans the next alarm.
            scheduleRepository.snapshot
                .debounce(300)
                .collect { alarmScheduler.reschedule(it) }
        }
    }
}
