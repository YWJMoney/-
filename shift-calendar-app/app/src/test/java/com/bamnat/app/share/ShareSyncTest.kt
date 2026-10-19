package com.bamnat.app.share

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/** [SHR-12] The sharer's link follows the calendar, keeps its end date, and says before it closes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShareSyncTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val api = FakeApi()
    private val zone = ZoneId.of("Asia/Seoul")

    private class FakeApi : ShareApi {
        override val enabled = true
        val sent = mutableListOf<JSONObject>()
        var failure: Exception? = null
        override suspend fun create(payload: JSONObject) = error("not used")
        override suspend fun update(state: ShareLinkState, payload: JSONObject): ShareLinkState {
            failure?.let { throw it }
            sent += payload
            val days = payload.optInt("ttlDays", 0)
            return state.copy(title = payload.optString("title"),
                expiresAt = if (days > 0) Instant.now().plus(Duration.ofDays(days.toLong())) else state.expiresAt,
                ttlDays = if (days > 0) days else state.ttlDays)
        }
        override suspend fun delete(state: ShareLinkState) = Unit
        override suspend fun read(id: String) = error("not used")
    }

    private fun link(ttl: Int = 30, from: YearMonth? = YearMonth.now(), expires: Instant = Instant.now().plus(Duration.ofDays(20))) =
        ShareLinkState("Ab3dEfGh5JkLmNoPqRsTuv", "https://share.example/s/Ab3dEfGh5JkLmNoPqRsTuv", "token", expires, "민지", ttl, from)

    @Before fun setUp() {
        c.shareApi = api
        c.shareLinks.clear()
        c.shareLinks.auto = true
        runBlocking {
            c.scheduleRepository.ensureDefaults()
            val day = c.scheduleRepository.shiftTypes.first().first { !it.isOff }.id
            c.scheduleRepository.setOverride(LocalDate.now().plusDays(1), ShiftOverride.Shift(day))
        }
    }

    @After fun tearDown() = c.shareLinks.clear()

    @Test fun aChangeGoesOutOnceAndKeepsTheEndDate() = runBlocking {
        val made = link()
        c.shareLinks.save(made)
        val sync = ShareSync(c)
        assertEquals(ShareSync.Result.Sent, sync.sync())
        assertFalse("the end date stays", api.sent.single().has("ttlDays"))
        assertEquals(made.expiresAt, c.shareLinks.link.value?.expiresAt)
        // Nothing changed since: nothing is sent.
        assertEquals(ShareSync.Result.Same, sync.sync())
        assertEquals(1, api.sent.size)

        val night = c.scheduleRepository.shiftTypes.first().last { !it.isOff }.id
        c.scheduleRepository.setOverride(LocalDate.now().plusDays(2), ShiftOverride.Shift(night))
        assertEquals(ShareSync.Result.Sent, sync.sync())
        assertEquals(2, api.sent.size)

        // Turned off, the link waits for the sharer.
        c.shareLinks.auto = false
        c.scheduleRepository.setOverride(LocalDate.now().plusDays(3), ShiftOverride.Off)
        assertEquals(ShareSync.Result.Same, sync.sync())
        assertEquals(2, api.sent.size)
    }

    @Test fun extendingMovesTheEndDateByThePeriodPicked() = runBlocking {
        c.shareLinks.save(link(ttl = 90))
        c.shareLinks.noticedFor = c.shareLinks.link.value?.expiresAt
        assertEquals(ShareSync.Result.Sent, ShareSync(c).extend())
        assertEquals(90, api.sent.single().getInt("ttlDays"))
        val after = c.shareLinks.link.value!!
        assertTrue(after.expiresAt.isAfter(Instant.now().plus(Duration.ofDays(89))))
        assertNull("the next period gets its own notice", c.shareLinks.noticedFor)
    }

    @Test fun aLinkCutElsewhereIsForgotten() = runBlocking {
        c.shareLinks.save(link())
        api.failure = ShareLinkException("not_found")
        assertEquals(ShareSync.Result.Gone, ShareSync(c).sync())
        assertNull(c.shareLinks.link.value)
    }

    @Test fun offlineKeepsTheLinkToTryAgain() = runBlocking {
        c.shareLinks.save(link())
        api.failure = java.io.IOException("no network")
        assertEquals(ShareSync.Result.Offline, ShareSync(c).sync())
        assertNotNull(c.shareLinks.link.value)
        api.failure = null
        assertEquals(ShareSync.Result.Sent, ShareSync(c).sync())
    }

    @Test fun theThreeMonthsMoveAlongWithTime() {
        val today = LocalDate.of(2026, 12, 5)
        assertEquals(YearMonth.of(2026, 12), ShareLinkPayload.startMonth(link(from = YearMonth.of(2026, 10)), today))
        assertEquals(YearMonth.of(2027, 1), ShareLinkPayload.startMonth(link(from = YearMonth.of(2027, 1)), today))
        assertEquals(YearMonth.of(2026, 12), ShareLinkPayload.startMonth(link(from = null), today))
    }

    @Test fun theFingerprintIgnoresThePeriod() {
        val a = JSONObject("""{"v":1,"title":"민지","ttlDays":30,"types":[],"days":[{"date":"2026-11-01","type":1}]}""")
        val b = JSONObject("""{"v":1,"title":"민지","types":[],"days":[{"date":"2026-11-01","type":1}]}""")
        val c = JSONObject("""{"v":1,"title":"민지","types":[],"days":[{"date":"2026-11-02","type":1}]}""")
        assertEquals(ShareLinkPayload.fingerprint(a), ShareLinkPayload.fingerprint(b))
        assertFalse(ShareLinkPayload.fingerprint(a) == ShareLinkPayload.fingerprint(c))
    }

    @Test fun theNoticeComesAheadOfTheEnd() {
        fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant()
        val now = at("2026-11-01T09:00")
        val month = link(ttl = 30, expires = at("2026-11-20T15:00"))
        assertEquals(at("2026-11-17T10:00"), ShareExpiryReminder.noticeAt(month, null, now, zone))
        val week = link(ttl = 7, expires = at("2026-11-05T15:00"))
        assertEquals(at("2026-11-04T10:00"), ShareExpiryReminder.noticeAt(week, null, now, zone))
        // Posted once for this end date.
        assertNull(ShareExpiryReminder.noticeAt(month, month.expiresAt, now, zone))
        // Already inside the last days (a phone off for a while): right away.
        assertEquals(at("2026-11-18T12:01"), ShareExpiryReminder.noticeAt(month, null, at("2026-11-18T12:00"), zone))
        assertNull("already closed", ShareExpiryReminder.noticeAt(month, null, at("2026-11-21T00:00"), zone))

        assertFalse(ShareExpiryReminder.closingSoon(month, at("2026-11-16T12:00")))
        assertTrue(ShareExpiryReminder.closingSoon(month, at("2026-11-17T16:00")))
    }

    @Test fun theStoreKeepsWhatItNeedsAcrossRestarts() {
        c.shareLinks.save(link(ttl = 90, from = YearMonth.of(2026, 11)), sent = "abc")
        val again = ShareLinkStore(app).load()!!
        assertEquals(90, again.ttlDays)
        assertEquals(YearMonth.of(2026, 11), again.from)
        assertEquals("abc", ShareLinkStore(app).sent)
    }
}
