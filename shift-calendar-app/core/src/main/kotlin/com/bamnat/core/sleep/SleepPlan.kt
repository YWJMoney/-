package com.bamnat.core.sleep

import com.bamnat.core.schedule.ResolvedDay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * [SLP-03][SLP-05][SLP-07][SLP-13] Sleep times that fit the schedule, worked out by rules from the
 * shifts around a day. Wording follows the plan's rules: "일정에 맞춘 수면 시간 제안", no claim
 * to diagnose, prevent or treat anything. Each tip says how strong its basis is.
 *
 * Rules (app defaults, to be checked by an occupational or sleep physician before release), worked
 * out in [SleepSchedule]:
 * - After work that runs overnight (a night, a 24-hour duty): sleep from the way home after it ends,
 *   at least 6 hours in one go (KOSHA guidance in the plan), or the user's target if longer.
 * - Before the first night of a run: a nap of up to 90 minutes in the afternoon, ending 2.5 hours
 *   before the shift.
 * - Before an early start: in bed early enough for the target, waking at its alarm (or 90 minutes
 *   before it without one).
 * - Caffeine: none in the 6 hours before a planned sleep (general information, low evidence).
 * - Drowsy driving: one reminder when overnight work ends.
 */
object SleepPlan {
    enum class Kind(val label: String) {
        NAP("야간 전 낮잠"),
        SLEEP_AFTER_NIGHT("퇴근 뒤 잠"),
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

    /** Tips for [day], given the day before and after it, without alarms; sorted by time. */
    fun forDay(prev: ResolvedDay?, day: ResolvedDay, next: ResolvedDay?, targetMinutes: Int = DEFAULT_TARGET_MINUTES): List<Tip> =
        tips(SleepSchedule.of(listOfNotNull(prev, day, next), emptyList(), targetMinutes), day.date)

    /**
     * [SLP-14] The tips for [date] from the plan the today card reads too: around the day's own work
     * (a nap before a first night, the way home and the sleep after work that ran overnight), and the
     * night before an early start the next day.
     */
    fun tips(schedule: SleepSchedule, date: LocalDate): List<Tip> {
        val tips = mutableListOf<Tip>()
        for (w in schedule.work.filter { it.date == date }) {
            schedule.napBefore(w)?.let { n ->
                tips += Tip(Kind.NAP, n.from, n.to, "첫 야간 전에 ${n.minutes}분 안쪽으로 짧게 자 두기", Basis.GENERAL)
            }
            if (!w.overnight) continue
            tips += Tip(Kind.DROWSY, w.to, null, "밤새 일한 직후라 졸릴 수 있어요. 운전은 쉬고 나서", Basis.GENERAL)
            schedule.sleepAfter(w)?.let { s ->
                tips += Tip(Kind.SLEEP_AFTER_NIGHT, s.from, s.to,
                    if (s.short) "다음 근무까지 쉴 틈이 짧아요. 퇴근하고 바로 자기 (암막, 휴대폰 무음)"
                    else "퇴근하고 바로 ${hours(s.minutes)} 이상 이어서 자기 (암막, 휴대폰 무음)", Basis.GUIDANCE)
                tips += caffeine(s.from)
            }
        }
        for (s in schedule.sleeps) {
            val w = s.before?.takeIf { s.after == null && it.date == date.plusDays(1) } ?: continue
            tips += Tip(Kind.BEDTIME, s.from, s.to,
                if (s.short) "내일 ${t(w.from)} 근무라 쉴 틈이 짧아요. ${t(s.from)}엔 잠자리에 들기"
                else "내일 ${t(w.from)} 근무라 ${t(s.from)}쯤 잠자리에 들기", Basis.GENERAL)
            tips += caffeine(s.from)
        }
        return tips.sortedBy { it.at }
    }

    private fun caffeine(sleep: LocalDateTime) =
        Tip(Kind.CAFFEINE, sleep.minusHours(CAFFEINE_GAP_HOURS), null, "잠들기 ${CAFFEINE_GAP_HOURS}시간 전부터는 커피·에너지 음료 줄이기", Basis.LOW)

    private fun t(x: LocalDateTime) = "%02d:%02d".format(x.hour, x.minute)
    private fun hours(m: Long) = if (m % 60 == 0L) "${m / 60}시간" else "${m / 60}시간 ${m % 60}분"
}
