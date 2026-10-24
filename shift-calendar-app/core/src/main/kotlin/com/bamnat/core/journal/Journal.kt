package com.bamnat.core.journal

import com.bamnat.core.guide.FeatureRoute
import com.bamnat.core.guide.Place
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * [OBS-01] The things the app does by itself, each written down in one line of '밤낮이 한 일'. [feature] is the
 * [com.bamnat.core.guide.FeatureRegistry] id it belongs to, [route] where the line leads ("자세히"·"되돌리기"),
 * [why] the reason every line of the kind shares. The ten places that write them are listed in
 * research/1.23-N-설계.md 5장.
 */
enum class JournalKind(val feature: String, val why: String, val route: FeatureRoute?) {
    ALARMS_PLANNED("alarm.check", "근무표나 알람 설정이 바뀌었어요", FeatureRoute(Place.DIAGNOSTICS)),
    REMOTE_FILE("pay.calc", "계산 자료를 하루 한 번 확인해요", null),
    HOLIDAYS("calendar.year", "새 공휴일표가 왔어요", FeatureRoute(Place.YEAR)),
    PAY_RECALC("pay.calc", "요율·세액표가 새 판으로 바뀌었어요", FeatureRoute(Place.PAY)),
    BACKUP("data.autoBackup", "매일 자동 백업이 켜져 있어요", FeatureRoute(Place.SETTINGS_DATA, "매일 자동 백업")),
    BACKUP_FAILED("data.autoBackup", "백업할 파일에 쓰지 못했어요", FeatureRoute(Place.SETTINGS_DATA, "매일 자동 백업")),
    WIDGETS("widget.lines", "날이 바뀌었거나 근무가 바뀌었어요", null),
    OUTBOX("help.inquiry", "연결이 돌아와 기다리던 것을 보냈어요", FeatureRoute(Place.INQUIRY)),
    LEAVE_NOTICE("leave.promotion", "근로기준법 제61조의 연차 사용 촉진 날이에요", FeatureRoute(Place.LEAVE)),
    MONEY_CAPTURED("money.auto", "결제 알림 읽기가 켜져 있어요", FeatureRoute(Place.MONEY)),
    NEWS("plaza.news", "밤낮 소식은 하루 한 번 묶어서 와요", FeatureRoute(Place.SETTINGS_NOTICES, "밤낮 소식")),
    /** [ROST-11] A month's roster read and put on the calendar; detail "month days source" ([rosterDetail]). */
    ROSTER("shift.roster", "한 달 근무 넣기로 넣은 날은 되돌릴 수 있어요", FeatureRoute(Place.DUTY_ROSTER)),
}

/** [ROST-11] Where a month's roster came from, as the journal's detail keeps it (a digit, never the roster). */
enum class RosterFrom(val code: Int, val word: String) {
    PHOTO(1, "사진"), FILE(2, "파일"), TEXT(3, "붙여 넣은 글"), TYPED(4, "직접");

    companion object {
        fun of(code: Int?): RosterFrom? = entries.firstOrNull { it.code == code }
    }
}

/** [ROST-11] "10 31 1": October, 31 days, from a photo. */
fun rosterDetail(month: Int, days: Int, from: RosterFrom): String = "$month $days ${from.code}"

/**
 * [OBS-01] One line: when, what kind, and a few small numbers ([count], [detail]) — never an amount, a name or
 * a memo's words, so the log says what happened without holding anyone's record. Lines of one kind on one day
 * are one line whose [count] goes up ([Journal.add]).
 */
data class JournalEntry(val at: Instant, val kind: JournalKind, val count: Int = 1, val detail: String? = null) {
    fun day(zone: ZoneId): LocalDate = at.atZone(zone).toLocalDate()
}

/** [OBS-01] The rules of the log: one line per kind a day, [KEEP_DAYS] days, at most [PER_DAY] lines a day. */
object Journal {
    const val KEEP_DAYS = 90L
    const val PER_DAY = 300
    /** A detail is a time, a version or a count ("06:30", "2026102301", "3"); anything longer is cut. */
    const val MAX_DETAIL = 24

