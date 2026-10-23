package com.bamnat.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.NoticeLinks
import com.bamnat.app.ui.Fmt
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.work.HealthYear
import com.bamnat.core.work.ShiftHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

/*
 * [SHFT-07] 연간 건강 지표: the year's night share, longest run of nights, short rests between shifts
 * (11시간 미만) and weekend and 공휴일 work, month by month and against last year. Numbers only, as the
 * month's cards are: no warnings about health, the user reads them for themselves.
 */

private fun pct(share: Double) = "${(share * 100).roundToInt()}%"

/** The year so far, or the whole year when it has passed. */
private fun yearOf(year: Int, snapshot: ScheduleSnapshot, today: LocalDate): HealthYear {
    val until = if (year >= today.year) today else LocalDate.of(year, 12, 31)
    return ShiftHealth.year(year, snapshot.resolver::resolve, KoreanHolidays::nameOf, until)
}

/** [SHFT-07] The card in 통계 of a shift calendar: the year so far in four numbers, and the way to the report. */
@Composable
internal fun HealthYearCard(snapshot: ScheduleSnapshot, calendarId: String? = null) {
    // [CAL-25] "올해까지" moves on at midnight with the screen open.
    val today = com.bamnat.app.ui.calendar.rememberToday()
    val year = today.year
    val data by produceState<HealthYear?>(null, snapshot, today) { value = withContext(Dispatchers.Default) { yearOf(year, snapshot, today) } }
    val y = data ?: return
    if (y.isEmpty) return
    // [CAL-39] I 리뷰 I18: which days the short rests were, one tap from the number.
    var showShort by rememberSaveable { mutableStateOf(false) }
    if (showShort) {
        val days by produceState<List<LocalDate>?>(null, snapshot, today) {
            value = withContext(Dispatchers.Default) { ShiftHealth.shortRestDays(year, snapshot.resolver::resolve, today) }
        }
        AlertDialog(
            onDismissRequest = { showShort = false },
            title = { Text("짧은 휴식 ${y.quickReturns}번") },
            text = {
                val list = days
                if (list == null) CircularProgressIndicator()
                else Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("앞 근무가 끝나고 11시간이 안 돼 다시 일한 날이에요. 누르면 달력에서 열어요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for (d in list.asReversed()) {
                        val day = snapshot.resolver.resolve(d)
                        TextButton(onClick = {
                            showShort = false
                            NoticeLinks.open(NoticeLinks.Target.DAY, calendarId, d.toString())
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("${Fmt.dayTitle(d)} · ${day.shiftType?.name ?: "추가 근무"}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showShort = false }) { Text("닫기") } },
        )
    }
    SectionCard(title = "${year}년 건강 지표", trailing = {
        TextButton(onClick = { NoticeLinks.open(NoticeLinks.Target.HEALTH_REPORT) }) { Text("자세히") }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("야간 비율", pct(y.nightShare), Modifier.weight(1f), caption = "근무 ${y.workDays}일 중 야간 ${y.nights}번")
            StatTile("최장 연속 야간", "${y.longestNightRun}일", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("짧은 휴식", "${y.quickReturns}번", Modifier.weight(1f), caption = "근무 사이 11시간 미만",
                onClick = if (y.quickReturns > 0) ({ showShort = true }) else null)
            StatTile("주말·공휴일 근무", "${y.weekendDays + y.holidayDays}일", Modifier.weight(1f),
                caption = "주말 ${y.weekendDays} · 공휴일 ${y.holidayDays}")
        }
    }
}

class HealthReportViewModel(container: AppContainer, val calendarId: String) : ViewModel() {
    val snapshot: StateFlow<ScheduleSnapshot?> = container.scopeOf(calendarId).schedule.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

/** [SHFT-07] The year's report, a month to a row, with last year's numbers beside it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HealthReportScreen(vm: HealthReportViewModel, onBack: () -> Unit) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    var year by rememberSaveable { mutableIntStateOf(LocalDate.now().year) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("연간 건강 지표") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } },
        )
    }) { padding ->
        val snap = snapshot
        val today = com.bamnat.app.ui.calendar.rememberToday() // [CAL-25]
        val pair by produceState<Pair<HealthYear, HealthYear>?>(null, snap, year, today) {
            value = snap?.let { s -> withContext(Dispatchers.Default) { yearOf(year, s, today) to yearOf(year - 1, s, today) } }
        }
        val (y, last) = pair ?: run {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (year == today.year) "${year}년 (오늘까지)" else "${year}년", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 해") }
                IconButton(onClick = { year++ }, enabled = year < today.year) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 해") }
            }
            if (y.isEmpty) {
                Text("이 해에는 달력에 근무가 없어요.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            fun versus(now: Int, before: Int): String? = if (last.isEmpty) null else when {
                now > before -> "작년보다 ${now - before} 많아요"
                now < before -> "작년보다 ${before - now} 적어요"
                else -> "작년과 같아요"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("야간 비율", pct(y.nightShare), Modifier.weight(1f),
                    caption = if (last.isEmpty) "야간 ${y.nights}번" else "야간 ${y.nights}번 · 작년 ${pct(last.nightShare)}")
                StatTile("최장 연속 야간", "${y.longestNightRun}일", Modifier.weight(1f), caption = versus(y.longestNightRun, last.longestNightRun))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("짧은 휴식", "${y.quickReturns}번", Modifier.weight(1f), caption = versus(y.quickReturns, last.quickReturns) ?: "근무 사이 11시간 미만")
                StatTile("주말·공휴일", "${y.weekendDays + y.holidayDays}일", Modifier.weight(1f),
                    caption = versus(y.weekendDays + y.holidayDays, last.weekendDays + last.holidayDays) ?: "주말 ${y.weekendDays} · 공휴일 ${y.holidayDays}")
            }
            SectionCard(title = "달마다 비교") {
                val maxNights = (y.months.maxOfOrNull { it.nights } ?: 0).coerceAtLeast(1)
                val busiest = y.busiestNightMonth?.month
                Row {
                    Text("", Modifier.width(36.dp))
                    Text("야간", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("비율", "연속", "짧은휴식", "주말·휴일").forEach {
                        Text(it, Modifier.width(52.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                y.months.forEach { m ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${m.month.monthValue}월", Modifier.width(36.dp), style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.weight(1f).height(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            val color = if (m.month == busiest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            if (m.nights > 0) Box(Modifier.fillMaxHeight().fillMaxWidth(m.nights.toFloat() / maxNights * 0.8f)
                                .clip(RoundedCornerShape(4.dp)).background(color))
                            Text(" ${m.nights}", style = MaterialTheme.typography.labelSmall.merge(NumberStyle))
                        }
                        listOf(pct(m.nightShare), "${m.longestNightRun}일", "${m.quickReturns}번", "${m.weekendDays + m.holidayDays}일").forEach {
                            Text(it, Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall.merge(NumberStyle))
                        }
                    }
                }
            }
            Text(
                "· 야간은 22시~6시에 걸친 근무예요(대근 포함).\n" +
                    "· 짧은 휴식은 앞 근무가 끝나고 다음 근무까지 11시간이 안 되는 경우예요.\n" +
                    "· 연속 야간은 야간을 며칠 이어서 했는지예요. 달을 넘긴 연속은 끝난 달에 셉니다.\n" +
                    "· 달력에 적힌 근무로 계산한 참고용 숫자예요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
