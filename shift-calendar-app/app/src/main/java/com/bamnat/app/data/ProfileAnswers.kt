package com.bamnat.app.data

import com.bamnat.app.AppContainer
import com.bamnat.core.model.AlarmSpec
import com.bamnat.core.pay.HolidayPayMode
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.Unknowns
import com.bamnat.core.pay.WageType
import com.bamnat.core.profile.AskScheduler
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.Questions
import com.bamnat.core.profile.Source
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import com.bamnat.core.time.Today

/**
 * [EASY-05] An answer on the question card, written where that fact has always been kept (the 정본 of
 * research/1.23-N-설계.md 1장) and then marked as typed in the profile. Only the facts a card answers in one
 * tap, one number or one date come here; the rest open the screen that already asks them.
 */
class ProfileAnswers(private val c: AppContainer, private val today: () -> LocalDate = Today::date) {

    /** Writes [value] (a chip's value, a number or an ISO date) for [key] of [calendarId]; false if it could not. */
    suspend fun answer(calendarId: String, key: FactKey, value: String): Boolean {
        val asks = c.asks
        if (value == Questions.UNKNOWN) {
            asks.set(calendarId, AskScheduler.unknown(asks.of(calendarId), key))
            return true
        }
        val written = runCatching { write(c.scopeOf(calendarId), key, value) }.getOrDefault(false)
        if (!written) return false
        c.profiles.mark(calendarId, key, Source.TYPED, why = "질문 카드")
        asks.set(calendarId, AskScheduler.answered(asks.of(calendarId), key))
        return true
    }

    private suspend fun write(scope: CalendarScope, key: FactKey, value: String): Boolean {
        val prefs = WorkTypePrefs.get(c.appContext)
        suspend fun pay(change: (PayProfile) -> PayProfile): Boolean {
            if (scope.pay.profile.value == null) return false
            scope.pay.update(change)
            return true
        }
        return when (key) {
            FactKey.OVERTIME_CAP -> true.also { prefs.setOvertimeCap(scope.id, value.toInt()) }
            FactKey.TAX_MODE -> {
                val mode = TaxMode.valueOf(value)
                prefs.setTaxMode(scope.id, mode)
                if (scope.pay.profile.value != null) scope.pay.update { it.copy(taxMode = mode) }
                true
            }
            FactKey.ALARM_LEAD -> {
                val minutes = value.toInt()
                val work = scope.schedule.shiftTypes.first().filter { it.hasTimes && !it.isOff }
                work.forEach { t -> scope.schedule.saveShiftType(t.copy(alarmEnabled = true, alarms = listOf(AlarmSpec(minutes)))) }
                work.isNotEmpty()
            }
            FactKey.SLEEP_TARGET -> true.also { c.settingsRepository.update { it.copy(sleepTargetMinutes = value.toInt()) } }
            FactKey.COMMUTE -> true.also { c.settingsRepository.update { it.copy(commuteMinutes = value.toInt()) } }
            FactKey.WAGE_TYPE -> pay { it.copy(wageType = WageType.valueOf(value)) }
            FactKey.PAY_DAY -> pay { it.copy(period = it.period.copy(payDay = value.toInt().coerceIn(1, 31))) }
            FactKey.MONTHLY_HOURS -> pay { it.copy(monthlyHours = value.toInt(), estimatedFields = it.estimatedFields - Unknowns.MONTHLY_HOURS) }
            FactKey.DAILY_HOURS -> pay { it.copy(dailyHours = value.toInt() * 60) }
            FactKey.HOLIDAY_PAY -> pay { it.copy(holidayPayMode = HolidayPayMode.valueOf(value), estimatedFields = it.estimatedFields - Unknowns.HOLIDAY_PAY_MODE) }
            FactKey.FIVE_OR_MORE -> pay { it.copy(fiveOrMoreEmployees = value.toBoolean(), estimatedFields = it.estimatedFields - Unknowns.FIVE_OR_MORE) }
            FactKey.HIRE_DATE -> pay { it.copy(hireDate = LocalDate.parse(value).takeIf { d -> !d.isAfter(today()) } ?: return@pay it) }
            FactKey.TAX_FAMILY -> pay { it.copy(taxFamily = value.toInt().coerceIn(1, 11)) }
            else -> false
        }
    }
}
