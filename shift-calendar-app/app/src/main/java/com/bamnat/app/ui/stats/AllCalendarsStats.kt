package com.bamnat.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.money.AllCalendarsFigures
import com.bamnat.app.ui.money.rememberAllCalendarsMonth
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import java.time.YearMonth

/**
 * [COMN-03] 통계's 전체: whatever kind of work each calendar is, only 총수입 · 총지출 · 순저축 for the
 * month over every calendar, and each calendar's days and hours of work. False with fewer than two
 * calendars, for the screen to show the calendar's own figures instead.
 */
@Composable
fun AllCalendarsStats(month: YearMonth): Boolean {
    val c = LocalContext.current.appContainer
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    if (calendars.size < 2) return false
    val all = rememberAllCalendarsMonth(month)
    if (all == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return true
    }
    SectionCard(title = "모든 달력 · ${month.monthValue}월") {
        AllCalendarsFigures(all)
    }
    SectionCard(title = "달력별 근무") {
        all.rows.forEach { row ->
            val color = calendars.firstOrNull { it.id == row.calendar }?.color
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline))
                Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text(
                    "${row.workDays}일" + if (row.workMinutes > 0) " · ${HoursFormat.korean(row.workMinutes)}" else "",
                    style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                )
            }
        }
        Text("일 종류가 달라도 수입과 지출만 모아 봐요. 달력마다 자세한 통계는 위에서 달력을 고르면 보여요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
    return true
}
