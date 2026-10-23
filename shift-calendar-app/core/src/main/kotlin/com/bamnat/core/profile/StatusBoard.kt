package com.bamnat.core.profile

/**
 * [OBS-03] "알고 있는 것 12 · 추정 3 · 모르는 것 2": how close this calendar is to every feature working, at
 * the top of settings. [unknown] are the facts a feature that is on still waits for, the ones the
 * question card will ask; [estimated] are known but read off something or guessed ([Fact.estimated]).
 */
data class StatusBoard(val known: Int, val estimated: Int, val unknown: List<FactKey>) {
    val line: String get() = "알고 있는 것 $known · 추정 $estimated · 모르는 것 ${unknown.size}"

    companion object {
        fun of(facts: Map<FactKey, Fact<*>>, missing: List<Missing>): StatusBoard {
            val waiting = missing.map { it.key }
            val held = facts.values.filter { it.known && it.key !in waiting }
            return StatusBoard(held.count { !it.estimated }, held.count { it.estimated }, waiting)
        }
    }
}
