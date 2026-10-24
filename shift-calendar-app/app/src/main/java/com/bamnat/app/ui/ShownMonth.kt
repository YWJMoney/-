package com.bamnat.app.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.YearMonth
import com.bamnat.core.time.Today

/**
 * [NAV-01] The month on screen, one for the calendar, 돈 and 근무·급여: turning the month in one of
 * them turns it in all, so each opens on the month the user was just looking at.
 */
class ShownMonth(start: YearMonth = Today.month()) {
    private val _month = MutableStateFlow(start)
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    fun show(month: YearMonth) {
        _month.value = month
    }
}
