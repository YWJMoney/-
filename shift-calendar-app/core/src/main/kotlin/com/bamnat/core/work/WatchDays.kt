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
    /** [WEAR-03] The shift's icon key ([com.bamnat.core.model.ShiftIcon]), for the watch face's small image. */
    val icon: String? = null,
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
    /** [WEAR-01] The phone's first day of the week (1 = Monday … 7 = Sunday), so the watch's month matches (I 리뷰 I23). */
    val weekStart: Int = 1,
    /** [WEAR-03] This Monday-to-Sunday week's hours so far and planned, in minutes; −1 when not sent (I 리뷰 N7 a). */
    val weekMinutes: Int = -1,
    /** [WEAR-03] The weekly limit the phone warns at (52시간 unless the user changed it). */
    val weekLimitMinutes: Int = 52 * 60,
    /** [WEAR-03] What the watch can write for an 알바 calendar today: [Clock.NONE], [Clock.IN] or [Clock.OUT] (I 리뷰 N7 d). */
    val clock: Clock = Clock.NONE,
    /** [WEAR-03] Today has an alarm that can be turned off from the tile (I 리뷰 N7 c). */
    val alarmToday: Boolean = false,
) {
    enum class Clock { NONE, IN, OUT }
}

object WatchDays {
    /** Days ahead the phone sends at least, so a watch away from the phone a while still knows. */
    const val DAYS = 45
    const val PATH = "/bamnat/days"
    const val KEY = "payload"
    private const val OFF_COLOR = 0xFF8E9AAFL
    private const val VERSION = "v1"

