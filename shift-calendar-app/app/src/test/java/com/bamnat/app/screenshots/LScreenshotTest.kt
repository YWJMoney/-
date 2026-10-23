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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.MainActivity
import com.bamnat.app.share.ShareGroupBoard
import com.bamnat.app.share.SharedSchedule
import com.bamnat.app.ui.calendar.SentLine
import com.bamnat.app.ui.calendar.ShareGroupScreen
import com.bamnat.app.ui.calendar.ShareGroupViewModel
import com.bamnat.app.ui.diag.AlarmWeekCard
import com.bamnat.app.ui.diag.ConsentSummary
import com.bamnat.app.ui.diag.ConsentSummaryList
import com.bamnat.app.ui.diag.ServerCheck
import com.bamnat.app.ui.diag.ServerCheckCard
import com.bamnat.app.ui.theme.BamnatTheme
import com.bamnat.core.diag.AlarmWeek
import com.bamnat.core.diag.DiagEvent
import com.bamnat.core.diag.DiagEvents
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * [SHR-13][SHR-14][ALRM-01][ALRM-02] Bundle L's screenshots, numbers 630~659 (계획/1.23-L-서버-광장-알람-개발계획.md):
 * 우리 조 모아 보기, the share card's state, the alarm week, the server check and the consent summary, each folded,
 * unfolded and at 200%. Its own file, so it never collides with the other bundles'.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class LScreenshotTest : ScreenshotBase() {
    private val day = LocalDate.of(2026, 11, 3)

    @Test fun groupBoardFolded() = board("630_l_group_board_folded", FOLDED)
    @Test fun groupBoardUnfolded() = board("631_l_group_board_unfolded", UNFOLDED)
    @Test fun groupBoardFoldedBigFont() = board("632_l_group_board_folded_200", FOLDED, 2f)

    @Test fun groupJoinFolded() = join("633_l_group_join_folded", FOLDED)
    @Test fun groupJoinUnfolded() = join("634_l_group_join_unfolded", UNFOLDED)
    @Test fun groupJoinFoldedBigFont() = join("635_l_group_join_folded_200", FOLDED, 2f)

    @Test fun consentFolded() = shoot("636_l_consent_summary_folded", FOLDED) { content { Consent() } }
    @Test fun consentUnfolded() = shoot("637_l_consent_summary_unfolded", UNFOLDED) { content { Consent() } }
    @Test fun consentFoldedBigFont() = shoot("638_l_consent_summary_folded_200", FOLDED, 2f) { content { Consent() } }

    @Test fun checksFolded() = shoot("639_l_server_week_share_folded", FOLDED) { content { Checks() } }
    @Test fun checksUnfolded() = shoot("640_l_server_week_share_unfolded", UNFOLDED) { content { Checks() } }
    @Test fun checksFoldedBigFont() = shoot("641_l_server_week_share_folded_200", FOLDED, 2f) { content { Checks() } }

    // [ALRM-04] 증명 사진 (순번 340): 715~719.
    @Test fun photoReferencesFolded() = shoot("715_l_photo_reference_settings_folded", FOLDED) { photoSettings() }
    @Test fun photoMissionFolded() = shoot("716_l_photo_mission_folded", FOLDED) { photoMission(wrong = 0) }
    @Test fun photoMissionWrongFolded() = shoot("717_l_photo_mission_wrong_folded", FOLDED) { photoMission(wrong = 1) }
    @Test fun photoMissionFoldedBigFont() = shoot("718_l_photo_mission_folded_200", FOLDED, 2f) { photoMission(wrong = 2) }
    @Test fun photoReferencesDark() = shoot("719_l_photo_reference_settings_dark", "w369dp-h905dp-port-night-420dpi") { photoSettings() }

    private fun Screen.photoSettings() {
        val refs = app.container.photoReferences
        refs.clear()
        refs.add(0x5A5A0F0F33CC66AAL, "욕실 거울", at = java.time.Instant.parse("2026-10-21T22:10:00Z").toEpochMilli())
        app.container.settingsRepository.update { it.copy(alarmMission = com.bamnat.core.alarm.AlarmMission.PHOTO.key) }
        settingsPage("알람"); reveal(com.bamnat.app.ui.settings.PHOTO_ROW, exact = true); reveal("지금 한 장 더", exact = true)
    }

    private fun Screen.photoMission(wrong: Int) {
        val since = System.currentTimeMillis() - 50_000
        content {
            com.bamnat.app.alarm.MissionScreen(com.bamnat.core.alarm.AlarmMission.PHOTO, since, 5, onSnooze = {}, onDone = {},
                references = listOf(1L), place = "욕실 거울", wrongBefore = wrong)
        }
    }

    private fun board(name: String, qualifiers: String, fontScale: Float = 1f) {
        val vm = ShareGroupViewModel(app.container, today = { day })
        fun week(vararg t: Long?) = SharedSchedule.parse(JSONObject().put("title", "x")
            .put("types", JSONArray()
                .put(JSONObject().put("id", 1).put("name", "주간").put("color", "#1E88E5"))
                .put(JSONObject().put("id", 2).put("name", "오후").put("color", "#43A047"))
                .put(JSONObject().put("id", 3).put("name", "야간").put("color", "#5E35B1")))
            .put("days", JSONArray().apply { t.forEachIndexed { i, id -> put(JSONObject().put("date", day.plusDays(i.toLong()).toString()).put("type", id ?: JSONObject.NULL)) } }))
        vm.show(ShareGroupViewModel.Ui(code = "K7MQ2X", members = listOf(
            ShareGroupBoard.Member("a", "나", true, week(1, 1, 3, 3, null, null, 2)),
            ShareGroupBoard.Member("b", "민지", false, week(3, 3, null, null, null, 1, 1)),
            ShareGroupBoard.Member("c", "동료 3", false, week(2, 2, 1, null, null, 3, 3)),
            ShareGroupBoard.Member("d", "동료 4", false, null),
        )))
        shoot(name, qualifiers, fontScale) { content { ShareGroupScreen(vm, onBack = {}, onShare = {}) } }
    }

    private fun join(name: String, qualifiers: String, fontScale: Float = 1f) {
        val vm = ShareGroupViewModel(app.container, today = { day })
        vm.show(ShareGroupViewModel.Ui(message = null))
        shoot(name, qualifiers, fontScale) { content { ShareGroupScreen(vm, onBack = {}, onShare = {}) } }
    }

    @Composable
    private fun Consent() {
        val lines = ConsentSummary.lines(ConsentSummary.Facts(usageStats = true, news = true, trialAnswer = "keep", notifications = true,
            fullScreen = false, exact = true, battery = false, calendar = true, location = false, paymentRead = false, health = true, lock = false))
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(top = 16.dp)) {
            ConsentSummaryList(lines) {}
        }
    }

    @Composable
    private fun Checks() {
        val now = Instant.parse("2026-11-03T03:00:00Z")
        val day = 24 * 60 * 60_000L
        val missed = now.toEpochMilli() - 2 * day
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AlarmWeekCard(AlarmWeek(planned = 6, rang = 5, late = 1, averageLateSeconds = 95, missed = listOf(missed)),
                listOf(DiagEvent(missed - 3_600_000, DiagEvents.ALIVE)))
            ServerCheckCard(ServerCheck.Facts(132, 210, true, now.minusSeconds(3 * 3600), listOf("문의 사진"), 2, LocalDate.of(2026, 11, 2), true))
            SentLine(Instant.parse("2026-11-02T23:40:00Z"), unsent = true, busy = false) {}
        }
    }
}
