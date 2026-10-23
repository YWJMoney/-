package com.bamnat.app.ui.stats

import com.bamnat.app.ui.guide.FeatureCard
import com.bamnat.app.appContainer
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.bamnat.app.AppContainer
import com.bamnat.app.data.AppSettings
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.Fmt
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.StatTile
import com.bamnat.app.ui.components.WarningBanner
import com.bamnat.app.ui.theme.LocalCalendarColors
import com.bamnat.app.ui.theme.NumberStyle
import com.bamnat.core.hours.HoursFormat
import com.bamnat.core.pay.PayCalculator
import com.bamnat.core.pay.PayProfile
import com.bamnat.core.hours.WeekSummary
import com.bamnat.core.hours.WorkHoursCalculator
import com.bamnat.core.money.MoneyBook
import com.bamnat.app.ui.money.wonBrief
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import com.bamnat.core.work.HourlyWeek
import com.bamnat.core.work.HourlyWork
import com.bamnat.app.ui.pay.TakeHomeLine
import com.bamnat.app.ui.pay.hourlyWeek
import com.bamnat.core.work.WorkStats
import com.bamnat.core.work.WorkStyle
import com.bamnat.core.work.words
import java.time.DayOfWeek
import java.time.YearMonth

class StatsViewModel(
    private val container: AppContainer,
    /** [MCAL-06] The calendar whose hours and pay these are: the one on the calendar tab. */
    val calendarId: String = com.bamnat.app.data.PersonalCalendar.MAIN,
) : ViewModel() {
    private val scope = container.scopeOf(calendarId)
    /** [NAV-01] The month shown here, the calendar's and 돈's too. */
    val shownMonth: StateFlow<YearMonth> = container.shownMonth.month
    fun showMonth(month: YearMonth) = container.shownMonth.show(month)
    val snapshot: StateFlow<ScheduleSnapshot?> =
        scope.schedule.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = scope.settings.stateIn(viewModelScope, SharingStarted.Eagerly, scope.currentSettings())
    val payProfile: StateFlow<PayProfile?> = scope.pay.profile
    private val payCalculator = PayCalculator()

    fun payTotal(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): Long =
        payCalculator.calculate(profile.hourlyWeek(settings.value), month, snapshot.resolver::resolve).total

    /** [PROV-02] A month's pay with its minutes, for the comparison cards; null when it cannot be worked out. */
    fun monthPay(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): com.bamnat.core.pay.MonthPay? =
        runCatching { payCalculator.calculate(profile.hourlyWeek(settings.value), month, snapshot.resolver::resolve, checks = false) }.getOrNull()

    /** [PROV-02] Whether the comparison cards are open. */
    val gate: StateFlow<com.bamnat.app.tier.Gate> = container.featureGate.state

    /** [CUST-01] 화면 꾸미기 changes the app's own settings. */
    fun updateSettings(change: (AppSettings) -> AppSettings) = container.settingsRepository.update(change)

    /** [LEDG-15] The calendar's money: field work counts its jobs here and a shop its sales, not hours. */
    val book: StateFlow<MoneyBook?> = scope.money.book.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun StatsScreen(vm: StatsViewModel, wide: Boolean, onOpenPay: () -> Unit = {}) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val payProfile by vm.payProfile.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // [NAV-01] The same month as the calendar and 돈.
    val month by vm.shownMonth.collectAsStateWithLifecycle()
    // [NAV-01] Work paid by the hour or the shift reads its pay here; field and shop work have 돈 for it.
    val pays = settings.workStyle?.usesPayEngine != false
    val style = settings.workStyle
    val book by vm.book.collectAsStateWithLifecycle()
    val gate by vm.gate.collectAsStateWithLifecycle()

    val snap = snapshot
    if (snap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    // [WEEK-01] Monday to Sunday, whatever day the calendar's rows start on.
    val calc = remember(snap, settings.limits) {
        WorkHoursCalculator(snap.resolver, DayOfWeek.MONDAY, settings.limits)
    }
    val summary = remember(calc, month) { calc.month(month) }
    val three = remember(calc, month) { calc.threeMonths(month) }
    val trend = remember(calc, month) { (5 downTo 0).map { calc.month(month.minusMonths(it.toLong())) } }
    // [LEDG-15] Field and shop work show their jobs or sales; hours only once some are on the calendar.
    val hasHours = pays || summary.totalMinutes > 0 || three.totalMinutes > 0
    val columns = if (wide) 4 else 2
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val statsParts = androidx.compose.runtime.remember(settings.statsLayout) { statsParts(settings.statsLayout) }
    fun shown(p: StatsPart) = statsParts.firstOrNull { it.part == p }?.shown != false

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // [CAL-21] A phone (or large text) puts the title on a line of its own, so it never wraps beside the month.
        val narrow = androidx.compose.ui.platform.LocalConfiguration.current.let { it.screenWidthDp / it.fontScale < 420 }
        if (narrow) {
            Text(style.words().statsTitle, style = MaterialTheme.typography.headlineSmall, maxLines = 1,
                modifier = Modifier.semantics { heading() })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // [TYPE-01] The tab's own words: 출근·시급, 일감·수입, 매출·영업.
            if (narrow) Spacer(Modifier.weight(1f)) else Text(
                style.words().statsTitle,
                style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.showMonth(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달")
            }
            Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { vm.showMonth(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달")
            }
            // [CUST-01] 화면 꾸미기: the cards' order and which show.
            IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Dashboard, "화면 꾸미기") }
        }
        if (editing) StatsEditSheet(settings, vm::updateSettings, onDismiss = { editing = false })
        // [MCAL-06] Whose hours and pay these are, once there are several calendars; [COMN-03] or all of them.
        var everyCalendar by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        com.bamnat.app.ui.calendar.CalendarScopeChips(androidx.compose.ui.platform.LocalContext.current.appContainer,
            all = everyCalendar, onAll = { everyCalendar = it })
        if (everyCalendar && AllCalendarsStats(month)) return@Column

        val money = book
        if (money != null && style?.usesJobs == true) JobFigures(money, month, columns)
        if (money != null && style == WorkStyle.FIELD) FieldStatsCards(money, month, calendarId = vm.calendarId)
        // [FLD-11][FLD-13] 일용직 세금과 견적서.
        if (money != null && style == WorkStyle.FIELD) com.bamnat.app.ui.work.FieldToolCards(vm.calendarId, money, month)
        if (money != null && style?.usesSales == true) SalesFigures(money, month, columns) { snap.resolver.resolve(it).isWorkDay }
        if (money != null && style == WorkStyle.BUSINESS) ShopStatsCards(vm.calendarId, money, month, { snap.resolver.resolve(it).isWorkDay })
        // [SHOP-09][SHOP-10][SHOP-11] 직원 인건비, 날씨별 매출, 간이과세 기준.
        if (money != null && style == WorkStyle.BUSINESS) com.bamnat.app.ui.work.ShopToolCards(vm.calendarId, money, month)
        if (!hasHours) {
            Text("달력에 근무 시간을 넣으면 주별 근무시간과 3개월 평균도 여기서 볼 수 있어요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
            return@Column
        }

        // [NAV-01] The month's pay, worked out once for the pay card and the work type's cards.
        // [STAB-01] A month of pay is worked out off the main thread, and an odd profile shows no total
        // instead of closing the app.
        // [MON-01] The month's pay is free.
        val payTotal = produceState<Long?>(null, payProfile, snap, month) {
            value = payProfile?.takeIf { pays }?.let { p ->
                runCatching { withContext(Dispatchers.Default) { vm.payTotal(p, snap, month) } }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
            }
        }.value
        // [SHFT-02][DAYW-02] A shift or day worker's first card.
        WorkTypeFirstCard(style, vm.calendarId, snap, month, summary, payTotal, payProfile, columns)

        // [EXTRA-01] The four tiles add up to the total: the roster, extra work on working days, work on days off.
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> StatTile("${month.monthValue}월 총 근무", HoursFormat.korean(summary.totalMinutes), m,
                caption = "근무 ${summary.workDays}일") },
            // [SETUP-11] Says what it leaves out, like the other three tiles do.
            { m -> StatTile("근무표대로", HoursFormat.korean(summary.scheduledMinutes), m, caption = "추가 근무 빼고") },
            { m -> StatTile("근무일 추가 근무", HoursFormat.korean(summary.extraMinutes - summary.restDayExtraMinutes), m,
                caption = "잔업·조출 등") },
            { m -> StatTile("쉬는 날 근무", HoursFormat.korean(summary.restDayExtraMinutes), m,
                caption = "${summary.restDayWorkDays}일") },
        )
        if (shown(StatsPart.TILES)) TileGrid(tiles, columns)

        // [TYPE-06] A part-timer's weeks against the 15 hours 주휴수당 needs.
        // [HRLY-02][HRLY-07] Counted from the 주휴 week's own start, with 결근 days as absences.
        if (style == WorkStyle.HOURLY) {
            val weekStart = payProfile?.holidayWeekFrom ?: DayOfWeek.MONDAY
            val weeks = remember(snap, month, weekStart, payProfile?.legalBreaks) {
                HourlyWork.weeks(month, weekStart, snap.copy(legalBreaks = payProfile?.legalBreaks == true).resolver::resolve)
            }
            // [GUIDE-05]
            FeatureCard("hourly.weeks", vm.calendarId) { HolidayPayWeeks(weeks, month) }
        }

        // [NAV-01] 근무·급여: the month's pay right under its hours.
        if (pays && shown(StatsPart.PAY)) {
            val total = payTotal
            PayEntryCard(
                month = month,
                total = total,
                hasProfile = payProfile != null,
                onClick = onOpenPay,
            )
            // [HRLY-04] 예상 실수령 next to the pay before tax.
            if (style == WorkStyle.HOURLY && total != null) payProfile?.let { TakeHomeLine(total, it.taxMode, Modifier.padding(start = 4.dp), on = month.atDay(1)) }
        }
        // [HRLY-09][HRLY-10][HRLY-11] 두 알바 비교, 근로계약서, 그만두는 날 정리.
        if (style == WorkStyle.HOURLY) FeatureCard("hourly.tools", vm.calendarId) { com.bamnat.app.ui.work.HourlyToolsCard(vm.calendarId, payProfile, snap) }
        // [SHFT-03][SHFT-04][DAYW-04][DAYW-05] Swaps, pay that came in, the 시간외 상한 and 징검다리 연휴.
        WorkTypeMoreCards(style, vm.calendarId, snap, month, summary, payTotal, payProfile, book)

        // [EXTRA-02] 주 52시간 (제53조), 64 under 탄력적 근로시간제; it does not apply under 5 employees.
        val profile = payProfile
        if (profile?.fiveOrMoreEmployees != false) {
            val legalHours = if (profile?.flexPeriod != null && profile.scheduleIsRegular) 64 else 52
            val overLegal = summary.weeks.filter { it.totalMinutes > legalHours * 60 }
            if (overLegal.isNotEmpty()) {
                WarningBanner(
                    "주 ${legalHours}시간을 넘은 주가 ${overLegal.size}번 있어요",
                    overLegal.joinToString(", ") { "${Fmt.shortDate(it.start)}주 ${HoursFormat.korean(it.totalMinutes)}" } +
                        ". 연장근로는 한 주 12시간까지예요 (근로기준법 제53조). 참고용이에요.",
                )
            }
        }

        // [CUST-01] The cards in the user's order (화면 꾸미기).
        statsParts.filter { it.part.movable && it.shown }.forEach { placed ->
            when (placed.part) {
                StatsPart.KINDS -> {
                if (summary.extraByKind.isNotEmpty()) {
                    SectionCard(title = "추가 근무 종류별") {
                        val kinds = settings.extraKinds
                        for ((id, minutes) in summary.extraByKind.entries.sortedByDescending { it.value }) {
                            val kind = kinds.of(id)
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                com.bamnat.app.ui.calendar.ExtraKindMark(kind, size = 20.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(kind.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                Text("${summary.extraCountByKind[id] ?: 0}번 · ", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(HoursFormat.korean(minutes), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                            }
                        }
                    }
                }

                }
                StatsPart.THREE -> {
                SectionCard(title = "3개월 평균 (${three.firstMonth.monthValue}월–${three.lastMonth.monthValue}월)") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(
                            "주 평균",
                            HoursFormat.korean(Math.round(three.averageWeeklyMinutes).toInt()),
                            Modifier.weight(1f),
                            caption = "기준 ${settings.averageLimitHours}시간",
                            warning = three.overLimit,
                        )
                        StatTile(
                            "월 평균",
                            HoursFormat.korean(Math.round(three.averageMonthlyMinutes).toInt()),
                            Modifier.weight(1f),
                            caption = "합계 ${HoursFormat.korean(three.totalMinutes)}",
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LimitBar(
                        value = three.averageWeeklyMinutes / 60.0,
                        limit = settings.averageLimitHours.toDouble(),
                        max = maxOf(settings.weeklyLimitHours.toDouble(), three.averageWeeklyMinutes / 60.0),
                    )
                    if (three.overLimit) {
                        Spacer(Modifier.height(10.dp))
                        WarningBanner(
                            "3개월 평균 주 ${settings.averageLimitHours}시간 초과",
                            "평균 주 ${HoursFormat.korean(Math.round(three.averageWeeklyMinutes).toInt())}이에요. 앞으로의 근무를 줄여야 평균이 내려가요.",
                        )
                    }
                }

                }
                StatsPart.WEEKS -> {
                SectionCard(title = "주별 근무시간 (주 ${settings.weeklyLimitHours}시간 기준)") {
                    val maxHours = maxOf(
                        settings.weeklyLimitHours.toDouble(),
                        summary.weeks.maxOfOrNull { it.totalMinutes / 60.0 } ?: 0.0,
                    )
                    summary.weeks.forEachIndexed { i, week ->
                        // [DAILY-07] A week's bar opens that week on the calendar, its Monday chosen.
                        WeekRow(week, settings.weeklyLimitHours, maxHours) {
                            com.bamnat.app.NoticeLinks.open(com.bamnat.app.NoticeLinks.Target.DAY, vm.calendarId, week.start.toString())
                        }
                        if (i != summary.weeks.lastIndex) Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        "주 근무시간은 월요일부터 일요일까지 세요(달력이 일요일부터 보여도 같아요). 달에 걸친 주도 7일 전체를 셉니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }

                }
                StatsPart.TREND -> {
                SectionCard(title = "최근 6개월") {
                    MonthTrend(trend.map { it.month to it.totalMinutes.toLong() }, floor = 60) { HoursFormat.decimalHours(it.toDouble()).substringBefore('.') }
                }

                }
                StatsPart.COMPARE -> CompareCard(vm, calc, snap, month, payProfile?.takeIf { pays }, gate)
                StatsPart.YEAR -> YearWorkCard(vm, calc, snap, month, payProfile?.takeIf { pays }, gate)
                else -> Unit
            }
        }

        Text(
            "· 야간처럼 자정을 넘기는 근무는 시작한 날에 모두 셉니다.\n" +
                "· 휴게 시간은 빼고, 잔업·특근·대근 같은 추가 근무는 더해서 계산해요.\n" +
                "· 3개월 평균은 해당 3개월의 총 근무시간 ÷ (일수 ÷ 7)이에요.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}


@Composable
private fun WeekRow(week: WeekSummary, limitHours: Int, maxHours: Double, onOpen: () -> Unit) {
    val cal = LocalCalendarColors.current
    val hours = week.totalMinutes / 60.0
    Column(Modifier.clickable(onClickLabel = "달력에서 이 주 보기", onClick = onOpen).padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Fmt.shortDate(week.start)}–${Fmt.shortDate(week.end)}",
                style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                modifier = Modifier.weight(1f),
            )
            if (week.extraMinutes > 0) {
                Text(
                    "추가 ${HoursFormat.short(week.extraMinutes)}  ",
                    style = MaterialTheme.typography.labelSmall.merge(NumberStyle),
                    color = cal.holidayWork,
                )
            }
            Text(
                HoursFormat.korean(week.totalMinutes),
                style = MaterialTheme.typography.titleSmall.merge(NumberStyle),
                color = if (week.overLimit) cal.warning else MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        LimitBar(value = hours, limit = limitHours.toDouble(), max = maxHours)
    }
}

/** Horizontal bar with a marker at the legal limit; turns red past it. */
@Composable
private fun LimitBar(value: Double, limit: Double, max: Double) {
    val cal = LocalCalendarColors.current
    val over = value > limit
    val safeMax = if (max <= 0.0) 1.0 else max
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val fraction = (value / safeMax).coerceIn(0.0, 1.0).toFloat()
        Box(
            Modifier
                .fillMaxHeight()
                .width(maxWidth * fraction)
                .background(if (over) cal.warning else MaterialTheme.colorScheme.primary),
        )
        val limitFraction = (limit / safeMax).coerceIn(0.0, 1.0).toFloat()
        Box(
            Modifier
                .padding(start = (maxWidth * limitFraction - 1.dp).coerceAtLeast(0.dp))
                .width(2.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)),
        )
    }
}

@Composable
private fun MonthTrend(values: List<Pair<YearMonth, Long>>, floor: Long, label: (Long) -> String) {
    val max = (values.maxOfOrNull { it.second } ?: 0).coerceAtLeast(floor)
    Row(
        Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, (m, minutes) ->
            val last = i == values.lastIndex
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (minutes > 0) label(minutes) else "",
                    style = MaterialTheme.typography.labelSmall.merge(NumberStyle), maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .widthIn(max = 36.dp)
                            .fillMaxWidth()
                            .fillMaxHeight((minutes.toFloat() / max).coerceIn(0.02f, 1f))
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(
                                if (last) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            ),
                    )
                }
                Text(Fmt.monthShort(m), style = MaterialTheme.typography.labelSmall,
                    color = if (last) MaterialTheme.colorScheme.primary else Color.Unspecified)
            }
        }
    }
}

