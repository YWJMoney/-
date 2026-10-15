package com.bamnat.core.schedule

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes the schedule as an iCalendar (.ics) file that Google Calendar, Samsung Calendar and
 * others can import [REL-04]. Work days become timed events in the phone's time zone; off days
 * are left out so they do not clutter the other calendar.
 */
object IcsWriter {
    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dateOnly = DateTimeFormatter.ofPattern("yyyyMMdd")

    fun write(
        days: List<ResolvedDay>,
        zone: ZoneId,
        nowUtc: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
        calendarName: String = "밤낮 근무",
    ): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Bamnat//Shift Calendar//KO",
            "CALSCALE:GREGORIAN",
            "METHOD:PUBLISH",
            "X-WR-CALNAME:${escape(calendarName)}",
            "X-WR-TIMEZONE:${zone.id}",
        )
        for (day in days) {
            val type = day.shiftType ?: continue
            if (!day.isWorkDay) continue
            lines += "BEGIN:VEVENT"
            // Stable per date, so importing again updates events instead of doubling them.
            lines += "UID:${day.date.format(dateOnly)}@bamnat.app"
            lines += "DTSTAMP:${nowUtc.format(stamp)}Z"
            val start = day.start
            val end = day.end
            if (start != null && end != null) {
                val startAt = day.date.atTime(start)
                // End at or before start means the shift runs into the next day.
                val endAt = if (end.isAfter(start)) day.date.atTime(end) else day.date.plusDays(1).atTime(end)
                lines += "DTSTART;TZID=${zone.id}:${startAt.format(stamp)}"
                lines += "DTEND;TZID=${zone.id}:${endAt.format(stamp)}"
            } else {
                lines += "DTSTART;VALUE=DATE:${day.date.format(dateOnly)}"
                lines += "DTEND;VALUE=DATE:${day.date.plusDays(1).format(dateOnly)}"
            }
            lines += "SUMMARY:${escape(type.name)}"
            val notes = buildList {
                if (day.overtimeMinutes > 0) add("연장 ${hours(day.overtimeMinutes)}")
                if (day.holidayWorkMinutes > 0) add("휴일근무 ${hours(day.holidayWorkMinutes)}")
            }
            if (notes.isNotEmpty()) lines += "DESCRIPTION:${escape(notes.joinToString(", "))}"
            lines += "TRANSP:OPAQUE"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    /** The default range: three months back and a year ahead. */
    fun defaultRange(today: LocalDate): Pair<LocalDate, LocalDate> = today.minusMonths(3) to today.plusYears(1)

    private fun hours(minutes: Int): String =
        if (minutes % 60 == 0) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"

    private fun escape(s: String) =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /** RFC 5545: lines longer than 75 octets continue on the next line after a space. */
    internal fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line
        val out = StringBuilder()
        var count = 0
        var limit = 75
        for (ch in line) {
            val n = ch.toString().toByteArray(Charsets.UTF_8).size
            if (count + n > limit) {
                out.append("\r\n ")
                count = 0
                limit = 74
            }
            out.append(ch)
            count += n
        }
        return out.toString()
    }
}
