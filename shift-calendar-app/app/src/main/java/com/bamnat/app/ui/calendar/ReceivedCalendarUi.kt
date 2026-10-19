package com.bamnat.app.ui.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.repeatOnLifecycle
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.share.ReceivedCalendar
import com.bamnat.app.share.SharedDay
import com.bamnat.app.share.SharedSchedule
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ConfirmDialog
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.components.isDarkSurface
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.schedule.ResolvedDay
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** [SHR-04] Days both people are off: a soft rose, the same in the grid, the chip and the day card. */
private val TogetherColor = Color(0xFFE5487A)

/** [SHR-02] A round avatar with one letter, the face of a calendar in the switcher. */
@Composable
fun CalendarAvatar(
    initial: String, color: Color, modifier: Modifier = Modifier, size: Dp = 28.dp, contentColor: Color = Color.White,
    shape: androidx.compose.ui.graphics.Shape = CircleShape,
) {
    Box(modifier.size(size).clip(shape).background(color), contentAlignment = Alignment.Center) {
        Text(
            initial,
            color = contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.46f).toSp() },
            maxLines = 1,
        )
    }
}

@Composable
internal fun MyAvatar(size: Dp = 28.dp) =
    CalendarAvatar("나", MaterialTheme.colorScheme.primary, size = size, contentColor = MaterialTheme.colorScheme.onPrimary)

@Composable
internal fun ReceivedCalendar.Avatar(size: Dp = 28.dp) = CalendarAvatar(initial, color.asColor(), size = size)

@Composable
internal fun SwitchRow(
    avatar: @Composable () -> Unit,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
    dim: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .semantics { this.selected = selected }
            .padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.alpha(if (dim) 0.45f else 1f)) { avatar() }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
        if (selected) Icon(Icons.Filled.Check, "보는 중", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 8.dp))
        trailing?.invoke()
    }
}

/** "10월 1일~12월 31일", or what happened to it. */
internal fun ReceivedCalendar.summary(): String {
    val s = schedule ?: return "공유가 끝났어요"
    return if (s.first != null && s.last != null) "${Fmt.monthDay(s.first)}~${Fmt.monthDay(s.last)}" else "근무 없음"
}

/** [SHR-01] The parts the sharer ticked, in the share sheet's words; null when only shifts came. */
internal fun SharedSchedule.showsText(): String? =
    listOfNotNull(
        "일정".takeIf { "jobs" in shows },
        "휴가 종류".takeIf { "leaveKinds" in shows },
        "연장·휴무일 근무".takeIf { "extras" in shows },
        "메모".takeIf { "memos" in shows },
        payKind.label.takeIf { "pay" in shows },
    ).takeIf { it.isNotEmpty() }?.joinToString(", ")

