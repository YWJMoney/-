package com.bamnat.app.data

import com.bamnat.app.pay.PayStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * [MCAL-06] One of the user's calendars as a screen or a link sees it: its own days, money and pay,
 * and the app's settings with that calendar's kind of work. The main calendar is the app as it was.
 */
class CalendarScope(private val book: CalendarBook, private val settingsRepository: SettingsRepository, val id: String) {
    val calendar: PersonalCalendar? get() = book.get(id)
    val isMain: Boolean get() = calendar?.isMain != false
    val schedule: ScheduleRepository get() = book.repository(id)
    val money: MoneyRepository get() = book.money(id)
    val pay: PayStore get() = book.pay(id)

    val settings: Flow<AppSettings> = combine(settingsRepository.settings, book.calendars) { s, list ->
        s.forCalendar(list.firstOrNull { it.id == id })
    }

    fun currentSettings(): AppSettings = settingsRepository.current.forCalendar(calendar)

    companion object {
        /** [MCAL-01] An added calendar answers its own kind of work; everything else in settings is the app's. */
        fun AppSettings.forCalendar(cal: PersonalCalendar?): AppSettings =
            if (cal == null || cal.isMain) this else copy(workStyle = cal.style ?: workStyle, rosterMonthly = false)
    }
}
