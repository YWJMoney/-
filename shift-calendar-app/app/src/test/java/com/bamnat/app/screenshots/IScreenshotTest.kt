package com.bamnat.app.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.calendar.CoverPanel
import com.bamnat.app.ui.calendar.MonthPickerDialog
import com.bamnat.app.ui.calendar.MonthRecapCard
import com.bamnat.app.ui.calendar.MonthSummaryBar
import com.bamnat.app.ui.calendar.QuickTextOffer
import com.bamnat.app.ui.calendar.RealignDialog
import com.bamnat.app.ui.shifts.PatternPreview
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.TeamRoster
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.MonthOverview
import com.bamnat.core.work.MonthRecap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Bundle I's screenshots, numbers 540~569 (계획/1.23-I-달력-위젯-워치-개발계획.md): the calendar's new dialogs and
 * cards, the swap helper and the weekly pattern's preview, each folded, unfolded and at 200% text. The widgets are
 * Glance and are not drawn by Robolectric; their content is checked in WidgetData's tests instead.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IScreenshotTest : ScreenshotBase() {
    private val start = LocalDate.of(2026, 7, 1)

    /** After the shared seed: a 4조 2교대 order (주야휴휴), so 맞바꾸기 has days to offer, and two days set by hand. */
    @Before
    fun seedI() = runBlocking<Unit> {
        val repo = app.container.scheduleRepository
        val types = repo.shiftTypes.first().associateBy { it.name }
        val day = types.getValue("주간").id
        val night = types.getValue("야간").id
        val off = types.getValue("휴무").id
        repo.savePattern(WorkPattern.Rotation(start, listOf(day, night, off, off)))
        // Two days set by hand ahead, the usual workaround for an order that slipped.
        repo.setOverride(today.plusDays(4), ShiftOverride.Shift(night))
        repo.setOverride(today.plusDays(9), ShiftOverride.Shift(day))
    }

    // [SCHED-03] 이 날부터 순서 다시 맞추기.
    @Test fun realignFolded() = shot("540_i_realign_folded", FOLDED) { Realign() }
    @Test fun realignUnfolded() = shot("541_i_realign_unfolded", UNFOLDED) { Realign() }
    @Test fun realignFoldedBigFont() = shot("542_i_realign_folded_200", FOLDED, fontScale = 2f) { Realign() }
    @Test fun realignFoldedDark() = shot("543_i_realign_folded_dark", FOLDED_NIGHT) { Realign() }

    // [CAL-35][CAL-46][CAL-43] The summary against last month, the month-end card and a typed line's offer.
    @Test fun cardsFolded() = shot("544_i_cards_folded", FOLDED) { Cards() }
    @Test fun cardsUnfolded() = shot("545_i_cards_unfolded", UNFOLDED) { Cards() }
    @Test fun cardsFoldedBigFont() = shot("546_i_cards_folded_200", FOLDED, fontScale = 2f) { Cards() }
    @Test fun cardsFoldedDark() = shot("547_i_cards_folded_dark", FOLDED_NIGHT) { Cards() }

    // [CAL-40] The month picker in rows that wrap at large text.
    @Test fun monthPickerFolded() = shot("548_i_month_picker_folded", FOLDED) { Picker() }
    @Test fun monthPickerUnfolded() = shot("549_i_month_picker_unfolded", UNFOLDED) { Picker() }
    @Test fun monthPickerFoldedBigFont() = shot("550_i_month_picker_folded_200", FOLDED, fontScale = 2f) { Picker() }

    // [SCHED-07] 대근 찾기 with 맞바꾸기 under it.
    @Test fun swapFolded() = shot("551_i_swap_folded", FOLDED) { Swap() }
    @Test fun swapUnfolded() = shot("552_i_swap_unfolded", UNFOLDED) { Swap() }
    @Test fun swapFoldedBigFont() = shot("553_i_swap_folded_200", FOLDED, fontScale = 2f) { Swap() }
    @Test fun swapFoldedDark() = shot("554_i_swap_folded_dark", FOLDED_NIGHT) { Swap() }

    // [SCHED-04][SCHED-06] A weekly pattern with 공휴일엔 쉬어요 and 격주 토요일, previewed.
    @Test fun weeklyFolded() = shot("555_i_weekly_preview_folded", FOLDED) { Weekly() }
    @Test fun weeklyUnfolded() = shot("556_i_weekly_preview_unfolded", UNFOLDED) { Weekly() }
    @Test fun weeklyFoldedBigFont() = shot("557_i_weekly_preview_folded_200", FOLDED, fontScale = 2f) { Weekly() }

    @Composable
    private fun WithSnapshot(body: @Composable (ScheduleSnapshot) -> Unit) {
        val snap by app.container.scheduleRepository.snapshot.collectAsState(null)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            snap?.let { body(it) }
        }
    }

    @Composable
    private fun Realign() = WithSnapshot { snap -> RealignDialog(today, snap, onDismiss = {}, onConfirm = { _, _ -> }) }

    @Composable
    private fun Cards() = WithSnapshot { snap ->
        val month = YearMonth.from(today)
        val overview = MonthOverview.of(month, CalendarFocus.SHIFTS, snap.resolver)
        val last = month.minusMonths(1)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MonthSummaryBar(overview, payEstimate = 3_120_000, onOpen = {},
                versus = overview.versus(MonthOverview.of(last, CalendarFocus.SHIFTS, snap.resolver)))
            MonthRecapCard(MonthRecap.of(last, snap.resolver.resolveRange(last.atDay(1), last.atEndOfMonth())), onShare = {}, onClose = {})
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("빠른 메모: 내일 야간 대근 7시", style = MaterialTheme.typography.bodyMedium)
                QuickTextOffer("${today.plusDays(1).monthValue}/${today.plusDays(1).dayOfMonth} 야간 대근 19:00부터", onPut = {})
            }
        }
    }

    @Composable
    private fun Picker() {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {}
        MonthPickerDialog(YearMonth.from(today), onDismiss = {}, onPick = {})
    }

    @Composable
    private fun Swap() = WithSnapshot { snap ->
        val rotation = snap.resolver.patternOn(today) as WorkPattern.Rotation
        // A day of my own 주간, so there is something to hand over.
        val day = (0..6).map { today.plusDays(it.toLong()) }.first { snap.resolver.resolve(it).shiftType?.isOff == false }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 16.dp)) {
            CoverPanel(day, TeamRoster.suggest(rotation).anchored(rotation.steps.size), rotation, snap, 11 * 60, onRest = {}, onClose = {})
        }
    }

    @Composable
    private fun Weekly() = WithSnapshot { snap ->
        val work = snap.shiftTypes.first { it.name == "주간" }.id
        val pattern = WorkPattern.Weekly(
            startDate = start,
            byDay = DayOfWeek.entries.associateWith { if (it == DayOfWeek.SUNDAY) null else work },
            holidaysOff = true,
            altSaturday = today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            PatternPreview(snap.shiftTypes, pattern, today, DayOfWeek.SUNDAY)
        }
    }

    private fun shot(name: String, qualifiers: String, fontScale: Float = 1f, body: @Composable () -> Unit) =
        shoot(name, qualifiers, fontScale) { content(body) }

    private companion object {
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
    }
}