/**
 * [SHR-02] Opening a received link: a look at whose schedule it is and what came with it, then
 * keeping it beside the user's own. [startId] comes from a tapped link; without it the user pastes one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReceivedSheet(vm: ReceivedViewModel, startId: String?, today: LocalDate, onDismiss: () -> Unit, onAdded: () -> Unit) {
    val preview by vm.preview.collectAsState()
    val calendars by vm.calendars.collectAsState()
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(startId) { if (startId != null) vm.previewId(startId) else vm.resetPreview() }
    ModalBottomSheet(onDismissRequest = { vm.resetPreview(); onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("받은 근무표 넣기", style = MaterialTheme.typography.titleLarge)
            Text("받은 링크를 넣으면 내 달력과 바꿔 가며 볼 수 있어요. 같이 쉬는 날도 바로 보여요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (startId == null) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; if (preview is ReceivedViewModel.Preview.Failed) vm.resetPreview() },
                    label = { Text("받은 링크") },
                    placeholder = { Text("https://…/s/…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.preview(text) }),
                    trailingIcon = {
                        IconButton(onClick = {
                            clipboard.getText()?.text?.let { pasted ->
                                text = pasted.trim()
                                vm.preview(text)
                            }
                        }) { Icon(Icons.Outlined.ContentPaste, "붙여넣기") }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (preview !is ReceivedViewModel.Preview.Ready) {
                    Button(onClick = { vm.preview(text) }, enabled = text.isNotBlank() && preview != ReceivedViewModel.Preview.Loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("불러오기") }
                }
            }
            AnimatedContent(preview, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "preview") { p ->
                when (p) {
                    ReceivedViewModel.Preview.Idle -> Spacer(Modifier.height(1.dp))
                    ReceivedViewModel.Preview.Loading -> Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("근무표를 받는 중…", style = MaterialTheme.typography.bodyMedium)
                    }
                    is ReceivedViewModel.Preview.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        if (startId != null) OutlinedButton(onClick = { vm.previewId(startId) }) { Text("다시 해 보기") }
                    }
                    is ReceivedViewModel.Preview.Ready -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val color = calendars.firstOrNull { it.id == p.id }?.color
                            ?: ReceivedCalendar.COLORS[calendars.size % ReceivedCalendar.COLORS.size]
                        PreviewCard(p.schedule, color.asColor(), p.share.expiresAt, today)
                        Button(onClick = { if (vm.add(p)) onAdded() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (p.existing) "새로 받아 보기" else "내 달력 옆에 두고 보기")
                        }
                        Text("읽기 전용이에요. 보낸 분이 근무표를 고치면 열 때마다 새로 받아요.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(s: SharedSchedule, color: Color, expiresAt: Instant, today: LocalDate) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CalendarAvatar(s.title.trim().take(1).ifEmpty { "?" }, color, size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.title.trim().takeIf { it.isNotEmpty() }?.let { "${it}의 근무표" } ?: "이름 없는 근무표", style = MaterialTheme.typography.titleMedium)
                    if (s.first != null && s.last != null) {
                        Text("${Fmt.monthDay(s.first)} ~ ${Fmt.monthDay(s.last)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // The next week at a glance, from today or from where the schedule starts.
            val from = s.first?.let { if (today.isBefore(it)) it else today } ?: today
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val d = from.plusDays(i.toLong())
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (d == today) "오늘" else Fmt.dayOfWeekShort(d.dayOfWeek), style = MaterialTheme.typography.labelSmall,
                            color = if (d == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        DayBadge(s, s.days[d], size = 22.dp, inRange = s.covers(d))
                    }
                }
            }
            Text(s.showsText()?.let { "함께 받은 것: $it" } ?: "근무와 휴가만 받았어요",
                style = MaterialTheme.typography.bodySmall)
            Text("${expiry(expiresAt)}까지 볼 수 있어요", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [CAL-12] Whether the sender keeps shifts at all; a freelancer's or a shop's link has only jobs and memos. */
internal fun SharedSchedule.usesShifts(): Boolean = days.values.any { it.type != null || it.second != null }

/**
 * The badge for one of their days: the shift (and the shift covered on top), or the leave. [CAL-12] A
 * day with only a job (or extra work) shows that, and a sender without shifts never gets "휴" stamped
 * on every empty day.
 */
@Composable
private fun DayBadge(s: SharedSchedule, day: SharedDay?, size: Dp, inRange: Boolean = true, shifts: Boolean = true) {
    if (!inRange) {
        Text("–", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        return
    }
    val type = s.type(day?.type)?.asShiftType()
    val second = s.type(day?.second)?.asShiftType()
    val leave = day?.leave?.takeIf { day.fullLeave }
    val extra = day?.extras?.firstOrNull()
    when {
        leave != null -> LeaveChip(leave, size)
        type != null && second != null -> Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ShiftBadge(type = type, size = size * 0.82f, outlined = type.isOff, showIcon = false)
            ShiftBadge(type = second, size = size * 0.82f, showIcon = false)
        }
        second != null -> ShiftBadge(type = second, size = size)
        type != null -> ShiftBadge(type = type, size = size, outlined = type.isOff)
        extra != null -> ExtraChip(extra.name, day.extras.size, size)
        shifts -> ShiftBadge(type = null, text = "휴", size = size, outlined = true)
        else -> Spacer(Modifier.height(size))
    }
}

/** [CAL-12] A job or extra work as the day's own mark: its first two letters, and how many there are. */
@Composable
private fun ExtraChip(name: String, count: Int, size: Dp) {
    Box(
        Modifier.height(size).clip(RoundedCornerShape(size / 4)).background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(2) + if (count > 1) "+" else "", color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.Bold,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.46f).toSp() }, maxLines = 1)
    }
}

@Composable
private fun LeaveChip(label: String, size: Dp) {
    Box(
        Modifier.clip(RoundedCornerShape(size / 3)).background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.take(3), color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.48f).toSp() }, maxLines = 1)
    }
}

private fun expiry(at: Instant): String = Fmt.monthDay(at.atZone(ZoneId.systemDefault()).toLocalDate())

