package com.bamnat.core.alarm

import com.bamnat.core.diag.DiagEvent
import com.bamnat.core.diag.DiagEvents
import com.bamnat.core.model.AlarmShift
import com.bamnat.core.model.ShiftType
import com.bamnat.core.sleep.SleepDebt
import com.bamnat.core.sleep.SleepEntry
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * [ALRM-03] 스마트 알람 제안 (I 리뷰 N3, 결정 D60): from when the user turned each alarm off and when they woke, one
 * suggestion for one kind of shift, asked on a card and applied with a tap. The app never moves an alarm by itself.
 *
 * Each kind of shift is looked at on its own, over its last [WINDOW] times, and only its first alarm (the earliest):
 * - [Kind.LATE] 늦게 꺼요: [HITS] of them turned off more than [LATE_MINUTES] after the alarm (snoozes count) → [EARLIER] earlier.
 * - [Kind.EARLY] 이미 깨 있어요: [HITS] of them awake more than [EARLY_MINUTES] before the alarm → [LATER] later.
 * - [Kind.SHORT] 잠 모자라요: [HITS] of the sleeps before it more than [SHORT_MINUTES] under the target → 잘 시간 알림.
 * The sleep before a shift is the longest one ending within [SLEEP_WINDOW_HOURS] hours before its alarm (a night
 * worker's sleep is the day's nap, not the one by the wake date). Fewer than [WINDOW] times says nothing.
 *
 * A card at most once a week ([CARD_EVERY_DAYS]); the same suggestion for the same shift not within [QUIET_DAYS]
 * of being answered; '다시 보지 않기' keeps it away until 설정 brings the hidden ones back. All on the phone.
 */
object AlarmSuggest {
    const val WINDOW = 5
    const val HITS = 3
    const val LATE_MINUTES = 20L
    const val EARLY_MINUTES = 30L
    const val SHORT_MINUTES = 90L
    const val EARLIER = 20
    const val LATER = 15
    const val SLEEP_WINDOW_HOURS = 12L
    const val CARD_EVERY_DAYS = 7L
    const val QUIET_DAYS = 30L
    /** How far back the shifts are looked at. */
    const val LOOK_BACK_DAYS = 60L

    enum class Kind(val key: String) { LATE("late"), EARLY("early"), SHORT("short") }

    /** One alarm turned off: the shift kind, when that first alarm was due, its offset, when it was turned off. */
    data class Off(val shiftTypeId: Long, val due: LocalDateTime, val offsetMinutes: Int, val at: LocalDateTime)

    /** What the card asks, and what a tap writes: [moved] for an alarm, [bedtime] for the 잘 시간 알림. */
    data class Suggestion(val kind: Kind, val type: ShiftType, val text: String, val moved: ShiftType? = null, val bedtime: Boolean = false) {
        val key: String get() = key(type.id, kind)
    }

    /**
     * What has been answered, on this phone: the day the last card was answered, the suggestions resting until a
     * day (key → epoch day), and those not to show again.
     */
    data class Memory(val answeredDay: Long = Long.MIN_VALUE / 2, val quietUntil: Map<String, Long> = emptyMap(), val never: Set<String> = emptySet()) {
        fun encode(): String = buildList {
            add("a=$answeredDay")
            quietUntil.forEach { (k, d) -> add("q=$k@$d") }
            never.forEach { add("n=$it") }
        }.joinToString(";")

        /** After an answer on [today]: the card rests a week, this one [QUIET_DAYS], or for good with [forGood]. */
        fun answered(key: String, today: LocalDate, forGood: Boolean = false): Memory = copy(
            answeredDay = today.toEpochDay(),
            quietUntil = quietUntil + (key to today.plusDays(QUIET_DAYS).toEpochDay()),
            never = if (forGood) never + key else never,
        )

        companion object {
            fun decode(text: String): Memory {
                var a = Memory().answeredDay
                val q = HashMap<String, Long>()
                val n = HashSet<String>()
                for (part in text.split(';')) {
                    val (tag, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: continue
                    when (tag) {
                        "a" -> value.toLongOrNull()?.let { a = it }
                        "q" -> value.split('@').takeIf { it.size == 2 }?.let { (k, d) -> d.toLongOrNull()?.let { q[k] = it } }
                        "n" -> if (value.isNotBlank()) n += value
                    }
                }
                return Memory(a, q, n)
            }
        }
    }

    fun key(shiftTypeId: Long, kind: Kind) = "$shiftTypeId:${kind.key}"

