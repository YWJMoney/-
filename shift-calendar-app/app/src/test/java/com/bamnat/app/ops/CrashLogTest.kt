package com.bamnat.app.ops

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.diag.CrashLog
import com.bamnat.app.diag.Telemetry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** [QA-06] 328 and review F3: what a stop leaves on the phone, and that no typed words go along. */
@RunWith(AndroidJUnit4::class)
class CrashLogTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()

    @After fun clean() = CrashLog.clear(app)

    @Test fun aStopLeavesWhereButNotWhatWasTyped() {
        val error = IllegalStateException("회사 이름 우리병원", RuntimeException("홍길동 근무"))
        val note = CrashLog.write(app, "main", error, System.currentTimeMillis()).readText()
        assertTrue(note, note.contains("java.lang.IllegalStateException"))
        assertTrue(note, note.contains("caused by java.lang.RuntimeException"))
        assertTrue(note, note.contains("CrashLogTest.aStopLeavesWhereButNotWhatWasTyped"))
        assertFalse("never the message", note.contains("우리병원") || note.contains("홍길동"))
    }

    @Test fun onlyTheNewestFiveStay() {
        repeat(8) { CrashLog.write(app, "main", RuntimeException(), 1_000L + it) }
        val notes = CrashLog.notes(app)
        assertEquals(CrashLog.KEEP, notes.size)
        assertEquals("crash-1007.txt", notes.first().name)
    }

    @Test fun theReportShowsTheLatestStop() {
        CrashLog.write(app, "main", ArithmeticException(), System.currentTimeMillis())
        val lines = CrashLog.reportLines(app)
        assertTrue(lines.toString(), lines.any { it == "남은 기록: 1개" })
        assertTrue(lines.toString(), lines.any { it.contains("ArithmeticException") })
    }

    @Test fun anEventKeepsCodesAndDropsWords() {
        assertEquals("at=123 late_s=4", Telemetry.cleanDetail("at=123 late_s=4"))
        assertEquals("verified,share,new", Telemetry.cleanDetail("verified,share,new"))
        assertEquals("text5", Telemetry.cleanDetail("간호 3교"))
        assertEquals("text200", Telemetry.cleanDetail("a".repeat(200)))
    }

    @Test fun theLogNeverHoldsASetName() {
        app.container.telemetry.event("shift_set_added", "우리병원 3교대")
        val last = kotlinx.coroutines.runBlocking { app.container.telemetry.log.read() }.last { it.name == "shift_set_added" }
        assertEquals("text8", last.detail)
    }
}