    /**
     * [entries] with [entry] added: on a day that has a line of its kind already, that line takes the newer
     * time and detail and counts one more. Old days go, and a day past [PER_DAY] keeps its newest.
     */
    fun add(entries: List<JournalEntry>, entry: JournalEntry, zone: ZoneId, today: LocalDate = entry.day(zone)): List<JournalEntry> {
        val clean = entry.copy(detail = entry.detail?.let(::safeDetail))
        val day = clean.day(zone)
        val i = entries.indexOfLast { it.kind == clean.kind && it.day(zone) == day }
        val next = if (i >= 0) {
            entries.toMutableList().apply { this[i] = clean.copy(count = entries[i].count + clean.count) }
        } else entries + clean
        return trim(next, zone, today)
    }

    fun trim(entries: List<JournalEntry>, zone: ZoneId, today: LocalDate): List<JournalEntry> {
        val from = today.minusDays(KEEP_DAYS - 1)
        return entries.filter { !it.day(zone).isBefore(from) }
            .groupBy { it.day(zone) }
            .flatMap { (_, list) -> list.sortedBy { it.at }.takeLast(PER_DAY) }
            .sortedBy { it.at }
    }

    /**
     * Only digits, times, dots and a few letters stay: a detail never carries a name or an amount with 원.
     * "06:30", "2026102301", "3일치" pass; "홍길동", "12,000원" do not.
     */
    fun safeDetail(text: String): String? {
        val t = text.trim().take(MAX_DETAIL)
        if (t.isEmpty() || t.contains('원') || t.contains(',')) return null
        return t.takeIf { s -> s.all { it.isDigit() || it in ":.-/ " || it in ALLOWED } }
    }

    private const val ALLOWED = "일치개번판시분주달회째"

    /** The line as '밤낮이 한 일' shows it: what, then why. */
    fun text(e: JournalEntry): String {
        val n = e.count
        val d = e.detail
        val what = when (e.kind) {
            JournalKind.ALARMS_PLANNED -> if (d != null) "다음 알람 $d · 알람 다시 잡음" else "알람 다시 잡음"
            JournalKind.REMOTE_FILE -> if (d != null) "계산 자료 새 판 받음 ($d)" else "계산 자료 새 판 받음"
            JournalKind.HOLIDAYS -> "공휴일표 새 판 적용 · 달력과 급여에 반영"
            JournalKind.PAY_RECALC -> "예상 급여·실수령 다시 계산"
            JournalKind.BACKUP -> "백업 저장"
            JournalKind.BACKUP_FAILED -> if (d != null) "백업 못 함 ($d)" else "백업 못 함"
            JournalKind.WIDGETS -> "위젯·워치 오늘 근무 고침"
            JournalKind.OUTBOX -> "기다리던 것 보냄"
            JournalKind.LEAVE_NOTICE -> "연차 사용 촉진 알림"
            JournalKind.MONEY_CAPTURED -> "결제 알림을 돈 기록으로 적음"
            JournalKind.NEWS -> "밤낮 소식 받음"
            JournalKind.ROSTER -> d?.split(' ')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 }
                ?.let { (m, days, src) -> "${m}월 근무 ${days}일 넣음" + (RosterFrom.of(src)?.let { " (${it.word})" } ?: "") }
                ?: "한 달 근무 넣음"
        }
        return if (n > 1) "$what · ${n}번" else what
    }

    /**
     * What happens every day the app is opened (the next alarm planned again, widgets redrawn): listed, but not
     * worth the home line on its own.
     */
    val ROUTINE = setOf(JournalKind.ALARMS_PLANNED, JournalKind.WIDGETS)

    /**
     * [OBS-02] "오늘 밤낮이 한 일 3개": the home line, by kinds rather than repeats; none on a day of nothing
     * but [ROUTINE].
     */
    fun todayLine(entries: List<JournalEntry>, zone: ZoneId, today: LocalDate): String? {
        val kinds = entries.filter { it.day(zone) == today }.map { it.kind }.distinct()
        if (kinds.none { it !in ROUTINE }) return null
        return "오늘 밤낮이 한 일 ${kinds.size}개"
    }

    /** [OBS-02] 설정 › 밤낮이 한 일: the days newest first, each day's lines newest first. */
    fun byDay(entries: List<JournalEntry>, zone: ZoneId): List<Pair<LocalDate, List<JournalEntry>>> =
        entries.groupBy { it.day(zone) }.toSortedMap(compareByDescending { it }).map { (d, list) -> d to list.sortedByDescending { it.at } }
}
