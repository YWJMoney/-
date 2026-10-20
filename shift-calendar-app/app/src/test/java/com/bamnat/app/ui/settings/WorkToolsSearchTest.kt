package com.bamnat.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [SHFT-06][SHFT-07][DAYW-06][DAYW-07] The shift and day tools are found by the words people use, and location stays local. */
class WorkToolsSearchTest {
    private fun first(query: String) = SettingsIndex.search(query).firstOrNull()?.title

    @Test fun theToolsAreFound() {
        assertEquals("듀티표 사진으로 넣기", first("듀티표"))
        assertEquals("연간 건강 지표", first("야간 비율"))
        assertEquals("근태 엑셀 가져오기", first("엑셀"))
        assertEquals("출퇴근 자동 기록", first("자동 출근"))
        // The searches the rest of the app relies on still land where they did.
        assertEquals("워치·휴대폰 수면 기록 함께 보기", first("갤럭시 워치"))
        assertEquals("근무 주기", first("근무 주기"))
    }

    @Test fun locationIsOnlyForTheGeofenceAndItsReceiverIsPrivate() {
        // Gradle runs unit tests in the module directory.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.ACCESS_BACKGROUND_LOCATION"))
        val receiver = Regex("""<receiver\s+android:name="\.commute\.CommuteReceiver"\s+android:exported="false"""").containsMatchIn(manifest)
        assertTrue("출퇴근 기록 수신기는 밖에서 부를 수 없어요", receiver)
        // Nothing in the commute code talks to a server.
        val code = File("src/main/java/com/bamnat/app/commute").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertTrue(listOf("HttpURLConnection", "URL(", "OkHttp", "CompanyClient", "ShareApi").none { it in code })
    }
}
