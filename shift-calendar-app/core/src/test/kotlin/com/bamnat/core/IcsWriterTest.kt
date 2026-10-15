package com.bamnat.core

import com.bamnat.core.model.WorkPattern
import com.bamnat.core.schedule.IcsWriter
import com.bamnat.core.schedule.ScheduleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class IcsWriterTest {
    private val start = LocalDate.of(2026, 10, 1)
    private val resolver = ScheduleResolver(
        Fixtures.all,
        WorkPattern.Rotation(start, listOf(Fixtures.day.id, Fixtures.night.id, Fixtures.off.id)),
        emptyList(),
    )
    private val ics = IcsWriter.write(
        resolver.resolveRange(start, start.plusDays(2)),
        ZoneId.of("Asia/Seoul"),
        nowUtc = LocalDateTime.of(2026, 10, 15, 0, 0),
    )

    @Test
    fun `work days become events and off days are left out`() {
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertTrue(ics.contains("SUMMARY:주간"))
        assertTrue(ics.contains("SUMMARY:야간"))
        assertFalse(ics.contains("SUMMARY:휴무"))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun `a night shift ends the next morning`() {
        assertTrue(ics.contains("DTSTART;TZID=Asia/Seoul:20261002T220000"))
        assertTrue(ics.contains("DTEND;TZID=Asia/Seoul:20261003T070000"))
        assertTrue(ics.contains("UID:20261002@bamnat.app"))
    }

    @Test
    fun `long lines are folded at 75 bytes without splitting a character`() {
        val folded = IcsWriter.fold("SUMMARY:" + "가".repeat(40))
        val parts = folded.split("\r\n")
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals("SUMMARY:" + "가".repeat(40), parts.joinToString("") { it.removePrefix(" ") })
    }
}
