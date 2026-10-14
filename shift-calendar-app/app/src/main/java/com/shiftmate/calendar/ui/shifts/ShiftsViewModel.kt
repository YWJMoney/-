package com.shiftmate.calendar.ui.shifts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiftmate.calendar.AppContainer
import com.shiftmate.calendar.data.AppSettings
import com.shiftmate.core.model.ShiftType
import com.shiftmate.core.model.WorkPattern
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/** Shared by the shift type list and the pattern editor. */
class ShiftsViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.scheduleRepository

    val shiftTypes: StateFlow<List<ShiftType>?> =
        repo.shiftTypes.map<List<ShiftType>, List<ShiftType>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until loaded; [PatternState.pattern] is null when there is no pattern. */
    val pattern: StateFlow<PatternState?> =
        repo.pattern.map { PatternState(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    /** Pattern being edited; survives navigating to the shift type screen and back. */
    val draft = PatternDraft()

    fun save(type: ShiftType) {
        viewModelScope.launch { repo.saveShiftType(type) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteShiftType(id) }
    }

    fun move(id: Long, delta: Int) {
        viewModelScope.launch { repo.moveShiftType(id, delta) }
    }

    fun savePattern(pattern: WorkPattern, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.savePattern(pattern)
            onDone()
        }
    }

    fun clearPattern(onDone: () -> Unit) {
        viewModelScope.launch {
            repo.clearPattern()
            onDone()
        }
    }
}

data class PatternState(val pattern: WorkPattern?)

enum class PatternKind { ROTATION, WEEKLY }

class PatternDraft {
    var initialized by mutableStateOf(false)
        private set
    var kind by mutableStateOf(PatternKind.ROTATION)
    val rotation = mutableStateListOf<Long?>()
    val weekly = mutableStateListOf<Long?>().apply { repeat(7) { add(null) } }
    var startDate: LocalDate by mutableStateOf(LocalDate.now())
    var endDate: LocalDate? by mutableStateOf(null)

    /** Loads the saved pattern once; later calls keep the user's unsaved edits. */
    fun initFrom(existing: WorkPattern?, today: LocalDate) {
        if (initialized) return
        when (existing) {
            is WorkPattern.Rotation -> {
                kind = PatternKind.ROTATION
                rotation.addAll(existing.steps)
            }
            is WorkPattern.Weekly -> {
                kind = PatternKind.WEEKLY
                existing.steps.forEachIndexed { i, id -> weekly[i] = id }
            }
            null -> Unit
        }
        startDate = existing?.startDate ?: today
        endDate = existing?.endDate
        initialized = true
    }
}
