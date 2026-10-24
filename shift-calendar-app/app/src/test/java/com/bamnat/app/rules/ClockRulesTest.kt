package com.bamnat.app.rules

import com.bamnat.core.time.Today
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * [QA-08] One clock (결정 D66): the main code of the phone app, core, the watch and the database asks core's Today
 * what day and time it is, never `LocalDate.now()` or `System.currentTimeMillis()` itself, so a screenshot test
 * that fixes the clock draws the same screen on any day. A line that needs the real clock (a new item's id) says
 * so with a `// real clock:` comment.
 */
class ClockRulesTest {
    private val roots = listOf("src/main", "../core/src/main", "../wear/src/main", "../database/src/main").map(::File)
    private val direct = Regex(
        """\b(?:LocalDate|LocalDateTime|LocalTime|YearMonth|ZonedDateTime|OffsetDateTime|Instant)\.now\(|""" +
            """System(?:\.|::)currentTimeMillis\b|ZoneId\.systemDefault\(\)|Clock\.system(?:DefaultZone|UTC)\(|""" +
            """\b(?:LocalDate|LocalDateTime|LocalTime|YearMonth|Instant)::now\b""",
    )

    @After fun back() = Today.reset()

    @Test fun noMainCodeReadsTheClockItself() {
        val found = roots.flatMap { root ->
            assertTrue("$root is missing", root.isDirectory)
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Today.kt" }.flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> direct.containsMatchIn(line) && "// real clock:" !in line && !line.trimStart().startsWith("*") }
                    .map { (i, line) -> "${f.path}:${i + 1}: ${line.trim()}" }
            }.toList()
        }
        assertEquals("read the clock through com.bamnat.core.time.Today", emptyList<String>(), found)
    }

    @Test fun aFixedClockStaysPutAndResetGoesBackToTheSystem() {
        val seoul = ZoneId.of("Asia/Seoul")
        Today.fix(ZonedDateTime.of(2026, 10, 14, 10, 30, 0, 0, seoul))
        assertTrue(Today.isFixed)
        assertEquals(LocalDate.of(2026, 10, 14), Today.date())
        assertEquals(LocalTime.of(10, 30), Today.time())
        assertEquals(seoul, Today.zone())
        assertEquals(Today.millis(), Today.millis())
        // 10:30 in Seoul is still the 14th in UTC, and already the 13th's evening in Los Angeles.
        assertEquals(LocalDate.of(2026, 10, 13), Today.date(ZoneId.of("America/Los_Angeles")))
        Today.reset()
        assertFalse(Today.isFixed)
        assertEquals(ZoneId.systemDefault(), Today.zone())
    }
}
