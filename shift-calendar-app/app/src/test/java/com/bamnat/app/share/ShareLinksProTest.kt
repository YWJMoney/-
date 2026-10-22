package com.bamnat.app.share

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bamnat.app.BamnatApp
import com.bamnat.app.data.PersonalCalendar
import com.bamnat.app.tier.Feature
import com.bamnat.app.tier.Tier
import com.bamnat.core.model.ShiftOverride
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * [PROV-04] A calendar's second to fifth links and the 180-day and 1-year periods, which are 프로's;
 * [FEAT-15] the calendar feed a link serves when the sharer turns it on, also 프로's.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ShareLinksProTest {
    private val app get() = ApplicationProvider.getApplicationContext<BamnatApp>()
    private val c get() = app.container
    private val api = FakeApi()
    private val second = ShareLinkKey.of(PersonalCalendar.MAIN, 2)

    private class FakeApi : ShareApi {
        override val enabled = true
        val sent = mutableListOf<JSONObject>()
        override suspend fun create(payload: JSONObject) = error("not used")
        override suspend fun update(state: ShareLinkState, payload: JSONObject): ShareLinkState {
            sent += payload
            val days = payload.optInt("ttlDays", 0)
            return state.copy(expiresAt = if (days > 0) Instant.now().plus(Duration.ofDays(days.toLong())) else state.expiresAt,
                ttlDays = if (days > 0) days else state.ttlDays)
        }
        override suspend fun delete(state: ShareLinkState) = Unit
        override suspend fun read(id: String) = error("not used")
    }

    private fun link(id: String, ttl: Int = 30) = ShareLinkState(id, "https://share.example/s/$id", "token",
        Instant.now().plus(Duration.ofDays(20)), "민지", ttl, YearMonth.now())

    /** The free tier as a phone without 프로 has it after the launch; the alarm check's preview stands in for it. */
    private fun free() = runBlocking {
        c.settingsRepository.update { it.copy(previewTier = Tier.FREE) }
        c.featureGate.state.first { it.locked(Feature.MORE_LINKS) }
    }

    @Before fun setUp() {
        c.shareApi = api
        ShareLinkKey.all(PersonalCalendar.MAIN).forEach { c.shareLinksOf(it).apply { clear(); feed = false; auto = true } }
        runBlocking {
            c.scheduleRepository.ensureDefaults()
            val day = c.scheduleRepository.shiftTypes.first().first { !it.isOff }.id
            c.scheduleRepository.setOverride(LocalDate.now().plusDays(1), ShiftOverride.Shift(day))
        }
    }

    @After fun tearDown() = runBlocking {
        ShareLinkKey.all(PersonalCalendar.MAIN).forEach { c.shareLinksOf(it).apply { clear(); feed = false } }
        c.settingsRepository.update { it.copy(previewTier = null) }
        c.featureGate.state.first { it.allows(Feature.MORE_LINKS) }
        Unit
    }

    @Test fun keysNameTheCalendarAndWhichOfItsLinks() {
        assertEquals(PersonalCalendar.MAIN, ShareLinkKey.of(PersonalCalendar.MAIN, 1))
        assertEquals("main~3", ShareLinkKey.of(PersonalCalendar.MAIN, 3))
        assertEquals("abc", ShareLinkKey.calendar("abc~5"))
        assertEquals(5, ShareLinkKey.slot("abc~5"))
        assertEquals(1, ShareLinkKey.slot("abc"))
        assertEquals("a slot out of range is the first", 1, ShareLinkKey.slot("abc~9"))
        assertEquals(listOf("abc", "abc~2", "abc~3", "abc~4", "abc~5"), ShareLinkKey.all("abc"))
        // The first link stays in the file links were always kept in.
        assertEquals("share_link", ShareLinkStore.prefsName(PersonalCalendar.MAIN))
        assertEquals("share_link_abc", ShareLinkStore.prefsName("abc"))
    }

    @Test fun eachLinkIsKeptApartAndFollowsTheCalendar() = runBlocking {
        val two = c.shareLinksOf(second)
        assertEquals(PersonalCalendar.MAIN, two.calendarId)
        assertEquals(2, two.slot)
        two.save(link("Second0000000000"))
        two.saveOptions(ShareOptions(memos = true))
        assertNull("the first link is untouched", c.shareLinks.link.value)
        assertEquals(ShareOptions(), c.shareLinks.options())
        assertTrue(c.hasShareLink(PersonalCalendar.MAIN))
        // Read again from its own file.
        assertEquals("Second0000000000", ShareLinkStore(app, second).load()?.id)
        assertEquals(ShareSync.Result.Sent, ShareSync(c).sync(second))
        assertEquals(listOf("memos"), api.sent.single().getJSONArray("shows").let { a -> (0 until a.length()).map(a::getString) })
        assertEquals(ShareSync.Result.Same, ShareSync(c).sync(PersonalCalendar.MAIN))
    }

    @Test fun anAddedCalendarTakesAllItsLinksWithIt() = runBlocking {
        val id = c.calendars.begin("알바").also { c.calendars.finishSetup(it, com.bamnat.core.work.WorkStyle.HOURLY) }
        ShareLinkKey.all(id).take(3).forEachIndexed { i, key -> c.shareLinksOf(key).save(link("Added${i}00000000000")) }
        c.forgetShareLinks(id)
        ShareLinkKey.all(id).forEach { assertNull(it, ShareLinkStore(app, it).load()) }
        assertFalse(c.hasShareLink(id))
        c.calendars.delete(id)
    }

    /**
     * [QA-03] Why the test above failed now and then in the whole app run (순번 286): the link sync that follows
     * the data (5 s after the last change) could be sending one of the calendar's links while it was forgotten,
     * and its answer was then saved with the old store into the file just deleted. A store kept from before
     * now writes nothing.
     */
    @Test fun aSendStillOnItsWayDoesNotBringAForgottenLinkBack() = runBlocking {
        val id = c.calendars.begin("알바").also { c.calendars.finishSetup(it, com.bamnat.core.work.WorkStyle.HOURLY) }
        val key = ShareLinkKey.of(id, 2)
        val inFlight = c.shareLinksOf(key).apply { save(link("Late000000000000")) }
        c.forgetShareLinks(id)
        inFlight.save(link("Late000000000000"), sent = "after")
        inFlight.auto = false
        assertNull(ShareLinkStore(app, key).load())
        assertTrue("what the sharer ticked is gone too", ShareLinkStore(app, key).auto)
        assertFalse(c.hasShareLink(id))
        c.calendars.delete(id)
    }

    @Test fun withoutProASecondLinkStaysButIsNotKeptOpenLonger() = runBlocking {
        c.shareLinksOf(second).save(link("Second0000000000"))
        c.shareLinks.save(link("First00000000000", ttl = 365))
        free()
        // It still follows the calendar until its end date.
        assertEquals(ShareSync.Result.Sent, ShareSync(c).sync(second))
        assertEquals(ShareSync.Result.Locked, ShareSync(c).extend(second))
        // The first link is kept open, for 90 days instead of a year.
        assertEquals(ShareSync.Result.Sent, ShareSync(c).extend())
        assertEquals(90, api.sent.last().getInt("ttlDays"))
    }

    @Test fun withProEveryPeriodGoesAsPicked() = runBlocking {
        c.shareLinksOf(second).save(link("Second0000000000", ttl = 180))
        assertEquals(ShareSync.Result.Sent, ShareSync(c).extend(second))
        assertEquals(180, api.sent.single().getInt("ttlDays"))
        assertEquals(ShareSync.Result.Sent, ShareSync(c).extend(second, 365))
        assertEquals(365, api.sent.last().getInt("ttlDays"))
    }

    @Test fun periods() {
        assertEquals(listOf(7, 30, 90, 180, 365), ShareLinkPayload.TTL_DAYS)
        assertEquals("1년", ShareLinkPayload.ttlLabel(365))
        assertEquals("180일", ShareLinkPayload.ttlLabel(180))
        assertEquals(90, ShareLinkPayload.allowedTtl(365, pro = false))
        assertEquals(30, ShareLinkPayload.allowedTtl(30, pro = false))
        assertEquals(365, ShareLinkPayload.allowedTtl(365, pro = true))
    }

    @Test fun theFeedGoesOutOnlyWhenTurnedOnAndWithPro() = runBlocking {
        val store = c.shareLinks
        store.save(link("First00000000000"))
        val sync = ShareSync(c)
        assertEquals(ShareSync.Result.Sent, sync.sync())
        assertFalse("off until the sharer turns it on", api.sent.last().has("ics"))
        store.feed = true
        // Sent at once, even with 고치면 바로 바꾸기 off.
        store.auto = false
        assertEquals(ShareSync.Result.Sent, sync.resend(store.key))
        assertTrue(api.sent.last().getBoolean("ics"))
        assertEquals(ZoneId.systemDefault().id.takeIf { "/" in it }, api.sent.last().optString("tz").ifEmpty { null })
        // Without 프로 the feed goes off on the next send.
        free()
        assertEquals(ShareSync.Result.Sent, sync.resend(store.key))
        assertFalse(api.sent.last().has("ics"))
        assertTrue("the setting stays for when 프로 is back", store.feed)
    }

    @Test fun theFeedAddressAndItsZone() {
        assertEquals("https://share.example/s/Ab3dEfGh5JkLmNoP.ics", link("Ab3dEfGh5JkLmNoP").feedUrl())
        assertEquals("Asia/Seoul", ShareLinkPayload.feedZone(ZoneId.of("Asia/Seoul")))
        assertEquals("America/Argentina/Buenos_Aires", ShareLinkPayload.feedZone(ZoneId.of("America/Argentina/Buenos_Aires")))
        assertNull("the server takes area/place names only", ShareLinkPayload.feedZone(ZoneId.of("UTC")))
        assertNull(ShareLinkPayload.feedZone(ZoneId.of("GMT+09:00")))
        // A received link pasted as its feed address still opens.
        assertEquals("Ab3dEfGh5JkLmNoP", ShareLinkId.find("https://share.example/s/Ab3dEfGh5JkLmNoP.ics"))
    }

    @Test fun theNoticeSaysWhichLink() {
        assertEquals("근무표 링크", ShareExpiryReminder.what(PersonalCalendar.MAIN, null))
        assertEquals("근무표 2번 링크", ShareExpiryReminder.what(second, null))
        assertEquals("‘알바’ 달력 3번 링크", ShareExpiryReminder.what("abc~3", "알바"))
    }

    @Test fun theLinksGoWithABackupAndNoneOnThisPhoneIsLost() = runBlocking {
        val a = "Ab3dEfGh5JkLmNoPqRsTuv"
        val b = "Zz9yXw8vUt7sRq6pOn5mLk"
        c.shareLinks.save(link(a, ttl = 180))
        c.shareLinks.apply { feed = true; auto = false; saveOptions(ShareOptions(memos = true)) }
        c.shareLinksOf(second).save(link(b))
        val json = c.backupManager.export()
        fun ids() = ShareLinkKey.all(PersonalCalendar.MAIN).mapNotNull { c.shareLinksOf(it).link.value?.id }
        fun emptyPhone() = ShareLinkKey.all(PersonalCalendar.MAIN).forEach {
            c.shareLinksOf(it).apply { clear(); feed = false; auto = true; saveOptions(ShareOptions()) }
        }

        // A new phone keeps updating the same links, the feed and what they show too.
        emptyPhone()
        c.backupManager.restore(json)
        val first = c.shareLinks
        assertEquals(a, first.link.value?.id)
        assertEquals(180, first.link.value?.ttlDays)
        assertEquals("token", first.link.value?.deleteToken)
        assertTrue(first.feed)
        assertFalse(first.auto)
        assertTrue(first.options().memos)
        assertNull("sent again at the next update", first.sent)
        assertEquals(b, c.shareLinksOf(second).link.value?.id)

        // A link of this phone's own is never lost: the file's go to the free places, once.
        emptyPhone()
        val own = "Own1Own2Own3Own4Own5Ow"
        c.shareLinks.save(link(own))
        c.backupManager.restore(json)
        assertEquals(own, c.shareLinks.link.value?.id)
        assertEquals(listOf(own, a, b), ids())
        c.backupManager.restore(json)
        assertEquals(listOf(own, a, b), ids())

        // A part that is not a link this app made is left out.
        emptyPhone()
        val bad = JSONObject(json).apply {
            getJSONArray("shareLinks").getJSONObject(0).put("url", "https://elsewhere.example/page")
            getJSONArray("shareLinks").getJSONObject(1).put("expires", Instant.now().minusSeconds(60).toString())
        }
        c.backupManager.restore(bad.toString())
        assertEquals(emptyList<String>(), ids())
    }
}
