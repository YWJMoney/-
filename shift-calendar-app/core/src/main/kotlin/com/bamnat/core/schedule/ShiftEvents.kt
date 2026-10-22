package com.bamnat.core.schedule

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [FEAT-07] The shifts as events for a calendar of their own on the phone, the way the .ics file has
 * them ([IcsWriter]): a work day is an event at its times, a day without times an all-day event, and
 * each piece of extra work with clock times an event too; days off are left out. Each event has a key
 * that stays the same for its date, so a changed day updates its event instead of adding another.
 */
data class ShiftEvent(
    val key: String,
    val title: String,
    /** Both null for an all-day event on [date]. */
    val start: LocalDateTime?,
    val end: LocalDateTime?,
    val date: LocalDate,
    val description: String?,
) {
    val allDay: Boolean get() = start == null || end == null

    /** What the phone's copy is compared with: any change here rewrites the event. */
    val fingerprint: String get() = listOf(title, start, end, if (allDay) date else null, description).joinToString("|")
}

object ShiftEvents {
    fun of(days: List<ResolvedDay>): List<ShiftEvent> = days.flatMap { day ->
        buildList {
            val type = day.shiftType?.takeIf { day.isWorkDay }
            if (type != null) {
                val start = day.start
                val end = day.end
                val notes = day.extras.filter { it.from == null }.map { "${it.kind.name} ${hours(it.minutes)}" }
                if (start != null && end != null) {
                    val endAt = if (end.isAfter(start)) day.date.atTime(end) else day.date.plusDays(1).atTime(end)
                    add(ShiftEvent("${day.date}", type.name, day.date.atTime(start), endAt, day.date, notes.joinToString(", ").ifEmpty { null }))
                } else {
                    add(ShiftEvent("${day.date}", type.name, null, null, day.date, notes.joinToString(", ").ifEmpty { null }))
                }
            }
            day.extras.forEachIndexed { i, e ->
                val from = e.from ?: return@forEachIndexed
                val to = e.to ?: return@forEachIndexed
                add(ShiftEvent("${day.date}-x$i", e.kind.name + (e.covered?.let { " (${it.name})" } ?: ""), from, to, day.date, hours(e.minutes)))
            }
        }
    }

    /** What to do to the phone's events, by key, to make them [wanted]. */
    data class Plan(val insert: List<ShiftEvent>, val update: List<ShiftEvent>, val delete: Set<String>) {
        val empty: Boolean get() = insert.isEmpty() && update.isEmpty() && delete.isEmpty()
    }

    /** [existing] is the phone's events as key → fingerprint. */
    fun plan(existing: Map<String, String>, wanted: List<ShiftEvent>): Plan {
        val byKey = wanted.associateBy { it.key }
        return Plan(
            insert = wanted.filter { it.key !in existing },
            update = wanted.filter { it.key in existing && existing[it.key] != it.fingerprint },
            delete = existing.keys - byKey.keys,
        )
    }

    private fun hours(minutes: Int): String =
        if (minutes % 60 == 0) "${minutes / 60}시간" else "${minutes / 60}시간 ${minutes % 60}분"
}
