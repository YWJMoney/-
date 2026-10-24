package com.bamnat.core

import com.bamnat.core.pay.PaySince
import com.bamnat.core.plaza.RepeatGuard
import com.bamnat.core.plaza.SetupDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** [COMM-13][COMM-14] 1.24 묶음 S: 같은 글 되풀이, 같은 회사 카드 구별, 확인 중 이유, 금액 시작 달 (점검 보고서 중-11·12, 하 '광장'). */
class PlazaEntryTest {
    private val t0 = Instant.parse("2026-10-14T01:30:00Z")

    @Test fun `the third same post in ten minutes is held back, spaces and marks do not make it new`() {
        var sent = emptyList<RepeatGuard.Sent>()
        repeat(2) { i ->
            val body = if (i == 0) "대근 12% 맞아요" else "대근 12%  맞아요!!"
            assertTrue(RepeatGuard.allowed(sent, "a", body, t0.plusSeconds(i * 60L)))
            sent = RepeatGuard.record(sent, "a", body, t0.plusSeconds(i * 60L))
        }
        assertFalse(RepeatGuard.allowed(sent, "a", "대근 12% 맞아요", t0.plusSeconds(180)))
        // Another talk, other words, or later on: fine.
        assertTrue(RepeatGuard.allowed(sent, "b", "대근 12% 맞아요", t0.plusSeconds(180)))
        assertTrue(RepeatGuard.allowed(sent, "a", "명세서로 확인했어요", t0.plusSeconds(180)))
        assertTrue(RepeatGuard.allowed(sent, "a", "대근 12% 맞아요", t0.plusSeconds(11 * 60L)))
        assertEquals("old ones are dropped", 1, RepeatGuard.record(sent, "a", "x", t0.plusSeconds(15 * 60L)).size)
    }

    private val base = listOf("근무" to "주간 07:00~19:00 · 야간 19:00~07:00", "순서" to "8일")
    private val a = SetupDiff.Item("s1", "", adopted = false, version = null, at = 1, lines = base + ("대근" to "기본급의 10%"))
    private val b = SetupDiff.Item("s2", "", adopted = false, version = null, at = 2, lines = base + ("대근" to "기본급의 12%"))
    private val other = SetupDiff.Item("s3", "포장팀", adopted = true, version = 2, at = 0, lines = base)

    @Test fun `two sets of one department get a name and the line only each has`() {
        val labels = SetupDiff.labels(listOf(a, b, other))
        assertEquals("제보 1", labels["s1"])
        assertEquals("제보 2", labels["s2"])
        assertNull("alone in its department", labels["s3"])
        val diff = SetupDiff.diff(listOf(a, b, other))
        assertEquals("다른 점: 대근 기본급의 10%", diff["s1"])
        assertEquals("다른 점: 대근 기본급의 12%", diff["s2"])
        assertNull(diff["s3"])
        val adopted = SetupDiff.labels(listOf(a.copy(adopted = true, version = 2), b))
        assertEquals("채택 v2", adopted["s1"])
        assertEquals("제보 1", adopted["s2"])
    }

    @Test fun `full points but waiting says why`() {
        assertNull(SetupDiff.waitReason(2, 3, adopted = false, agree = 100))
        assertNull(SetupDiff.waitReason(3, 3, adopted = true, agree = 67))
        assertEquals("달라요가 많아 개발자 확인 대기", SetupDiff.waitReason(3, 3, adopted = false, agree = 67))
        assertEquals("전에 거절된 설정이라 개발자 확인 대기", SetupDiff.waitReason(3, 3, adopted = false, agree = 100, rejectedBefore = true))
    }

    @Test fun `the pay starts a year back unless the hire date is later`() {
        val now = YearMonth.of(2026, 10)
        assertEquals(YearMonth.of(2025, 10), PaySince.start(now, null))
        assertEquals(YearMonth.of(2025, 10), PaySince.start(now, LocalDate.of(2019, 3, 2)))
        assertEquals(YearMonth.of(2026, 4), PaySince.start(now, LocalDate.of(2026, 4, 15)))
        assertTrue("입사" in PaySince.why(LocalDate.of(2026, 4, 15), now))
        assertTrue("1년 전" in PaySince.why(null, now))
    }
}