private fun ago(at: Instant?, now: Instant = Instant.now()): String {
    if (at == null) return "아직 받지 않았어요"
    val d = Duration.between(at, now)
    return when {
        d.toMinutes() < 1 -> "방금 받음"
        d.toMinutes() < 60 -> "${d.toMinutes()}분 전에 받음"
        d.toHours() < 24 -> "${d.toHours()}시간 전에 받음"
        else -> "${Fmt.monthDay(at.atZone(ZoneId.systemDefault()).toLocalDate())}에 받음"
    }
}

/** [SHR-04] Whether the user has the day off: no shift and no work on top, or a whole day of leave. */
private fun ResolvedDay.isRestForMe(): Boolean = (isOff || leave?.isFullDay == true) && extras.isEmpty()

/**
 * [SHR-02][SHR-04] Someone's shared calendar in place of the user's own: read only, with the days
 * both are off marked, the day picked compared with the user's own, and what else they sent.
 */
@Composable
fun ReceivedCalendarView(
    vm: ReceivedViewModel,
    cal: ReceivedCalendar,
    mine: ScheduleSnapshot,
    weekStart: DayOfWeek,
    wide: Boolean,
    today: LocalDate,
    startMonth: YearMonth,
    identityBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val refresh by vm.refresh.collectAsState()
    // Each time the app comes back too, so a link that ran out or changed meanwhile shows as it is now.
    LifecycleStartEffect(cal.id) {
        vm.refreshIfStale(cal.id)
        onStopOrDispose { }
    }
    // [SHR-12] While it stays on screen, a quiet look every few minutes; only a change is downloaded.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(cal.id, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                kotlinx.coroutines.delay(5 * 60_000L)
                vm.refreshIfStale(cal.id)
            }
        }
    }
    val schedule = cal.schedule
    if (schedule == null) {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            identityBar()
            GoneCard(cal, onRemove = { vm.remove(cal.id) }, onMine = { vm.view(null) }, modifier = Modifier.padding(16.dp))
        }
        return
    }
    val first = schedule.first?.let(YearMonth::from) ?: YearMonth.from(today)
    val initial = if (schedule.covers(startMonth)) startMonth else if (schedule.covers(YearMonth.from(today))) YearMonth.from(today) else first
    val pager = rememberPagerState(initialPage = monthToPage(BASE_MONTH, initial)) { PAGE_COUNT }
    val month = pageToMonth(BASE_MONTH, pager.currentPage)
    val scope = rememberCoroutineScope()
    var selected by remember(cal.id) { mutableStateOf(if (schedule.covers(today)) today else schedule.first ?: today) }
    LaunchedEffect(pager, cal.id) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val m = pageToMonth(BASE_MONTH, page)
            if (YearMonth.from(selected) != m) selected = if (YearMonth.from(today) == m) today else m.atDay(1)
        }
    }
    var together by rememberSaveable { mutableStateOf(true) }
    val canCompare = !mine.isEmpty
    val restTogether: (LocalDate) -> Boolean = { d -> canCompare && schedule.isRest(d) == true && mine.resolver.resolve(d).isRestForMe() }
    val togetherCount = remember(schedule, mine, month) { (1..month.lengthOfMonth()).count { restTogether(month.atDay(it)) } }
    val goTo: (YearMonth) -> Unit = { m -> scope.launch { pager.animateScrollToPage(monthToPage(BASE_MONTH, m)) } }
    val loading = refresh == ReceivedViewModel.Refresh.Loading(cal.id)
    val offline = refresh == ReceivedViewModel.Refresh.Offline(cal.id)

    val calendarPart: @Composable () -> Unit = {
        identityBar()
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { goTo(month.minusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달") }
            Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 6.dp))
            IconButton(onClick = { goTo(month.plusMonths(1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selected = today; goTo(YearMonth.from(today)) }) { Text("오늘") }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // [CAL-12] Only for a month the link covers: outside it there is nothing to compare.
            if (canCompare && schedule.covers(month)) {
                FilterChip(
                    selected = together,
                    onClick = { together = !together },
                    label = { Text("같이 쉬는 날 ${togetherCount}일") },
                    leadingIcon = { Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp), tint = if (together) TogetherColor else MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = TogetherColor.copy(alpha = 0.14f)),
                )
            }
            Spacer(Modifier.weight(1f))
            Freshness(cal, loading, offline, onRefresh = { vm.refresh(cal.id) })
        }
        WeekdayHeader(weekStart, Modifier.padding(horizontal = 8.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val cellHeight = (maxWidth / 7 * 1.3f).coerceIn(66.dp, 100.dp) + cellTextGrowth()
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(cellHeight * GRID_ROWS), beyondViewportPageCount = 1, key = { it }) { page ->
                ReceivedMonthGrid(
                    month = pageToMonth(BASE_MONTH, page),
                    schedule = schedule,
                    weekStart = weekStart,
                    selected = selected,
                    today = today,
                    together = { d -> together && restTogether(d) },
                    cellHeight = cellHeight,
                    onDayClick = { d ->
                        selected = d
                        if (YearMonth.from(d) != month) goTo(YearMonth.from(d))
                    },
                )
            }
        }
        if (!schedule.covers(month)) {
            Text("이 달은 받은 근무표에 없어요. ${schedule.first?.let(Fmt::monthDay)}~${schedule.last?.let(Fmt::monthDay)}만 볼 수 있어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Legend(schedule, month, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), together = canCompare && schedule.covers(month))
    }
    val detailPart: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReceivedDayCard(selected, today, cal, schedule, mine.resolver.resolve(selected).takeIf { canCompare }, restTogether(selected))
            schedule.pay.firstOrNull { it.month == month }?.let { PayCard(cal, it, schedule.payKind) }
            Text("${cal.expiresAt?.let(::expiry) ?: "기간 끝"}까지 볼 수 있어요 · 받은 달력은 이 휴대폰에만 있어요",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
    if (wide) {
        Row(modifier.fillMaxSize()) {
            Column(Modifier.weight(1.05f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { calendarPart() }
            VerticalDivider()
            detailPart(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp))
        }
    } else {
        Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            calendarPart()
            detailPart(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
}

/** How fresh the copy on this phone is, with a refresh; offline it says the copy is what shows. */
@Composable
private fun Freshness(cal: ReceivedCalendar, loading: Boolean, offline: Boolean, onRefresh: () -> Unit) {
    val text = when {
        loading -> "받는 중…"
        offline -> "연결 안 됨 · ${ago(cal.fetchedAt).removeSuffix(" 받음")} 것"
        else -> ago(cal.fetchedAt)
    }
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = if (offline) "인터넷이 안 돼 $text 근무표를 보여 줘요" else text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (offline) Icon(Icons.Outlined.CloudOff, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        if (offline) Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
    if (loading) {
        CircularProgressIndicator(Modifier.padding(15.dp).size(18.dp), strokeWidth = 2.dp)
    } else {
        IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, "새로 받기") }
    }
}

@Composable
private fun GoneCard(cal: ReceivedCalendar, onRemove: () -> Unit, onMine: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.LinkOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("공유가 끝났어요", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text(
                "보낸 분이 링크를 끊었거나 기간이 지나 ${cal.fullName}${Fmt.obj(cal.fullName)} 이 휴대폰에서도 지웠어요. 다시 보려면 새 링크를 받아 넣어 주세요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Button(onClick = onMine, modifier = Modifier.fillMaxWidth()) { Text("내 달력으로") }
            TextButton(onClick = onRemove) { Text("목록에서 빼기") }
        }
    }
}

@Composable
private fun ReceivedMonthGrid(
    month: YearMonth,
    schedule: SharedSchedule,
    weekStart: DayOfWeek,
    selected: LocalDate,
    today: LocalDate,
    together: (LocalDate) -> Boolean,
    cellHeight: Dp,
    onDayClick: (LocalDate) -> Unit,
) {
    val firstDay = month.atDay(1)
    val start = firstDay.minusDays(((firstDay.dayOfWeek.value - weekStart.value + 7) % 7).toLong())
    val shifts = remember(schedule) { schedule.usesShifts() }
    Column(Modifier.fillMaxWidth()) {
        for (row in 0 until GRID_ROWS) {
            Row(Modifier.fillMaxWidth().height(cellHeight)) {
                for (col in 0 until 7) {
                    val date = start.plusDays((row * 7 + col).toLong())
                    ReceivedDayCell(
                        date = date,
                        day = schedule.days[date],
                        schedule = schedule,
                        inMonth = YearMonth.from(date) == month,
                        isToday = date == today,
                        isSelected = date == selected,
                        together = together(date),
                        onClick = { onDayClick(date) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shifts = shifts,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceivedDayCell(
    date: LocalDate,
    day: SharedDay?,
    schedule: SharedSchedule,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    together: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shifts: Boolean = true,
) {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val inRange = schedule.covers(date)
    // [CAL-12] The day's job or extra work is its badge when it has no shift; then no corner letter repeats it.
    val extraIsBadge = day != null && day.type == null && day.second == null && !day.fullLeave && day.extras.isNotEmpty()
    val type = schedule.type(day?.type)
    val second = schedule.type(day?.second)
    val holiday = KoreanHolidays.nameOf(date)
    val numberColor = when {
        holiday != null || date.dayOfWeek == DayOfWeek.SUNDAY -> cal.sunday
        date.dayOfWeek == DayOfWeek.SATURDAY -> cal.saturday
        else -> scheme.onSurface
    }
    val fullLeave = day?.leave?.takeIf { day.fullLeave }
    val dark = isDarkSurface()
    val tint = when {
        together -> TogetherColor.copy(alpha = if (dark) 0.24f else 0.13f)
        fullLeave != null -> null
        type != null && !type.off -> type.color.asColor().copy(alpha = if (dark) 0.22f else 0.13f)
        second != null -> second.color.asColor().copy(alpha = if (dark) 0.22f else 0.13f)
        else -> null
    }
    val description = buildString {
        append(Fmt.dayTitle(date))
        if (isToday) append(", 오늘")
        holiday?.let { append(", ").append(it) }
        if (!inRange) append(", 받은 근무표에 없는 날")
        else {
            // A 대근 on a day with no shift of its own is that day's work.
            val main = type ?: second
            if (main != null || day?.extras.isNullOrEmpty()) append(", ").append(main?.name ?: if (shifts) "근무 없음" else "일정 없음")
            main?.time?.takeIf { !main.off }?.let { append(" ").append(it.replace("-", "부터 ")).append("까지") }
            second?.takeIf { type != null }?.let { append(", ").append(it.name).append(" 더") }
            day?.leave?.let { append(", ").append(it) }
            day?.extras?.forEach { append(", ").append(it.name) }
            day?.memo?.let { append(", 메모 ").append(it) }
            if (together) append(", 같이 쉬는 날")
        }
    }
    Box(
        modifier
            .padding(1.5.dp)
            .clip(shape)
            .background(tint ?: Color.Transparent)
            .then(if (isSelected) Modifier.border(2.dp, scheme.primary, shape) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; this.selected = isSelected }
            .alpha(if (inMonth) 1f else 0.35f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                Box(
                    Modifier.align(Alignment.Center).size(20.dp).clip(CircleShape).background(if (isToday) scheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                        fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isToday) scheme.onPrimary else numberColor,
                        fontSize = 13.sp,
                    )
                }
                if (together) {
                    Icon(Icons.Filled.Favorite, null, tint = TogetherColor, modifier = Modifier.align(Alignment.TopStart).size(11.dp))
                }
                day?.extras?.firstOrNull()?.takeIf { !extraIsBadge }?.let { e ->
                    Box(
                        Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(4.dp)).background(scheme.secondaryContainer).padding(horizontal = 2.dp),
                    ) { Text(e.name.take(1), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = scheme.onSecondaryContainer) }
                }
            }
            if (inRange) DayBadge(schedule, day, size = 22.dp, shifts = shifts)
            // [CAL-12] Without a memo, a job's start time says when the day begins.
            Text(
                day?.memo ?: day?.extras?.firstOrNull { it.time != null }?.time?.substringBefore("-").orEmpty(),
                fontSize = 9.sp,
                lineHeight = 10.sp,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(schedule: SharedSchedule, month: YearMonth, modifier: Modifier = Modifier, together: Boolean = false) {
    val inMonth = remember(schedule, month) { schedule.days.values.filter { YearMonth.from(it.date) == month } }
    val used = remember(inMonth) { inMonth.flatMap { listOfNotNull(it.type, it.second) }.toSet() }
    val types = schedule.types.filter { it.id in used }
    // [CAL-12] Jobs and extra work, and the days both are off, are named too.
    val extras = remember(inMonth) { inMonth.any { it.extras.isNotEmpty() } }
    if (types.isEmpty() && !extras && !together) return
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (extras) Row(verticalAlignment = Alignment.CenterVertically) {
            ExtraChip("일", 1, 18.dp)
            Spacer(Modifier.width(4.dp))
            Text("일감·추가 근무", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (together) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Favorite, null, tint = TogetherColor, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("같이 쉬는 날", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        types.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShiftBadge(type = t.asShiftType(), size = 18.dp, outlined = t.off, showIcon = false)
                Spacer(Modifier.width(4.dp))
                Text(t.name + (t.time?.takeIf { !t.off }?.let { " " + it.replace("-", "~") } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReceivedDayCard(
    date: LocalDate,
    today: LocalDate,
    cal: ReceivedCalendar,
    schedule: SharedSchedule,
    mine: ResolvedDay?,
    together: Boolean,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(Fmt.dayTitle(date) + if (date == today) " · 오늘" else "", style = MaterialTheme.typography.titleMedium)
            val day = schedule.days[date]
            Row(verticalAlignment = Alignment.Top) {
                cal.Avatar(size = 32.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!schedule.covers(date)) {
                        Text("이 날은 받은 근무표에 없어요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        val type = schedule.type(day?.type)
                        val second = schedule.type(day?.second)
                        // A 대근 on a day with no shift of its own is that day's work.
                        val main = type ?: second
                        val shifts = remember(schedule) { schedule.usesShifts() }
                        // [CAL-12] A day with only jobs lists them below instead of saying "근무 없음".
                        if (main != null || day?.extras.isNullOrEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                            DayBadge(schedule, day, size = 22.dp, shifts = shifts)
                            if (main != null || shifts || day?.leave != null) Spacer(Modifier.width(8.dp))
                            Text(
                                when {
                                    main == null -> if (shifts) "근무 없음" else "일정 없음"
                                    main.off -> main.name
                                    else -> main.name + (main.time?.let { " " + it.replace("-", "~") } ?: "")
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        second?.takeIf { type != null }?.let { s ->
                            Text("${s.name} 더 해요" + (s.time?.let { " (${it.replace("-", "~")})" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                        }
                        day?.leave?.let { Text("휴가: $it", style = MaterialTheme.typography.bodyMedium) }
                        day?.extras?.forEach { e ->
                            Text(
                                e.name + (e.time?.let { " " + it.replace("-", "~") } ?: "") +
                                    (e.minutes?.let { " (${com.bamnat.core.hours.HoursFormat.korean(it)})" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        day?.memo?.let { m ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                                Text(m, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
            }
            if (mine != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MyAvatar(size = 32.dp)
                    Spacer(Modifier.width(12.dp))
                    val type = mine.shiftType
                    val leave = mine.leave
                    when {
                        leave?.isFullDay == true -> LeaveChip(leave.label, 22.dp)
                        type != null -> ShiftBadge(type = type, size = 22.dp, outlined = type.isOff)
                        else -> ShiftBadge(type = null, text = "휴", size = 22.dp, outlined = true)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            leave?.isFullDay == true -> leave.label
                            type == null || !mine.isWorkDay -> type?.name ?: "쉬는 날"
                            mine.start != null && mine.end != null -> "${type.name} ${Fmt.timeRange(mine.start!!, mine.end!!)}"
                            else -> type.name
                        } + (leave?.takeIf { !it.isFullDay }?.let { " · ${it.label}" } ?: "") +
                            mine.extras.joinToString("") { " · ${ExtraText.name(it)}" },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (together) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TogetherColor.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Favorite, null, tint = TogetherColor, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("둘 다 쉬는 날이에요", style = MaterialTheme.typography.titleSmall, color = TogetherColor)
                }
            }
        }
    }
}

@Composable
private fun PayCard(cal: ReceivedCalendar, pay: com.bamnat.app.share.SharedPay, kind: com.bamnat.core.work.MoneyShare) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(cal.title.trim().takeIf { it.isNotEmpty() }?.let { "$it 님이 함께 보낸 ${kind.label}" } ?: "함께 받은 ${kind.label}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${pay.month.monthValue}월", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(Fmt.won(pay.amount), style = MaterialTheme.typography.headlineSmall.merge(NumberStyle), fontWeight = FontWeight.Bold)
            }
            // [SHR-11] Pay is the sender's estimate; job income and sales are what the sender wrote down.
            Text((pay.label.ifBlank { "예상" }) + " · " + if (kind == com.bamnat.core.work.MoneyShare.PAY && pay.label != "받은 급여") "보낸 분의 앱이 계산한 금액이라 명세서와 다를 수 있어요" else "보낸 분이 앱에 적은 금액을 달마다 더했어요",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
