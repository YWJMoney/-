package com.bamnat.app.screenshots

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
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.ui.components.AddButton
import com.bamnat.app.ui.components.CountStepper
import com.bamnat.app.ui.components.FilterChip
import com.bamnat.app.ui.components.SegmentedButton
import com.bamnat.app.ui.components.TimeRangePart
import com.bamnat.app.ui.components.LeaveMark
import com.bamnat.app.ui.components.MinutesStepper
import com.bamnat.app.ui.components.SectionCard
import com.bamnat.app.ui.components.Selection
import com.bamnat.app.ui.components.StepperRow
import com.bamnat.app.ui.components.TimeRange
import com.bamnat.app.ui.components.TimeRangeDialog
import com.bamnat.app.ui.pay.kindColor
import com.bamnat.app.ui.settings.MOVE_ALL_ALARMS_ROW
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.pay.LineKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalTime

/**
 * [UX-08][VIS-03] Bundle J's screenshots, numbers 570~599 (계획/1.23-고도화-통합계획.md 1장 7번). This file is
 * the bundle's own and shares [ScreenshotBase]'s machinery (bundle M, QA-05). 580~589 are the bundle's screens in
 * dark mode, 590~599 the same at the phone's 200% text size (research/1.23-리뷰/M 3-4 9번, E2).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class JScreenshotTest : ScreenshotBase() {

    @Test fun componentsFolded() = shoot("570_j_components_folded", FOLDED) { content { Gallery() } }

    @Test fun componentsUnfolded() = shoot("571_j_components_unfolded", UNFOLDED) { content { Gallery() } }

    @Test fun componentsFoldedBigFont() = shoot("572_j_components_folded_200", FOLDED, fontScale = 2f) { content { Gallery() } }

    @Test fun componentsFoldedDark() = shoot("573_j_components_folded_dark", FOLDED_NIGHT) { content { Gallery() } }

    @Test fun timeRangeFolded() = shoot("574_j_time_range_folded", FOLDED) { content { RangeDialog() } }

    @Test fun timeRangeBreakUnfolded() = shoot("575_j_time_range_break_unfolded", UNFOLDED) { content { RangeDialog(onBreak = true) } }

    @Test fun timeRangeFoldedBigFont() = shoot("576_j_time_range_folded_200", FOLDED, fontScale = 2f) { content { RangeDialog() } }

    @Test fun timeRangeFoldedDark() = shoot("577_j_time_range_folded_dark", FOLDED_NIGHT) { content { RangeDialog() } }


    // [A11Y-04][UX-09] Bundle J's screens in dark mode (580~589) and at 200% text (590~599), the same ten each.
    @Test fun setupStyleDark() = shoot("580_j_setup_style_dark", FOLDED_NIGHT, fresh(null))
    @Test fun setupTimesDark() = shoot("581_j_setup_times_dark", FOLDED_NIGHT, fresh(null) { toTimes() })
    @Test fun setupTimeWindowDark() = shoot("582_j_setup_time_window_dark", FOLDED_NIGHT, fresh(null) { toTimes(); tapText("시작", exact = true) })
    @Test fun setupTodayDark() = shoot("583_j_setup_today_dark", FOLDED_NIGHT, fresh(null) { setupToToday() })
    @Test fun settingsHubDark() = shoot("584_j_settings_hub_dark", FOLDED_NIGHT) { tab(4, wide = false) }
    @Test fun settingsAlarmDark() = shoot("585_j_settings_alarm_dark", FOLDED_NIGHT) { settingsPage("알람"); reveal(MOVE_ALL_ALARMS_ROW, exact = true) }
    @Test fun moveAllAlarmsDark() = shoot("586_j_move_all_alarms_dark", FOLDED_NIGHT) { settingsPage("알람"); tapText(MOVE_ALL_ALARMS_ROW, exact = true) }
    @Test fun featureSwitchesDark() = shoot("587_j_feature_switches_dark", FOLDED_NIGHT) { tab(4, wide = false); tapText("기능 켜고 끄기", exact = true) }
    @Test fun recommendedDark() = shoot("588_j_recommended_dark", FOLDED_NIGHT, recommended())
    @Test fun settingsSearchDark() = shoot("589_j_settings_search_dark", FOLDED_NIGHT) { tab(4, wide = false); typeInto("설정 찾기", "알람") }

    @Test fun setupStyleBig() = shoot("590_j_setup_style_200", FOLDED, 2f, fresh(null))
    @Test fun setupTimesBig() = shoot("591_j_setup_times_200", FOLDED, 2f, fresh(null) { toTimes() })
    @Test fun setupTimeWindowBig() = shoot("592_j_setup_time_window_200", FOLDED, 2f, fresh(null) { toTimes(); tapText("시작", exact = true) })
    @Test fun setupTodayBig() = shoot("593_j_setup_today_200", FOLDED, 2f, fresh(null) { toTodayBig() })
    @Test fun settingsHubBig() = shoot("594_j_settings_hub_200", PHONE, 2f) { tab(4, wide = false) }
    @Test fun settingsAlarmBig() = shoot("595_j_settings_alarm_200", PHONE, 2f) { settingsPage("알람"); reveal(MOVE_ALL_ALARMS_ROW, exact = true) }
    @Test fun moveAllAlarmsBig() = shoot("596_j_move_all_alarms_200", PHONE, 2f) { settingsPage("알람"); tapText(MOVE_ALL_ALARMS_ROW, exact = true) }
    @Test fun featureSwitchesBig() = shoot("597_j_feature_switches_200", PHONE, 2f) { tab(4, wide = false); tapText("기능 켜고 끄기", exact = true) }
    @Test fun setupEscapeBig() = shoot("598_j_setup_escape_200", FOLDED, 2f, fresh(null) { toTodayBig(answer = false); reveal("잘 모르겠어요 · 달력에 직접 칠할게요", exact = true) })
    @Test fun settingsSearchBig() = shoot("599_j_settings_search_200", PHONE, 2f) { tab(4, wide = false); typeInto("설정 찾기", "알람") }

    /**
     * [setupToToday] at 200% on the folded screen (the setup is what a new user sees first, on the cover
     * screen): each answer is brought up first, since the pinned button covers the lower part; without
     * [answer] it stops at the question.
     */
    private fun Screen.toTodayBig(answer: Boolean = true) {
        toTimes(); tapText("네, 시간이 맞아요")
        tapText("4조 2교대 8일 (주휴야휴)"); tapText("다음", exact = true)
        if (!answer) return
        reveal("야간", exact = true); tapText("야간", exact = true)
        reveal("둘째 날", exact = true); tapText("둘째 날")
    }

    private fun Screen.toTimes() { tapText("순서가 돌아가요"); tapText("4조 2교대 12시간") }

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
            startOn = if (onBreak) TimeRangePart.BREAK else TimeRangePart.START,
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

    private companion object {
        const val FOLDED_NIGHT = "w369dp-h905dp-port-night-420dpi"
    }
}
