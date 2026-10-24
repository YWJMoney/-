package com.bamnat.core

import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.journal.Journal
import com.bamnat.core.journal.JournalEntry
import com.bamnat.core.journal.JournalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** [OBS-01][OBS-02] The log of what the app did by itself. */
class JournalTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant()
    private val today = LocalDate.of(2026, 10, 23)

    @Test fun `one line per kind a day, counting`() {
        var log = emptyList<JournalEntry>()
        log = Journal.add(log, JournalEntry(at(today, 4), JournalKind.ALARMS_PLANNED, detail = "06:30"), zone)
        log = Journal.add(log, JournalEntry(at(today, 9), JournalKind.ALARMS_PLANNED, detail = "06:10"), zone)
        log = Journal.add(log, JournalEntry(at(today, 9), JournalKind.BACKUP), zone)
        log = Journal.add(log, JournalEntry(at(today.plusDays(1), 4), JournalKind.ALARMS_PLANNED), zone)
        assertEquals(3, log.size)
        val first = log.first()
        assertEquals(2, first.count)
        assertEquals("06:10", first.detail)
        assertEquals("다음 알람 06:10 · 알람 다시 잡음 · 2번", Journal.text(first))
        assertEquals("오늘 밤낮이 한 일 2개", Journal.todayLine(log, zone, today))
        assertNull(Journal.todayLine(log, zone, today.plusDays(5)))
    }

    @Test fun `ninety days and three hundred lines a day`() {
        val old = JournalEntry(at(today.minusDays(Journal.KEEP_DAYS), 12), JournalKind.NEWS)
        val kept = JournalEntry(at(today.minusDays(Journal.KEEP_DAYS - 1), 12), JournalKind.NEWS)
        assertEquals(listOf(kept), Journal.trim(listOf(old, kept), zone, today))
        val many = (0 until Journal.PER_DAY + 20).map { JournalEntry(Instant.ofEpochSecond(at(today, 0).epochSecond + it), JournalKind.WIDGETS) }
        val trimmed = Journal.trim(many, zone, today)
        assertEquals(Journal.PER_DAY, trimmed.size)
        assertEquals(many.last(), trimmed.last())
    }

    @Test fun `a detail never holds an amount or a name`() {
        assertEquals("06:30", Journal.safeDetail("06:30"))
        assertEquals("2026102301", Journal.safeDetail("2026102301"))
        assertEquals("3일치", Journal.safeDetail("3일치"))
        assertNull(Journal.safeDetail("12,000원"))
        assertNull(Journal.safeDetail("12000원"))
        assertNull(Journal.safeDetail("홍길동"))
        assertNull(Journal.safeDetail("스타벅스"))
        assertNull(Journal.add(emptyList(), JournalEntry(at(today, 1), JournalKind.MONEY_CAPTURED, detail = "4,500원 스타벅스"), zone).single().detail)
    }

    @Test fun `every kind belongs to a feature and says why`() {
        JournalKind.entries.forEach { k ->
            assertTrue(k.feature, FeatureRegistry.get(k.feature) != null)
            assertTrue(k.why.endsWith("요"))
            assertTrue(Journal.text(JournalEntry(at(today, 1), k)).isNotBlank())
        }
        // The ten places of the design memo (with the failed backup as the backup's other line), and 1.24's 한 달 근무 넣기.
        assertEquals(12, JournalKind.entries.size)
    }
}
