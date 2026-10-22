package com.bamnat.core.money

import java.time.LocalDateTime

/** [WORK-02] Picks the next job reminder to set; only one is set at a time, like shift alarms. */
object JobReminders {
    data class Next(val job: Job, val at: LocalDateTime)

    /** Choices in the job editor; null means no reminder. */
    val CHOICES: List<Int?> = listOf(null, 30, 60, 120, 24 * 60)

    fun label(minutes: Int?): String = when (minutes) {
        null -> "알림 없음"
        24 * 60 -> "하루 전"
        else -> if (minutes % 60 == 0) "${minutes / 60}시간 전" else "${minutes}분 전"
    }

    /** The earliest reminder strictly after [now]; jobs without a start time or reminder are skipped. */
    fun next(jobs: List<Job>, now: LocalDateTime): Next? = jobs.mapNotNull { j ->
        // [FLD-03] A cancelled job is not reminded of.
        if (j.cancelled) return@mapNotNull null
        val start = j.start ?: return@mapNotNull null
        val before = j.remindMinutes ?: return@mapNotNull null
        val at = j.date.atTime(start).minusMinutes(before.toLong())
        if (at.isAfter(now)) Next(j, at) else null
    }.minWithOrNull(compareBy<Next>({ it.at }, { it.job.id }))
}
