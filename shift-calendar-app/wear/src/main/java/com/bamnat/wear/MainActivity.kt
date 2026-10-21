package com.bamnat.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.bamnat.core.work.WatchDay
import com.bamnat.core.work.WatchDays
import com.bamnat.core.work.WatchPayload
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * [SHFT-08][REL-09] The watch app: 오늘 (today's shift, tomorrow's and the next alarm), 앞으로 7일 (today and six days on as a
 * list) and 월 보기 (a month at a glance, swiping to the next month). Swipe left and right between them.
 * It shows the watch's last copy of the phone's days with when they came, and asks the phone for fresh ones.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchStore.load(this)
        setContent { MaterialTheme { WatchApp() } }
    }
}

@Composable
private fun WatchApp() {
    val context = LocalContext.current
    val payload by WatchStore.payload.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun refresh() {
        refreshing = true
        scope.launch { runCatching { WatchStore.refresh(context) }; refreshing = false }
    }
    LaunchedEffect(Unit) { refresh() }
    val pager = rememberPagerState(pageCount = { 3 })
    HorizontalPager(pager, Modifier.fillMaxSize().background(Color.Black)) { page ->
        val p = payload
        when {
            p == null -> Empty(refreshing, ::refresh)
            page == 0 -> TodayPage(p, refreshing, ::refresh)
            page == 1 -> WeekPage(p)
            else -> MonthPage(p)
        }
    }
}

@Composable
private fun Empty(refreshing: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("밤낮", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("휴대폰의 밤낮을 한 번 열면 근무가 여기로 와요.", textAlign = TextAlign.Center, fontSize = 13.sp, color = Color(0xFFD6DAE3))
        Spacer(Modifier.height(8.dp))
        RefreshChip(refreshing, onRefresh)
    }
}

@Composable
private fun RefreshChip(refreshing: Boolean, onRefresh: () -> Unit) {
    Chip(
        onClick = onRefresh,
        enabled = !refreshing,
        label = { Text(if (refreshing) "받는 중…" else "휴대폰에서 다시 받기", fontSize = 12.sp) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

private fun color(d: WatchDay) = Color(ShiftTileService.readable(d.colorArgb))

@Composable
private fun TodayPage(p: WatchPayload, refreshing: Boolean, onRefresh: () -> Unit) {
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    val d = WatchDays.on(p.days, today)
    val list = rememberScalingLazyListState()
    ScalingLazyColumn(Modifier.fillMaxSize(), state = list, horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("오늘 · ${WatchText.date(today)}", fontSize = 12.sp, color = Color(0xFF9AA3B5)) }
        item {
            if (d == null) Text(WatchText.NOT_SENT, fontSize = 16.sp)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.name, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color(d), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (d.times.isNotEmpty()) Text(d.times, fontSize = 16.sp)
            }
        }
        WatchDays.on(p.days, today.plusDays(1))?.let { t -> item { Text("내일 ${t.line}", fontSize = 13.sp, color = Color(0xFFD6DAE3)) } }
        WatchText.alarm(p, now)?.let { a -> item { Text(a, fontSize = 12.sp, color = Color(0xFFFFC266), textAlign = TextAlign.Center) } }
        item { Text(WatchText.synced(p, now), fontSize = 11.sp, color = Color(0xFF9AA3B5), textAlign = TextAlign.Center) }
        item { RefreshChip(refreshing, onRefresh) }
        item { Text("옆으로 넘기면 앞으로 7일 · 월 보기", fontSize = 10.sp, color = Color(0xFF6E7687)) }
    }
}

@Composable
private fun WeekPage(p: WatchPayload) {
    val today = LocalDate.now()
    val days = (0L until 7L).map { today.plusDays(it) }
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("앞으로 7일", fontSize = 14.sp, fontWeight = FontWeight.Bold) }
        items(days) { date ->
            val d = WatchDays.on(p.days, date)
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${date.dayOfMonth} ${WatchText.dayOfWeek(date)}", fontSize = 13.sp, modifier = Modifier.width(44.dp),
                    color = if (date == today) Color.White else Color(0xFFB8BFCC), fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                Box(Modifier.size(10.dp).clip(CircleShape).background(d?.let(::color) ?: Color.DarkGray))
                Spacer(Modifier.width(6.dp))
                Text(d?.line ?: "-", fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MonthPage(p: WatchPayload) {
    val today = LocalDate.now()
    var offset by remember { mutableIntStateOf(0) }
    val month = YearMonth.from(today).plusMonths(offset.toLong())
    val grid = WatchDays.monthGrid(month.year, month.monthValue)
    ScalingLazyColumn(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp).clip(CircleShape)
                    .then(if (offset > -1) Modifier.clickableNoRipple { offset-- } else Modifier),
                    color = if (offset > -1) Color.White else Color.DarkGray)
                Text("${month.monthValue}월", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("›", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp).clip(CircleShape)
                    .then(if (offset < 1) Modifier.clickableNoRipple { offset++ } else Modifier),
                    color = if (offset < 1) Color.White else Color.DarkGray)
            }
        }
        item {
            Row(Modifier.padding(horizontal = 14.dp)) {
                listOf("월", "화", "수", "목", "금", "토", "일").forEach {
                    Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 9.sp, color = Color(0xFF9AA3B5))
                }
            }
        }
        items(grid) { week ->
            Row(Modifier.padding(horizontal = 14.dp)) {
                week.forEach { date ->
                    Column(Modifier.weight(1f).padding(1.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (date != null) {
                            val d = WatchDays.on(p.days, date)
                            Text("${date.dayOfMonth}", fontSize = 8.sp, color = if (date == today) Color.White else Color(0xFF9AA3B5),
                                fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal)
                            Box(Modifier.fillMaxWidth().height(13.dp).clip(RoundedCornerShape(3.dp))
                                .background(when {
                                    d == null -> Color(0xFF222222)
                                    d.off -> Color(0xFF333A46)
                                    else -> Color(d.colorArgb.toInt())
                                }), contentAlignment = Alignment.Center) {
                                Text(d?.label?.take(1) ?: "", fontSize = 8.sp, color = Color.White, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        item { Text("받은 기간 밖의 날은 비어 있어요", fontSize = 9.sp, color = Color(0xFF6E7687)) }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
