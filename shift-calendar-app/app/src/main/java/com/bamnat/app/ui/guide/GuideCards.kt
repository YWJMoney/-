package com.bamnat.app.ui.guide

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bamnat.app.data.GuideStore
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.guide.AppFeature
import com.bamnat.core.guide.GuideChecklist
import com.bamnat.core.guide.GuideMoments
import com.bamnat.core.guide.GuideTips
import com.bamnat.core.guide.MomentFacts
import com.bamnat.core.model.isNight
import com.bamnat.core.money.MoneyBook
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.work.HourlyWork
import com.bamnat.core.work.WorkStyle
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * [GUIDE-02][GUIDE-03] At the top of the calendar: '이 달력으로 할 수 있는 것' on a calendar's first days,
 * then at most one tip a day. Quiet by design: one card, one line, each put away for good in one tap, and
 * both can be turned off in 설정 › 기능 켜고 끄기.
 */
@Composable
fun GuideArea(
    calendar: String,
    style: WorkStyle?,
    snapshot: ScheduleSnapshot,
    book: MoneyBook,
    profile: PayProfile?,
    weekStart: DayOfWeek,
    modifier: Modifier = Modifier,
    now: LocalDateTime = LocalDateTime.now(),
) {
    val context = LocalContext.current
    val store = remember { GuideStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val today = now.toLocalDate()
    // [GUIDE-02] A calendar seen for the first time, or with its kind of work changed, starts its checklist.
    LaunchedEffect(calendar, style) { store.startChecklist(calendar, style, today) }
    // A calendar still waiting for its first pattern has its own start card; nothing more on top of it.
    if (style?.usesPattern != false && snapshot.isEmpty) return

    val checklist = remember(state, calendar, style, today) {
        val listOff = com.bamnat.core.guide.FeatureRegistry.get("guide.checklist")?.let { state.isOff(it, calendar) } ?: false
        if (listOff) null
        else GuideChecklist.visible(state.checklists[calendar], style, today, state::isUsed) { state.isOff(it, calendar) }
    }
    if (checklist != null) {
        ChecklistCard(
            style = style,
            items = checklist,
            onTry = { f -> store.used(f.id); FeatureLinks.open(f) },
            onLater = { store.later(calendar, today) },
            onClose = { store.dismiss(calendar) },
            modifier = modifier,
        )
        return
    }
    val facts = remember(snapshot, book, profile, style, weekStart, now.hour, today) { factsOf(style, now, snapshot, book, profile, weekStart) }
    val moments = remember(facts) { GuideMoments.of(facts) }
    val pick = remember(state, calendar, style, moments, today) { GuideTips.pick(state, calendar, style, moments, today) } ?: return
    LaunchedEffect(pick.feature.id, today) { if (pick.isNew) store.tipShown(today, pick.feature.id) }
    TipLine(
        feature = pick.feature,
        onTry = { store.used(pick.feature.id); FeatureLinks.open(pick.feature) },
        onClose = { store.closeTip(today) },
        onNever = { store.never(pick.feature.id); store.closeTip(today) },
        onTipsOff = {
            com.bamnat.core.guide.FeatureRegistry.get("guide.tips")?.let { tips ->
                store.setOn(tips, calendar, false)
                FeatureLinks.saidOff(tips.name)
            }
        },
        modifier = modifier,
    )
}

/** [GUIDE-03] What the moments are worked out from, read off the calendar on screen. */
internal fun factsOf(style: WorkStyle?, now: LocalDateTime, snapshot: ScheduleSnapshot, book: MoneyBook, profile: PayProfile?, weekStart: DayOfWeek): MomentFacts {
    val today = now.toLocalDate()
    val day = runCatching { snapshot.resolver.resolve(today) }.getOrNull()
    val yesterday = runCatching { snapshot.resolver.resolve(today.minusDays(1)) }.getOrNull()
    val weekFrom = today.with(TemporalAdjusters.previousOrSame(profile?.holidayWeekFrom ?: weekStart))
    return MomentFacts(
        style = style,
        now = now,
        workDay = day?.isWorkDay == true,
        shiftEnd = day?.shiftTo,
        // The latest 급여일 up to today: this month's pay, or last month's when it comes the month after.
        payDate = profile?.period?.let { p -> listOf(YearMonth.from(today), YearMonth.from(today).minusMonths(1)).mapNotNull(p::payDate).filter { !it.isAfter(today) }.maxOrNull() },
        weekMinutes = if (style == WorkStyle.HOURLY) runCatching { HourlyWork.week(weekFrom, snapshot.resolver::resolve).minutes }.getOrNull() else null,
        salesDays = book.sales.count { it.sales > 0 },
        jobs = book.jobs.count { !it.cancelled },
        unpaidJobs = book.jobs.count { it.unpaid > 0 && !it.date.isAfter(today) },
        afterNight = yesterday?.shiftType?.isNight == true && yesterday.isWorkDay,
    )
}

/** [GUIDE-02] '이 달력으로 할 수 있는 것': each line opens the feature, ticks once used; 나중에 and × put it away. */
@Composable
private fun ChecklistCard(
    style: WorkStyle?,
    items: List<GuideChecklist.Item>,
    onTry: (AppFeature) -> Unit,
    onLater: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val done = items.count { it.done }
    Surface(shape = MaterialTheme.shapes.large, color = scheme.surfaceContainerLow, modifier = modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("이 달력으로 할 수 있는 것", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text("${(style ?: WorkStyle.SHIFT).label} 달력에 쓸모 있는 것부터 · ${done}/${items.size}",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "이 안내 닫기", tint = scheme.onSurfaceVariant) }
            }
            items.forEach { item -> ChecklistRow(item, onTry) }
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                Text("끄려면 설정 › 기능 켜고 끄기", style = MaterialTheme.typography.labelSmall, color = scheme.outline, modifier = Modifier.weight(1f))
                TextButton(onClick = onLater) { Text("나중에") }
            }
        }
    }
}

@Composable
private fun ChecklistRow(item: GuideChecklist.Item, onTry: (AppFeature) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClickLabel = "해 보기") { onTry(item.feature) }
            .semantics { stateDescription = if (item.done) "해 봤어요" else "아직 안 해 봤어요" }
            .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null,
            tint = if (item.done) scheme.primary else scheme.outline, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.feature.name, style = MaterialTheme.typography.bodyLarge,
                color = if (item.done) scheme.onSurfaceVariant else scheme.onSurface)
            Text(item.feature.what, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
        }
        Spacer(Modifier.width(8.dp))
        Text(if (item.done) "다시 보기" else "해 보기", style = MaterialTheme.typography.labelLarge,
            color = scheme.primary, fontWeight = FontWeight.Medium)
    }
}

/** [GUIDE-03] One line: the tip, 해 보기, × for today, and ⋮ with 다시 보지 않기 and 안내 끄기. */
@Composable
private fun TipLine(
    feature: AppFeature,
    onTry: () -> Unit,
    onClose: () -> Unit,
    onNever: () -> Unit,
    onTipsOff: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var menu by remember { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, top = 8.dp, end = 4.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Lightbulb, null, modifier = Modifier.padding(top = 2.dp).size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(feature.tip.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(top = 1.dp))
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Filled.MoreVert, "안내 더 보기") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("다시 보지 않기") }, onClick = { menu = false; onNever() })
                        DropdownMenuItem(text = { Text("이런 안내 모두 끄기") }, onClick = { menu = false; onTipsOff() })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onNever) { Text("다시 보지 않기") }
                TextButton(onClick = onClose) { Text("닫기") }
                TextButton(onClick = onTry) { Text("해 보기") }
            }
        }
    }
}
