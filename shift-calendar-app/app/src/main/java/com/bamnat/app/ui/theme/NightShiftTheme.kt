package com.bamnat.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.AppContainer
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.data.ThemeMode
import com.bamnat.core.model.NightShift
import kotlinx.coroutines.delay
import java.time.LocalDateTime

/**
 * [SHFT-05] 야간 근무 중 어둡게: dark while the calendar on the calendar tab is in a night shift (one whose
 * hours reach into 22:00~06:00), the phone's theme otherwise. Checked once a minute, on the minute.
 */
@Composable
fun rememberThemeMode(mode: ThemeMode, container: AppContainer): ThemeMode {
    if (mode != ThemeMode.NIGHT_SHIFT) return mode
    val snapshot by container.calendars.activeSnapshot.collectAsStateWithLifecycle(initialValue = null as ScheduleSnapshot?)
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = LocalDateTime.now()
        }
    }
    val night = remember(snapshot, now) { snapshot?.let { NightShift.isOn(now, it.resolver::resolve) } ?: false }
    return if (night) ThemeMode.DARK else ThemeMode.SYSTEM
}
