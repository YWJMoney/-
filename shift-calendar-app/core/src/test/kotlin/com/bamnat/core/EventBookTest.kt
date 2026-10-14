package com.bamnat.core

import com.bamnat.core.diag.DiagEvent
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.diag.EventBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventBookTest {
    @Test
    fun `lines round trip and tabs or newlines in detail cannot break a line`() {
        val e = DiagEvent(123L, "alarm_fired", "at=5\tx\ny")
        val back = EventBook.decode(EventBook.encode(e))!!
        assertEquals(123L, back.atMillis)
        assertEquals("alarm_fired", back.name)
        assertEquals("at=5 x y", back.detail)
        assertNull(EventBook.decode("garbage"))
        assertNull(EventBook.decode("12\t"))
        assertEquals(2, EventBook.decodeAll("1\ta\n\nbad\n2\tb\tc").size)
    }

    @Test
    fun `log is trimmed to the newest lines only past the limit`() {
        assertNull(EventBook.trimmed(List(EventBook.MAX) { "$it" }))
        val cut = EventBook.trimmed(List(EventBook.MAX + 1) { "$it" })!!
        assertEquals(EventBook.KEEP, cut.size)
        assertEquals("${EventBook.MAX}", cut.last())
    }

    @Test
    fun `counts only the window and splits screens`() {
        val events = listOf(
            DiagEvent(1, DiagEvents.APP_OPEN),
            DiagEvent(10, DiagEvents.APP_OPEN),
            DiagEvent(11, DiagEvents.APP_OPEN),
            DiagEvent(12, DiagEvents.SCREEN, "stats"),
        )
        assertEquals(listOf("app_open" to 2, "screen:stats" to 1), EventBook.counts(events, sinceMillis = 10))
    }

    @Test
    fun `a planned alarm with no delivery is reported, a replaced or delivered one is not`() {
        val min = 60_000L
        val t1 = 100 * min
        val t2 = 200 * min
        val t3 = 300 * min
        val events = listOf(
            // t1 planned and delivered
            DiagEvent(0, DiagEvents.ALARM_SCHEDULED, EventBook.triggerDetail(t1)),
            DiagEvent(t1, DiagEvents.ALARM_FIRED, EventBook.triggerDetail(t1)),
            // t2 planned, then the schedule changed to t3 before t2
            DiagEvent(t1 + 1, DiagEvents.ALARM_SCHEDULED, EventBook.triggerDetail(t2)),
            DiagEvent(t1 + 2, DiagEvents.ALARM_SCHEDULED, EventBook.triggerDetail(t3, "exact=true")),
            // t3 never fired
        )
        assertEquals(listOf(t3), EventBook.possiblyMissed(events, nowMillis = t3 + 30 * min))
        // Still inside the grace period: not reported yet.
        assertEquals(emptyList<Long>(), EventBook.possiblyMissed(events, nowMillis = t3 + 5 * min))
        val cleared = events + DiagEvent(t3 - 1, DiagEvents.ALARM_CLEARED)
        assertEquals(emptyList<Long>(), EventBook.possiblyMissed(cleared, nowMillis = t3 + 30 * min))
    }
}
