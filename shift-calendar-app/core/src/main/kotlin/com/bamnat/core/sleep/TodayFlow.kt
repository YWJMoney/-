package com.bamnat.core.sleep

import com.bamnat.core.alarm.AlarmPlanner
import com.bamnat.core.alarm.PlannedAlarm
import com.bamnat.core.schedule.RestCheck
import com.bamnat.core.schedule.ScheduleResolver
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [SLP-14] 오늘의 흐름: the next 24 hours as one card on the calendar. One thing to do now in a big
 * line, why in a small one, and the one button that fits: 잠들었어요 after work that ran overnight,
 * 깼어요 while asleep, 알람 켜기 when the next work's alarm is off for its day. It reads the same plan
 * as the day view ([SleepSchedule]), and the rest sign of today or the next work goes on it as a
 * small line, so sleep and rest never say different things. Reference only, like all of sleep.
 */
object TodayFlow {
    /** What the card is about, most pressing first. */
    enum class State {
        /** The next work within 24 hours rings nothing: its day's alarm was turned off. */
        ALARM_OFF,
        SLEEPING,
        /** A sleep started more than 16 hours ago, or before work that has since begun: the wake tap was missed. */
        FORGOT_WAKE,
        ON_SHIFT,
        /** Overnight work just ended: on the way home. */
        AFTER_SHIFT,
        SLEEP_TIME,
        NAP,
        /** Up within the last 3 hours: how long the sleep was. */
        AWAKE,
        /** No work of its own today: one line. */
        REST_DAY,
        /** The next work, and the sleep or nap before it. */
        NEXT,
    }

    enum class Action { ASLEEP, AWAKE, AWAKE_AT, ALARM_ON }

    enum class Part { WORK, SLEEP, NAP }

    /** A piece of the strip, clipped to it. */
    data class Segment(val part: Part, val from: LocalDateTime, val to: LocalDateTime, val name: String, val colorArgb: Long? = null)

    data class Card(
        val state: State,
        /** "오늘 · 야간 19:00–07:00", or "지금 · …" during work. */
        val caption: String,
        val headline: String,
        val detail: String?,
        /** The rest sign of today or the next work, e.g. "야간 4일 연속 (11/3부터)". */
        val rest: String?,
        val action: Action?,
        /** For [Action.ALARM_ON], the day whose alarms to turn back on. */
        val actionDate: LocalDate?,
        /** The strip runs from now for [SPAN_HOURS]. */
        val from: LocalDateTime,
        val to: LocalDateTime,
        val segments: List<Segment>,
        /** Alarms that ring within the strip. */
        val alarms: List<LocalDateTime>,
        /** A day off says it all in one line. */
        val compact: Boolean,
        /** The short form for the widget and the day view, e.g. "잘 시간 08:00–15:00"; null when the day's own lines say it. */
        val line: String?,
        /** When the card may read differently next, so the widget can be redrawn then. */
        val changesAt: LocalDateTime,
    )

    const val SPAN_HOURS = 24L
    /** A bedtime or a nap takes over the card this long before it. */
    const val PROMPT_MINUTES = 60L
    /** How long after waking the card says how long the sleep was. */
    const val AWAKE_MINUTES = 3 * 60L
    /** Shorter than this is a nap, not held against the target. */
    const val MAIN_SLEEP_MINUTES = 3 * 60L
    /** A log shorter than this was a mistaken tap, not a sleep. */
    const val MIN_LOGGED_MINUTES = 20L
    /** How far to look for the next work from a day off. */
    const val LOOKAHEAD_DAYS = 14L

    private data class Pick(
        val state: State,
        val headline: String,
        val detail: String?,
        val action: Action? = null,
        val actionDate: LocalDate? = null,
        val line: String? = null,
        /** The detail already says the rest is short, so the rest sign need not. */
        val saysShort: Boolean = false,
        val compact: Boolean = false,
    )

