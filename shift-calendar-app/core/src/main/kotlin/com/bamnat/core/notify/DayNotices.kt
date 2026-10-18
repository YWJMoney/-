package com.bamnat.core.notify

import com.bamnat.core.schedule.ScheduleResolver
import com.bamnat.core.sleep.SleepDebt
import com.bamnat.core.sleep.SleepEntry
import com.bamnat.core.sleep.SleepPlan
import com.bamnat.core.sleep.SleepSchedule
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [SLP-15] The few notices the day's plan sends, worked out from the same [SleepSchedule] as 오늘의
 * 흐름 so a notice never says something the card does not. They replace five sleep tips that each
 * made a sound (11 in a 주주야야휴 cycle) with:
 * - [Kind.BRIEFING], silent, once for work that needs planning: on a night, 30 minutes before the nap
 *   of a first night, else 3 hours before it; the day before an early start, when coffee should stop,
 *   or when work ends if it is still going then. Gone once the work starts or bed time comes.
 * - [Kind.COMMUTE], with sound, when work that ran overnight ends; gone after 2 hours, and not sent
 *   at all when more than 10 minutes late, so it never lands mid-drive.
 * - [Kind.BEDTIME], silent and only when asked for: 30 minutes before the night before an early start.
 * Reference only, like all of sleep.
 */
object DayNotices {
    enum class Kind { BRIEFING, COMMUTE, BEDTIME }

    data class Notice(
        val kind: Kind,
        val at: LocalDateTime,
        /** When it has nothing more to say and goes away. */
        val until: LocalDateTime,
        val title: String,
        val text: String,
        /** The same work gives the same key when planned again. */
        val key: String,
    )

    /** Which notices the user wants. */
    data class Wanted(val briefing: Boolean = true, val commute: Boolean = true, val bedtime: Boolean = false) {
        val any: Boolean get() = briefing || commute || bedtime
    }

    const val BEFORE_NAP_MINUTES = 30L
    const val BEFORE_NIGHT_HOURS = 3L
    const val BEFORE_BED_MINUTES = 30L
    const val COMMUTE_SHOWN_MINUTES = 120L
    /** A commute notice later than this is dropped: the drive has started. */
    const val LATE_MINUTES = 10L
    /** Work starting this soon after the last one ends: no drive home in between. */
    const val NO_DRIVE_MINUTES = 60L
    const val LOOK_AHEAD_DAYS = 3L

    /** Every notice planned from yesterday to [LOOK_AHEAD_DAYS] ahead, earliest first. */
    fun plan(
        now: LocalDateTime,
        resolver: ScheduleResolver,
        alarmsOn: Boolean,
        wanted: Wanted,
        targetMinutes: Int = SleepPlan.DEFAULT_TARGET_MINUTES,
        commuteMinutes: Int = SleepPlan.COMMUTE_MINUTES.toInt(),
    ): List<Notice> {
        if (!wanted.any) return emptyList()
        val today = now.toLocalDate()
        val plan = SleepSchedule.of(resolver, today.minusDays(1), today.plusDays(LOOK_AHEAD_DAYS), alarmsOn, targetMinutes, commuteMinutes)
        return of(plan, wanted)
    }

