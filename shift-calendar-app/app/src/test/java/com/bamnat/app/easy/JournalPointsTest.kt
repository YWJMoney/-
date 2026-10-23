package com.bamnat.app.easy

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.company.InquiryInfo
import com.bamnat.app.company.InquiryKind
import com.bamnat.app.company.InquiryOutbox
import com.bamnat.app.company.QueuedInquiry
import com.bamnat.app.data.MoneyRepository
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.pay.LeaveReminder
import com.bamnat.app.screenshots.ScreenshotBase
import com.bamnat.app.widget.WidgetRefresher
import com.bamnat.core.journal.JournalEntry
import com.bamnat.core.journal.JournalKind
import com.bamnat.core.leave.LeavePromotion
import com.bamnat.core.profile.FactKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDateTime

/**
 * [OBS-01] The places that write '밤낮이 한 일' (research/1.23-N-설계.md 5장), each called the way the app calls
 * it: one line each, of kinds and small numbers only. Writing is off the main thread, so each waits for its line.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class JournalPointsTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container

    @Before fun ready() = runBlocking {
        val end = System.currentTimeMillis() + 60_000
        while (c.scheduleRepository.shiftTypes.first().isEmpty()) {
            check(System.currentTimeMillis() < end) { "Timed out waiting for the first shifts" }
            shadowOf(Looper.getMainLooper()).idle()
        }
        c.journal.clear()
    }

    private fun line(kind: JournalKind): JournalEntry {
        val end = System.currentTimeMillis() + 30_000
        while (true) {
            runBlocking { c.journal.load() }.lastOrNull { it.kind == kind }?.let { return it }
            check(System.currentTimeMillis() < end) { "No line of $kind" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
    }

    @Test fun `a different next alarm is a line, the same one again is not`() {
        // 기록 지점 1: a working day tomorrow and the alarm lead answered re-plan the next alarm.
        runBlocking {
            val work = c.scheduleRepository.shiftTypes.first().first { it.hasTimes && !it.isOff }
            c.scheduleRepository.setOverride(java.time.LocalDate.now().plusDays(1), com.bamnat.core.model.ShiftOverride.Shift(work.id))
            c.answers.answer(PersonalCalendar.MAIN, FactKey.ALARM_LEAD, "90")
        }
        val first = line(JournalKind.ALARMS_PLANNED)
        assertTrue(first.detail.orEmpty(), first.detail!!.matches(Regex("\\d\\d/\\d\\d \\d\\d:\\d\\d")))
        assertTrue(!runBlocking { c.journal.addIfChanged(JournalKind.ALARMS_PLANNED, first.detail) })
        assertTrue(runBlocking { c.journal.addIfChanged(JournalKind.ALARMS_PLANNED, "12/31 23:59") })
        assertEquals("12/31 23:59", line(JournalKind.ALARMS_PLANNED).detail)
    }

    @Test fun `a payment written down says how many, never how much`() {
        // 기록 지점 9.
        val outcome = runBlocking {
            c.capture("KB국민카드\nKB국민카드1234승인 8,000원 일시불 GS25", "com.kakao.talk", "카카오톡", System.currentTimeMillis(), fromMessenger = true)
        }
        assertEquals(MoneyRepository.Outcome.SAVED, outcome)
        val e = line(JournalKind.MONEY_CAPTURED)
        assertNull(e.detail)
        assertEquals("결제 알림을 돈 기록으로 적음", com.bamnat.core.journal.Journal.text(e))
    }

    @Test fun `what waited and went, and the 연차 notice`() {
        // 기록 지점 7.
        c.inquiryApi = ScreenshotBase.FakeInquiryApi()
        c.inquiryStore.queue(QueuedInquiry("request-id-0000001", InquiryKind.BUG, "알람이 안 울려요", InquiryInfo("1.23.0", "Pixel", "15"), Instant.now()))
        assertTrue(runBlocking { InquiryOutbox.flush(c) })
        assertEquals(1, line(JournalKind.OUTBOX).count)
        // 기록 지점 8: the days left, not their pay.
        val now = LocalDateTime.now()
        LeaveReminder.post(app, LeavePromotion.Notice(now, now.toLocalDate().plusMonths(2), firstYear = false, stage = 1), 3.0)
        assertEquals("3일", line(JournalKind.LEAVE_NOTICE).detail)
    }

    @Test fun `no widget on the home screen, no widget line`() = runBlocking {
        // 기록 지점 6 writes only when there was something to redraw.
        WidgetRefresher.refresh(app)
        c.journal.add(JournalEntry(Instant.now(), JournalKind.NEWS))
        line(JournalKind.NEWS)
        assertTrue(c.journal.load().none { it.kind == JournalKind.WIDGETS })
    }
}
