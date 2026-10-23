package com.bamnat.app.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.MainActivity
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.app.ui.calendar.CoverPanel
import com.bamnat.app.ui.calendar.MonthPickerDialog
import com.bamnat.app.ui.calendar.MonthRecapCard
import com.bamnat.app.ui.calendar.MonthSummaryBar
import com.bamnat.app.ui.calendar.QuickTextOffer
import com.bamnat.app.ui.calendar.RealignDialog
import com.bamnat.app.ui.shifts.PatternPreview
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.core.model.ShiftOverride
import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.TeamRoster
import com.bamnat.core.work.CalendarFocus
import com.bamnat.core.work.MonthOverview
import com.bamnat.core.work.MonthRecap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Bundle I's screenshots, numbers 540~569 (계획/1.23-I-달력-위젯-워치-개발계획.md): the calendar's new dialogs and
 * cards, the swap helper and the weekly pattern's preview, each folded, unfolded and at 200% text. The widgets are
 * Glance and are not drawn by Robolectric; their content is checked in WidgetData's tests instead.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class IScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val today = LocalDate.now()
    private val start = LocalDate.of(2026, 7, 1)

    @Before
    fun seed() = runBlocking<Unit> {
        val repo = app.container.scheduleRepository
        repo.ensureDefaults()
        val types = repo.shiftTypes.first().associateBy { it.name }
        val day = repo.saveShiftType(types.getValue("주간").copy(start = LocalTime.of(8, 0), end = LocalTime.of(20, 0)))
        val night = repo.saveShiftType(types.getValue("야간").copy(start = LocalTime.of(20, 0), end = LocalTime.of(8, 0)))
        val off = types.getValue("휴무").id
        repo.savePattern(WorkPattern.Rotation(start, listOf(day, night, off, off)))
        // Two days set by hand ahead, the usual workaround for an order that slipped.
        repo.setOverride(today.plusDays(4), ShiftOverride.Shift(night))
        repo.setOverride(today.plusDays(9), ShiftOverride.Shift(day))
    }

    // [SCHED-03] 이 날부터 순서 다시 맞추기.
    @Test fun realignFolded() = shoot("540_i_realign_folded", FOLDED) { Realign() }
    @Test fun realignUnfolded() = shoot("541_i_realign_unfolded", UNFOLDED) { Realign() }
    @Test fun realignFoldedBigFont() = shoot("542_i_realign_folded_200", FOLDED, fontScale = 2f) { Realign() }
    @Test fun realignFoldedDark() = shoot("543_i_realign_folded_dark", FOLDED_NIGHT) { Realign() }

    // [CAL-35][CAL-46][CAL-43] The summary against last month, the month-end card and a typed line's offer.
    @Test fun cardsFolded() = shoot("544_i_cards_folded", FOLDED) { Cards() }
    @Test fun cardsUnfolded() = shoot("545_i_cards_unfolded", UNFOLDED) { Cards() }
    @Test fun cardsFoldedBigFont() = shoot("546_i_cards_folded_200", FOLDED, fontScale = 2f) { Cards() }
    @Test fun cardsFoldedDark() = shoot("547_i_cards_folded_dark", FOLDED_NIGHT) { Cards() }

    // [CAL-40] The month picker in rows that wrap at large text.
    @Test fun monthPickerFolded() = shoot("548_i_month_picker_folded", FOLDED) { Picker() }
    @Test fun monthPickerUnfolded() = shoot("549_i_month_picker_unfolded", UNFOLDED) { Picker() }
    @Test fun monthPickerFoldedBigFont() = shoot("550_i_month_picker_folded_200", FOLDED, fontScale = 2f) { Picker() }

    // [SCHED-07] 대근 찾기 with 맞바꾸기 under it.
    @Test fun swapFolded() = shoot("551_i_swap_folded", FOLDED) { Swap() }
    @Test fun swapUnfolded() = shoot("552_i_swap_unfolded", UNFOLDED) { Swap() }
    @Test fun swapFoldedBigFont() = shoot("553_i_swap_folded_200", FOLDED, fontScale = 2f) { Swap() }
    @Test fun swapFoldedDark() = shoot("554_i_swap_folded_dark", FOLDED_NIGHT) { Swap() }

    // [SCHED-04][SCHED-06] A weekly pattern with 공휴일엔 쉬어요 and 격주 토요일, previewed.
    @Test fun weeklyFolded() = shoot("555_i_weekly_preview_folded", FOLDED) { Weekly() }
    @Test fun weeklyUnfolded() = shoot("556_i_weekly_preview_unfolded", UNFOLDED) { Weekly() }
    @Test fun weeklyFoldedBigFont() = shoot("557_i_weekly_preview_folded_200", FOLDED, fontScale = 2f) { Weekly() }

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

    private fun shoot(name: String, qualifiers: String, fontScale: Float = 1f, body: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        RuntimeEnvironment.setFontScale(fontScale)
        compose.mainClock.autoAdvance = false
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            settle()
            scenario.onActivity { it.setContent { BamnatTheme { body() } } }
            settle()
            lateinit var bmp: Bitmap
            scenario.onActivity { activity ->
                val view = activity.window.decorView
                bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                view.draw(canvas)
                for (dialog in ShadowDialog.getShownDialogs().filter { it.isShowing }.distinct()) {
                    canvas.drawColor(0x66000000)
                    val dv = dialog.window!!.decorView
                    canvas.save()
                    canvas.translate((view.width - dv.width) / 2f, (view.height - dv.height) / 2f)
                    dv.draw(canvas)
                    canvas.restore()
                }
            }
            File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private companion object {
        const val FOLDED = "w369dp-h905dp-port-420dpi"
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
        const val UNFOLDED = "w707dp-h823dp-port-420dpi"
    }
}