    /** The notices [plan] gives, earliest first. */
    fun of(plan: SleepSchedule, wanted: Wanted): List<Notice> {
        val out = mutableListOf<Notice>()
        val work = plan.work
        for ((i, w) in work.withIndex()) {
            val prev = work.getOrNull(i - 1)
            val next = work.getOrNull(i + 1)
            if (wanted.briefing) briefing(plan, w, prev)?.let(out::add)
            if (wanted.commute && w.overnight && (next == null || Duration.between(w.to, next.from).toMinutes() >= NO_DRIVE_MINUTES)) {
                val sleep = plan.sleepAfter(w)
                out += Notice(Kind.COMMUTE, w.to, w.to.plusMinutes(COMMUTE_SHOWN_MINUTES), "${w.name} 수고했어요",
                    "졸리면 운전 전에 쉬어 가요" + (sleep?.let { " · 잘 시간 ${span(it.from, it.to)}" } ?: ""), key("commute", w))
            }
        }
        if (wanted.bedtime) for (s in plan.sleeps) {
            val w = s.before?.takeIf { s.after == null } ?: continue
            val at = s.from.minusMinutes(BEFORE_BED_MINUTES)
            val alarm = plan.alarmFor(w)
            out += Notice(Kind.BEDTIME, at, s.from, "${hm(s.from)} 잘 시간이에요",
                listOfNotNull("${dayWord(w.from.toLocalDate(), at.toLocalDate())}${hm(w.from)} ${w.name}",
                    alarm?.let { "알람 ${hm(it.triggerAt)} 켜져 있어요" }).joinToString(" · "), key("bed", w))
        }
        return out.sortedWith(compareBy({ it.at }, { it.kind }))
    }

    /** One briefing for [w]: the day before an early start, or the afternoon of work that runs overnight. */
    private fun briefing(plan: SleepSchedule, w: SleepSchedule.Work, prev: SleepSchedule.Work?): Notice? {
        if (w.early) {
            val s = plan.sleeps.firstOrNull { it.before == w && it.after == null } ?: return null
            val cut = s.from.minusHours(SleepPlan.CAFFEINE_GAP_HOURS)
            // Still at work when coffee should stop: when it ends.
            val at = prev?.takeIf { !cut.isBefore(it.from) && cut.isBefore(it.to) }?.to ?: cut
            if (!at.isBefore(s.from)) return null
            val day = dayWord(w.from.toLocalDate(), at.toLocalDate())
            val alarm = plan.alarmFor(w)
            val rest = s.restMinutes?.takeIf { s.short }
            val title = "$day${hm(w.from)} ${w.name}" + if (rest != null) " · 쉴 틈 ${hours(rest)}" else "이에요"
            val text = listOfNotNull(
                when {
                    at.isBefore(cut) -> "커피는 ${hm(cut)}까지"
                    at == cut -> "커피는 지금까지"
                    else -> null
                },
                if (s.short) "잘 수 있는 시간 ${span(s.from, s.to)}" else "${hm(s.from)}에 누우면 ${hours(s.minutes)}",
                alarm?.let { "알람 ${hm(it.triggerAt)}" },
            ).joinToString(" · ")
            return Notice(Kind.BRIEFING, at, s.from, title, text, key("brief", w))
        }
        if (!w.overnight) return null
        val nap = plan.napBefore(w)
        var at = nap?.from?.minusMinutes(BEFORE_NAP_MINUTES) ?: w.from.minusHours(BEFORE_NIGHT_HOURS)
        // Not before the work before it ends, nor while the sleep before it is planned: it says the day ahead.
        prev?.to?.let { if (it.isAfter(at)) at = it }
        plan.sleeps.lastOrNull { it.from.isBefore(w.from) && it.to.isAfter(at) && !it.to.isAfter(w.from) }?.to?.let { if (it.isAfter(at)) at = it }
        if (!at.isBefore(w.from)) return null
        val sleep = plan.sleepAfter(w)
        val text = listOfNotNull(
            nap?.takeIf { it.to.isAfter(at) }?.let { "${span(it.from, it.to)} 낮잠" },
            sleep?.let { "커피는 ${hm(it.from.minusHours(SleepPlan.CAFFEINE_GAP_HOURS))}까지" },
            sleep?.let { "퇴근 후 ${span(it.from, it.to)} " + if (it.short) "잘 수 있어요" else "잘 시간" },
        ).joinToString(" · ")
        return Notice(Kind.BRIEFING, at, w.from, "${dayWord(w.from.toLocalDate(), at.toLocalDate())}${w.name} ${span(w.from, w.to)}",
            text.ifEmpty { "${hm(w.from)}부터 근무예요" }, key("brief", w))
    }

