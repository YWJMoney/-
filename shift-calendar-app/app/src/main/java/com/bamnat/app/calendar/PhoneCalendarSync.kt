package com.bamnat.app.calendar

import com.bamnat.app.AppContainer
import com.bamnat.app.tier.Feature
import com.bamnat.core.schedule.IcsWriter
import com.bamnat.core.schedule.ShiftEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import com.bamnat.core.time.Today

/**
 * [FEAT-07] Keeps the phone's 밤낮 calendars in step with the calendars the user writes there
 * ([com.bamnat.app.data.AppSettings.phoneCalendars]): a change goes in a few seconds later, three months
 * back to a year ahead, as the .ics export covers. Without 프로 or once a calendar is switched off, its
 * phone calendar is deleted rather than left behind out of date.
 */
class PhoneCalendarSync(private val c: AppContainer, private val today: () -> LocalDate = Today::date) {
    private val lock = Mutex()
    private val phone by lazy { PhoneCalendar(c.appContext) }

    sealed interface Status {
        data object Off : Status
        /** On, but the phone's calendar permission is not given (a restored backup, or taken back). */
        data object NeedsPermission : Status
        data class Written(val at: Instant, val events: Int) : Status
        data class Failed(val reason: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    suspend fun follow() {
        val chosen = c.settingsRepository.settings.map { it.phoneCalendars }.distinctUntilChanged()
        val open = c.featureGate.state.map { it.allows(Feature.DEVICE_CALENDAR) }.distinctUntilChanged()
        val days: Flow<Any?> = combine(chosen, c.calendars.calendars) { ids, list -> list.filter { it.id in ids } }.flatMapLatest { list ->
            if (list.isEmpty()) flowOf(Unit) else combine(list.map { c.calendars.repository(it.id).snapshot }) { Unit }
        }
        combine(chosen, open, days, c.calendars.calendars) { _, _, _, _ -> Unit }.debounce(DEBOUNCE_MILLIS).collect { sync() }
    }

    /** Writes the chosen calendars now and deletes the rest; what [status] then says. */
    suspend fun sync(): Status = lock.withLock {
        withContext(Dispatchers.IO) {
            val ids = c.settingsRepository.current.phoneCalendars.toSet()
            val open = c.featureGate.allows(Feature.DEVICE_CALENDAR)
            val wanted = if (open) c.calendars.calendars.value.filterNot { it.isExample }.filter { it.id in ids } else emptyList()
            val status = runCatching {
                if (!phone.allowed()) return@runCatching if (ids.isEmpty()) Status.Off else Status.NeedsPermission
                (phone.written() - wanted.map { it.id }.toSet()).forEach { phone.remove(it) }
                if (wanted.isEmpty()) return@runCatching Status.Off
                val (from, to) = IcsWriter.defaultRange(today())
                var events = 0
                for (cal in wanted) {
                    val snapshot = c.calendars.repository(cal.id).snapshot.first()
                    val list = ShiftEvents.of(snapshot.resolver.resolveRange(from, to))
                    phone.write(cal.id, if (cal.isMain) "밤낮 근무" else "밤낮 · ${cal.name}", cal.color.toInt(), list)
                    events += list.size
                }
                Status.Written(Today.instant(), events)
            }.getOrElse { Status.Failed(it.javaClass.simpleName) }
            if (status is Status.Written) c.telemetry.event("phone_calendar", "events=${status.events}")
            _status.value = status
            status
        }
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 3_000L
    }
}
