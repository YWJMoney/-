package com.bamnat.core.schedule

import com.bamnat.core.model.WorkPattern
import java.time.LocalDate

/**
 * [CREW-01] Every team of a rotation such as 3조 2교대 or 4조 2교대: the teams work the same order, each
 * a few days apart. A team is my rotation moved by [Team.lag] days, so team B with lag 2 works today
 * what my team worked two days ago.
 */
data class TeamRoster(val teams: List<Team>) {
    data class Team(val name: String, val lag: Int, val mine: Boolean = false)

    init {
        require(teams.isNotEmpty()) { "A roster needs a team" }
    }

    /** The shift id [team] works on [date] under [rotation]; null for a day off or outside the rotation. */
    fun shiftOn(rotation: WorkPattern.Rotation, team: Team, date: LocalDate): Long? {
        if (!rotation.covers(date)) return null
        val size = rotation.steps.size
        return rotation.steps[Math.floorMod(rotation.stepIndexOn(date) - team.lag, size)]
    }

    /** The team [date] is 쉬는 for under [rotation]: no shift that day. */
    fun offOn(rotation: WorkPattern.Rotation, date: LocalDate): List<Team> = teams.filter { shiftOn(rotation, it, date) == null }

    val mine: Team get() = teams.firstOrNull { it.mine } ?: teams.first()

    /** The same roster with [team] set to work [stepIndex] of the order today, the way people know it ("B조 오늘 야간"). */
    fun withToday(rotation: WorkPattern.Rotation, name: String, stepIndex: Int, today: LocalDate): TeamRoster {
        val size = rotation.steps.size
        val lag = Math.floorMod(rotation.stepIndexOn(today) - stepIndex, size)
        return copy(teams = teams.map { if (it.name == name) it.copy(lag = lag) else it })
    }

    fun encode(): String = teams.joinToString(";") { "${it.name.replace(";", " ").replace("|", " ")}|${it.lag}|${if (it.mine) 1 else 0}" }

    companion object {
        const val MAX_TEAMS = 8
        private val NAMES = listOf("A조", "B조", "C조", "D조", "E조", "F조", "G조", "H조")

        fun decode(text: String?): TeamRoster? {
            if (text.isNullOrBlank()) return null
            val teams = text.split(";").mapNotNull { part ->
                val bits = part.split("|")
                if (bits.size != 3) return@mapNotNull null
                val lag = bits[1].toIntOrNull() ?: return@mapNotNull null
                Team(bits[0].trim().ifEmpty { return@mapNotNull null }.take(12), lag, bits[2] == "1")
            }.take(MAX_TEAMS)
            return teams.takeIf { it.isNotEmpty() }?.let(::TeamRoster)
        }

        /**
         * How many teams [steps] split into: the fewest teams, a whole share of the order apart, that put
         * every working shift on exactly one team every day (주주야야비휴 → 3, 주야비휴 → 4). Null when no
         * even split does, such as 24시간 4조 맞교대; then the user sets the teams.
         */
        fun teamCount(steps: List<Long?>): Int? {
            val size = steps.size
            val work = steps.filterNotNull().toSet()
            if (work.isEmpty()) return null
            return (2..minOf(size, MAX_TEAMS)).firstOrNull { n ->
                size % n == 0 && (0 until size).all { i ->
                    val today = (0 until n).mapNotNull { j -> steps[Math.floorMod(i - j * (size / n), size)] }
                    today.size == work.size && today.toSet() == work
                }
            }
        }

        /** [teamCount] teams (or [fallback]) named A조, B조…, mine first, each a share of the order behind the one before. */
        fun suggest(rotation: WorkPattern.Rotation, fallback: Int = 4): TeamRoster {
            val size = rotation.steps.size
            val n = teamCount(rotation.steps) ?: fallback.coerceIn(2, MAX_TEAMS)
            val share = maxOf(1, size / n)
            return TeamRoster((0 until n).map { j -> Team(NAMES[j], Math.floorMod(j * share, size), mine = j == 0) })
        }
    }
}
