package com.bamnat.app.ui.shifts

import com.bamnat.core.model.ShiftIcon
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.core.model.ShiftType
import com.bamnat.core.schedule.ShiftTypeSet
import com.bamnat.core.schedule.ShiftTypeSets
import com.bamnat.core.model.WorkPattern
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

    /** Adds the set's shift types the user does not have yet [MKT-03]. */
    fun addSet(set: ShiftTypeSet) {
        viewModelScope.launch {
            val existing = shiftTypes.value ?: return@launch
            // Sets come with a fitting icon [FEAT-01]; the user can change or remove it.
            ShiftTypeSets.missing(set, existing).forEach { repo.saveShiftType(it.copy(icon = it.icon ?: ShiftIcon.suggest(it)?.key)) }
            container.telemetry.event("shift_set_added", set.name)
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.deleteShiftType(id) }
    }

    fun move(id: Long, delta: Int) {
        viewModelScope.launch { repo.moveShiftType(id, delta) }
    }

    /** Every pattern period, earliest first, for the history list. */
    val history: StateFlow<List<WorkPattern>> =
        repo.patterns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** First pattern: also fills the last two years. */
    fun saveFirstPattern(pattern: WorkPattern, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.setFirstPattern(pattern)
            onDone()
        }
    }

    /** Team change: the new pattern from [from] on, every earlier day as it was. */
    fun changePatternFrom(pattern: WorkPattern, from: LocalDate, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.changePatternFrom(pattern, from)
            onDone()
        }
    }

    /** Corrects the period being worked now without starting a new one. */
    fun fixCurrentPattern(pattern: WorkPattern, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.replaceCurrentPattern(pattern)
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
        // A rotation is edited by its step-0 day; its period may reach back years.
        startDate = (existing as? WorkPattern.Rotation)?.anchorDate ?: existing?.startDate ?: today
        endDate = existing?.endDate
        initialized = true
    }
}
