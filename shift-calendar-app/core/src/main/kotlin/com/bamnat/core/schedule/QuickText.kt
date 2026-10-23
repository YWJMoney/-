package com.bamnat.core.schedule

import com.bamnat.core.model.ExtraKind
import com.bamnat.core.model.ExtraKinds
import com.bamnat.core.model.ShiftType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * [CAL-43] I 리뷰 N2: a line typed in the day sheet's memo field, "내일 야간 대근 7시" or "다음주 월화 연차", read as
 * what to put on the calendar, without a server: the days from words people use (오늘·내일·모레, 다음주 월화,
 * 11/3, 3일, 금요일), and the change from the user's own shift names, extra-work kinds and 연차·반차. Only a
 * proposal: the sheet asks "이렇게 넣을까요?" before anything is written; sending the line as a memo works as before.
 */
object QuickText {
    sealed interface Act {
        data class Shift(val shiftTypeId: Long) : Act
        data object Off : Act
        /** 연차 4, 반차 2, 반반차 1 quarters. */
        data class Leave(val quarters: Int) : Act
        /** A piece of extra work: a 대근 of [coveredShiftId], or [minutes] (from [start] when said). */
        data class Extra(val kindId: Long, val coveredShiftId: Long? = null, val minutes: Int? = null, val start: LocalTime? = null) : Act
    }

    data class Proposal(val dates: List<LocalDate>, val act: Act, val text: String)