/** Entry to 급여 계산 [PAYX-06]: the month's estimate when set up and unlocked, otherwise an invitation. */
@Composable
private fun PayEntryCard(month: YearMonth, total: Long?, hasProfile: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${month.monthValue}월 예상 급여 (세전)", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    when {
                        total != null -> "%,d원".format(total)
                        hasProfile -> "계산하는 중이에요"
                        else -> "근무표로 급여 계산하기"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    if (hasProfile) "누르면 항목별 계산 풀이를 볼 수 있어요" else "직군을 고르고 기본급만 넣으면 연장·야간·휴일까지 계산해요",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Tiles [columns] to a row; a short last row keeps the tiles' width. */
@Composable
private fun TileGrid(tiles: List<@Composable (Modifier) -> Unit>, columns: Int) {
    tiles.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { tile -> tile(Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/**
 * [LEDG-15][WORK-02] Field work's month in jobs: how many, what they earned, what is still owed and
 * what is left after costs, and six months of earnings. Words match the calendar's month summary.
 */
@Composable
private fun JobFigures(book: MoneyBook, month: YearMonth, columns: Int) {
    val m = remember(book, month) { book.month(month) }
    val days = remember(book, month) {
        book.jobs.filter { YearMonth.from(it.date) == month && !it.cancelled }
            .flatMap { j -> (0 until j.days).map { j.date.plusDays(it.toLong()) } }
            .filter { YearMonth.from(it) == month }.distinct().size
    }
    val trend = remember(book, month) { (5 downTo 0).map { month.minusMonths(it.toLong()).let { mm -> mm to book.month(mm).jobEarned } } }
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { md -> StatTile("${month.monthValue}월 일감", "${m.jobCount}건", md, caption = "${days}일 일해요") },
        { md -> StatTile("번 돈", Fmt.won(m.jobEarned), md, caption = m.jobReceived.takeIf { it > 0 }?.let { "받음 ${wonBrief(it)}" }) },
        { md -> StatTile("받을 돈", Fmt.won(m.jobOwed), md, warning = m.jobOwed > 0) },
        { md -> StatTile("남은 돈", Fmt.won(m.jobProfit), md, caption = m.jobCosts.takeIf { it > 0 }?.let { "경비 ${wonBrief(it)}" }) },
    )
    TileGrid(tiles, columns)
    // [TYPE-07] Who the month's work came from and who still owes.
    val clients = remember(book, month) { WorkStats.clients(month, book.jobs) }
    // [FLD-05] A client's row opens 거래내역서 보내기.
    var statement by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    statement?.let { StatementDialog(it, month, book.jobs) { statement = null } }
    if (clients.isNotEmpty()) {
        SectionCard(title = "거래처별") {
            clients.take(8).forEach { c ->
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "거래내역서 보내기") { statement = c.client }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.client, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        Text("${c.jobs}건" + (if (c.owed > 0) " · 받을 돈 ${wonBrief(c.owed)}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.owed > 0) LocalCalendarColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(Fmt.won(c.earned), style = MaterialTheme.typography.titleSmall.merge(NumberStyle))
                }
            }
            Text("거래처를 누르면 거래내역서를 사진으로 보낼 수 있어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.jobEarned > 0) {
                Text("3.3% 떼고 받으면 ${Fmt.won(m.jobEarned - m.jobEarned * 33 / 1000)} · 5월 종합소득세 때 돌려받을 수 있어요",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (trend.any { it.second > 0 }) {
        SectionCard(title = "최근 6개월 번 돈") { MonthTrend(trend, floor = 1) { wonBrief(it) } }
    }
}

/**
 * [LEDG-15][WORK-03][TYPE-05] A shop's month in sales: the total, costs, what is left, a typical open day,
 * open and closed days, each weekday's average and six months of sales.
 */
@Composable
private fun SalesFigures(book: MoneyBook, month: YearMonth, columns: Int, isOpen: (java.time.LocalDate) -> Boolean) {
    val m = remember(book, month) { book.month(month) }
    val shop = remember(book, month, isOpen) { WorkStats.shop(month, book.sales, isOpen) }
    val trend = remember(book, month) { (5 downTo 0).map { month.minusMonths(it.toLong()).let { mm -> mm to book.month(mm).sales } } }
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { md -> StatTile("${month.monthValue}월 매출", Fmt.won(m.sales), md, caption = "${m.salesDays}일 적음") },
        { md -> StatTile("경비", Fmt.won(m.salesCosts), md) },
        { md -> StatTile("남은 돈", Fmt.won(m.salesNet), md, warning = m.salesNet < 0) },
        { md -> StatTile("영업일 평균", Fmt.won(shop.perOpenDay), md,
            caption = shop.best?.let { "최고 ${it.date.monthValue}/${it.date.dayOfMonth} ${wonBrief(it.sales)}" }) },
        { md -> StatTile("영업일", "${shop.openDays}일", md, caption = "휴무 ${shop.closedDays}일") },
        { md -> StatTile("가장 적은 날", shop.worst?.let { wonBrief(it.sales) } ?: "-", md,
            caption = shop.worst?.let { "${it.date.monthValue}/${it.date.dayOfMonth} ${WEEKDAY[it.date.dayOfWeek.ordinal]}" }) },
    )
    TileGrid(tiles, columns)
    if (shop.salesDays > 0) {
        SectionCard(title = "요일별 평균 매출") {
            val max = (shop.byWeekday.values.maxOrNull() ?: 0).coerceAtLeast(1)
            Row(
                Modifier.fillMaxWidth().height(120.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                DayOfWeek.entries.forEach { d ->
                    val v = shop.byWeekday[d] ?: 0
                    val best = d == shop.bestWeekday
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (v > 0) wonBrief(v) else "", style = MaterialTheme.typography.labelSmall.merge(NumberStyle),
                            maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(
                                Modifier.widthIn(max = 32.dp).fillMaxWidth()
                                    .fillMaxHeight((v.toFloat() / max).coerceIn(0.02f, 1f))
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .background(if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            )
                        }
                        Text(WEEKDAY[d.ordinal], style = MaterialTheme.typography.labelSmall,
                            color = if (best) MaterialTheme.colorScheme.primary else Color.Unspecified)
                    }
                }
            }
            shop.bestWeekday?.let {
                Text("${WEEKDAY[it.ordinal]}요일이 평균 매출이 가장 높아요. 휴무일과 매출 없는 날은 평균에서 빼요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
    if (trend.any { it.second > 0 }) {
        SectionCard(title = "최근 6개월 매출") { MonthTrend(trend, floor = 1) { wonBrief(it) } }
    }
}

private val WEEKDAY = listOf("월", "화", "수", "목", "금", "토", "일")

/** [TYPE-06] Each week's hours with whether it reaches 주휴수당 (15 hours a week, 근로기준법 제55조·제18조). */
@Composable
private fun HolidayPayWeeks(weeks: List<HourlyWeek>, month: YearMonth) {
    val cal = LocalCalendarColors.current
    SectionCard(title = "주휴수당 (주 15시간 이상)") {
        weeks.forEach { w ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${Fmt.shortDate(w.start)}주", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle), modifier = Modifier.weight(1f))
                Text(HoursFormat.korean(w.minutes) + "  ", style = MaterialTheme.typography.bodyMedium.merge(NumberStyle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        w.holidayPay -> "주휴 ${HoursFormat.korean(w.holidayPayMinutes)}"
                        // [HRLY-02] 지각·조퇴는 개근을 깨지 않지만 결근은 깨요.
                        w.absent > 0 -> "결근 ${w.absent}일, 주휴 없음"
                        w.minutes == 0 -> "-"
                        else -> "${HoursFormat.korean(w.shortBy)} 모자람"
                    },
                    style = MaterialTheme.typography.titleSmall.merge(NumberStyle),
                    color = if (w.holidayPay) MaterialTheme.colorScheme.primary else cal.warning.takeIf { w.minutes > 0 } ?: Color.Unspecified,
                )
            }
        }
        val paid = weeks.count { it.holidayPay }
        Text(
            "${month.monthValue}월에 걸친 주 ${weeks.size}번 중 ${paid}번 주휴수당을 받을 수 있어요. 주휴 시간은 주 근무시간 ÷ 40 × 8 (최대 8시간)이고, 결근한 주는 빠져요. 참고용이에요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** [CUST-01] The 근무·급여 page's cards the user can order (the hour cards) or only turn off (the month's tiles, pay). */
internal enum class StatsPart(val id: String, val label: String, val what: String, val movable: Boolean = true) {
    TILES("tiles", "이번 달 숫자 네 칸", "총 근무·근무표대로·추가 근무·쉬는 날 근무", movable = false),
    PAY("pay", "급여 카드", "이번 달 예상 급여와 계산 풀이", movable = false),
    KINDS("kinds", "추가 근무 종류별", "잔업·특근·대근마다 횟수와 시간"),
    THREE("three", "3개월 평균", "3개월 주평균과 52시간 기준"),
    WEEKS("weeks", "주별 근무시간", "이번 달 주마다(월~일) 시간 막대"),
    TREND("trend", "최근 6개월", "달마다 근무시간 흐름"),
    /** [PROV-02] 프로. */
    COMPARE("compare", "지난달·작년 비교", "근무시간·야간·휴일·추가 근무·급여를 지난달·작년 같은 달과"),
    YEAR("year", "올해 근무·급여", "달마다 근무시간과 급여, 한 해 합계"),
    ;
}

internal fun statsParts(layout: String) = com.bamnat.core.layout.PageLayout.decode(layout).arrange(StatsPart.entries.toList()) { it.id }

@Composable
private fun StatsEditSheet(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, onDismiss: () -> Unit) {
    val placed = statsParts(settings.statsLayout)
    fun write(next: List<com.bamnat.core.layout.PageLayout.Placed<StatsPart>>) =
        update { it.copy(statsLayout = com.bamnat.core.layout.PageLayout.of(next) { p -> p.id }.encode()) }
    val rows = { movable: Boolean ->
        placed.filter { it.part.movable == movable }.map { com.bamnat.app.ui.components.EditRow(it.part.id, it.part.label, it.part.what, it.shown, movable, it.isNew) }
    }
    com.bamnat.app.ui.components.PageEditSheet(
        title = "근무·급여 화면 꾸미기",
        groups = listOf(
            com.bamnat.app.ui.components.EditGroup("위쪽 (이번 달)", rows(false)),
            com.bamnat.app.ui.components.EditGroup("아래쪽 카드 순서", rows(true)),
        ),
        middle = "일하는 방식 카드", // [SET-05]
        onShow = { id, shown -> write(placed.map { if (it.part.id == id) it.copy(shown = shown) else it }) },
        onMove = { id, by ->
            val fixed = placed.filterNot { it.part.movable }
            val movable = placed.filter { it.part.movable }
            write(fixed + com.bamnat.core.layout.PageLayout.move(movable, movable.indexOfFirst { it.part.id == id }, by))
        },
        onReset = { update { it.copy(statsLayout = "") } },
        onAsk = {
            onDismiss()
            com.bamnat.app.ui.settings.InquiryLinks.ask(com.bamnat.app.ui.settings.InquiryStart(about = "화면 꾸미기"))
        },
        onDismiss = onDismiss,
    )
}
