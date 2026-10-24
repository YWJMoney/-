package com.bamnat.core

import com.bamnat.core.journal.Journal
import com.bamnat.core.journal.JournalEntry
import com.bamnat.core.journal.JournalKind
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.pay.WageType
import com.bamnat.core.profile.Fact
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.FactText
import com.bamnat.core.profile.Needs
import com.bamnat.core.profile.Profile
import com.bamnat.core.profile.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** [EASY-08][EASY-09][OBS-02] 내 정보's words, 모두 처음부터's mark and the home line of 밤낮이 한 일. */
class EasyProfileTextTest {
    private fun <T> fact(key: FactKey, value: T?, source: Source = Source.TYPED, why: String? = null, confidence: Double = 1.0) =
        Fact(key, value, source, confidence, null, why)

    @Test fun `a fact in a few words, never a long or private value`() {
        assertEquals("월급", FactText.of(fact(FactKey.WAGE_TYPE, WageType.MONTHLY)))
        assertEquals("매달 25일", FactText.of(fact(FactKey.PAY_DAY, 25)))
        assertEquals("8시간", FactText.of(fact(FactKey.DAILY_HOURS, 480)))
        assertEquals("7시간 30분", FactText.of(fact(FactKey.SLEEP_TARGET, 450)))
        assertEquals("3.3%", FactText.of(fact(FactKey.TAX_MODE, TaxMode.WITHHOLDING_3_3)))
        assertEquals("2명", FactText.of(fact(FactKey.TAX_FAMILY, 2 to 0)))
        assertEquals("근무 3종류", FactText.of(fact(FactKey.SHIFTS, listOf(1, 2, 3))))
        // 기본급 is only said to be there.
        assertEquals("정해 둠", FactText.of(fact(FactKey.BASE_PAY, 2_500_000L)))
        assertEquals(FactText.NOT_KNOWN, FactText.of(fact<Int>(FactKey.PAY_DAY, null)))
    }

    @Test fun `the edit sheet starts from the value as its answer writes it`() {
        assertEquals("MONTHLY", FactText.answerOf(fact(FactKey.WAGE_TYPE, WageType.MONTHLY)))
        assertEquals("8", FactText.answerOf(fact(FactKey.DAILY_HOURS, 480)))
        assertEquals("2", FactText.answerOf(fact(FactKey.TAX_FAMILY, 2 to 0)))
        assertEquals("true", FactText.answerOf(fact(FactKey.FIVE_OR_MORE, true)))
        assertNull(FactText.answerOf(fact(FactKey.BASE_PAY, 1L)))
    }

    @Test fun `what a change reaches, and what a feature waits for`() {
        assertTrue(FactText.applies(FactKey.BASE_PAY), FactText.applies(FactKey.BASE_PAY).endsWith("곳에 바로 적용돼요"))
        assertTrue(FactText.applies(FactKey.BASE_PAY).startsWith("급여 계산"))
        val facts = mapOf(FactKey.HIRE_DATE to fact<LocalDate>(FactKey.HIRE_DATE, null), FactKey.DAILY_HOURS to fact(FactKey.DAILY_HOURS, 480))
        val line = FactText.startsWith(facts, "leave.ledger")
        assertEquals("이걸 알려 주면 시작해요 · 입사일", line)
        assertNull(FactText.startsWith(facts + (FactKey.HIRE_DATE to fact(FactKey.HIRE_DATE, LocalDate.of(2024, 3, 1))), "leave.ledger"))
    }

    @Test fun `모두 처음부터 asks a known value again until it is said again`() {
        val again = fact(FactKey.PAY_DAY, 25, Source.INFERRED, Profile.RECHECK, Profile.MIN_CONFIDENCE)
        assertTrue(Needs.isMissing(again))
        assertTrue(again.estimated)
        // A payslip's guess at 80% is not asked again by itself.
        assertTrue(!Needs.isMissing(fact(FactKey.PAY_DAY, 25, Source.INFERRED, "명세서 사진", 0.8)))
    }

    @Test fun `the home line waits for more than the usual, and the list goes newest first`() {
        val zone = ZoneId.of("Asia/Seoul")
        val today = LocalDate.of(2026, 10, 23)
        fun at(d: LocalDate, h: Int) = d.atTime(h, 0).atZone(zone).toInstant()
        val usual = listOf(JournalEntry(at(today, 4), JournalKind.ALARMS_PLANNED), JournalEntry(at(today, 5), JournalKind.WIDGETS))
        assertNull(Journal.todayLine(usual, zone, today))
        val more = usual + JournalEntry(at(today, 6), JournalKind.BACKUP) + JournalEntry(at(today.minusDays(1), 9), JournalKind.NEWS)
        assertEquals("오늘 밤낮이 한 일 3개", Journal.todayLine(more, zone, today))
        val days = Journal.byDay(more, zone)
        assertEquals(listOf(today, today.minusDays(1)), days.map { it.first })
        assertEquals(JournalKind.BACKUP, days.first().second.first().kind)
    }
}
