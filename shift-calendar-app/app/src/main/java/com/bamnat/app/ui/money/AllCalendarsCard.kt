package com.bamnat.app.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.appContainer
import com.bamnat.app.data.EveryCalendar
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.money.AllCalendarsMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.YearMonth

/** [COMN-02][COMN-03] Every calendar's [month] added up, worked out off the main thread; null while it is counted. */
@Composable
internal fun rememberAllCalendarsMonth(month: YearMonth): AllCalendarsMonth? {
    val c = LocalContext.current.appContainer
    val parts by remember(c) { EveryCalendar.parts(c) }.collectAsStateWithLifecycle(null)
    val tier by c.featureGate.tier.collectAsStateWithLifecycle()
    val all by produceState<AllCalendarsMonth?>(null, parts, month, tier) {
        val p = parts ?: return@produceState
        value = withContext(Dispatchers.Default) { EveryCalendar.month(p, month, tier) }
    }
    return all
}

/**
 * [COMN-02] The money tab's 모든 달력 합계, once there are two calendars or more: the month's income,
 * spending and what is left over every calendar, and each calendar's own line under it. Each
 * calendar keeps its own book; this only adds them up.
 */
@Composable
fun AllCalendarsCard(month: YearMonth) {
    val c = LocalContext.current.appContainer
    val calendars by c.calendars.calendars.collectAsStateWithLifecycle()
    if (calendars.size < 2) return
    val all = rememberAllCalendarsMonth(month) ?: return
    val cal = LocalCalendarColors.current
    SectionCard(title = "모든 달력 합계") {
        AllCalendarsFigures(all)
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        all.rows.forEach { row ->
            val color = calendars.firstOrNull { it.id == row.calendar }?.color
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline))
                Text(row.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(Fmt.wonShort(row.money.balance), style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                        color = if (row.money.balance < 0) cal.expense else MaterialTheme.colorScheme.onSurface)
                    Text("수입 ${Fmt.wonShort(row.money.income)} · 지출 ${Fmt.wonShort(row.money.spent)}",
                        style = MaterialTheme.typography.bodySmall.merge(NumberStyle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** [COMN-02][COMN-03] 총수입 · 총지출 · 순저축 side by side. */
@Composable
internal fun AllCalendarsFigures(all: AllCalendarsMonth) {
    val cal = LocalCalendarColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Figure("총수입", all.income, cal.income, Modifier.weight(1f))
        Figure("총지출", all.spent, cal.expense, Modifier.weight(1f))
        Figure("순저축", all.balance, if (all.balance < 0) cal.expense else MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
    }
}

@Composable
private fun Figure(label: String, amount: Long, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Fmt.wonShort(amount), style = MaterialTheme.typography.titleMedium.merge(NumberStyle), color = color, maxLines = 1)
    }
}