    /**
     * Reads [text]; [today] anchors 오늘·내일 and the weekdays, [base] is the day the sheet is open on (used when the
     * line names no day). Null when the line says nothing the calendar can take.
     */
    fun parse(text: String, today: LocalDate, base: LocalDate, types: List<ShiftType>, kinds: ExtraKinds): Proposal? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val act = act(t, types, kinds) ?: return null
        val dates = dates(t, today).ifEmpty { listOf(base) }
        return Proposal(dates, act, "${datesText(dates)} ${actText(act, types, kinds)}")
    }

    private fun act(t: String, types: List<ShiftType>, kinds: ExtraKinds): Act? {
        val compact = t.replace(" ", "")
        when {
            "반반차" in compact -> return Act.Leave(1)
            "반차" in compact -> return Act.Leave(2)
            "연차" in compact || "휴가" in compact -> return Act.Leave(4)
        }
        // The longest name first, so 장주간 is not read as 주간.
        val shift = types.filter { !it.isOff && it.name.isNotBlank() }.sortedByDescending { it.name.length }
            .firstOrNull { it.name.replace(" ", "") in compact }
            ?: types.filter { !it.isOff && it.label.isNotBlank() }.firstOrNull { ty -> t.split(' ').any { it == ty.label } }
        val kind = kinds.visible().filter { it.name.isNotBlank() }.sortedByDescending { it.name.length }
            .firstOrNull { it.name.replace(" ", "") in compact }
        if (kind != null) {
            val minutes = HOURS.find(t)?.let { m -> m.groupValues[1].toInt() * 60 + (m.groupValues[2].toIntOrNull() ?: 0) }
            val start = clock(t)
            return when {
                kind.id == ExtraKind.COVER_ID || kind.timing is com.bamnat.core.model.ExtraTiming.AsShift ->
                    Act.Extra(kind.id, coveredShiftId = shift?.id, minutes = minutes, start = start)
                else -> Act.Extra(kind.id, minutes = minutes, start = start)
            }
        }
        if (shift != null) return Act.Shift(shift.id)
        if (OFF_WORDS.any { it in compact }) return types.firstOrNull { it.isOff && it.name in compact }?.let { Act.Shift(it.id) } ?: Act.Off
        return null
    }

    /** The days the line names, in order; empty when it names none. */
    internal fun dates(t: String, today: LocalDate): List<LocalDate> {
        val compact = t.replace(" ", "")
        RELATIVE.entries.firstOrNull { (w, _) -> w in compact }?.let { (_, d) -> return listOf(today.plusDays(d)) }
        SLASH.find(t)?.let { m ->
            return listOfNotNull(dateIn(today, m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        }
        MONTH_DAY.find(t)?.let { m ->
            return listOfNotNull(dateIn(today, m.groupValues[1].toInt(), m.groupValues[2].toInt()))
        }
        // "다음주 월화", "이번주 금", "다음 주 월~금".
        val week = when {
            "다다음주" in compact -> 2L
            "다음주" in compact -> 1L
            "이번주" in compact -> 0L
            else -> null
        }
        val days = weekdays(t)
        if (week != null) {
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(week)
            val list = days.ifEmpty { if (week == 0L) emptyList() else DayOfWeek.entries.take(5) }
            return list.map { monday.plusDays((it.value - 1).toLong()) }
        }
        DAY_ONLY.find(t)?.let { m ->
            val d = m.groupValues[1].toInt()
            // The coming such day: this month's if it has not passed, else next month's.
            val here = runCatching { today.withDayOfMonth(d) }.getOrNull()
            return listOfNotNull(if (here != null && !here.isBefore(today)) here else runCatching { today.plusMonths(1).withDayOfMonth(d) }.getOrNull())
        }
        if (days.isNotEmpty()) return days.map { today.with(TemporalAdjusters.nextOrSame(it)) }.sorted()
        return emptyList()
    }

    /** 월~금 as a range, 월화 or 월,화 as single days, "토요일" as one; only where they stand as day words. */
    private fun weekdays(t: String): List<DayOfWeek> {
        RANGE.find(t)?.let { m ->
            val a = KOREAN.indexOf(m.groupValues[1][0])
            val b = KOREAN.indexOf(m.groupValues[2][0])
            if (a >= 0 && b >= a) return (a..b).map { DayOfWeek.of(it + 1) }
        }
        val run = WEEKDAY_RUN.find(t)?.groupValues?.get(1) ?: return emptyList()
        return run.filter { it in KOREAN }.map { DayOfWeek.of(KOREAN.indexOf(it) + 1) }.distinct()
    }

    /** "7시", "19시 30분", "오후 7시", "07:30". */
    private fun clock(t: String): LocalTime? {
        COLON.find(t)?.let { m -> return runCatching { LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull() }
        val m = OCLOCK.find(t) ?: return null
        var h = m.groupValues[2].toInt()
        if (m.groupValues[1] == "오후" && h < 12) h += 12
        if (m.groupValues[1] == "새벽" && h == 12) h = 0
        return runCatching { LocalTime.of(h, m.groupValues[3].toIntOrNull() ?: 0) }.getOrNull()
    }

    /** The coming [month]/[day]: this year's, or next year's once it has passed by more than a month. */
    private fun dateIn(today: LocalDate, month: Int, day: Int): LocalDate? {
        val d = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
        return if (d.isBefore(today.minusMonths(1))) d.plusYears(1) else d
    }

    fun datesText(dates: List<LocalDate>): String = when {
        dates.size == 1 -> dayText(dates[0])
        dates.zipWithNext().all { (a, b) -> b == a.plusDays(1) } -> "${dayText(dates.first())}~${dayText(dates.last())}"
        else -> dates.joinToString(", ") { dayText(it) }
    }

    private fun dayText(d: LocalDate) = "${d.monthValue}/${d.dayOfMonth}(${KOREAN[d.dayOfWeek.value - 1]})"

    fun actText(act: Act, types: List<ShiftType>, kinds: ExtraKinds): String = when (act) {
        is Act.Shift -> types.firstOrNull { it.id == act.shiftTypeId }?.name ?: "근무"
        Act.Off -> "쉬는 날"
        is Act.Leave -> when (act.quarters) { 1 -> "반반차"; 2 -> "반차"; else -> "연차" }
        is Act.Extra -> buildString {
            val covered = act.coveredShiftId?.let { id -> types.firstOrNull { it.id == id } }
            val name = kinds.name(act.kindId)
            append(covered?.name?.takeUnless { it in name }?.let { "$it $name" } ?: name)
            act.start?.let { append(" %02d:%02d부터".format(it.hour, it.minute)) }
            act.minutes?.let { append(" ${it / 60}시간" + if (it % 60 > 0) " ${it % 60}분" else "") }
        }
    }

    private const val KOREAN = "월화수목금토일"
    private val RELATIVE = linkedMapOf("그저께" to -2L, "어제" to -1L, "오늘" to 0L, "내일모레" to 2L, "모레" to 2L, "글피" to 3L, "내일" to 1L)
    private val OFF_WORDS = listOf("쉬는날", "휴무", "쉼", "쉬어", "쉬기")
    private val SLASH = Regex("""(\d{1,2})\s*/\s*(\d{1,2})""")
    private val MONTH_DAY = Regex("""(\d{1,2})\s*월\s*(\d{1,2})\s*일""")
    private val DAY_ONLY = Regex("""(?<![\d월])(\d{1,2})\s*일(?!간)""")
    private val RANGE = Regex("""([월화수목금토일])\s*[~\-–]\s*([월화수목금토일])""")
    private val WEEKDAY_RUN = Regex("""(?:^|\s|주)([월화수목금토일](?:[,\s]?[월화수목금토일])*)(?:요일)?(?=\s|$|에|날)""")
    private val HOURS = Regex("""(\d{1,2})\s*시간(?:\s*(\d{1,2})\s*분)?""")
    private val COLON = Regex("""(\d{1,2}):(\d{2})""")
    private val OCLOCK = Regex("""(오전|오후|새벽|아침|저녁|밤)?\s*(\d{1,2})\s*시(?!간)(?:\s*(\d{1,2})\s*분)?""")
}