    fun of(
        now: LocalDateTime,
        resolver: ScheduleResolver,
        alarmsOn: Boolean,
        log: List<SleepEntry> = emptyList(),
        targetMinutes: Int = SleepPlan.DEFAULT_TARGET_MINUTES,
        commuteMinutes: Int = SleepPlan.COMMUTE_MINUTES.toInt(),
        restCheck: Boolean = true,
        strictRest: Boolean = false,
        /** [SLP-16] How long the user's own sleeps after night work have been, from [SleepInsights.afterNightAverage]. */
        afterNightMinutes: Long? = null,
    ): Card {
        val today = now.toLocalDate()
        val end = now.plusHours(SPAN_HOURS)
        val t = Words(today)
        val plan = SleepSchedule.of(resolver, today.minusDays(1), today.plusDays(2), alarmsOn, targetMinutes, commuteMinutes)
        val work = plan.work
        val next = work.firstOrNull { it.from.isAfter(now) }
            ?: (4L..LOOKAHEAD_DAYS).firstNotNullOfOrNull { SleepSchedule.work(resolver.resolve(today.plusDays(it))).firstOrNull() }
        // What each day would ring with its own switch on, to tell a day turned off from work without alarms.
        val wouldRing = (-2L..3L).flatMap { AlarmPlanner.alarmsOn(resolver, today.plusDays(it), includeSkipped = true) }
        fun ringing(w: SleepSchedule.Work, all: Boolean): List<PlannedAlarm> {
            val list = when {
                w in work -> if (all) wouldRing else plan.alarms
                all -> AlarmPlanner.alarmsOn(resolver, w.date, includeSkipped = true)
                alarmsOn -> AlarmPlanner.alarmsOn(resolver, w.date)
                else -> emptyList()
            }
            return list.filter { SleepSchedule.belongs(it, w) && it.triggerAt.isAfter(now) }
        }
        fun alarm(w: SleepSchedule.Work): PlannedAlarm? = ringing(w, all = false).minByOrNull { it.triggerAt }
        fun rings(w: SleepSchedule.Work): Boolean = ringing(w, all = true).isNotEmpty()
        /** "내일 07:00 주간 · 알람 05:30"; "· 알람 꺼 둠" when its alarm would ring with the switch on. */
        fun workLine(w: SleepSchedule.Work, sep: String = " · "): String {
            val a = alarm(w)?.triggerAt
            return "${t.at(w.from)} ${w.name}" + when {
                a != null -> "${sep}알람 " + if (a.toLocalDate() == w.from.toLocalDate()) t.hm(a) else t.at(a)
                rings(w) -> "${sep}알람 꺼 둠"
                else -> ""
            }
        }
        // Finished sleeps that count: a mistaken tap or a missed "깼어요" closed much later says nothing.
        val done = log.filter { (it.minutes ?: -1) in MIN_LOGGED_MINUTES..SleepDebt.MAX_SLEEP_MINUTES }
        /** A logged sleep over [from, to] (from a little before it): that sleep is done. */
        fun slept(from: LocalDateTime, to: LocalDateTime) = done.any { e ->
            e.end!!.isAfter(from.minusMinutes(PROMPT_MINUTES)) && e.start.isBefore(to)
        }
        fun sleepHead(s: SleepSchedule.Sleep) = "${t.span(s.from, s.to)} " + if (s.short) "잘 수 있어요" else "잘 시간"
        fun sleepLine(s: SleepSchedule.Sleep) = "잘 시간 ${t.span(s.from, s.to)}"
        fun shortText(s: SleepSchedule.Sleep): String = s.restMinutes?.takeIf { it < RestCheck.MIN_REST_MINUTES }
            ?.let { "오늘은 쉴 틈이 ${t.hours(it)}뿐이에요" } ?: "목표보다 ${t.hours(targetMinutes - s.minutes)} 짧아요"

        val open = log.firstOrNull { it.end == null && !it.imported }
        val woke = done.maxByOrNull { it.end!! }
        val ongoing = work.firstOrNull { !now.isBefore(it.from) && now.isBefore(it.to) }

        val pick = run {
            // The next work's alarm is turned off for its day. A switch turned off for all alarms is the
            // user's own setting, said in the lines below rather than over everything else.
            if (open == null && alarmsOn) work.firstOrNull { it.from.isAfter(now) && !it.from.isAfter(end) && alarm(it) == null && rings(it) }?.let { w ->
                return@run Pick(State.ALARM_OFF, "${t.dayOrToday(w.from.toLocalDate())}${t.hm(w.from)} ${w.name}인데 알람이 꺼져 있어요",
                    "이 날 알람을 꺼 두었어요", Action.ALARM_ON, w.date, line = "${t.at(w.from)} ${w.name} 알람 꺼짐")
            }
            if (open != null) {
                val minutes = Duration.between(open.start, now).toMinutes()
                // Too long for a sleep, or work has started since: the "깼어요" tap was missed.
                if (minutes > SleepDebt.MAX_SLEEP_MINUTES || work.any { it.from.isAfter(open.start) && !it.from.isAfter(now) }) {
                    return@run Pick(State.FORGOT_WAKE, "깼을 때 누르는 걸 잊었나요?",
                        "${t.dayOrToday(open.start.toLocalDate())}${t.hm(open.start)}부터 자는 중으로 남아 있어요", Action.AWAKE_AT, line = "깬 시각을 적어 주세요")
                }
                val a = plan.alarms.firstOrNull { it.triggerAt.isAfter(now) && !it.triggerAt.isAfter(end) }
                val n = next?.takeIf { !it.from.isAfter(end) }
                val detail = a?.let { "다음 알람 ${t.at(it.triggerAt)}" } ?: n?.let { "다음 근무 ${workLine(it)}" }
                return@run Pick(State.SLEEPING, "${t.hm(open.start)}부터 자는 중", detail, Action.AWAKE, line = "${t.hm(open.start)}부터 자는 중")
            }
            ongoing?.let { w ->
                val s = plan.sleepAfter(w)
                if (s != null) {
                    val cut = s.from.minusHours(SleepPlan.CAFFEINE_GAP_HOURS)
                    return@run if (now.isBefore(cut)) {
                        Pick(State.ON_SHIFT, "${t.hm(cut)}까지만 커피", "${t.hm(s.from)}에 잠들 수 있게요", line = "커피는 ${t.hm(cut)}까지")
                    } else {
                        Pick(State.ON_SHIFT, "퇴근하면 ${t.span(s.from, s.to)} 잘 시간",
                            if (s.short) shortText(s) else "잠들기 ${SleepPlan.CAFFEINE_GAP_HOURS}시간 안이라 커피는 쉬어 가요", line = sleepLine(s), saysShort = s.short)
                    }
                }
                val after = next?.takeIf { !it.from.isBefore(w.to) }
                val bed = plan.sleeps.firstOrNull { !it.from.isBefore(w.to) && (after == null || it.from.isBefore(after.from)) }
                val detail = when {
                    bed != null -> "퇴근 뒤 잘 시간 ${t.span(bed.from, bed.to)}" + if (bed.short) " · ${shortText(bed)}" else ""
                    after != null -> "다음 근무 ${workLine(after)}"
                    else -> null
                }
                return@run Pick(State.ON_SHIFT, "근무 중 · ${t.hm(w.to)}까지", detail, line = bed?.let(::sleepLine), saysShort = bed?.short == true)
            }
            work.lastOrNull { !it.to.isAfter(now) && it.overnight }?.let { w ->
                val s = plan.sleepAfter(w) ?: return@let
                if (now.isBefore(s.from) && !slept(w.to, s.to)) {
                    // [SLP-16] The user's own past sleeps after night work, said once there are a few.
                    val past = afterNightMinutes?.let { m ->
                        "지난 야간 뒤엔 평균 ${t.hours(m)} 잤어요" + if (s.minutes - m >= SleepInsights.SHORT_MINUTES) " · 오늘은 ${t.hm(s.to)}까지 누워 있어 봐요" else ""
                    }
                    return@run Pick(State.AFTER_SHIFT, sleepHead(s), past ?: "수고했어요. 졸리면 운전 전에 쉬어 가요", Action.ASLEEP, line = sleepLine(s))
                }
            }
            plan.sleeps.firstOrNull { s ->
                val start = if (s.after != null) s.from else s.from.minusMinutes(PROMPT_MINUTES)
                !now.isBefore(start) && now.isBefore(s.to) && !slept(s.from, s.to)
            }?.let { s ->
                val up = s.before ?: next?.takeIf { !it.from.isBefore(s.to) }
                return@run Pick(State.SLEEP_TIME, sleepHead(s), if (s.short) shortText(s) else up?.let { workLine(it) }, Action.ASLEEP,
                    line = sleepLine(s), saysShort = s.short)
            }
            plan.naps.firstOrNull { n -> !now.isBefore(n.from.minusMinutes(PROMPT_MINUTES)) && now.isBefore(n.to) && !slept(n.from, n.to) }?.let { n ->
                return@run Pick(State.NAP, "${t.hm(n.from)} 낮잠 ${n.minutes}분", "첫 야간 전 낮잠 시간이에요 · ${t.hm(n.to)}엔 일어나요",
                    line = "낮잠 ${t.span(n.from, n.to)}")
            }
            val wokeAt = woke?.end
            val sleptMinutes = woke?.minutes ?: 0
            if (wokeAt != null && !wokeAt.isAfter(now) && Duration.between(wokeAt, now).toMinutes() <= AWAKE_MINUTES) {
                val n = next?.takeIf { !it.from.isAfter(end) }
                val head = n?.let { w ->
                    alarm(w)?.let { a -> "${t.at(a.triggerAt)} 알람 · " + (if (a.triggerAt.toLocalDate() == w.from.toLocalDate()) t.hm(w.from) else t.at(w.from)) + " ${w.name}" }
                        ?: workLine(w)
                } ?: if (work.none { it.date == today }) "오늘은 쉬는 날" else "오늘 근무는 끝났어요"
                val short = sleptMinutes in MAIN_SLEEP_MINUTES until targetMinutes
                return@run Pick(State.AWAKE, head, "${t.hours(sleptMinutes)} 잤어요" + if (short) " (목표보다 ${t.hours(targetMinutes - sleptMinutes)} 적어요)" else "",
                    line = "${t.hours(sleptMinutes)} 잤어요")
            }
            if (ongoing == null && work.none { it.date == today }) {
                return@run Pick(State.REST_DAY, "오늘은 쉬는 날" + (next?.let { " · " + workLine(it, sep = ", ") } ?: ""), null, compact = true)
            }
            val bed = plan.sleeps.firstOrNull { it.from.isAfter(now) && (next == null || it.from.isBefore(next.from)) && !slept(it.from, it.to) }
            val nap = next?.let(plan::napBefore)?.takeIf { it.from.isAfter(now) }
            val soon = next?.takeIf { !it.from.isAfter(end) }
            // Work about to start that runs overnight: the sleep after it, which the coffee is planned around.
            val after = soon?.let(plan::sleepAfter)?.takeIf { bed == null && nap == null }
            /** " · 오늘은 쉴 틈이 …" for a short sleep, else until when coffee is fine. */
            fun sleepNote(s: SleepSchedule.Sleep): String {
                val cut = s.from.minusHours(SleepPlan.CAFFEINE_GAP_HOURS)
                return when {
                    s.short -> " · ${shortText(s)}"
                    now.isBefore(cut) -> " · 커피는 ${t.hm(cut)}까지"
                    else -> ""
                }
            }
            val detail = when {
                bed != null -> sleepLine(bed) + sleepNote(bed)
                nap != null -> "${t.hm(nap.from)} 낮잠 ${nap.minutes}분 · ${t.hm(nap.to)}엔 일어나요"
                next == null -> "다음 근무가 아직 없어요"
                soon == null -> "다음 근무 ${workLine(next)}"
                after != null -> "퇴근 뒤 ${sleepLine(after)}${sleepNote(after)}"
                else -> null
            }
            Pick(State.NEXT, soon?.let { workLine(it) } ?: "오늘 근무는 끝났어요", detail,
                line = bed?.let(::sleepLine) ?: nap?.let { "낮잠 ${t.span(it.from, it.to)}" } ?: after?.let(::sleepLine),
                saysShort = (bed ?: after)?.short == true)
        }

        val restSign = if (!restCheck) null else {
            val nextDate = next?.date?.takeIf { !it.isAfter(today.plusDays(1)) }
            RestCheck.check((-RestCheck.NIGHT_STREAK.toLong()..2L).map { resolver.resolve(today.plusDays(it)) }, strictRest)
                .filter { it.date == today || it.date == nextDate }
                .firstOrNull { !(pick.saysShort && (it.kind == RestCheck.Kind.SHORT_REST || it.kind == RestCheck.Kind.AFTER_NIGHT)) }
                ?.let { (if (it.date == today) "" else "${t.day(it.date)}· ") + it.text }
        }

        fun clip(from: LocalDateTime, to: LocalDateTime): Pair<LocalDateTime, LocalDateTime>? {
            val a = maxOf(from, now)
            val b = minOf(to, end)
            return if (b.isAfter(a)) a to b else null
        }
        val segments = buildList {
            for (w in work) clip(w.from, w.to)?.let { (a, b) -> add(Segment(Part.WORK, a, b, w.name, w.colorArgb)) }
            for (s in plan.sleeps) if (!slept(s.from, s.to)) clip(s.from, s.to)?.let { (a, b) -> add(Segment(Part.SLEEP, a, b, "잠")) }
            for (n in plan.naps) if (!slept(n.from, n.to)) clip(n.from, n.to)?.let { (a, b) -> add(Segment(Part.NAP, a, b, "낮잠")) }
            // Asleep outside the plan: the sleep still shows, up to the target or the next work.
            if (open != null && pick.state == State.SLEEPING && none { it.part == Part.SLEEP && !it.from.isAfter(now) }) {
                val until = listOfNotNull(open.start.plusMinutes(targetMinutes.toLong()), next?.from).min()
                clip(now, until)?.let { (a, b) -> add(Segment(Part.SLEEP, a, b, "잠")) }
            }
        }.sortedBy { it.from }

        val changesAt = buildList {
            work.forEach { add(it.from); add(it.to) }
            plan.sleeps.forEach { add(it.from.minusMinutes(PROMPT_MINUTES)); add(it.from); add(it.to); add(it.from.minusHours(SleepPlan.CAFFEINE_GAP_HOURS)) }
            plan.naps.forEach { add(it.from.minusMinutes(PROMPT_MINUTES)); add(it.to) }
            plan.alarms.forEach { add(it.triggerAt) }
            open?.let { add(it.start.plusMinutes(SleepDebt.MAX_SLEEP_MINUTES)) }
            woke?.end?.let { add(it.plusMinutes(AWAKE_MINUTES)) }
            add(today.plusDays(1).atStartOfDay())
        }.filter { it.isAfter(now) }.minOrNull() ?: end

        val caption = ongoing?.let { "지금 · ${it.name} ${t.span(it.from, it.to)}" }
            ?: work.filter { it.date == today }.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = "오늘 · ") { "${it.name} ${t.span(it.from, it.to)}" }
            ?: "오늘 · 쉬는 날"

