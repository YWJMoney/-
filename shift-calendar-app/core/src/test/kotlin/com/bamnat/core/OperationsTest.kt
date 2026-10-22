package com.bamnat.core

import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.ops.AppNotice
import com.bamnat.core.ops.AppNotices
import com.bamnat.core.ops.ServerSwitch
import com.bamnat.core.plaza.HideList
import com.bamnat.core.plaza.PlazaNews
import com.bamnat.core.plaza.PlazaNewsItem
import com.bamnat.core.plaza.PlazaRules
import com.bamnat.core.stats.UsageBook
import com.bamnat.core.stats.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** [STAT-01][SRV-04][COMM-08][COMM-09][COMM-10] 1.22 묶음 B: what the phone keeps for running the service. */
class OperationsTest {
    private val iso: (Long) -> String = { LocalDate.ofEpochDay(it).toString() }
    private val d = LocalDate.of(2026, 10, 21).toEpochDay()

    @Test
    fun `usage counts are names and numbers per finished day, never today`() {
        var b = UsageBook()
        repeat(3) { b = b.record(UsageEvent.OPEN, d) }
        b = b.record(UsageEvent.PAY_VIEW, d).record(UsageEvent.OPEN, d + 1)
        assertEquals(listOf(d), b.due(d + 1))
        assertEquals(
            "{\"v\":1,\"app\":\"1.22.0\",\"days\":[{\"day\":\"2026-10-21\",\"events\":{\"open\":3,\"pay_view\":1},\"recent\":[\"open\",\"pay_view\"]}]}",
            b.bundle(d + 1, "1.22.0", iso),
        )
        val after = b.sent(d + 1)
        assertNull(after.bundle(d + 1, "1.22.0", iso))
        // What happened today stays for tomorrow, and "recent" remembers sent days.
        assertEquals(listOf("open", "pay_view"), after.recent(d + 1))
        assertEquals(after, UsageBook.decode(after.encode()))
    }

    @Test
    fun `recent covers 28 days and old unsent days are dropped`() {
        var b = UsageBook().record(UsageEvent.SHARE_MADE, d)
        b = b.record(UsageEvent.OPEN, d + 27).record(UsageEvent.OPEN, d + 28)
        assertTrue("share_made" in b.recent(d + 27))
        assertFalse("share_made" in b.recent(d + 28))
        // Days older than a week before now are not sent (the server takes 7).
        assertEquals(listOf(d + 27), b.due(d + 28))
        assertEquals(UsageBook.MAX_COUNT, (1..1500).fold(UsageBook()) { x, _ -> x.record(UsageEvent.OPEN, d) }.days[d]!!["open"])
    }

    @Test
    fun `log lines map to counted events and the rest are not counted`() {
        assertEquals(UsageEvent.OPEN, UsageEvent.fromLog(DiagEvents.APP_OPEN, ""))
        assertEquals(UsageEvent.PAY_VIEW, UsageEvent.fromLog(DiagEvents.SCREEN, "pay"))
        assertNull(UsageEvent.fromLog(DiagEvents.SCREEN, "settings"))
        assertNull(UsageEvent.fromLog(DiagEvents.ALARM_SCHEDULED, "08:00"))
        assertEquals(UsageEvent.TRIAL_START, UsageEvent.fromLog("trial_start", ""))
        assertEquals(UsageEvent.PAY_SETUP, UsageEvent.fromLog("pay_setup_done", "COMPANY"))
        assertEquals(UsageEvent.PLAZA_APPLY, UsageEvent.fromLog("plaza_rate", "applied"))
        assertEquals(UsageEvent.PLAZA_POST, UsageEvent.fromLog("plaza_rate", "sent"))
        assertNull(UsageEvent.fromLog(DiagEvents.COMPANY_SHARE, ""))
        // A stored book from a newer app keeps only names this app knows.
        assertEquals(mapOf("open" to 2), UsageBook.decode("d\t$d\topen=2,someday=4\ns\tsomeday\t$d").days[d])
    }

    @Test
    fun `a notice shows while it runs, for its versions, until closed`() {
        val a = AppNotice("n000000001", "점검", "새벽에 쉬어요", important = false, from = 100, until = 200)
        val b = a.copy(id = "n000000002", important = true, from = 150, minApp = 33)
        assertEquals(a, AppNotices.showing(listOf(a, b), now = 120, versionCode = 33, dismissed = emptySet()))
        assertEquals(b, AppNotices.showing(listOf(a, b), now = 160, versionCode = 33, dismissed = emptySet()))
        assertEquals(a, AppNotices.showing(listOf(a, b), now = 160, versionCode = 32, dismissed = emptySet()))
        assertNull(AppNotices.showing(listOf(a, b), now = 160, versionCode = 32, dismissed = setOf(a.id)))
        assertNull(AppNotices.showing(listOf(a), now = 200, versionCode = 33, dismissed = emptySet()))
        assertEquals(setOf(a.id), AppNotices.keepDismissed(setOf(a.id, "n999999999"), listOf(a, b)))
        assertEquals(ServerSwitch.PLAZA_WRITE, ServerSwitch.of("PLAZA_WRITE"))
        assertNull(ServerSwitch.of("NEW_THING"))
    }

    @Test
    fun `hidden writers stay on the phone, newest first, at most 50`() {
        val mark = { i: Int -> i.toString().padStart(64, 'x') }
        var h = HideList()
        for (i in 0 until 55) h = h.hide(mark(i), "참여자 $i", i.toLong())
        assertEquals(50, h.writers.size)
        assertEquals(mark(54), h.writers.first().mark)
        h = h.hide(mark(10), "참여자 10", 99)
        assertEquals(50, h.writers.size)
        assertEquals(mark(10), h.writers.first().mark)
        assertEquals(h, HideList.decode(h.encode()))
        assertEquals(h.writers.size - 1, h.show(mark(10)).writers.size)
        assertEquals(h, h.hide("short", "x", 1))
        assertNull(HideList().header())
        assertEquals(50, h.header()!!.split(',').size)
    }

    @Test
    fun `plaza news is one bundled notice in plain words`() {
        val t = PlazaNews.text(listOf(
            PlazaNewsItem("changed", company = "예시정밀", dept = "생산1팀", version = 3, atMillis = 2),
            PlazaNewsItem("rate", trade = "용접", atMillis = 1),
        ))!!
        assertEquals("광장 소식 2개", t.title)
        assertEquals("예시정밀 생산1팀 규칙이 v3으로 바뀌었어요. 눌러서 바꿀지 골라요.", t.lines[0])
        assertEquals("올린 용접 임금표가 시세에 들어갔어요.", t.lines[1])
        assertNull(PlazaNews.text(listOf(PlazaNewsItem("unknown"))))
        assertFalse(PlazaRules.agreed(0))
        assertTrue(PlazaRules.agreed(PlazaRules.VERSION))
        // No line asks for or shows an amount.
        assertTrue(PlazaRules.LINES.none { Regex("\\d+원").containsMatchIn(it) })
    }
}
