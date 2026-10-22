package com.bamnat.app.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.BamnatApp
import com.bamnat.app.ui.components.isDarkSurface
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.money.ClientColors
import com.bamnat.core.money.DayRate
import com.bamnat.core.money.Job
import com.bamnat.core.money.JobStatus
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.work.CardSettlement
import com.bamnat.core.work.WorkStyle
import java.time.LocalDate

/** [FLD-04] Colours a client is told by on the calendar; a name always gets the same one ([ClientColors]). */
internal val CLIENT_COLORS = listOf(
    Color(0xFF2F80ED), Color(0xFF27AE60), Color(0xFFE67E22), Color(0xFF8E44AD),
    Color(0xFFD6457A), Color(0xFF00A88F), Color(0xFFB7950B), Color(0xFF5D6D7E),
)

internal fun clientColor(client: String): Color = CLIENT_COLORS[ClientColors.index(client, CLIENT_COLORS.size)]

/** [FLD-04] The jobs on each day from [from] to [to], oldest booking first, for field work's cells. */
internal fun fieldJobsOf(money: MoneyBook, from: LocalDate, to: LocalDate): Map<LocalDate, List<Job>> {
    if (money.jobs.isEmpty()) return emptyMap()
    val out = HashMap<LocalDate, MutableList<Job>>()
    for (j in money.jobs.filter { !it.lastDate.isBefore(from) && !it.date.isAfter(to) }.sortedWith(compareBy({ it.date }, { it.id }))) {
        var d = maxOf(j.date, from)
        while (!d.isAfter(minOf(j.lastDate, to))) { out.getOrPut(d) { mutableListOf() }.add(j); d = d.plusDays(1) }
    }
    return out
}

/** [FLD-02] The day's 공수 for the line under a field cell ("1공", "0.5"); null when no job counts 공수. */
internal fun fieldUnitsText(jobs: List<Job>?): String? {
    val units = jobs.orEmpty().filter { !it.cancelled }.mapNotNull { it.units }.takeIf { it.isNotEmpty() } ?: return null
    return DayRate.cell(units.sum())
}

/**
 * [FLD-04] Field work's jobs in a cell: up to two bars in the client's colour, joined across the days
 * of a longer job, with the 업체 name or the 공수 inside. 가계약 is drawn as a dashed outline and 취소
 * faded and struck through; a third job and more say "+n".
 */
@Composable
internal fun FieldJobMarks(jobs: List<Job>, date: LocalDate) {
    val dark = isDarkSurface()
    val shown = jobs.take(if (jobs.size > 2) 1 else 2)
    Column(Modifier.fillMaxWidth().height(22.dp), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        shown.forEach { j -> FieldBar(j, date, dark, Modifier.weight(1f, fill = false)) }
        if (jobs.size > 2) {
            Text("+${jobs.size - 1}", fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun FieldBar(job: Job, date: LocalDate, dark: Boolean, modifier: Modifier) {
    val color = clientColor(job.client.ifBlank { job.title })
    val first = date == job.date
    val last = date == job.lastDate
    val tentative = job.status == JobStatus.TENTATIVE
    val cancelled = job.cancelled
    val fill = color.copy(alpha = if (cancelled) 0.12f else if (dark) 0.45f else 0.28f)
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .drawBehind {
                val reach = 4.dp.toPx() // across the cell's own padding, so a longer job's days join up
                val left = if (first) 0f else -reach
                val right = if (last) size.width else size.width + reach
                val r = CornerRadius(size.height / 2)
                if (tentative) {
                    drawRoundRect(color, topLeft = Offset(left, 0f), size = Size(right - left, size.height), cornerRadius = r,
                        style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))))
                } else {
                    drawRoundRect(fill, topLeft = Offset(left, 0f), size = Size(right - left, size.height), cornerRadius = r)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Only the first day names the job; the days after carry its colour.
        if (first) {
            val label = job.units?.let { DayRate.cell(it) } ?: job.client.ifBlank { job.title }.trim().take(4)
            Text(
                label, fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, overflow = TextOverflow.Clip, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (cancelled) 0.5f else 1f),
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
        }
    }
}

/**
 * [SHOP-02] Days a card deposit is expected on and not yet ticked, for the calendar on screen: the
 * shop's own fee and settlement days when set. Empty for any other kind of work.
 */
@Composable
internal fun rememberCardDue(money: MoneyBook): Set<LocalDate> {
    if (money.style != WorkStyle.BUSINESS || money.sales.none { it.card > 0 && !it.cardSettled }) return emptySet()
    val app = LocalContext.current.applicationContext as? BamnatApp
    val rules = app?.let { a ->
        val cal by a.container.calendars.activeCalendar.collectAsStateWithLifecycle(a.container.calendars.current())
        cal.shopMoney
    } ?: com.bamnat.core.work.ShopMoney()
    return remember(money.sales, rules) {
        CardSettlement.expected(money.sales, rules, KoreanHolidays::isHoliday).filter { !it.settled }.map { it.payDate }.toSet()
    }
}

/** [SHOP-02] The small 입금 mark on a day a card deposit is expected. */
@Composable
internal fun CardDueMark(modifier: Modifier = Modifier) {
    Text("입금", fontSize = 8.sp, lineHeight = 9.sp, maxLines = 1, fontWeight = FontWeight.Bold,
        color = com.bamnat.app.ui.theme.LocalCalendarColors.current.income, modifier = modifier)
}