        return Card(
            state = pick.state,
            caption = caption,
            headline = pick.headline,
            detail = pick.detail,
            rest = restSign,
            action = pick.action,
            actionDate = pick.actionDate,
            from = now,
            to = end,
            segments = segments,
            alarms = plan.alarms.map { it.triggerAt }.filter { it.isAfter(now) && !it.isAfter(end) }.distinct(),
            compact = pick.compact,
            line = pick.line,
            changesAt = changesAt,
        )
    }

    /** Times and days as the card says them. */
    private class Words(val today: LocalDate) {
        fun hm(x: LocalDateTime) = "%02d:%02d".format(x.hour, x.minute)
        fun span(a: LocalDateTime, b: LocalDateTime) = "${hm(a)}–${hm(b)}"
        /** "" for today, then "내일 ", "모레 ", "10/21(화) ". */
        fun day(d: LocalDate): String = when (d) {
            today -> ""
            today.minusDays(1) -> "어제 "
            today.plusDays(1) -> "내일 "
            today.plusDays(2) -> "모레 "
            else -> "${d.monthValue}/${d.dayOfMonth}(${DAYS[d.dayOfWeek.value - 1]}) "
        }
        fun dayOrToday(d: LocalDate) = if (d == today) "오늘 " else day(d)
        fun at(x: LocalDateTime) = day(x.toLocalDate()) + hm(x)
        fun hours(m: Long): String = when {
            m < 60 -> "${m}분"
            m % 60 == 0L -> "${m / 60}시간"
            else -> "${m / 60}시간 ${m % 60}분"
        }

        private companion object {
            val DAYS = listOf("월", "화", "수", "목", "금", "토", "일")
        }
    }
}