    /**
     * The one suggestion for now, or null. [days] are the past days with work and their shift ([LOOK_BACK_DAYS] or
     * so, any order); [offs] the alarms turned off ([offsOf]); [sleeps] the merged sleep log; [bedtimeOn] whether
     * 잘 시간 알림 is already on, so it is never suggested twice.
     */
    fun find(
        today: LocalDate,
        types: List<ShiftType>,
        days: List<Pair<LocalDate, ShiftType>>,
        offs: List<Off>,
        sleeps: List<SleepEntry>,
        targetMinutes: Int,
        bedtimeOn: Boolean,
        memory: Memory,
    ): Suggestion? {
        if (today.toEpochDay() - memory.answeredDay < CARD_EVERY_DAYS) return null
        fun open(key: String) = key !in memory.never && (memory.quietUntil[key] ?: Long.MIN_VALUE) <= today.toEpochDay()
        // The kinds of shift worked most lately first.
        val order = days.filter { it.first.isBefore(today) }.sortedByDescending { it.first }.map { it.second.id }.distinct()
        for (id in order) {
            val type = types.firstOrNull { it.id == id } ?: continue
            val first = type.activeAlarms.firstOrNull() ?: continue
            val start = type.start ?: continue
            val dues = days.filter { it.second.id == id && it.first.isBefore(today) }.map { it.first }.distinct().sortedDescending()
                .map { it.atTime(start).minusMinutes(first.offsetMinutes.toLong()) }

            late(type, first.offsetMinutes, offs)?.takeIf { open(key(id, Kind.LATE)) }?.let { return it }
            val slept = dues.mapNotNull { due -> sleepBefore(due, sleeps)?.let { due to it } }.take(WINDOW)
            if (slept.size < WINDOW) continue
            early(type, slept)?.takeIf { open(key(id, Kind.EARLY)) }?.let { return it }
            if (!bedtimeOn) short(type, slept, targetMinutes)?.takeIf { open(key(id, Kind.SHORT)) }?.let { return it }
        }
        return null
    }

    private fun late(type: ShiftType, offset: Int, offs: List<Off>): Suggestion? {
        val recent = offs.filter { it.shiftTypeId == type.id && it.offsetMinutes == offset }
            .distinctBy { it.due }.sortedByDescending { it.due }.take(WINDOW)
        if (recent.size < WINDOW) return null
        val lates = recent.map { Duration.between(it.due, it.at).toMinutes() }.filter { it > LATE_MINUTES }
        if (lates.size < HITS) return null
        val moved = AlarmShift.move(type, EARLIER).takeIf { it != type } ?: return null
        return Suggestion(Kind.LATE, type, "${type.name} 알람을 보통 ${round5(lates.median())}분 뒤에 꺼요. 알람을 ${EARLIER}분 앞당길까요?", moved = moved)
    }

    private fun early(type: ShiftType, slept: List<Pair<LocalDateTime, SleepEntry>>): Suggestion? {
        val early = slept.map { (due, s) -> Duration.between(s.end, due).toMinutes() }.filter { it > EARLY_MINUTES }
        if (early.size < HITS) return null
        val moved = AlarmShift.move(type, -LATER).takeIf { it != type } ?: return null
        return Suggestion(Kind.EARLY, type, "${type.name} 전에는 알람보다 ${round5(early.median())}분쯤 먼저 깨요. 알람을 ${LATER}분 늦출까요?", moved = moved)
    }

    private fun short(type: ShiftType, slept: List<Pair<LocalDateTime, SleepEntry>>, targetMinutes: Int): Suggestion? {
        val short = slept.map { (_, s) -> targetMinutes - s.minutes!! }.filter { it > SHORT_MINUTES }
        if (short.size < HITS) return null
        return Suggestion(Kind.SHORT, type,
            "${type.name} 전 잠이 목표보다 보통 ${hours(short.median())} 짧아요. 잘 시간 30분 전에 알려 드릴까요?", bedtime = true)
    }

    /** The longest sleep that ended in the [SLEEP_WINDOW_HOURS] hours before [due] (or a little after: slept through it). */
    fun sleepBefore(due: LocalDateTime, sleeps: List<SleepEntry>): SleepEntry? = sleeps
        .filter { s -> s.end != null && (s.minutes ?: 0) in 1..SleepDebt.MAX_SLEEP_MINUTES }
        .filter { s -> s.end!!.isAfter(due.minusHours(SLEEP_WINDOW_HOURS)) && !s.end.isAfter(due.plusHours(2)) }
        .maxByOrNull { it.minutes!! }

    /**
     * The shift alarms turned off, from the event log: [DiagEvents.ALARM_DISMISSED] lines that carry
     * "type=<id> due=<millis> off=<minutes>" ([offDetail]). Older lines without them are left out.
     */
    fun offsOf(events: List<DiagEvent>, zone: ZoneId): List<Off> = events.filter { it.name == DiagEvents.ALARM_DISMISSED }.mapNotNull { e ->
        val words = e.detail.split(' ').mapNotNull { w -> w.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val type = words["type"]?.toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val due = words["due"]?.toLongOrNull() ?: return@mapNotNull null
        val off = words["off"]?.toIntOrNull() ?: return@mapNotNull null
        Off(type, local(due, zone), off, local(e.atMillis, zone))
    }

    /** What a dismissal line adds for [offsOf]: the shift kind, when its alarm was due and how long before the shift. */
    fun offDetail(shiftTypeId: Long, dueMillis: Long, offsetMinutes: Int) = "type=$shiftTypeId due=$dueMillis off=$offsetMinutes"

    private fun local(millis: Long, zone: ZoneId) = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    private fun List<Long>.median(): Long = sorted()[size / 2]

    private fun round5(minutes: Long): Long = ((minutes / 5.0).roundToInt() * 5L).coerceAtLeast(5)

    private fun hours(minutes: Long): String {
        val m = round5(minutes)
        return when {
            m < 60 -> "${m}분"
            m % 60 == 0L -> "${m / 60}시간"
            m % 60 == 30L -> "${m / 60}시간 반"
            else -> "${m / 60}시간 ${m % 60}분"
        }
    }
}
