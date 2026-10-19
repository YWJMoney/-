package com.bamnat.app.ui.calendar

import com.bamnat.core.holiday.KoreanLunar
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.Leave
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.bamnat.app.ui.components.asColor
import com.bamnat.app.ui.components.isDarkSurface
import com.bamnat.core.model.ShiftOverride
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.bamnat.core.money.DayMoney
import com.bamnat.core.money.MoneyBook
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.ColorDot
import com.bamnat.app.ui.components.ShiftBadge
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.holiday.KoreanHolidays
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.schedule.DaySource
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.CalendarFocus
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Work
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

const val GRID_ROWS = 6

/** [CAL-01] Room left of each week for the bar that marks a week over the hour limit. */
val WEEK_BAR_SPACE = 5.dp

/**
 * [VIS-02] What a bigger system font adds to a day cell. The date and the badges keep their size; only
 * the line under the badge grows, up to 13dp, so the cell grows by that much.
 */
@Composable
internal fun cellTextGrowth(): Dp = with(LocalDensity.current) {
    ((minOf(11.sp.toDp(), 13.dp) - 11.dp) * FOOTER_LINE).coerceAtLeast(0.dp)
}

private const val FOOTER_LINE = 1.15f

@Composable
fun WeekdayHeader(weekStart: DayOfWeek, modifier: Modifier = Modifier) {
    val cal = LocalCalendarColors.current
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        for (i in 0 until 7) {
            val dow = weekStart.plus(i.toLong())
            Text(
                Fmt.dayOfWeekShort(dow),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = when (dow) {
                    DayOfWeek.SUNDAY -> cal.sunday
                    DayOfWeek.SATURDAY -> cal.saturday
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Shortcuts offered on a long press, so a day can be set without opening it. */
sealed interface QuickAction {
    data class Shift(val override: ShiftOverride) : QuickAction
    /** [EXTRA-01] Adds the piece, or takes it off when the day has it. */
    data class Extra(val choice: ExtraChoice) : QuickAction
    /** 연차·반차·반반차, or null to remove it [FEAT-10]. */
    data class SetLeave(val leave: Leave?) : QuickAction
    /** [DAY-03] This day's alarm off (true) or back on. */
    data class Alarm(val skipped: Boolean) : QuickAction
    /** [CAL-05] 일감 적기 or 매출 적기 on this day. */
    data object AddMoney : QuickAction
    /** [CAL-05] A memo on this day. */
    data object Memo : QuickAction
    data object Open : QuickAction
}

/** [CAL-06] A job over several days, drawn as one bar across them: its ends and whether money is still owed. */
internal data class JobSpan(val first: Boolean, val last: Boolean, val owed: Boolean, val title: String, val nth: Int, val days: Int)

/**
 * [CAL-14] What the cells of one month show from the money book, worked out once for the month
 * instead of in each of its 42 cells.
 */
internal class MonthMoneyCells(
    val days: Map<LocalDate, DayMoney>,
    val spans: Map<LocalDate, JobSpan>,
    val sales: Map<LocalDate, Long>,
    /** Jobs starting on each date. */
    val starts: Map<LocalDate, Int>,
    private val bestSales: Long,
) {
    /** [CAL-06] How strong a shop's day was against the month's best, 0 for a day without sales. */
    fun heat(date: LocalDate): Float =
        sales[date]?.takeIf { bestSales > 0 && it > 0 }?.let { 0.3f + 0.7f * it.toFloat() / bestSales } ?: 0f

    companion object {
        val EMPTY = MonthMoneyCells(emptyMap(), emptyMap(), emptyMap(), emptyMap(), 0)

        fun of(money: MoneyBook, months: List<YearMonth>, from: LocalDate, to: LocalDate): MonthMoneyCells {
            if (money.jobs.isEmpty() && money.sales.isEmpty() && money.entries.isEmpty()) return EMPTY
            val days = months.distinct().fold(emptyMap<LocalDate, DayMoney>()) { acc, m -> acc + money.days(m) }
            val spans = HashMap<LocalDate, JobSpan>()
            for (j in money.jobs.filter { it.days > 1 && !it.lastDate.isBefore(from) && !it.date.isAfter(to) }.sortedBy { it.date }) {
                var d = maxOf(j.date, from)
                while (!d.isAfter(minOf(j.lastDate, to))) {
                    spans.putIfAbsent(d, JobSpan(d == j.date, d == j.lastDate, j.unpaid > 0, j.title,
                        (d.toEpochDay() - j.date.toEpochDay()).toInt() + 1, j.days))
                    d = d.plusDays(1)
                }
            }
            val sales = money.sales.filter { it.date in from..to && it.sales > 0 }.associate { it.date to it.sales }
            val starts = money.jobs.filter { it.date in from..to }.groupingBy { it.date }.eachCount()
            return MonthMoneyCells(days, spans, sales, starts, sales.values.maxOrNull() ?: 0)
        }
    }
}

@Composable
fun MonthGrid(
    month: YearMonth,
    snapshot: ScheduleSnapshot,
    calculator: WorkHoursCalculator,
    selected: LocalDate,
    today: LocalDate,
    memoDates: Set<LocalDate>,
    showHours: Boolean,
    showLunar: Boolean = false,
    cellHeight: Dp,
    onDayClick: (LocalDate) -> Unit,
    onQuickAction: (LocalDate, QuickAction) -> Unit,
    modifier: Modifier = Modifier,
    /** [WORK-02][WORK-03] Field and shop work show the day's income in place of hours. */
    money: MoneyBook = MoneyBook.EMPTY,
    showMoney: Boolean = false,
    /** [FEAT-17] Memo-first: the start of each day's memo in place of the hours. */
    memoTexts: Map<LocalDate, String> = emptyMap(),
    /** [CAL-01] With a stamp chosen in quick input, a finger dragged over days paints them all at once. */
    onPaint: ((List<LocalDate>) -> Unit)? = null,
    /** The hour limit a week over it is marked against, said to TalkBack at the week's first day. */
    weeklyLimitHours: Int = com.bamnat.core.hours.HourLimits().weeklyLimitHours,
    /** [SETUP-02] The first setup paints days only: no menu on a long press. */
    quickMenu: Boolean = true,
    /** [CAL-05] What the calendar is about: shifts, jobs or sales. */
    focus: CalendarFocus = CalendarFocus.SHIFTS,
    /** [CAL-09] In quick input a long press starts a range instead of opening the menu. */
    onLongPress: ((LocalDate) -> Unit)? = null,
    /** [CAL-09] The first day of a range being painted. */
    rangeFrom: LocalDate? = null,
) {
    val cal = LocalCalendarColors.current
    val haptics = LocalHapticFeedback.current
    val firstCell = remember(month, calculator) { calculator.weekStartOf(month.atDay(1)) }
    val cells = remember(money, month, firstCell) {
        MonthMoneyCells.of(money, listOf(month), firstCell, firstCell.plusDays(GRID_ROWS * 7L - 1))
    }
    // Days under the finger while painting, shown at once and written when it lifts.
    var dragged by remember { mutableStateOf<List<LocalDate>>(emptyList()) }
    val paint by rememberUpdatedState(onPaint)
    val painting = Modifier.pointerInput(firstCell, cellHeight, onPaint != null) {
        if (paint == null) return@pointerInput
        val bar = WEEK_BAR_SPACE.toPx()
        fun dateAt(p: Offset): LocalDate? {
            val row = (p.y / cellHeight.toPx()).toInt()
            val col = ((p.x - bar) / ((size.width - bar) / 7f)).toInt()
            if (p.x < bar || p.y < 0f || row !in 0 until GRID_ROWS || col !in 0..6) return null
            return firstCell.plusDays(row * 7L + col)
        }
        // [STAB-01] Quick input closed in the middle of a drag cancels it without onDragCancel; the days
        // already lit would stay lit.
        try {
            detectDragGestures(
                onDragStart = { p ->
                    dragged = listOfNotNull(dateAt(p))
                    // [VIS-02] Felt when the finger starts painting, a tick per day, and a firmer one when it is written.
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                },
                onDragEnd = {
                    val days = dragged
                    dragged = emptyList()
                    if (days.isNotEmpty()) {
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        paint?.invoke(days)
                    }
                },
                onDragCancel = { dragged = emptyList() },
            ) { change, _ ->
                change.consume()
                val d = dateAt(change.position)
                if (d != null && d !in dragged) {
                    dragged = dragged + d
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
        } finally {
            dragged = emptyList()
        }
    }
    Column(modifier.fillMaxWidth().then(painting)) {
        for (row in 0 until GRID_ROWS) {
            val rowStart = firstCell.plusWeeks(row.toLong())
            val week = remember(calculator, rowStart) { calculator.week(rowStart) }
            // [CAL-01] A week over the limit gets a bar at its left instead of a tint that mixes with the shifts' colors.
            val barColor = cal.warning
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(cellHeight)
                    .drawBehind {
                        if (week.overLimit) {
                            val inset = 6.dp.toPx()
                            drawRoundRect(
                                barColor,
                                topLeft = Offset(0f, inset),
                                size = Size(3.dp.toPx(), size.height - inset * 2),
                                cornerRadius = CornerRadius(1.5.dp.toPx()),
                            )
                        }
                    }
                    .padding(start = WEEK_BAR_SPACE),
            ) {
                for (col in 0 until 7) {
                    val date = rowStart.plusDays(col.toLong())
                    val day = remember(snapshot, date) { snapshot.resolver.resolve(date) }
                    val inMonth = date.year == month.year && date.month == month.month
                    DayCell(
                        day = day,
                        snapshot = snapshot,
                        inMonth = inMonth,
                        isToday = date == today,
                        isSelected = date == selected || date in dragged,
                        hasMemo = date in memoDates,
                        money = if (inMonth) cells.days[date] else null,
                        memoText = memoTexts[date],
                        showHours = showHours,
                        showLunar = showLunar,
                        onClick = { onDayClick(date) },
                        onQuickAction = { onQuickAction(date, it) },
                        quickMenu = quickMenu,
                        focus = focus,
                        showMoney = showMoney,
                        span = cells.spans[date],
                        startsHere = cells.starts[date] ?: 0,
                        sales = cells.sales[date],
                        heat = if (focus == CalendarFocus.SALES && inMonth) cells.heat(date) else 0f,
                        onLongPress = onLongPress?.let { f -> { f(date) } },
                        isRangeStart = date == rangeFrom,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        weekNote = if (col == 0 && week.overLimit) {
                            "이 주 ${HoursFormat.hours(week.totalMinutes.toDouble())}시간, ${weeklyLimitHours}시간 넘음"
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/**
 * [CAL-03] One week, Monday to Sunday, for the phone view that folds the month to the tapped date's week.
 * The same cells as the month, so a day looks and answers alike in both.
 */
@Composable
fun WeekStrip(
    monday: LocalDate,
    snapshot: ScheduleSnapshot,
    selected: LocalDate,
    today: LocalDate,
    memoDates: Set<LocalDate>,
    showHours: Boolean,
    showLunar: Boolean,
    cellHeight: Dp,
    onDayClick: (LocalDate) -> Unit,
    onQuickAction: (LocalDate, QuickAction) -> Unit,
    modifier: Modifier = Modifier,
    money: MoneyBook = MoneyBook.EMPTY,
    showMoney: Boolean = false,
    memoTexts: Map<LocalDate, String> = emptyMap(),
    focus: CalendarFocus = CalendarFocus.SHIFTS,
) {
    val month = YearMonth.from(selected)
    val cells = remember(money, monday) {
        MonthMoneyCells.of(money, listOf(YearMonth.from(monday), YearMonth.from(monday.plusDays(6))), monday, monday.plusDays(6))
    }
    Row(modifier.fillMaxWidth().height(cellHeight).padding(start = WEEK_BAR_SPACE)) {
        for (col in 0 until 7) {
            val date = monday.plusDays(col.toLong())
            val day = remember(snapshot, date) { snapshot.resolver.resolve(date) }
            DayCell(
                day = day,
                snapshot = snapshot,
                // Days of the next or last month in this week are dimmed as in the month.
                inMonth = YearMonth.from(date) == month,
                isToday = date == today,
                isSelected = date == selected,
                hasMemo = date in memoDates,
                money = cells.days[date],
                memoText = memoTexts[date],
                showHours = showHours,
                showLunar = showLunar,
                onClick = { onDayClick(date) },
                onQuickAction = { onQuickAction(date, it) },
                focus = focus,
                showMoney = showMoney,
                span = cells.spans[date],
                startsHere = cells.starts[date] ?: 0,
                sales = cells.sales[date],
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    day: ResolvedDay,
    snapshot: ScheduleSnapshot,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    hasMemo: Boolean,
    showHours: Boolean,
    showLunar: Boolean = false,
    money: DayMoney? = null,
    memoText: String? = null,
    onClick: () -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    modifier: Modifier = Modifier,
    weekNote: String? = null,
    quickMenu: Boolean = true,
    focus: CalendarFocus = CalendarFocus.SHIFTS,
    /** [WORK-02][WORK-03] The day's income under the date, for field and shop work. */
    showMoney: Boolean = false,
    span: JobSpan? = null,
    startsHere: Int = 0,
    sales: Long? = null,
    /** [CAL-06] A shop's day tinted by how strong its sales were. */
    heat: Float = 0f,
    onLongPress: (() -> Unit)? = null,
    isRangeStart: Boolean = false,
) {
    val cal = LocalCalendarColors.current
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    // [CAL-01] Date 14sp and the line under it 11sp, growing with the font setting only as far as the cell holds.
    val density = LocalDensity.current
    val dateSize = with(density) { minOf(14.sp.toDp(), 17.dp).toSp() }
    val footerSize = with(density) { minOf(11.sp.toDp(), 13.dp).toSp() }
    val date = day.date
    val type = day.shiftType
    val holidayName = KoreanHolidays.nameOf(date)
    val numberColor = when {
        // Public holidays are red like Sundays [FEAT-05].
        holidayName != null -> cal.sunday
        else -> when (date.dayOfWeek) {
        DayOfWeek.SUNDAY -> cal.sunday
        DayOfWeek.SATURDAY -> cal.saturday
        else -> scheme.onSurface
        }
    }
    // Working days are tinted with their shift's color so the month reads at a glance.
    // A 대근 on a day off tints the cell with the covered shift [EXTRA-01].
    val dark = isDarkSurface()
    val tint = (type?.takeIf { !it.isOff && day.leave?.isFullDay != true } ?: day.coveredShift)
        ?.colorArgb?.asColor()?.copy(alpha = if (dark) 0.22f else 0.13f)
        ?: cal.income.takeIf { heat > 0f }?.copy(alpha = heat * if (dark) 0.30f else 0.20f)
    val jobs = money?.jobs ?: 0
    // [VIS-01] What the cell shows, read out in the same order.
    val description = buildString {
        append(Fmt.dayTitle(date))
        if (isToday) append(", 오늘")
        holidayName?.let { append(", ").append(it) }
        // [CAL-05] A field or shop day without work is not a day off: it says what the day has, or that it is empty.
        val shiftWords = when {
            day.isWorkDay -> (type?.name ?: "근무") + " " + HoursFormat.korean(day.scheduledMinutes)
            type != null && (focus == CalendarFocus.SHIFTS || !type.isOff || jobs == 0 && sales == null) -> type.name
            focus == CalendarFocus.SHIFTS -> "쉬는 날"
            jobs > 0 || sales != null -> null
            else -> focus.emptyDay
        }
        shiftWords?.let { append(", ").append(it) }
        day.leave?.let { append(", ").append(it.label) }
        for (e in day.extras) {
            append(", ").append(ExtraText.name(e))
            if (e.entry.hasTimes || e.covered != null) append(" ").append(HoursFormat.korean(e.minutes))
        }
        if (day.source == DaySource.OVERRIDE || day.hasCustomTimes) append(", 직접 바꿈")
        // [VIS-02] Said the way the day screen says it.
        if (day.alarmSkipped) append(", 알람 꺼짐")
        if (hasMemo) append(", 메모 있음")
        span?.let { append(", ‘${it.title}’ ${it.days}일 중 ${it.nth}일째") }
        money?.let { m ->
            if (m.jobs > 0 && (span == null || m.jobs > 1)) append(", 일감 ${m.jobs}건")
            sales?.let { append(", 매출 ").append(Fmt.won(it)) }
            if (m.income != 0L && sales == null) append(", 수입 ").append(Fmt.won(m.income))
            if (m.unpaid > 0) append(", 받을 돈 ").append(Fmt.won(m.unpaid))
        }
        weekNote?.let { append(", ").append(it) }
    }

    Box(
        modifier
            .padding(1.5.dp)
            .clip(shape)
            .background(tint ?: Color.Transparent)
            .then(
                when {
                    // [CAL-09] The first day of a range stands out until its end is tapped.
                    isRangeStart -> Modifier.background(scheme.tertiary.copy(alpha = 0.16f)).border(2.5.dp, scheme.tertiary, shape)
                    // [CAL-06] The picked day: a ring and a light wash, clear on any shift color.
                    isSelected -> Modifier.background(scheme.primary.copy(alpha = 0.08f)).border(2.dp, scheme.primary, shape)
                    else -> Modifier
                },
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = when {
                    onLongPress != null -> ({
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    })
                    quickMenu -> ({
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    })
                    else -> null
                },
                onLongClickLabel = when {
                    onLongPress != null -> "여기부터 칠하기"
                    quickMenu -> "빠른 설정"
                    else -> null
                },
            )
            .semantics {
                contentDescription = description
                selected = isSelected
            }
            .alpha(if (inMonth) 1f else 0.35f),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 2.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.fillMaxWidth().height(20.dp)) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(if (isToday) scheme.primary else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge.merge(NumberStyle),
                        fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (isToday) scheme.onPrimary else numberColor,
                        fontSize = dateSize,
                        lineHeight = dateSize,
                    )
                }
                // [CAL-01] Two corners, filled in order: 알람 꺼짐 (always top left, so it is never pushed out),
                // then the kinds of extra work, a part-day leave, a day changed by hand. What does not fit
                // is still read out and shown under the calendar.
                val jobCorner = if (focus == CalendarFocus.SHIFTS && jobs > 0) (money?.unpaid ?: 0) > 0 || span?.owed == true else null
                val marks = remember(day, jobCorner) { cornerMarks(day, jobCorner) }
                val alarmOff = day.alarmSkipped
                val topEnd = marks.firstOrNull()
                val topStart = if (alarmOff) null else marks.getOrNull(1)
                topEnd?.let { Box(Modifier.align(Alignment.TopEnd)) { CornerMark(it) } }
                if (alarmOff) {
                    // [DAY-03] A day whose alarm was turned off says so, so a missed alarm is never a surprise.
                    Icon(Icons.Filled.AlarmOff, contentDescription = null, tint = cal.alarmOff,
                        modifier = Modifier.align(Alignment.TopStart).size(13.dp))
                }
                topStart?.let { Box(Modifier.align(Alignment.TopStart)) { CornerMark(it) } }
            }
            val fullLeave = day.leave?.takeIf { it.isFullDay }
            val covered = day.coveredShift
            val badgeKind = day.extras.firstOrNull()?.kind?.takeIf { !day.isWorkDay && covered == null && day.leave == null }
            // [CAL-05] For field and shop work a job outranks a day-off mark; a painted 휴무 is not the day's news.
            val jobFirst = focus != CalendarFocus.SHIFTS && jobs > 0 && (type == null || type.isOff) && !day.hasCustomTimes &&
                fullLeave == null && covered == null && badgeKind == null
            if (jobFirst) {
                JobMark(span = span, count = if (span != null && startsHere == 0) 0 else jobs, owed = (money?.unpaid ?: 0) > 0 || span?.owed == true && span.first)
            } else if (fullLeave != null) {
                // A whole day of leave shows the leave instead of the shift it replaced [FEAT-10].
                LeaveMark(fullLeave, size = 22.dp)
            } else if (covered != null && day.isWorkDay && type != null) {
                // [FEAT-02][EXTRA-01] The day's shift and the shift covered on top, labels only so both fit a folded cell.
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ShiftBadge(type = type, size = 18.dp, outlined = type.isOff, showIcon = false)
                    ShiftBadge(type = covered, size = 18.dp, showIcon = false)
                }
            } else if (covered != null) {
                // [EXTRA-01] A 대근 on a day off: the covered shift's badge.
                ShiftBadge(type = covered, size = 22.dp)
            } else if (badgeKind != null) {
                // [EXTRA-01] Other extra work on a day off (특근 09–18): the kind itself is the day's mark.
                ExtraKindMark(badgeKind, size = 22.dp)
            } else if (type != null || day.hasCustomTimes) {
                ShiftBadge(type = type, text = type?.label ?: "근", size = 22.dp, outlined = type?.isOff == true)
            } else {
                Spacer(Modifier.height(22.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                val extraMinutes = day.extraMinutes
                // [CAL-05] A shop's cell says its sales; field work says what came in.
                val moneyText = if (!showMoney) null else (sales?.takeIf { focus == CalendarFocus.SALES } ?: money?.income?.takeIf { it != 0L })
                    ?.let { Fmt.wonShort(it) }
                val footer = when {
                    memoText != null -> memoText
                    moneyText != null -> moneyText
                    extraMinutes > 0 -> "+" + HoursFormat.short(extraMinutes)
                    // [FEAT-05] Lunar date takes the place of the hours when turned on.
                    showLunar -> remember(date) { KoreanLunar.fromSolar(date).short }
                    showHours && day.scheduledMinutes > 0 -> HoursFormat.short(day.scheduledMinutes)
                    else -> ""
                }
                // [CAL-04] A red day says which day it is, unless the user wrote a memo there.
                if (holidayName != null && memoText == null) {
                    HolidayLabel(holidayName, footerSize, cal.sunday, footerSize * FOOTER_LINE, Modifier.weight(1f, fill = false))
                } else Text(
                    footer,
                    fontSize = footerSize,
                    lineHeight = footerSize * FOOTER_LINE,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    color = when {
                        memoText != null -> cal.memoDot
                        moneyText != null -> if ((sales ?: money?.income ?: 0L) < 0) cal.expense else cal.income
                        extraMinutes > 0 -> cal.holidayWork
                        else -> scheme.onSurfaceVariant
                    },
                    fontWeight = if (extraMinutes > 0 || moneyText != null) FontWeight.Bold else FontWeight.Normal,
                    style = NumberStyle,
                )
                if (hasMemo && memoText == null) {
                    if (footer.isNotEmpty() || holidayName != null) Spacer(Modifier.width(2.dp))
                    ColorDot(cal.memoDot, size = 5.dp)
                }
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            Text(
                Fmt.dayTitle(date),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            // [CAL-05] Field and shop work write the day's job or sales first.
            focus.addLabel?.let { label ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.AddMoney) },
                )
            }
            // [CAL-05] Shifts are offered where the calendar keeps them: always for shift work, and for field
            // or shop work only once a pattern or a painted day says it does.
            val usesShifts = focus == CalendarFocus.SHIFTS || snapshot.hasPattern || day.source == DaySource.OVERRIDE
            if (usesShifts && focus.addLabel != null) HorizontalDivider()
            if (usesShifts) for (t in snapshot.shiftTypes) {
                DropdownMenuItem(
                    text = { Text(t.name) },
                    leadingIcon = { ShiftBadge(t, size = 20.dp, outlined = t.isOff) },
                    trailingIcon = { if (type?.id == t.id) Icon(Icons.Filled.Check, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.Shift(ShiftOverride.Shift(t.id))) },
                )
            }
            if (day.source == DaySource.OVERRIDE) {
                DropdownMenuItem(
                    text = { Text("패턴대로") },
                    leadingIcon = { Icon(Icons.Filled.Autorenew, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.Shift(ShiftOverride.None)) },
                )
            }
            val choices = remember(snapshot, day, usesShifts) { if (usesShifts) ExtraText.choices(snapshot.kinds, snapshot.shiftTypes, day) else emptyList() }
            if (choices.isNotEmpty()) HorizontalDivider()
            for (c in choices) {
                val i = ExtraText.indexOf(day, c)
                DropdownMenuItem(
                    text = { Text(if (i >= 0) "${ExtraText.name(day.extras[i])} 빼기" else c.label) },
                    leadingIcon = { ExtraKindMark(c.kind, size = 20.dp) },
                    trailingIcon = { if (i >= 0) Icon(Icons.Filled.Check, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.Extra(c)) },
                )
            }
            val shift = type?.takeIf { it.hasTimes }
            if (shift != null || day.leave != null) {
                HorizontalDivider()
                for (q in listOf(4, 2, 1)) {
                    val l = Leave(LeaveKind.ANNUAL, q)
                    DropdownMenuItem(
                        text = { Text(if (day.leave == l) "${l.label} 해제" else l.label) },
                        leadingIcon = { LeaveMark(l, size = 20.dp) },
                        onClick = { menu = false; onQuickAction(QuickAction.SetLeave(if (day.leave == l) null else l)) },
                    )
                }
            }
            HorizontalDivider()
            if (day.isWorkDay || day.coveredShift != null || day.alarmSkipped) {
                DropdownMenuItem(
                    text = { Text(if (day.alarmSkipped) "이 날 알람 켜기" else "이 날 알람 끄기") },
                    leadingIcon = { Icon(if (day.alarmSkipped) Icons.Filled.Alarm else Icons.Filled.AlarmOff, null) },
                    onClick = { menu = false; onQuickAction(QuickAction.Alarm(!day.alarmSkipped)) },
                )
            }
            DropdownMenuItem(
                text = { Text("메모 쓰기") },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) },
                onClick = { menu = false; onQuickAction(QuickAction.Memo) },
            )
            DropdownMenuItem(
                text = { Text("자세히 열기") },
                leadingIcon = { Icon(Icons.Filled.OpenInFull, null) },
                onClick = { menu = false; onQuickAction(QuickAction.Open) },
            )
        }
    }
}

/**
 * [CAL-06] A job in a field worker's cell: the badge on the day it starts, and a bar that carries a
 * job over several days across them, so a three-day job reads as one piece of work.
 */
@Composable
private fun JobMark(span: JobSpan?, count: Int, owed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val cal = LocalCalendarColors.current
    val barColor = if (span?.owed == true) cal.warningContainer else scheme.secondaryContainer
    Box(
        Modifier
            .fillMaxWidth()
            .height(22.dp)
            .drawBehind {
                if (span == null) return@drawBehind
                val h = 8.dp.toPx()
                val reach = 4.dp.toPx() // across the cell's own padding, so the days join up
                val left = if (span.first) size.width / 2 else -reach
                val right = if (span.last) size.width / 2 else size.width + reach
                drawRoundRect(
                    barColor,
                    topLeft = Offset(left, (size.height - h) / 2),
                    size = Size(right - left, h),
                    cornerRadius = CornerRadius(h / 2),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (count > 0) JobBadge(count = count, owed = owed)
    }
}

@Composable
internal fun JobBadge(count: Int, owed: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (owed) LocalCalendarColors.current.warningContainer else scheme.secondaryContainer
    Box(
        Modifier.height(22.dp).clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 1) "일$count" else "일", fontSize = 11.sp, fontWeight = FontWeight.Bold,
            color = if (owed) LocalCalendarColors.current.warning else scheme.onSecondaryContainer, maxLines = 1)
    }
}

/** [CAL-01] What a cell's corners can show, most important first. */
internal sealed interface Corner {
    data class Kind(val kind: com.bamnat.core.model.ExtraKind) : Corner
    data class PartLeave(val leave: Leave) : Corner
    /** [CAL-06] A side job (부업) on a shift worker's day; amber while money is owed. */
    data class Job(val owed: Boolean) : Corner
    data object Changed : Corner
}

/** The corner marks of a day, in order; 알람 꺼짐 has its own place and is not among them. */
private fun cornerMarks(day: ResolvedDay, job: Boolean? = null): List<Corner> = buildList {
    job?.let { add(Corner.Job(it)) }
    // [EXTRA-01] On a day off whose only work is extra work, the first kind is the day's badge, not a corner.
    val kinds = day.extras.map { it.kind }.distinctBy { it.id }
    val kindIsBadge = !day.isWorkDay && day.coveredShift == null && kinds.isNotEmpty() && day.leave == null
    (if (kindIsBadge) kinds.drop(1) else kinds).forEach { add(Corner.Kind(it)) }
    day.leave?.takeIf { !it.isFullDay }?.let { add(Corner.PartLeave(it)) }
    if (day.source == DaySource.OVERRIDE || day.hasCustomTimes) add(Corner.Changed)
}

@Composable
internal fun CornerMark(mark: Corner) {
    when (mark) {
        is Corner.Kind -> ExtraKindMark(mark.kind, size = 13.dp)
        is Corner.PartLeave -> LeaveMark(mark.leave, size = 13.dp)
        is Corner.Job -> Icon(Icons.Filled.Work, contentDescription = null, modifier = Modifier.size(13.dp),
            tint = if (mark.owed) LocalCalendarColors.current.warning else MaterialTheme.colorScheme.secondary)
        // Days the user changed by hand.
        Corner.Changed -> Box(Modifier.padding(2.dp).size(5.dp).clip(CircleShape).background(LocalCalendarColors.current.changed))
    }
}
