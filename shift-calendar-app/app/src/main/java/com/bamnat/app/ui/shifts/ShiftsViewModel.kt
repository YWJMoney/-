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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/**
 * Shared by the shift type list and the pattern editor. [MCAL-01] Both edit one calendar: the one the
 * calendar tab showed when they were opened from it, the main one from settings.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ShiftsViewModel(
    private val container: AppContainer,
    val calendarId: String = com.bamnat.app.data.PersonalCalendar.MAIN,
) : ViewModel() {
    private val repo: com.bamnat.app.data.ScheduleRepository get() = container.calendars.repository(calendarId)
    // Taken out meanwhile: the main calendar's, as [repository] answers.
    private val active = container.calendars.calendars.map { container.calendars.repository(calendarId) }.distinctUntilChanged()

    /** [MCAL-01] The added calendar being edited, for the screens' titles; null for the main one. */
    val calendarName: StateFlow<String?> = container.calendars.calendars.map { list -> list.firstOrNull { it.id == calendarId && !it.isMain }?.name }
        .stateIn(viewModelScope, SharingStarted.Eagerly, container.calendars.get(calendarId)?.takeIf { !it.isMain }?.name)

    val shiftTypes: StateFlow<List<ShiftType>?> =
        active.flatMapLatest { it.shiftTypes }.map<List<ShiftType>, List<ShiftType>?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until loaded; [PatternState.pattern] is null when there is no pattern. */
    val pattern: StateFlow<PatternState?> =
        active.flatMapLatest { it.pattern }.map { PatternState(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> = container.settingsRepository.settings

    /** [SHFT-01] A shift calendar (or the main one from before work styles), where each kind can carry its own premium. */
    val isShiftCalendar: Boolean
        get() = container.scopeOf(calendarId).currentSettings().workStyle.let { it == null || it == com.bamnat.core.work.WorkStyle.SHIFT }

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
        active.flatMapLatest { it.patterns }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** First pattern: also fills the last two years. */
    fun saveFirstPattern(pattern: WorkPattern, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.setFirstPattern(pattern)
            container.settingsRepository.update { if (it.reviewAsked) it else it.copy(reviewDue = true) }
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
    fun initFrom(existing: WorkPattern?, today: LocalDate, weekdayShiftId: Long? = null) {
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
            // [WORK-01] Day workers start from Monday to Friday on their day shift.
            null -> if (weekdayShiftId != null) {
                kind = PatternKind.WEEKLY
                for (i in 0 until 5) weekly[i] = weekdayShiftId
            }
        }
        // A rotation is edited by its step-0 day; its period may reach back years.
        startDate = (existing as? WorkPattern.Rotation)?.anchorDate ?: existing?.startDate ?: today
        endDate = existing?.endDate
        initialized = true
    }
}
