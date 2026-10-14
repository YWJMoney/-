package com.shiftmate.calendar.ui.calendar

import com.shiftmate.core.model.DayAlarm
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiftmate.calendar.AppContainer
import com.shiftmate.calendar.alarm.UpcomingAlarm
import com.shiftmate.calendar.data.AppSettings
import com.shiftmate.calendar.data.ScheduleSnapshot
import com.shiftmate.calendar.ui.UndoMessage
import com.shiftmate.core.memo.Memo
import com.shiftmate.core.model.ShiftOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.shiftmate.core.model.ExtraWork
import com.shiftmate.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.LocalTime

/** Quick-input tool: tap days to stamp a shift on them. */
sealed interface PaintTool {
    data class Shift(val shiftTypeId: Long) : PaintTool
    /** A day off when the user has no off-type shift. */
    data object Off : PaintTool
    /** Back to the pattern. */
    data object Clear : PaintTool
}

class CalendarViewModel(private val container: AppContainer) : ViewModel() {
    private val schedule = container.scheduleRepository
    private val memoRepo = container.memoRepository

    val snapshot: StateFlow<ScheduleSnapshot?> =
        schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val memoDates: StateFlow<Set<LocalDate>> =
        memoRepo.datesWithMemos.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val upcomingAlarm: StateFlow<UpcomingAlarm?> = container.alarmScheduler.upcoming

    private val _selected = MutableStateFlow(LocalDate.now())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    private val _paint = MutableStateFlow<PaintTool?>(null)
    val paint: StateFlow<PaintTool?> = _paint.asStateFlow()

    private val _messages = MutableSharedFlow<UndoMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UndoMessage> = _messages.asSharedFlow()

    fun memosOn(date: LocalDate): Flow<List<Memo>> = memoRepo.memosOn(date)

    fun select(date: LocalDate) {
        _selected.value = date
    }

    fun setPaint(tool: PaintTool?) {
        _paint.value = tool
    }

    /** A tap on a calendar cell: selects it, and stamps it when quick input is on. */
    fun onDayTapped(date: LocalDate) {
        _selected.value = date
        val tool = _paint.value ?: return
        val target = when (tool) {
            is PaintTool.Shift -> ShiftOverride.Shift(tool.shiftTypeId)
            PaintTool.Off -> ShiftOverride.Off
            PaintTool.Clear -> ShiftOverride.None
        }
        // Stamping the same shift twice undoes it, so a mis-tap is fixed with one more tap.
        // Decided inside the write so quick double taps see each other's result.
        changeShift(date) { current -> if (current == target) ShiftOverride.None else target }
    }

    /** Sets the shift for a day. Choosing what the pattern already gives stores nothing. */
    fun assign(date: LocalDate, override: ShiftOverride) = changeShift(date) { override }

    private fun changeShift(date: LocalDate, choose: (ShiftOverride) -> ShiftOverride) {
        val pattern = snapshot.value?.resolver?.patternOn(date)
        viewModelScope.launch {
            schedule.updateDay(date) { entry ->
                val patternId = pattern?.shiftTypeIdOn(date)
                val patternCovers = pattern != null
                val next = when (val o = choose(entry.override)) {
                    is ShiftOverride.Shift -> if (patternCovers && patternId == o.shiftTypeId) ShiftOverride.None else o
                    ShiftOverride.Off -> if (patternCovers && patternId == null) ShiftOverride.None else o
                    ShiftOverride.None -> o
                }
                if (next == entry.override) {
                    entry
                } else {
                    // Times set for the old shift do not carry over to a different one.
                    entry.copy(override = next, customStart = null, customEnd = null, customBreakMinutes = null)
                }
            }
        }
    }

    fun setCustomTimes(date: LocalDate, start: LocalTime, end: LocalTime) {
        viewModelScope.launch {
            schedule.updateDay(date) { it.copy(customStart = start, customEnd = end) }
        }
    }

    fun setBreak(date: LocalDate, minutes: Int?) {
        viewModelScope.launch { schedule.updateDay(date) { it.copy(customBreakMinutes = minutes) } }
    }

    fun clearCustomTimes(date: LocalDate) {
        viewModelScope.launch {
            schedule.setCustomTimes(date, null, null, null)
        }
    }

    fun setOvertime(date: LocalDate, minutes: Int) {
        viewModelScope.launch { schedule.setOvertime(date, minutes) }
    }

    fun setExtraWork(day: ResolvedDay, kind: ExtraWork?, minutes: Int) {
        viewModelScope.launch { schedule.setExtraWork(day.date, kind, minutes.coerceIn(0, 24 * 60), day.isWorkDay) }
    }

    fun setHolidayWork(date: LocalDate, minutes: Int) {
        viewModelScope.launch { schedule.setHolidayWork(date, minutes) }
    }

    fun setAlarmSkipped(date: LocalDate, skipped: Boolean) {
        viewModelScope.launch { schedule.setAlarmSkipped(date, skipped) }
    }

    fun resetDay(date: LocalDate) {
        viewModelScope.launch {
            val before = schedule.resetDay(date)
            if (before != null && !before.isEmpty) {
                _messages.emit(UndoMessage("${date.monthValue}월 ${date.dayOfMonth}일을 패턴대로 되돌렸어요") {
                    schedule.restoreDay(date, before)
                })
            }
        }
    }

    fun setDayAlarms(date: LocalDate, alarms: List<DayAlarm>?) {
        viewModelScope.launch { schedule.setDayAlarms(date, alarms) }
    }

    fun setAlarmsEnabled(on: Boolean) = container.settingsRepository.update { it.copy(alarmsEnabled = on) }

    fun hideDayAlarmNotice() = container.settingsRepository.update { it.copy(hideDayAlarmNotice = true) }

    fun deleteMemo(memo: Memo) {
        viewModelScope.launch { memoRepo.delete(memo) }
    }

    fun addQuickMemo(date: LocalDate, text: String) {
        viewModelScope.launch { memoRepo.addQuick(date, text) }
    }

    fun runUndo(message: UndoMessage) {
        val undo = message.undo ?: return
        container.appScope.launch { undo() }
    }
}
