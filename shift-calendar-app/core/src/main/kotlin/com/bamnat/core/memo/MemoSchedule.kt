package com.bamnat.core.memo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * [SCHED-01] A memo that is a plan: when it is, and how it is brought up. At least one of [ring]
 * (an alarm that rings like the shift alarm) and [notify] (a message in the notification shade).
 */
data class MemoReminder(val at: LocalDateTime, val ring: Boolean, val notify: Boolean) {
    init {
        require(ring || notify) { "A schedule that neither rings nor notifies" }
    }
}

/**
 * [SCHED-01] Finds a time written in a memo ("내일 오후 3시 치과", "10월 20일 14:30 회의", "금요일 7시 반"),
 * so the editor can offer to make the memo a schedule. Only a clock time makes a schedule; a date
 * alone does not. A time already past is not offered.
 */
object MemoTime {
    data class Found(val at: LocalDateTime, val phrase: String) {
        /** Stored when the user answers, so the same time is not asked about again. */
        val key: String get() = at.toString()
    }

    private const val MARKS = "오전|오후|아침|낮|저녁|밤|새벽"
    private val HOUR = Regex("($MARKS)?\\s*(\\d{1,2})\\s*시(?!간)(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?")
    private val CLOCK = Regex("(?:($MARKS)\\s*)?(?<![\\d:])(\\d{1,2})\\s*:\\s*([0-5]\\d)(?![\\d:])")
    private val RELATIVE = Regex("(오늘|내일|모레|글피)")
    private val MONTH_DAY = Regex("(?<!\\d)(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일")
    private val SLASH = Regex("(?<![\\d/])(\\d{1,2})\\s*/\\s*(\\d{1,2})(?![\\d/])")
    private val WEEKDAY = Regex("(다다음\\s*주|다음\\s*주|담주|이번\\s*주)?\\s*([월화수목금토일])요일")
    private val DAY_ONLY = Regex("(?<![\\d월/])(\\d{1,2})\\s*일(?!\\s*(?:간|동안|째))")

    fun find(memo: Memo, now: LocalDateTime): Found? = find(
        (sequenceOf(memo.title, memo.body) + memo.checklist.asSequence().map { it.text }).joinToString("\n"),
        now,
        memo.date,
    )

    /** The first clock time in [text] on its date: a date written next to it, else [memoDate], else the next such time. */
    fun find(text: String, now: LocalDateTime, memoDate: LocalDate? = null): Found? {
        val time = findTime(text) ?: return null
        val written = findDate(text, now.toLocalDate())
        val at = when {
            written != null -> written.first.atTime(time.time).plusDays(time.nextDay)
            memoDate != null -> memoDate.atTime(time.time).plusDays(time.nextDay)
            else -> now.toLocalDate().atTime(time.time).plusDays(time.nextDay).let { if (it.isAfter(now)) it else it.plusDays(1) }
        }
        if (!at.isAfter(now)) return null
        val phrase = listOfNotNull(written?.second, time.phrase).joinToString(" ")
        return Found(at, phrase)
    }

    private class Time(val time: LocalTime, val nextDay: Long, val phrase: String)

    private fun findTime(text: String): Time? {
        val clock = CLOCK.find(text)
        val hour = HOUR.find(text)
        val first = listOfNotNull(clock, hour).minByOrNull { it.range.first } ?: return null
        val mark = first.groupValues[1].ifEmpty { null }
        val h = first.groupValues[2].toInt()
        val minute = when {
            first === clock -> first.groupValues[3].toInt()
            first.groupValues[4].isNotEmpty() -> 30
            first.groupValues[3].isNotEmpty() -> first.groupValues[3].toInt()
            else -> 0
        }
        if (minute > 59) return null
        // A written 24-hour clock ("14:30") is taken as written; "3시" with no mark is most often the afternoon.
        val (hour24, nextDay) = hourOf(mark, h, guessAfternoon = first === hour) ?: return null
        return Time(LocalTime.of(hour24, minute), nextDay, first.value.trim())
    }

    /** The hour on a 24-hour clock, and 1 when it falls after midnight ("밤 12시"). */
    private fun hourOf(mark: String?, h: Int, guessAfternoon: Boolean): Pair<Int, Long>? {
        if (h > 24) return null
        if (h == 24) return 0 to 1L
        return when (mark) {
            "오후", "저녁" -> (if (h in 1..11) h + 12 else h) to 0L
            "밤" -> when (h) {
                12 -> 0 to 1L
                in 6..11 -> h + 12 to 0L
                else -> h to 0L
            }
            "낮" -> (if (h in 1..6) h + 12 else h) to 0L
            "오전", "아침", "새벽" -> if (h > 12) null else (if (h == 12) 0 else h) to 0L
            else -> (if (guessAfternoon && h in 1..6) h + 12 else h) to 0L
        }
    }

    private fun findDate(text: String, today: LocalDate): Pair<LocalDate, String>? {
        RELATIVE.find(text)?.let { m ->
            val days = when (m.value) { "오늘" -> 0L; "내일" -> 1L; "모레" -> 2L; else -> 3L }
            return today.plusDays(days) to m.value
        }
        (MONTH_DAY.find(text) ?: SLASH.find(text))?.let { m ->
            val date = dateOf(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt()) ?: return@let
            return (if (date.isBefore(today)) date.plusYears(1) else date) to m.value.trim()
        }
        WEEKDAY.find(text)?.let { m ->
            val day = DayOfWeek.entries["월화수목금토일".indexOf(m.groupValues[2])]
            val which = m.groupValues[1].replace(" ", "")
            // A week from Sunday, as a Korean wall calendar shows it: on a Sunday, "다음주 월요일" is eight days on.
            val thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).with(TemporalAdjusters.nextOrSame(day))
            val date = when (which) {
                "다음주", "담주" -> thisWeek.plusWeeks(1)
                "다다음주" -> thisWeek.plusWeeks(2)
                "이번주" -> thisWeek
                else -> today.with(TemporalAdjusters.nextOrSame(day))
            }
            return date to m.value.trim()
        }
        DAY_ONLY.find(text)?.let { m ->
            val d = m.groupValues[1].toInt()
            val date = dateOf(today.year, today.monthValue, d)
                ?.let { if (it.isBefore(today)) dateOf(it.plusMonths(1).year, it.plusMonths(1).monthValue, d) else it }
                ?: return@let
            return date to m.value.trim()
        }
        return null
    }

    private fun dateOf(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

/** [SCHED-01] Which memo schedules come next, for the one system alarm kept for them. */
object MemoSchedules {
    /** The earliest schedule strictly after [after], or null. */
    fun next(memos: List<Memo>, after: LocalDateTime): LocalDateTime? =
        memos.asSequence().filter { !it.inTrash }.mapNotNull { it.reminder?.at }.filter { it.isAfter(after) }.minOrNull()

    /** The memos whose schedule is at [at], to the minute. */
    fun dueAt(memos: List<Memo>, at: LocalDateTime): List<Memo> {
        val minute = at.withSecond(0).withNano(0)
        return memos.filter { !it.inTrash && it.reminder?.at?.withSecond(0)?.withNano(0) == minute }
    }
}