    /** The notices due at [firedAt], the moment their alarm was set for, that still have something to say at [now]. */
    fun due(notices: List<Notice>, firedAt: LocalDateTime, now: LocalDateTime): List<Notice> =
        notices.filter { n -> n.at.isAfter(firedAt.minusMinutes(1)) && !n.at.isAfter(firedAt) && fresh(n, now) }

    /** Still worth showing at [now]: a commute notice up to 10 minutes late, the rest until they expire. */
    fun fresh(n: Notice, now: LocalDateTime): Boolean = when (n.kind) {
        Kind.COMMUTE -> Duration.between(n.at, now).toMinutes() <= LATE_MINUTES
        else -> now.isBefore(n.until)
    }

    private fun key(prefix: String, w: SleepSchedule.Work) = "$prefix-${w.date}-${hm(w.from)}"

    /** "오늘 ", "내일 ", "모레 ", "어제 " or "11/7 ", for [date] seen from [from]. */
    internal fun dayWord(date: LocalDate, from: LocalDate): String = when (date) {
        from -> "오늘 "
        from.plusDays(1) -> "내일 "
        from.plusDays(2) -> "모레 "
        from.minusDays(1) -> "어제 "
        else -> "${date.monthValue}/${date.dayOfMonth} "
    }

    internal fun hm(x: LocalDateTime) = "%02d:%02d".format(x.hour, x.minute)
    internal fun span(a: LocalDateTime, b: LocalDateTime) = "${hm(a)}–${hm(b)}"
    internal fun hours(m: Long): String = when {
        m < 60 -> "${m}분"
        m % 60 == 0L -> "${m / 60}시간"
        else -> "${m / 60}시간 ${m % 60}분"
    }
}

/**
 * [SLP-15] Notices that can wait (money due or owed, 연차 about to lapse) do not come while the user
 * sleeps: they come 30 minutes after waking. Shift alarms and job reminders never wait. Asleep is a
 * sleep the user logged with "잠들었어요", else a sleep the plan has at that time, unless the log
 * says the user was already up.
 */
object QuietHours {
    const val AFTER_WAKE_MINUTES = 30L
    /** A notice held this long comes anyway: a missed "깼어요" must not hide it for good. */
    const val MAX_HOLD_HOURS = 16L

    /**
     * When a notice due at [at] may come, seen at [now]: null when it need not wait. An open log is
     * expected to end when the plan's sleep does (or after the target); "깼어요" moves it to then.
     */
    fun holdUntil(at: LocalDateTime, now: LocalDateTime, plan: SleepSchedule?, log: List<SleepEntry>, targetMinutes: Int): LocalDateTime? {
        val cap = at.plusHours(MAX_HOLD_HOURS)
        fun result(until: LocalDateTime): LocalDateTime? = minOf(until.plusMinutes(AFTER_WAKE_MINUTES), cap).takeIf { it.isAfter(now) }
        val manual = log.filter { !it.imported }
        // A logged sleep around the notice's time: it waits for the wake.
        manual.firstOrNull { it.end != null && !at.isBefore(it.start) && at.isBefore(it.end) }?.let { return result(it.end!!) }
        val open = manual.firstOrNull { it.end == null && !at.isBefore(it.start) && Duration.between(it.start, at).toMinutes() < SleepDebt.MAX_SLEEP_MINUTES }
        val planned = plan?.sleeps?.firstOrNull { !at.isBefore(it.from) && at.isBefore(it.to) }
        if (open != null) {
            // Still open past the expected wake: most likely a missed "깼어요", so the notice comes.
            return result(planned?.takeIf { !open.start.isAfter(it.to) }?.to ?: open.start.plusMinutes(targetMinutes.toLong()))
        }
        if (planned == null) return null
        // Up already: a logged sleep that ended inside the planned one, before the notice.
        if (manual.any { it.end != null && it.end.isAfter(planned.from) && !it.end.isAfter(at) }) return null
        return result(planned.to)
    }
}
