package com.bamnat.app.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.MainActivity
import com.bamnat.app.ui.components.AddButton
import com.bamnat.app.ui.components.CountStepper
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.Selection
import com.bamnat.app.ui.components.StepperRow
import com.bamnat.app.ui.components.TimeRange
import com.bamnat.app.ui.components.TimeRangeDialog
import com.bamnat.app.ui.pay.kindColor
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.pay.LineKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalTime

/**
 * [UX-08][VIS-03] Bundle J's screenshots, numbers 570~599 (계획/1.23-고도화-통합계획.md 1장 7번). This file is
 * the bundle's own, so it never collides with the others in ScreenshotTest.kt; once bundle M's ScreenshotBase
 * lands it extends that instead of carrying its own shoot() and settle().
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class JScreenshotTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun componentsFolded() = shoot("570_j_components_folded", FOLDED) { Gallery() }

    @Test fun componentsUnfolded() = shoot("571_j_components_unfolded", UNFOLDED) { Gallery() }

    @Test fun componentsFoldedBigFont() = shoot("572_j_components_folded_200", FOLDED, fontScale = 2f) { Gallery() }

    @Test fun componentsFoldedDark() = shoot("573_j_components_folded_dark", FOLDED_NIGHT) { Gallery() }

    @Test fun timeRangeFolded() = shoot("574_j_time_range_folded", FOLDED) { RangeDialog() }

    @Test fun timeRangeBreakUnfolded() = shoot("575_j_time_range_break_unfolded", UNFOLDED) { RangeDialog(onBreak = true) }

    @Test fun timeRangeFoldedBigFont() = shoot("576_j_time_range_folded_200", FOLDED, fontScale = 2f) { RangeDialog() }

    @Test fun timeRangeFoldedDark() = shoot("577_j_time_range_folded_dark", FOLDED_NIGHT) { RangeDialog() }

    /** A 야간 22:00~07:00 with an hour's break, as the shift editor would open it. */
    @Composable
    private fun RangeDialog(onBreak: Boolean = false) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {}
        TimeRangeDialog(
            title = "야간 근무 시간",
            initial = TimeRange(LocalTime.of(22, 0), LocalTime.of(7, 0), 60),
            note = "원래 22:00~07:00 · 휴게 1시간",
            onDismiss = {},
            onConfirm = {},
            startOnBreak = onBreak,
        )
    }

    /** Every shared control of bundle J's first step on one page. */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Gallery() {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard("고르기 (선택 색)") {
                var chosen by remember { mutableIntStateOf(1) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("주간", "야간", "휴무").forEachIndexed { i, s -> FilterChip(selected = chosen == i, onClick = { chosen = i }, label = { Text(s) }) }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    listOf("일", "주", "월").forEachIndexed { i, s ->
                        SegmentedButton(selected = i == 2, onClick = {}, shape = SegmentedButtonDefaults.itemShape(i, 3),
                            colors = Selection.segmentedColors()) { Text(s) }
                    }
                }
            }
            SectionCard("수 고르기 (스테퍼 하나)") {
                var people by remember { mutableIntStateOf(3) }
                var minutes by remember { mutableIntStateOf(90) }
                StepperRow("가족 수", hint = "본인 포함") {
                    CountStepper(people, { people = it }, name = "가족 수", range = 1..11, format = { "${it}명" })
                }
                StepperRow("잔업") {
                    MinutesStepper(minutes, { minutes = it }, dialogTitle = "잔업")
                }
                StepperRow("주 한도", hint = "넘으면 알려 줘요") {
                    MinutesStepper(52 * 60, {}, step = 60, max = 168 * 60, dialogTitle = "주 한도", format = { "${it / 60}시간" })
                }
                AddButton("지출 적기", onClick = {})
            }
            SectionCard("의미 색 (휴가·급여)") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LeaveKind.entries.forEach { k -> LeaveMark(Leave(k), size = 24.dp) }
                    Text("연차·유급·무급·보상", style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    LineKind.entries.forEach { k ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(Modifier.size(10.dp).background(kindColor(k)))
                            Text(" ${k.name.lowercase()}", color = kindColor(k), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
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
