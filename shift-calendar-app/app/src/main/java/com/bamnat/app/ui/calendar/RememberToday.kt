package com.bamnat.app.ui.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import com.bamnat.core.time.Today

/**
 * [CAL-25] Today's date that moves on at midnight while a screen stays open (I 리뷰 P2~P4·I1). A night
 * worker opens the app at 23:00 and still has it open at 01:00: every "오늘" mark, "올해까지" count and
 * rotation must be about the new day. The one place screens get today from, instead of each holding a
 * `remember { LocalDate.now() }` taken when they opened.
 */
@Composable
fun rememberToday(): LocalDate {
    val today by produceState(Today.date()) {
        while (true) {
            delay(millisToMidnight(Today.time()).coerceIn(1_000L, 60_000L))
            value = Today.date()
        }
    }
    return today
}

/** Milliseconds until the next midnight; the wait is capped at a minute, so a changed clock is caught too. */
internal fun millisToMidnight(now: LocalTime): Long =
    (24L * 3600 - now.toSecondOfDay()) * 1000 - now.nano / 1_000_000
