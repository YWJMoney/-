package com.bamnat.core.schedule

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [CREW-04] Who can take my shift on a day I want off: every other team, with whether it can come in
 * and why not. A team that finished a 야간 that morning cannot start my 주간 (no rest), so on the first
 * 모닝 of my run the team that worked 모닝 the day before, now off, is the one to ask.
 *
 * The rest rule is the 11 hours between the end of one day's work and the start of the next that
 * 근로기준법 제59조 제2항 requires of 특례업종 (the same line [RestCheck] uses); the user can move it, as
 * hospitals and plants set their own. Other teams follow the rotation only: their swaps are unknown.
 */
object CoverFinder {
    const val DEFAULT_REST_MINUTES = RestCheck.MIN_REST_MINUTES
    const val MIN_REST_SETTING = 8 * 60
    const val MAX_REST_SETTING = 16 * 60

    enum class Verdict(val label: String) {
        /** Off that day and rested on both sides. */
        CAN("들어올 수 있어요"),
        /** Rested on both sides, but works another shift the same day: a 연달아 근무. */
        ALSO_WORKS("가능하지만 그날 근무가 하나 더 있어요"),
        /** Its own shift that day lacks times, so the rest cannot be told. */
        UNKNOWN("시간을 몰라 확인이 필요해요"),
        /** Less than the rest rule before or after my shift. */
        SHORT_REST("쉴 시간이 모자라요"),
        /** Works at the same time. */
        BUSY("그 시간에 근무해요"),
    }

    data class Option(
        val team: TeamRoster.Team,
        val yesterday: ShiftType?,
        val today: ShiftType?,
        val tomorrow: ShiftType?,
        val verdict: Verdict,
        /** Minutes from the team's last work to my start; null when nothing in the days around. */
        val restBefore: Long?,
        /** Minutes from my end to the team's next work. */
        val restAfter: Long?,
        /** The team worked my shift the day before, so it is already on these hours. */
        val sameShiftYesterday: Boolean,
        /** One line on why, e.g. "어제 야간(07:00 끝) 뒤 0시간". */
        val reason: String,
    ) {
        val canCover: Boolean get() = verdict == Verdict.CAN || verdict == Verdict.ALSO_WORKS
    }

    data class Result(val date: LocalDate, val myShift: ShiftType?, val options: List<Option>) {
        val candidates: List<Option> get() = options.filter { it.canCover }

        /** The 대근 request to paste into the team chat. */
        fun request(dateLabel: String): String = buildString {
            append(dateLabel).append(' ')
            append(if (myShift != null) "${myShift.name} 대근 구해요" else "근무 바꿔 주실 분 구해요")
            val names = candidates.map { it.team.name }
            if (names.isNotEmpty()) append(" · 들어올 수 있는 조: ${names.joinToString(", ")}")
        }
    }

    private data class Span(val from: LocalDateTime, val to: LocalDateTime, val type: ShiftType, val date: LocalDate)

    /**
     * [myShiftId] is what my calendar has on [date] (with its swaps), not the rotation's guess.
     * Teams are listed most useful first: rested and off, the team on my shift yesterday at the top.
     */
    fun find(
        roster: TeamRoster,
        rotation: WorkPattern.Rotation,
        types: Map<Long, ShiftType>,
        date: LocalDate,
        myShiftId: Long?,
        minRestMinutes: Int = DEFAULT_REST_MINUTES,
    ): Result {
        val mine = myShiftId?.let(types::get)?.takeIf { !it.isOff }
        val my = mine?.let { span(it, date) }
        val options = roster.teams.filterNot { it.mine }.map { team ->
            fun on(d: LocalDate) = roster.shiftOn(rotation, team, d)?.let(types::get)?.takeIf { !it.isOff }
            val yesterday = on(date.minusDays(1))
            val today = on(date)
            val tomorrow = on(date.plusDays(1))
            val same = mine != null && yesterday?.id == mine.id
            if (my == null) {
                return@map Option(team, yesterday, today, tomorrow, if (today == null) Verdict.CAN else Verdict.ALSO_WORKS, null, null, same, "")
            }
            val around = (-1L..2L).mapNotNull { k -> val d = date.plusDays(k); on(d)?.let { t -> span(t, d) } }
            val untimed = (-1L..1L).any { k -> on(date.plusDays(k))?.let { !it.hasTimes } == true }
            val clash = around.firstOrNull { it.from < my.to && my.from < it.to }
            val before = around.filter { !it.to.isAfter(my.from) }.maxByOrNull { it.to }
            val after = around.filter { !it.from.isBefore(my.to) }.minByOrNull { it.from }
            val restBefore = before?.let { Duration.between(it.to, my.from).toMinutes() }
            val restAfter = after?.let { Duration.between(my.to, it.from).toMinutes() }
            val (verdict, reason) = when {
                clash != null -> Verdict.BUSY to "${dayWord(clash.date, date)} ${clash.type.name}(${hm(clash.from)}~${hm(clash.to)}) 시간과 겹쳐요"
                restBefore != null && restBefore < minRestMinutes ->
                    Verdict.SHORT_REST to "${dayWord(before!!.date, date)} ${before.type.name}(${hm(before.to)} 끝) 뒤 ${hours(restBefore)}밖에 못 쉬어요"
                restAfter != null && restAfter < minRestMinutes ->
                    Verdict.SHORT_REST to "끝나고 ${hours(restAfter)} 뒤 ${dayWord(after!!.date, date)} ${after.type.name}(${hm(after.from)} 시작)이 있어요"
                untimed -> Verdict.UNKNOWN to "앞뒤 근무에 시간이 없어요"
                today != null -> Verdict.ALSO_WORKS to "그날 ${today.name}도 해요(연달아 근무)"
                else -> Verdict.CAN to (if (restBefore == null) "앞뒤로 근무가 없어요" else "${hours(restBefore)} 쉬고 와요")
            }
            // The team on my shift yesterday is who people think of first; say so whatever the verdict.
            val why = if (same) "어제 같은 근무(${mine.name})를 한 조 · $reason" else reason
            Option(team, yesterday, today, tomorrow, verdict, restBefore, restAfter, same, why)
        }
        val sorted = options.sortedWith(
            compareBy<Option> { it.verdict.ordinal }
                .thenBy { if (it.sameShiftYesterday) 0 else 1 }
                .thenByDescending { it.restBefore ?: Long.MAX_VALUE },
        )
        return Result(date, mine, sorted)
    }

    private fun span(t: ShiftType, d: LocalDate): Span? {
        if (!t.hasTimes) return null
        val from = d.atTime(t.start!!)
        return Span(from, from.plusMinutes(t.durationMinutes.toLong()), t, d)
    }

    private fun dayWord(d: LocalDate, base: LocalDate): String = when (d.toEpochDay() - base.toEpochDay()) {
        -1L -> "어제"
        0L -> "그날"
        1L -> "다음 날"
        2L -> "이틀 뒤"
        else -> "${d.monthValue}/${d.dayOfMonth}"
    }

    private fun hm(t: LocalDateTime) = "%02d:%02d".format(t.hour, t.minute)

    private fun hours(minutes: Long): String =
        if (minutes % 60 == 0L) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"
}
