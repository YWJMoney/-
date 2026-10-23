package com.bamnat.app.share

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.company.InquiryInfo
import com.bamnat.app.company.InquiryKind
import com.bamnat.app.company.InquiryStore
import com.bamnat.app.company.PlazaNewsJob
import com.bamnat.app.company.QueuedInquiry
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.ui.diag.ConsentSummary
import com.bamnat.app.ui.diag.ServerCheck
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 1.23 묶음 L on the phone: a received schedule's changed week [SHR-13], 모든 링크 끊기 and the share card's state
 * [SHR-13], 우리 조 모아 보기 [SHR-14], the question outbox [INQ-05], 밤낮 소식's report outcomes [COMM-11], the
 * server check and consent summary [ALRM-02], and the app lock hiding the screen [SRV-08].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LShareInquiryTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val today = LocalDate.of(2026, 11, 3)

    private class FakeApi : ShareApi {
        override val enabled = true
        var next: ReceivedShare? = null
        val deleted = mutableListOf<String>()
        val offline = mutableSetOf<String>()
        val groups = mutableMapOf<String, MutableList<String>>()
        override suspend fun create(payload: JSONObject) = error("not used")
        override suspend fun update(state: ShareLinkState, payload: JSONObject) = error("not used")
        override suspend fun delete(state: ShareLinkState) {
            if (state.id in offline) throw IOException("offline")
            deleted += state.id
        }
        override suspend fun read(id: String) = next ?: throw ShareLinkException("not_found")
        override suspend fun readIfChanged(id: String, etag: String?) = next
        override suspend fun groupStart(own: ShareLinkState) = ShareGroup("ABC234", null, listOf(own.id)).also { groups[it.code] = it.links.toMutableList() }
        override suspend fun groupJoin(code: String, own: ShareLinkState): ShareGroup {
            val list = groups[code] ?: throw ShareLinkException("not_found")
            if (own.id !in list) list += own.id
            return ShareGroup(code, null, list.toList())
        }
        override suspend fun groupRead(code: String) = ShareGroup(code, null, groups[code]?.toList() ?: throw ShareLinkException("not_found"))
        override suspend fun groupLeave(code: String, own: ShareLinkState) { groups[code]?.remove(own.id) }
    }

    private val api = FakeApi()

    private fun schedule(vararg days: Pair<LocalDate, Long?>) = JSONObject()
        .put("title", "민지")
        .put("types", JSONArray()
            .put(JSONObject().put("id", 1).put("name", "주간").put("short", "주").put("color", "#1E88E5"))
            .put(JSONObject().put("id", 3).put("name", "야간").put("short", "야").put("color", "#5E35B1")))
        .put("days", JSONArray().apply { days.forEach { (d, t) -> put(JSONObject().put("date", d.toString()).put("type", t ?: JSONObject.NULL)) } })

    private fun week(change: Long?) = schedule(*(0L until 7).map { today.plusDays(it) to (if (it == 2L) change else 1L) }.toTypedArray())

    private fun link(id: String) = ShareLinkState(id, "https://share.example/s/$id", "token-$id", Instant.now().plus(Duration.ofDays(20)), "나", 30, null)

    @Before fun setUp() {
        c.shareApi = api
        ShareLinkKey.all(PersonalCalendar.MAIN).forEach { c.shareLinksOf(it).clear() }
        c.received.calendars.value.forEach { c.received.remove(it.id) }
        c.shareGroup.clear()
    }

    @After fun tearDown() {
        ShareLinkKey.all(PersonalCalendar.MAIN).forEach { c.shareLinksOf(it).clear() }
        c.received.calendars.value.forEach { c.received.remove(it.id) }
        c.shareGroup.clear()
    }

    @Test fun aChangeInTheNextWeekOfAReceivedScheduleIsALine() = runBlocking {
        val id = "Ab3dEfGh5JkLmNoPqRsTuv"
        c.received.put(id, ReceivedShare(week(1L), Instant.now().plus(Duration.ofDays(10)), null, "v1"))
        api.next = ReceivedShare(week(3L), Instant.now().plus(Duration.ofDays(10)), null, "v2")
        val lines = PlazaNewsJob.receivedChanges(c, today)
        assertEquals(1, lines.size)
        assertTrue(lines.single(), lines.single().contains("11/5 주간 → 야간"))
        // Unchanged since (the server answers 304): nothing to say.
        api.next = null
        assertTrue(PlazaNewsJob.receivedChanges(c, today).isEmpty())
        // The same week again: no line.
        val same = SharedSchedule.parse(week(3L))
        assertTrue(ReceivedChanges.nextDays(same, same, today).isEmpty())
        assertTrue("no old schedule, nothing to compare", ReceivedChanges.nextDays(null, same, today).isEmpty())
    }

    @Test fun cutAllCutsWhatItReachesAndKeepsTheRest() = runBlocking {
        val first = c.shareLinksOf(ShareLinkKey.of(PersonalCalendar.MAIN, 1))
        val second = c.shareLinksOf(ShareLinkKey.of(PersonalCalendar.MAIN, 2))
        first.save(link("A1b2C3d4E5f6G7h8J9k0Lm"))
        second.save(link("Z9y8X7w6V5u4T3s2R1q0Pn"))
        assertEquals(2, c.shareSync.allLinks().size)
        api.offline += "Z9y8X7w6V5u4T3s2R1q0Pn"
        assertEquals(1, c.shareSync.cutAll())
        assertEquals(listOf("A1b2C3d4E5f6G7h8J9k0Lm"), api.deleted)
        assertNull(first.link.value)
        assertNotNull("the one not reached stays, to try again", second.link.value)
        api.offline.clear()
        assertEquals(0, c.shareSync.cutAll())
        assertTrue(c.shareSync.allLinks().isEmpty())
    }

    @Test fun theShareCardKnowsWhenItLastSentAndWhatWaits() {
        val store = c.shareLinksOf(PersonalCalendar.MAIN)
        store.save(link("A1b2C3d4E5f6G7h8J9k0Lm"))
        assertNull(store.sentAt)
        val at = Instant.parse("2026-11-03T01:00:00Z")
        store.sendResult(at)
        assertEquals(at, store.sentAt)
        assertFalse(store.unsent.value)
        store.sendResult(null)
        assertTrue(store.unsent.value)
        assertEquals("the last good send stays", at, store.sentAt)
        store.clear()
        assertFalse(store.unsent.value)
        assertNull(store.sentAt)
    }

    @Test fun aGroupCodeIsSixLettersAndTheBoardSitsSideBySide() {
        assertEquals("ABC234", ShareGroup.code(" abc-234 "))
        assertNull("0 and 1 are not in codes", ShareGroup.code("ABC210"))
        assertNull(ShareGroup.code("ABCDE"))
        val g = ShareGroup.parse(JSONObject().put("code", "ABC234").put("expiresAt", "2026-12-01T00:00:00Z").put("links", JSONArray().put("a").put("b")))
        assertEquals(listOf("a", "b"), g.links)
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), g.expiresAt)
        assertTrue(ShareGroup.invite("ABC234").contains("ABC234"))

        val me = SharedSchedule.parse(schedule(today to 1L, today.plusDays(1) to null))
        val her = SharedSchedule.parse(schedule(today to 3L, today.plusDays(1) to null))
        val members = listOf(
            ShareGroupBoard.Member("a", "나", true, me),
            ShareGroupBoard.Member("b", ShareGroupBoard.name("  ", 1), false, her),
            ShareGroupBoard.Member("c", ShareGroupBoard.name(null, 2), false, null),
        )
        val rows = ShareGroupBoard.rows(members, today, days = 3)
        assertEquals(listOf("주간", "쉬는 날", null), rows[0].cells)
        assertEquals(listOf("야간", "쉬는 날", null), rows[1].cells)
        assertEquals("동료 2", rows[1].name)
        assertEquals(listOf(null, null, null), rows[2].cells)
        assertEquals(listOf(today.plusDays(1)), ShareGroupBoard.restTogether(members, today, days = 3))
    }

    @Test fun startingJoiningAndLeavingAGroup() = runBlocking {
        val mine = link("A1b2C3d4E5f6G7h8J9k0Lm")
        val started = api.groupStart(mine)
        c.shareGroup.save(started.code, PersonalCalendar.MAIN)
        assertEquals("ABC234", c.shareGroup.code.value)
        assertEquals(PersonalCalendar.MAIN, c.shareGroup.ownKey)
        val joined = api.groupJoin("ABC234", link("Z9y8X7w6V5u4T3s2R1q0Pn"))
        assertEquals(2, joined.links.size)
        api.groupLeave("ABC234", mine)
        assertEquals(listOf("Z9y8X7w6V5u4T3s2R1q0Pn"), api.groupRead("ABC234").links)
        c.shareGroup.clear()
        assertNull(c.shareGroup.code.value)
        assertNull(c.shareGroup.ownKey)
    }

    @Test fun theOutboxKeepsFiveAndForgetsWhatWasSent() {
        val store = InquiryStore(app)
        store.outbox.value.forEach { store.unqueue(it.requestId) }
        val info = InquiryInfo("1.23.0", "Pixel", "15")
        fun q(n: Int) = QueuedInquiry("request-id-000000$n", InquiryKind.BUG, "알람이 안 울려요 $n", info, Instant.parse("2026-11-03T00:00:00Z"))
        (1..InquiryStore.OUTBOX_MAX).forEach { assertTrue(store.queue(q(it))) }
        assertFalse("full", store.queue(q(9)))
        assertTrue("the same request again replaces itself", store.outbox.value.size == InquiryStore.OUTBOX_MAX)
        store.refused("request-id-0000001", "text")
        assertEquals("text", store.outbox.value.first { it.requestId == "request-id-0000001" }.refused)
        // Read back from the file as the job would after a restart.
        val again = InquiryStore(app)
        assertEquals(InquiryStore.OUTBOX_MAX, again.outbox.value.size)
        assertEquals("알람이 안 울려요 2", again.outbox.value[1].text)
        (1..InquiryStore.OUTBOX_MAX).forEach { again.unqueue("request-id-000000$it") }
        assertTrue(again.outbox.value.isEmpty())
    }

    @Test fun theDigestReadsReportOutcomes() {
        val item = PlazaNewsJob.item(JSONObject().put("kind", "post_result").put("result", "hidden").put("post", "p123").put("at", "2026-11-03T00:00:00Z"))
        assertEquals("post_result", item.kind)
        assertEquals("hidden", item.result)
        assertEquals("p123", item.post)
        assertEquals(Instant.parse("2026-11-03T00:00:00Z").toEpochMilli(), item.atMillis)
    }

    @Test fun theServerCardSaysWhatItFound() {
        val now = Instant.parse("2026-11-03T03:00:00Z")
        val lines = ServerCheck.lines(ServerCheck.Facts(120, null, true, now.minusSeconds(2 * 3600), listOf("문의 사진"), 2,
            LocalDate.of(2026, 11, 2), statsOn = true), now, ZoneId.of("Asia/Seoul"))
        assertEquals("회사 서버: 연결됨 (120ms)", lines[0])
        assertEquals("근무표 공유 서버: 연결 안 됨 · 링크가 앱으로 열려요", lines[1])
        assertEquals("마지막으로 설정을 받은 때: 2시간 전", lines[2])
        assertEquals("잠시 쉬는 기능: 문의 사진", lines[3])
        assertTrue(lines[4].startsWith("보내지 못한 것: 2개"))
        assertEquals("사용 통계를 마지막으로 보낸 날: 11월 2일", lines[5])
        val quiet = ServerCheck.lines(ServerCheck.Facts(null, null, null, null, emptyList(), 0, null, statsOn = false), now)
        assertEquals(5, quiet.size)
        assertEquals("보내지 못한 것: 없음", quiet[4])
    }

    @Test fun theConsentSummaryHasEveryLineWithAPlaceToChangeIt() {
        val f = ConsentSummary.Facts(usageStats = false, news = true, trialAnswer = null, notifications = false, fullScreen = null,
            exact = true, battery = false, calendar = false, location = false, paymentRead = false, health = false, lock = true)
        val lines = ConsentSummary.lines(f)
        assertFalse("no trial answer, no line", lines.any { it.change == ConsentSummary.Change.PRO })
        assertFalse("before Android 14 there is no full-screen line", lines.any { it.change == ConsentSummary.Change.FULL_SCREEN })
        assertEquals("보내지 않아요", lines.first { it.change == ConsentSummary.Change.USAGE_STATS }.state)
        assertFalse(lines.first { it.change == ConsentSummary.Change.NOTIFICATIONS }.on)
        assertEquals(lines.size, lines.map { it.change }.toSet().size)
        val withTrial = ConsentSummary.lines(f.copy(trialAnswer = "keep", fullScreen = true))
        assertEquals(lines.size + 2, withTrial.size)
    }

    @Test fun theAppLockHidesTheScreenFromRecentsAndCaptures() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        com.bamnat.app.AppLock.secure(activity.window, true)
        assertTrue(activity.window.attributes.flags and secure != 0)
        com.bamnat.app.AppLock.secure(activity.window, false)
        assertEquals(0, activity.window.attributes.flags and secure)
    }
}