    /** [SHFT-08] The days from [from] to [until] off the calendar [resolve]. */
    fun of(from: LocalDate, until: LocalDate, resolve: (LocalDate) -> ResolvedDay): List<WatchDay> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(until) }.map { day(resolve(it)) }.toList()

    /**
     * What the watch window covers around [today]: the watch's month view pages last, this and next month, so
     * from last month's 1st to the end of next month, and at least [DAYS] ahead.
     */
    fun window(today: LocalDate): Pair<LocalDate, LocalDate> {
        val nextMonthEnd = today.plusMonths(1).let { it.withDayOfMonth(it.lengthOfMonth()) }
        return today.minusMonths(1).withDayOfMonth(1) to maxOf(today.plusDays(DAYS.toLong()), nextMonthEnd)
    }

    fun day(d: ResolvedDay): WatchDay {
        val type = d.shiftType
        val leave = d.leave
        return when {
            leave != null && leave.isFullDay -> WatchDay(d.date, leave.label, leave.label.take(1), OFF_COLOR, off = true)
            d.isWorkDay -> WatchDay(d.date, (type?.name ?: "근무") + (leave?.let { " · ${it.label}" } ?: ""), type?.label ?: "근",
                type?.colorArgb ?: 0xFF2F80EDL, d.start, d.end, icon = type?.icon)
            d.extras.isNotEmpty() -> d.extras.first().let { e ->
                // [DAYW-10] Two pieces on a day off (특근 and a 야간 대근) both show: "특근 외 1건" (I 리뷰 1-5).
                val first = e.covered?.name?.let { "$it ${e.kind.name}" } ?: e.kind.name
                val more = d.extras.size - 1
                WatchDay(d.date, if (more > 0) "$first 외 ${more}건" else first, e.kind.mark, e.kind.colorArgb, e.start, e.end)
            }
            type != null -> WatchDay(d.date, type.name, type.label, type.colorArgb, off = true, icon = type.icon)
            else -> WatchDay(d.date, "쉬는 날", "휴", OFF_COLOR, off = true)
        }
    }

    /**
     * Plain text, so the watch reads it without any library: a header line (version, sent at, next alarm,
     * its label, calendar) and one line per day, fields apart by tabs.
     */
    fun encode(p: WatchPayload): String {
        // [WEAR-01][WEAR-03] Fields 6 on are new in 1.23; an older watch reads the first six and ignores them.
        val head = listOf(VERSION, p.syncedAt.toString(), p.nextAlarm?.toString().orEmpty(), clean(p.alarmLabel), clean(p.calendar), p.sentAtMillis.toString(),
            p.weekStart.toString(), p.weekMinutes.toString(), p.weekLimitMinutes.toString(), p.clock.name, if (p.alarmToday) "1" else "0")
            .joinToString("\t")
        return (listOf(head) + p.days.map { d ->
            listOf(d.date.toString(), clean(d.name), clean(d.label), d.colorArgb.toString(), d.start?.let(::hm).orEmpty(),
                d.end?.let(::hm).orEmpty(), if (d.off) "1" else "0", d.icon.orEmpty()).joinToString("\t")
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
                    f[5].takeIf { it.isNotEmpty() }?.let(LocalTime::parse), f[6] == "1", f.getOrNull(7)?.takeIf { it.isNotEmpty() })
            }.getOrNull()
        }
        return WatchPayload(days, synced, head[2].takeIf { it.isNotEmpty() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }, head[3], head[4],
            head.getOrNull(5)?.toLongOrNull() ?: 0,
            weekStart = head.getOrNull(6)?.toIntOrNull()?.takeIf { it in 1..7 } ?: 1,
            weekMinutes = head.getOrNull(7)?.toIntOrNull() ?: -1,
            weekLimitMinutes = head.getOrNull(8)?.toIntOrNull()?.takeIf { it > 0 } ?: 52 * 60,
            clock = head.getOrNull(9)?.let { c -> WatchPayload.Clock.entries.firstOrNull { it.name == c } } ?: WatchPayload.Clock.NONE,
            alarmToday = head.getOrNull(10) == "1")
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

    /** Weeks of [month] for the month view from [weekStart] (1 = Monday), null for the days of other months. */
    fun monthGrid(year: Int, month: Int, weekStart: Int = 1): List<List<LocalDate?>> {
        val first = LocalDate.of(year, month, 1)
        val lead = Math.floorMod(first.dayOfWeek.value - weekStart, 7)
        val cells = List(lead) { null } + (1..first.lengthOfMonth()).map { first.withDayOfMonth(it) }
        return cells.chunked(7).map { w -> w + List(7 - w.size) { null } }
    }

    /** [WEAR-01] Day-of-week names from [weekStart], for the month view's head row. */
    fun weekHead(weekStart: Int): List<String> = (0 until 7).map { WEEKDAYS[Math.floorMod(weekStart - 1 + it, 7)] }

    /**
     * [WEAR-01] Months the watch can page to: as far as the days the phone sent reach (I 리뷰 I24), at least this
     * month. Offsets from this month, e.g. −1..2.
     */
    fun monthRange(days: List<WatchDay>, today: LocalDate): IntRange {
        val first = days.minOfOrNull { it.date } ?: return 0..0
        val last = days.maxOf { it.date }
        fun off(d: LocalDate) = ((d.year - today.year) * 12 + d.monthValue - today.monthValue)
        return minOf(0, off(first))..maxOf(0, off(last))
    }

    /** [WEAR-03] "이번 주 38시간 / 52시간": the week's share of the limit for the ranged complication. */
    fun weekText(p: WatchPayload): String? =
        if (p.weekMinutes < 0) null else "이번 주 ${p.weekMinutes / 60}시간" + (if (p.weekMinutes % 60 != 0) " ${p.weekMinutes % 60}분" else "") + " / ${p.weekLimitMinutes / 60}시간"

    /** [WEAR-03] Messages the watch sends the phone: turn today's alarm off, and an 알바's 출근·퇴근. */
    const val ALARM_OFF_PATH = "/bamnat/sync/alarm-off"
    const val CLOCK_PATH = "/bamnat/sync/clock"

    private val WEEKDAYS = listOf("월", "화", "수", "목", "금", "토", "일")

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')
}

private fun hm(t: LocalTime) = "%02d:%02d".format(t.hour, t.minute)
