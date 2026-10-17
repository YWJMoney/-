package com.bamnat.app.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.FeatureGate
import com.bamnat.app.tier.Tier
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.YearMonth

class StatsViewModel(container: AppContainer) : ViewModel() {
    val snapshot: StateFlow<ScheduleSnapshot?> =
        container.scheduleRepository.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<AppSettings> = container.settingsRepository.settings
    val payProfile: StateFlow<PayProfile?> = container.payStore.profile
    val tier: StateFlow<Tier> = container.featureGate.tier
    private val payCalculator = PayCalculator()

    fun payTotal(profile: PayProfile, snapshot: ScheduleSnapshot, month: YearMonth): Long =
        payCalculator.calculate(profile, month, snapshot.resolver::resolve).total
}

@Composable
fun StatsScreen(vm: StatsViewModel, wide: Boolean, onOpenPay: () -> Unit = {}) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val payProfile by vm.payProfile.collectAsStateWithLifecycle()
    val tier by vm.tier.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val month = remember(monthText) { YearMonth.parse(monthText) }

    val snap = snapshot
    if (snap == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val calc = remember(snap, settings.weekStart, settings.limits) {
        WorkHoursCalculator(snap.resolver, settings.weekStart, settings.limits)
    }
    val summary = remember(calc, month) { calc.month(month) }
    val three = remember(calc, month) { calc.threeMonths(month) }
    val trend = remember(calc, month) { (5 downTo 0).map { calc.month(month.minusMonths(it.toLong())) } }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("근무 통계", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { monthText = month.minusMonths(1).toString() }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 달")
            }
            Text(Fmt.month(month), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { monthText = month.plusMonths(1).toString() }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 달")
            }
        }

        // [EXTRA-01] The four tiles add up to the total: the roster, extra work on working days, work on days off.
        val tiles: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> StatTile("${month.monthValue}월 총 근무", HoursFormat.korean(summary.totalMinutes), m,
                caption = "근무 ${summary.workDays}일") },
            { m -> StatTile("근무표대로", HoursFormat.korean(summary.scheduledMinutes), m) },
            { m -> StatTile("근무일 추가 근무", HoursFormat.korean(summary.extraMinutes - summary.restDayExtraMinutes), m,
                caption = "잔업·조출 등") },
            { m -> StatTile("쉬는 날 근무", HoursFormat.korean(summary.restDayExtraMinutes), m,
                caption = "${summary.restDayWorkDays}일") },
        )
        val columns = if (wide) 4 else 2
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // [EXTRA-02] 주 52시간 (제53조), 64 under 탄력적 근로시간제; it does not apply under 5 employees.
        val profile = payProfile
        if (profile?.fiveOrMoreEmployees != false) {
            val legalHours = if (profile?.flexPeriod != null && profile.scheduleIsRegular) 64 else 52
            val overLegal = summary.weeks.filter { it.totalMinutes > legalHours * 60 }
            if (overLegal.isNotEmpty()) {
                WarningBanner(
                    "주 ${legalHours}시간을 넘은 주가 ${overLegal.size}번 있어요",
                    overLegal.joinToString(", ") { "${Fmt.shortDate(it.start)}주 ${HoursFormat.hours(it.totalMinutes.toDouble())}시간" } +
                        ". 연장근로는 한 주 12시간까지예요 (근로기준법 제53조). 참고용이에요.",
                )
            }
        }

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

        PayEntryCard(
            month = month,
            total = remember(payProfile, snap, month, tier) {
                payProfile?.takeIf { FeatureGate.allows(tier, Feature.PAY_ENGINE) }?.let { vm.payTotal(it, snap, month) }
            },
            hasProfile = payProfile != null,
            onClick = onOpenPay,
        )

        SectionCard(title = "3개월 평균 (${three.firstMonth.monthValue}월–${three.lastMonth.monthValue}월)") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(
                    "주 평균",
                    HoursFormat.hours(three.averageWeeklyMinutes) + "시간",
                    Modifier.weight(1f),
                    caption = "기준 ${settings.averageLimitHours}시간",
                    warning = three.overLimit,
                )
                StatTile(
                    "월 평균",
                    HoursFormat.hours(three.averageMonthlyMinutes) + "시간",
                    Modifier.weight(1f),
                    caption = "합계 ${HoursFormat.hours(three.totalMinutes.toDouble())}시간",
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
                    "평균 주 ${HoursFormat.hours(three.averageWeeklyMinutes)}시간이에요. 앞으로의 근무를 줄여야 평균이 내려가요.",
                )
            }
        }

        SectionCard(title = "주별 근무시간 (주 ${settings.weeklyLimitHours}시간 기준)") {
            val maxHours = maxOf(
                settings.weeklyLimitHours.toDouble(),
                summary.weeks.maxOfOrNull { it.totalMinutes / 60.0 } ?: 0.0,
            )
            summary.weeks.forEachIndexed { i, week ->
                WeekRow(week, settings.weeklyLimitHours, maxHours)
                if (i != summary.weeks.lastIndex) Spacer(Modifier.height(10.dp))
            }
            Text(
                "주는 ${weekStartLabel(settings.weekStart)}에 시작해요. 달에 걸친 주도 7일 전체를 셉니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        SectionCard(title = "최근 6개월") {
            MonthTrend(trend.map { it.month to it.totalMinutes })
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

private fun weekStartLabel(d: DayOfWeek) = if (d == DayOfWeek.SUNDAY) "일요일" else "월요일"

@Composable
private fun WeekRow(week: WeekSummary, limitHours: Int, maxHours: Double) {
    val cal = LocalCalendarColors.current
    val hours = week.totalMinutes / 60.0
    Column {
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
private fun MonthTrend(values: List<Pair<YearMonth, Int>>) {
    val max = (values.maxOfOrNull { it.second } ?: 0).coerceAtLeast(60)
    Row(
        Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, (m, minutes) ->
            val last = i == values.lastIndex
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    HoursFormat.decimalHours(minutes.toDouble()).substringBefore('.'),
                    style = MaterialTheme.typography.labelSmall.merge(NumberStyle),
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
                        hasProfile -> "${Tier.PRO.label}에서 금액이 보여요"
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
