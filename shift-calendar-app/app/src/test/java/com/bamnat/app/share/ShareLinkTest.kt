package com.bamnat.app.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.data.ScheduleSnapshot
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** [MKT-09] What a link sends, and (when a test server is running) the whole round trip. */
@RunWith(AndroidJUnit4::class)
class ShareLinkTest {
    private val day = ShiftType(1, "주간", "주", 0xFF1E88E5, LocalTime.of(7, 0), LocalTime.of(19, 0))
    private val night = ShiftType(2, "야간", "야", 0xFF5E35B1, LocalTime.of(19, 0), LocalTime.of(7, 0))
    private val off = ShiftType(3, "휴무", "휴", 0xFF9E9E9E, null, null, isOff = true)
    private val start = LocalDate.of(2026, 11, 1)
    private val snapshot = ScheduleSnapshot(
        listOf(day, night, off),
        WorkPattern.Rotation(start, listOf(1L, 1L, 2L, 2L, 3L, 3L)),
        listOf(
            DayEntry(start, leave = Leave(LeaveKind.ANNUAL, 2)),
            DayEntry(start.plusDays(2), leave = Leave(LeaveKind.UNPAID)),
            DayEntry(start.plusDays(6), secondShiftId = 2L),
        ),
    )

    @Test fun payloadHasOnlyWhatThePageShows() {
        val p = ShareLinkPayload.build(snapshot, start, " 민지 ", 30)
        assertEquals(1, p.getInt("v"))
        assertEquals("민지", p.getString("title"))
        val days = p.getJSONArray("days")
        assertEquals(ShareLinkPayload.DAYS, days.length())
        assertEquals("반차", days.getJSONObject(0).getString("leave"))
        // The kind of leave other than 연차 stays on the phone.
        assertEquals("휴가", days.getJSONObject(2).getString("leave"))
        assertEquals(2, days.getJSONObject(6).getInt("second"))
        val types = p.getJSONArray("types")
        assertEquals("#1E88E5", types.getJSONObject(0).getString("color"))
        assertEquals("07:00-19:00", types.getJSONObject(0).getString("time"))
        assertTrue(types.getJSONObject(2).getBoolean("off"))
        assertFalse(p.toString().contains("무급"))
        // Saved for the worker's own validator test in server/share-worker.
        File("build/share-payload.json").writeText(p.toString())
    }

    /** Runs against `npx wrangler dev` when SHARE_TEST_URL points at it; skipped otherwise. */
    @Test fun roundTripAgainstTestServer() = runBlocking {
        val base = System.getenv("SHARE_TEST_URL").orEmpty()
        assumeTrue(base.isNotBlank())
        val client = ShareLinkClient(base)
        val made = client.create(ShareLinkPayload.build(snapshot, start, "민지", 7))
        assertTrue(made.url.startsWith(base.trimEnd('/') + "/s/"))
        val page = java.net.URL(made.url).readText()
        assertTrue(page.contains("민지의 근무표"))
        val updated = client.update(made, ShareLinkPayload.build(snapshot, start, "민지2", 30))
        assertTrue(java.net.URL(updated.url).readText().contains("민지2의 근무표"))
        client.delete(updated)
        client.delete(updated) // already gone: still fine
        val gone = (java.net.URL(made.url).openConnection() as java.net.HttpURLConnection).responseCode
        assertEquals(404, gone)
    }
}
