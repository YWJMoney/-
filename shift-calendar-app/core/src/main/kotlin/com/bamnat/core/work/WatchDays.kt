package com.bamnat.core.work

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SHFT-08][REL-09] One day as the watch shows it (tile, complication, today, week and month): the shift's
 * name and short name, its colour and times, or a day off or leave. The phone sends the next weeks; the
 * watch picks the days by its own date, so it keeps working away from the phone.
 */
data class WatchDay(
    val date: LocalDate,
    val name: String,
    val label: String,
    val colorArgb: Long,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val off: Boolean = false,
) {
    /** "주간 09:00–18:00", "휴무", "연차". */
    val line: String
        get() = if (start != null && end != null && !off) "$name ${hm(start)}–${hm(end)}" else name

    /** "09:00–18:00", or empty on a day off. */
    val times: String get() = if (start != null && end != null && !off) "${hm(start)}–${hm(end)}" else ""
}

/** [SHFT-08] Everything the phone sends the watch at once: the days, the next alarm and when it was sent. */
data class WatchPayload(
    val days: List<WatchDay>,
    val syncedAt: LocalDateTime,
    /** The next shift alarm the phone will ring, or null when none is set. */
    val nextAlarm: LocalDateTime? = null,
    /** What the alarm is for ("주간 근무"), or empty. */
    val alarmLabel: String = "",
    /** The calendar's name ("내 달력"). */
    val calendar: String = "",
    /**
     * [SHFT-08] When it was sent, as epoch milliseconds (0 from an older phone app): the phone's clock or time
     * zone changing does not make a newer copy look older, as [syncedAt] (the phone's local time) can.
     */
    val sentAtMillis: Long = 0,
)

object WatchDays {
    /** Days the phone sends: this month and the next for the month view, and a watch away from the phone a while still knows. */
    const val DAYS = 45
    /** Days before today the phone sends too, so the month view starts on the 1st. */
    const val DAYS_BACK = 31
    const val PATH = "/bamnat/days"
    const val KEY = "payload"
    private const val OFF_COLOR = 0xFF8E9AAFL
    private const val VERSION = "v1"

    /** [SHFT-08] The days from [from] to [until] off the calendar [resolve]. */
    fun of(from: LocalDate, until: LocalDate, resolve: (LocalDate) -> ResolvedDay): List<WatchDay> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(until) }.map { day(resolve(it)) }.toList()

    /** What the watch window covers around [today]: from the 1st of last month's last days to [DAYS] ahead. */
    fun window(today: LocalDate): Pair<LocalDate, LocalDate> =
        minOf(today.withDayOfMonth(1), today.minusDays(DAYS_BACK.toLong())) to today.plusDays(DAYS.toLong())

    fun day(d: ResolvedDay): WatchDay {
        val type = d.shiftType
        val leave = d.leave
        return when {
            leave != null && leave.isFullDay -> WatchDay(d.date, leave.label, leave.label.take(1), OFF_COLOR, off = true)
            d.isWorkDay -> WatchDay(d.date, (type?.name ?: "근무") + (leave?.let { " · ${it.label}" } ?: ""), type?.label ?: "근",
                type?.colorArgb ?: 0xFF2F80EDL, d.start, d.end)
            d.extras.isNotEmpty() -> d.extras.first().let { e ->
                WatchDay(d.date, e.covered?.name?.let { "$it ${e.kind.name}" } ?: e.kind.name, e.kind.mark, e.kind.colorArgb, e.start, e.end)
            }
            type != null -> WatchDay(d.date, type.name, type.label, type.colorArgb, off = true)
            else -> WatchDay(d.date, "쉬는 날", "휴", OFF_COLOR, off = true)
        }
    }

    /**
     * Plain text, so the watch reads it without any library: a header line (version, sent at, next alarm,
     * its label, calendar) and one line per day, fields apart by tabs.
     */
    fun encode(p: WatchPayload): String {
        val head = listOf(VERSION, p.syncedAt.toString(), p.nextAlarm?.toString().orEmpty(), clean(p.alarmLabel), clean(p.calendar), p.sentAtMillis.toString())
            .joinToString("\t")
        return (listOf(head) + p.days.map { d ->
            listOf(d.date.toString(), clean(d.name), clean(d.label), d.colorArgb.toString(), d.start?.let(::hm).orEmpty(),
                d.end?.let(::hm).orEmpty(), if (d.off) "1" else "0").joinToString("\t")
        }).joinToString("\n")
    }

    /** What [encode] wrote, or null when it does not read; day lines that do not read are skipped. */
    fun decode(text: String): WatchPayload? {
        val lines = text.lines()
        val head = lines.firstOrNull()?.split('\t') ?: return null
        if (head.size < 5 || head[0] != VERSION) return null
        val synced = runCatching { LocalDateTime.parse(head[1]) }.getOrNull() ?: return null
        val days = lines.drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 7) return@mapNotNull null
            runCatching {
                WatchDay(LocalDate.parse(f[0]), f[1], f[2], f[3].toLong(), f[4].takeIf { it.isNotEmpty() }?.let(LocalTime::parse),
                    f[5].takeIf { it.isNotEmpty() }?.let(LocalTime::parse), f[6] == "1")
            }.getOrNull()
        }
        return WatchPayload(days, synced, head[2].takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }, head[3], head[4],
            head.getOrNull(5)?.toLongOrNull() ?: 0)
    }

    /**
     * [SHFT-08] Whether [next] may replace [kept] on the watch: by the epoch milliseconds both were sent at; a copy
     * without them (an older phone app) by its local time, and one with them always over one without.
     */
    fun replaces(next: WatchPayload, kept: WatchPayload?): Boolean = when {
        kept == null -> true
        next.sentAtMillis > 0 && kept.sentAtMillis > 0 -> next.sentAtMillis >= kept.sentAtMillis
        next.sentAtMillis > 0 -> true
        else -> !kept.syncedAt.isAfter(next.syncedAt)
    }

    /** The day for [date] among what the phone sent, or null when it is not there. */
    fun on(days: List<WatchDay>, date: LocalDate): WatchDay? = days.firstOrNull { it.date == date }

    /** The next alarm still ahead of [now], or null. */
    fun alarmAhead(p: WatchPayload, now: LocalDateTime): LocalDateTime? = p.nextAlarm?.takeIf { it.isAfter(now) }

    /** "방금", "15분 전", "3시간 전", "2일 전": how old the watch's copy is. */
    fun age(syncedAt: LocalDateTime, now: LocalDateTime): String {
        val minutes = java.time.Duration.between(syncedAt, now).toMinutes().coerceAtLeast(0)
        return when {
            minutes < 2 -> "방금"
            minutes < 60 -> "${minutes}분 전"
            minutes < 48 * 60 -> "${minutes / 60}시간 전"
            else -> "${minutes / (24 * 60)}일 전"
        }
    }

    /** Monday-first weeks of [month] for the month view, null for the days of other months. */
    fun monthGrid(year: Int, month: Int): List<List<LocalDate?>> {
        val first = LocalDate.of(year, month, 1)
        val lead = first.dayOfWeek.value - 1
        val cells = List(lead) { null } + (1..first.lengthOfMonth()).map { first.withDayOfMonth(it) }
        return cells.chunked(7).map { w -> w + List(7 - w.size) { null } }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}

private fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
