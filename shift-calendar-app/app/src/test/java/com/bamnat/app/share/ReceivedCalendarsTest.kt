package com.bamnat.app.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant

/** [SHR-02] Received calendars stay on this phone only while the sharer keeps the link. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ReceivedCalendarsTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = Instant.parse("2026-11-01T00:00:00Z")
    private val file get() = java.io.File(folder.root, "received_calendars.json")
    private fun store() = ReceivedCalendarStore(file) { now }

    private fun share(title: String, days: Long = 30) = ReceivedShare(
        JSONObject("""{"v":1,"title":"$title","types":[{"id":1,"name":"주간","short":"주","color":"#1E88E5","time":"07:00-19:00","off":false}],
            "days":[{"date":"2026-11-01","type":1}]}"""),
        now.plus(Duration.ofDays(days)), now,
    )

    @Test fun keepsAndReloads() {
        val s = store()
        val a = s.put("AAAAAAAAAAAAAAAA", share("민지"))
        val b = s.put("BBBBBBBBBBBBBBBB", share(" "))
        assertEquals(ReceivedCalendar.COLORS[0], a.color)
        assertEquals(ReceivedCalendar.COLORS[1], b.color)
        assertEquals("민지의 근무표", a.fullName)
        assertEquals("민", a.initial)
        assertEquals("이름 없는 근무표", b.fullName)
        assertEquals("?", b.initial)

        // A new start of the app reads the same list, on the user's own calendar.
        val again = store()
        assertEquals(listOf("AAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBB"), again.calendars.value.map { it.id })
        assertEquals("주간", again.get("AAAAAAAAAAAAAAAA")!!.schedule!!.types.single().name)
        assertNull(again.viewing.value)
    }

    @Test fun updatingKeepsColourAndWhenItCame() {
        val s = store()
        val first = s.put("AAAAAAAAAAAAAAAA", share("민지"))
        now = now.plus(Duration.ofHours(3))
        val second = s.put("AAAAAAAAAAAAAAAA", share("민지 (새 근무)"))
        assertEquals(1, s.calendars.value.size)
        assertEquals(first.color, second.color)
        assertEquals(first.addedAt, second.addedAt)
        assertEquals(now, second.fetchedAt)
        assertEquals("민지 (새 근무)", second.title)
    }

    @Test fun cutLinksLoseTheirSchedule() {
        val s = store()
        s.put("AAAAAAAAAAAAAAAA", share("민지"))
        s.markGone("AAAAAAAAAAAAAAAA")
        val gone = s.get("AAAAAAAAAAAAAAAA")!!
        assertTrue(gone.gone)
        assertNull(gone.schedule)
        // Nothing of the schedule is left in the file either.
        assertFalse(file.readText().contains("주간"))
        assertEquals("민지의 근무표", gone.fullName)
    }

    @Test fun expiredLinksGoByThePhonesClock() {
        val s = store()
        s.put("AAAAAAAAAAAAAAAA", share("민지", days = 7))
        s.put("BBBBBBBBBBBBBBBB", share("준호", days = 30))
        now = now.plus(Duration.ofDays(8))
        s.expire()
        assertTrue(s.get("AAAAAAAAAAAAAAAA")!!.gone)
        assertFalse(s.get("BBBBBBBBBBBBBBBB")!!.gone)
    }

    @Test fun viewingFollowsTheList() {
        val s = store()
        s.put("AAAAAAAAAAAAAAAA", share("민지"))
        s.view("AAAAAAAAAAAAAAAA")
        assertEquals("AAAAAAAAAAAAAAAA", s.viewing.value)
        s.view("ZZZZZZZZZZZZZZZZ")
        assertNull(s.viewing.value)
        s.view("AAAAAAAAAAAAAAAA")
        s.remove("AAAAAAAAAAAAAAAA")
        assertNull(s.viewing.value)
        assertTrue(s.calendars.value.isEmpty())
        assertTrue(store().calendars.value.isEmpty())
    }

    @Test fun aBrokenFileStartsEmpty() {
        file.writeText("{not json")
        assertTrue(store().calendars.value.isEmpty())
    }
}
