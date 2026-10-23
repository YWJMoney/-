package com.bamnat.core.schedule

import com.bamnat.core.model.ShiftType
import com.bamnat.core.model.WorkPattern
import java.time.LocalDate
import kotlin.math.abs

/**
 * [SCHED-07] I 리뷰 N1 근무 바꾸기 도우미: to be off on a day, a team takes my shift that day and I take one of theirs
 * on a day I am off, both ways checked with the same rest rule [CoverFinder] uses (11시간 by default). Where
 * [CoverFinder] looks one way (대근), this pairs each team that can come in with the days I could pay it back,
 * nearest first, and words the request for the team chat.
 */
object SwapFinder {
    data class Swap(
        val team: TeamRoster.Team,
        /** The day I want off and my shift on it, which [team] works instead. */
        val giveDate: LocalDate,
        val myShift: ShiftType,
        /** The day I work [theirShift] for [team] in return. */
        val takeDate: LocalDate,
        val theirShift: ShiftType,
        /** Why I can work it, e.g. "앞뒤로 근무가 없어요". */
        val reason: String,
    ) {
        fun request(label: (LocalDate) -> String): String =
            "${label(giveDate)} 제 ${myShift.name}을 ${team.name}에서 해 주시고, ${label(takeDate)} ${team.name} ${theirShift.name}을 제가 할게요. 바꿔 주실 수 있나요?"
    }

    /**
     * The swaps for being off on [date], at most [perTeam] for each team, the days within [window] days either
     * side; empty when I do not work that day.
     */
    fun find(
        roster: TeamRoster,
        rotation: WorkPattern.Rotation,
        types: Map<Long, ShiftType>,
        date: LocalDate,
        myShiftId: Long?,
        minRestMinutes: Int = CoverFinder.DEFAULT_REST_MINUTES,
        window: Int = 14,
        perTeam: Int = 3,
    ): List<Swap> {
        val give = CoverFinder.find(roster, rotation, types, date, myShiftId, minRestMinutes)
        val mine = give.myShift ?: return emptyList()
        val me = roster.mine
        fun shift(team: TeamRoster.Team, d: LocalDate) = roster.shiftOn(rotation, team, d)?.let(types::get)?.takeIf { !it.isOff }
        val days = (1..window).flatMap { k -> listOf(date.plusDays(k.toLong()), date.minusDays(k.toLong())) }
        return give.options.filter { it.verdict == CoverFinder.Verdict.CAN }.flatMap { o ->
            // The same roster seen from the other team, so CoverFinder judges me as the one coming in.
            val theirs = TeamRoster(roster.teams.map { it.copy(mine = it.name == o.team.name) })
            days.mapNotNull { d ->
                val their = shift(o.team, d) ?: return@mapNotNull null
                if (shift(me, d) != null) return@mapNotNull null
                val back = CoverFinder.find(theirs, rotation, types, d, their.id, minRestMinutes)
                    .options.firstOrNull { it.team.name == me.name }
                    ?.takeIf { it.verdict == CoverFinder.Verdict.CAN } ?: return@mapNotNull null
                Swap(o.team, date, mine, d, their, back.reason)
            }.sortedBy { abs(it.takeDate.toEpochDay() - date.toEpochDay()) }.take(perTeam)
        }
    }
}
