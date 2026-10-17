package com.bamnat.core.sleep

import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.schedule.RestCheck
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SLP-03][SLP-05][SLP-07][SLP-13] Sleep times that fit the schedule, worked out by rules from the
 * shifts around a day. Wording follows the plan's rules: "일정에 맞춘 수면 시간 제안", no claim
 * to diagnose, prevent or treat anything. Each tip says how strong its basis is.
 *
 * Rules (app defaults, to be checked by an occupational or sleep physician before release):
 * - After a night shift: sleep from an hour after it ends (the way home), at least 6 hours in one
 *   go (KOSHA guidance in the plan), or the user's target if longer.
 * - Before the first night of a run: a nap of up to 90 minutes in the afternoon, ending 2.5 hours
 *   before the shift.
 * - Before an early day shift: in bed early enough for the target, waking 90 minutes before it.
 * - Caffeine: none in the 6 hours before a planned sleep (general information, low evidence).
 * - Drowsy driving: one reminder when a night shift ends.
 */
object SleepPlan {
    enum class Kind(val label: String) {
        NAP("야간 전 낮잠"),
        SLEEP_AFTER_NIGHT("야간 뒤 잠"),
        BEDTIME("잠자리"),
        CAFFEINE("카페인 마지막"),
        DROWSY("퇴근길 졸음 주의"),
    }

    enum class Basis(val label: String) {
        GUIDANCE("안전보건 지침"),
        GENERAL("일반 권고"),
        LOW("근거 수준 낮음"),
    }

    data class Tip(val kind: Kind, val at: LocalDateTime, val until: LocalDateTime?, val text: String, val basis: Basis)

    const val DEFAULT_TARGET_MINUTES = 7 * 60
    const val MIN_AFTER_NIGHT_MINUTES = 6 * 60
    const val COMMUTE_MINUTES = 60L
    const val NAP_MINUTES = 90L
    const val NAP_GAP_MINUTES = 150L
    const val WAKE_BEFORE_MINUTES = 90L
    const val CAFFEINE_GAP_HOURS = 6L
    /** Day shifts starting before this need an early night. */
    val EARLY_START: LocalTime = LocalTime.of(10, 0)

    /** Tips for [day], given the day before and after it; sorted by time. */
    fun forDay(prev: ResolvedDay?, day: ResolvedDay, next: ResolvedDay?, targetMinutes: Int = DEFAULT_TARGET_MINUTES): List<Tip> {
        val tips = mutableListOf<Tip>()
        val shift = shift(day)
        if (shift != null && isNight(shift)) {
            val (from, to) = shift
            if (prev == null || shift(prev)?.let(::isNight) != true) {
                val napEnd = from.minusMinutes(NAP_GAP_MINUTES)
                tips += Tip(Kind.NAP, napEnd.minusMinutes(NAP_MINUTES), napEnd,
                    "첫 야간 전에 ${NAP_MINUTES}분 안쪽으로 짧게 자 두기", Basis.GENERAL)
            }
            val sleep = to.plusMinutes(COMMUTE_MINUTES)
            val length = maxOf(targetMinutes, MIN_AFTER_NIGHT_MINUTES).toLong()
            tips += Tip(Kind.DROWSY, to, null, "야간을 마친 직후라 졸릴 수 있어요. 운전은 쉬고 나서", Basis.GENERAL)
            tips += Tip(Kind.SLEEP_AFTER_NIGHT, sleep, sleep.plusMinutes(length),
                "퇴근하고 바로 ${hours(length)} 이상 이어서 자기 (암막, 휴대폰 무음)", Basis.GUIDANCE)
            tips += Tip(Kind.CAFFEINE, sleep.minusHours(CAFFEINE_GAP_HOURS), null,
                "잠들기 ${CAFFEINE_GAP_HOURS}시간 전부터는 커피·에너지 음료 줄이기", Basis.LOW)
        } else if (next != null) {
            val tomorrow = shift(next)
            if (tomorrow != null && !isNight(tomorrow) && tomorrow.first.toLocalTime() < EARLY_START &&
                tomorrow.first.toLocalDate() == day.date.plusDays(1)
            ) {
                val wake = tomorrow.first.minusMinutes(WAKE_BEFORE_MINUTES)
                val bed = wake.minusMinutes(targetMinutes.toLong())
                // Only when today's own work is over by then.
                if (shift == null || !shift.second.isAfter(bed)) {
                    tips += Tip(Kind.BEDTIME, bed, wake, "내일 ${t(tomorrow.first)} 근무라 ${t(bed)}쯤 잠자리에 들기", Basis.GENERAL)
                    tips += Tip(Kind.CAFFEINE, bed.minusHours(CAFFEINE_GAP_HOURS), null,
                        "잠들기 ${CAFFEINE_GAP_HOURS}시간 전부터는 커피·에너지 음료 줄이기", Basis.LOW)
                }
            }
        }
        return tips.sortedBy { it.at }
    }

    private fun shift(day: ResolvedDay): Pair<LocalDateTime, LocalDateTime>? {
        if (!day.isWorkDay || day.leave?.isFullDay == true) return null
        val s = day.start ?: return null
        val e = day.end ?: return null
        val from = day.date.atTime(s)
        val sameDay = day.date.atTime(e)
        val to = (if (sameDay.isAfter(from)) sameDay else sameDay.plusDays(1)).plusMinutes(day.overtimeMinutes.toLong())
        return from to to
    }

    private fun isNight(shift: Pair<LocalDateTime, LocalDateTime>): Boolean {
        val total = Duration.between(shift.first, shift.second).toMinutes()
        return total > 0 && RestCheck.nightMinutes(shift.first, shift.second) * 2 >= total
    }

    private fun t(x: LocalDateTime) = "%02d:%02d".format(x.hour, x.minute)
    private fun hours(m: Long) = if (m % 60 == 0L) "${m / 60}시간" else "${m / 60}시간 ${m % 60}분"
}
