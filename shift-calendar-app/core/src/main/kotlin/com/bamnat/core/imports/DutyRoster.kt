package com.bamnat.core.imports

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.model.ShiftOverride
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlin.math.abs

/*
 * [SHFT-06] A month's duty roster (듀티표) read from a photo on the phone, or from text pasted from
 * another app: a row of day numbers (1 2 3 … 31) and, under it, a row of codes for each person
 * (D E N O, 주 야 비 휴, or the calendar's own names). Codes are matched to the calendar's kinds of
 * work; what cannot be matched stays as "?" for the user to fix before anything is written.
 */

/** A word recognised in a photo with its box in pixels; text pasted by hand gets boxes by position. */
data class RosterWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** [ROST-03] Split off a name it was stuck to ("김민지D*"): its day is one to look at. */
    val doubt: Boolean = false,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

/** What one cell of a roster means on the calendar. */
sealed interface DutyCell {
    /** A kind of work (or a day-off kind such as 휴무) of the calendar. */
    data class Shift(val shiftTypeId: Long) : DutyCell
    /** A day off the calendar has no kind for. */
    data object Off : DutyCell
    /**
     * 연차·휴가. [ROST-05] With its kind and part of the day (상-3): 반차 is half a day of 연차, 생휴 is 무급, never a
     * whole day of 연차 for every leave word as before.
     */
    data class Leave(val kind: LeaveKind = LeaveKind.ANNUAL, val quarters: Int = 4, val fromStart: Boolean = false) : DutyCell {
        val leave: com.bamnat.core.model.Leave get() = com.bamnat.core.model.Leave(kind, quarters, fromStart && quarters < 4)
    }
    /**
     * Read, but matches nothing: the user picks. [ROST-06] [leave] is a leave word whose kind each company sets
     * (병가·경조·공가): the user is asked which kind once, and the calendar remembers the answer.
     */
    data class Unknown(val raw: String, val leave: Boolean = false) : DutyCell
}

/** One kind of day of the calendar, for matching the roster's codes. */
data class DutyType(
    val id: Long,
    val name: String,
    val label: String,
    val isOff: Boolean,
    val start: LocalTime? = null,
    val night: Boolean = false,
)

/**
 * One person's line of the roster: the name at its left (may be blank) and the cell of each day of the month.
 * [guessed] are the days read from a common code (주, N, 휴) rather than the calendar's own name or short name
 * of a kind of day, so they may be the wrong one of the user's kinds [NURS-04].
 */
data class RosterRow(
    val label: String,
    val cells: Map<Int, DutyCell>,
    val guessed: Set<Int> = emptySet(),
    /**
     * [ROST-03] What a cell said besides its code, by day: a mark after it ("D*" → "*"), the next code joined to it
     * ("N-O") or a time before it ("11D" → "11"). Kept as the day's memo so nothing written on the roster is lost.
     */
    val notes: Map<Int, String> = emptyMap(),
) {
    val unknown: Int get() = cells.values.count { it is DutyCell.Unknown }

    /**
     * [NURS-04] The days to look at before saving (순번 275): cells read but not matched, cells guessed from a
     * common code, and days missing between the first and last cell read (a cell the photo lost).
     */
    fun doubtful(lastDay: Int): Set<Int> {
        val days = cells.keys.filter { it <= lastDay }
        if (days.isEmpty()) return emptySet()
        val missing = (days.min()..days.max()).filter { it !in cells }
        return (cells.filter { it.value is DutyCell.Unknown }.keys + guessed + missing).filter { it <= lastDay }.toSortedSet()
    }
}

/**
 * [SHFT-06] The roster's codes, matched to the calendar's kinds: their own names and short names first,
 * then the codes hospitals and factories use (D/E/N/O, 데이·이브닝·나이트, 주·야·비·휴, 연차).
 */
class DutyCodes(
    private val types: List<DutyType>,
    /**
     * [ROST-06] The calendar's own code book: what the user said a code means once (병가 → 유급 휴가, M → 미들),
     * by [norm]ed code. It comes before the common codes, after the calendar's own names.
     */
    private val learned: Map<String, DutyCell> = emptyMap(),
) {
    private enum class Group(val words: Set<String>) {
        // [IMPORT-06] 경찰 (주·야·비·휴, 일근) and 소방 (당·비·휴) words, from no real roster yet (표본 없음, 확인 필요). A lone
        // "일" is left out: it is also 일요일 in a weekday row.
        DAY(setOf("D", "DAY", "DY", "데", "데이", "주", "주간", "낮", "일근", "주간근무", "상시", "상시근무")),
        EVENING(setOf("E", "EV", "EVE", "이", "이브", "이브닝", "오후", "저녁")),
        NIGHT(setOf("N", "NT", "NIGHT", "나", "나이트", "야", "야간", "밤", "야간근무")),
        // [NURS-03] S is a middle shift (09~18) on the rosters seen, not an evening.
        MID(setOf("M", "MD", "MID", "미", "미드", "S", "중", "중간")),
        DUTY(setOf("당", "당직", "당번", "당무", "당직근무", "24H")),
        OFF(setOf("O", "OF", "OFF", "0", "휴", "휴무", "휴일", "X", "-", "/", "비", "비번", "오프", "OH", "공", "공휴", "PH", "주휴", "비번일", "휴무일")),
    }

    private val exact: Map<String, Long> = buildMap {
        // Short names win over names, and earlier kinds over later ones.
        types.reversed().forEach { t -> norm(t.name).takeIf { it.isNotEmpty() }?.let { put(it, t.id) } }
        types.reversed().forEach { t -> norm(t.label).takeIf { it.isNotEmpty() }?.let { put(it, t.id) } }
    }

    private fun typeFor(g: Group): DutyCell? {
        types.firstOrNull { !it.isOff && (norm(it.name) in g.words || norm(it.label) in g.words) }?.let { return DutyCell.Shift(it.id) }
        val work = types.filter { !it.isOff && it.start != null }
        val pick = when (g) {
            Group.DAY -> work.filter { !it.night && it.start!!.hour in 5..10 }.minByOrNull { it.start!! }
            Group.EVENING -> work.filter { !it.night || it.start!!.hour in 12..17 }.filter { it.start!!.hour in 11..17 }.minByOrNull { it.start!! }
            Group.NIGHT -> work.filter { it.night && (it.start!!.hour >= 18 || it.start.hour < 3) }.minByOrNull { (it.start!!.hour + 12) % 24 }
            else -> null
        }
        if (pick != null) return DutyCell.Shift(pick.id)
        return when (g) {
            Group.OFF -> types.firstOrNull { it.isOff && (norm(it.name) in g.words || norm(it.label) in g.words) }
                ?.let { DutyCell.Shift(it.id) } ?: types.firstOrNull { it.isOff }?.let { DutyCell.Shift(it.id) } ?: DutyCell.Off
            else -> null
        }
    }

    private val aliases: Map<String, DutyCell?> = Group.entries.flatMap { g -> g.words.map { it to g } }
        .associate { (w, g) -> w to typeFor(g) } + LEAVE_WORDS

    /** What [token] means, or null when it is no code at all (a name, a number). */
    fun match(token: String): DutyCell? {
        val n = norm(token)
        if (n.isEmpty()) return null
        exact[n]?.let { return DutyCell.Shift(it) }
        learned[n]?.let { return it }
        if (aliases.containsKey(n)) return aliases[n] ?: DutyCell.Unknown(token.trim())
        return null
    }

    /**
     * [ROST-03] [token] as it stands on a real roster (상-1·2): a code with a mark after it ("D*", "E/", "주'"), two
     * codes joined ("N-O", "N/O", "N→O": the first is the day's), or a time with the code ("11D", "9E", "D11").
     * The code's cell and what else was written, or null when the token holds no code.
     */
    fun read(token: String): Pair<DutyCell, String?>? {
        match(token)?.let { return it to null }
        val t = fold(token).trim()
        MARKED.matchEntire(t)?.let { m -> match(m.groupValues[1])?.let { return it to m.groupValues[2] } }
        JOINED.matchEntire(t)?.let { m ->
            val first = match(m.groupValues[1])
            if (first != null && match(m.groupValues[2]) != null) return first to t
        }
        TIMED.matchEntire(t)?.let { m ->
            val code = m.groupValues[2].ifEmpty { m.groupValues[3] }
            val hour = m.groupValues[1].ifEmpty { m.groupValues[4] }
            if (code.any { it.isLetter() }) match(code)?.let { return it to hour }
        }
        return null
    }

    /**
     * [NURS-04] True when [cell] is a kind of work picked from a common code in [token] (주, N, 이브닝) rather than
     * the calendar's own name or short name: the right group, maybe not the user's own kind. Days off never are.
     */
    fun guessedWork(token: String, cell: DutyCell): Boolean =
        cell is DutyCell.Shift && types.firstOrNull { it.id == cell.shiftTypeId }?.isOff == false &&
            codeOf(token).let { !exact.containsKey(it) && !learned.containsKey(it) }

    /** [ROST-11] The code a token stands for, marks and times taken off: what the code book keeps an answer under. */
    fun codeOf(token: String): String {
        val t = fold(token).trim()
        MARKED.matchEntire(t)?.let { return norm(it.groupValues[1]) }
        JOINED.matchEntire(t)?.let { return norm(it.groupValues[1]) }
        TIMED.matchEntire(t)?.let { m -> return norm(m.groupValues[2].ifEmpty { m.groupValues[3] }) }
        return norm(t)
    }

    /** True for a token that stands in a code's place but matches nothing: short and not a number. */
    fun looksLikeCode(token: String): Boolean {
        val n = norm(token)
        if (n.isEmpty() || n.any { it.isDigit() } || n.none { it.isLetter() }) return false
        return n.length <= 2 || (n.length <= 4 && n.all { it in 'A'..'Z' })
    }

    /** True for letters A–Z only that stand for several codes ("DDEN"): never a person's name. */
    fun mergedLatin(token: String): Boolean = norm(token).let { n -> n.isNotEmpty() && n.all { it in 'A'..'Z' } } && split(token) != null

    /** [token] as single-letter codes ("DDEN" → D D E N), or null when it is not one. */
    fun split(token: String): List<String>? {
        val n = norm(token)
        // Letters only: "N/" is a code with a mark and "N-O" two codes joined, never N and a day off.
        if (n.length < 2 || match(token) != null || n in RANKS || !n.all { it.isLetter() }) return null
        val chars = n.map { it.toString() }
        return chars.takeIf { cs -> cs.all { match(it) != null } }
    }

    /**
     * [ROST-03][ROST-04] A name with something stuck to it (하): a job title ("김민지RN" → 김민지) or the first day's
     * code ("김민지D*" → 김민지 + D*). The name and the code token, or null when [token] is a name as it is.
     */
    fun splitName(token: String): Pair<String, String?>? {
        val t = fold(token).trim()
        val m = NAME_TAIL.matchEntire(t) ?: return null
        val name = m.groupValues[1]
        val tail = m.groupValues[2]
        if (norm(tail) in RANKS) return name to null
        if (tail.none { it in 'A'..'Z' || it in 'a'..'z' } && MARKS.none { it in tail }) return null
        return if (read(tail) != null) name to tail else null
    }

    companion object {
        private val STRIP = Regex("""[\s.,·:;|()\[\]{}'"`‘’“”]""")
        /** [ROST-04] Full-width letters and digits from a 한글 document (Ｄ, １) read as plain ones (중-7). */
        fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
        fun norm(s: String): String = fold(s).replace(STRIP, "").uppercase()

        /** [ROST-03] Marks written after a code on rosters: 교육 *, 반 근무 /, 표시 ', 추가 +, △ ○ ※. */
        private const val MARKS = "*/'+△▲○●※#!~`^"
        private val MARKED = Regex("""^([^*/'+△▲○●※#!~`^\s]+)([*/'+△▲○●※#!~`^]+)$""")
        private val JOINED = Regex("""^([^\s\-/→>]+)\s*(?:-|/|→|>|->)\s*([^\s\-/→>]+)$""")
        private val TIMED = Regex("""^(?:(\d{1,2})([A-Za-z가-힣]{1,3})|([A-Za-z가-힣]{1,3})(\d{1,2}))$""")
        private val NAME_TAIL = Regex("""^([가-힣]{2,4})([A-Za-z]{1,3}[*/'+△▲○●※#!~`^]*|[가-힣]{1,2}[*/'+△▲○●※#!~`^]+)$""")

        /** [ROST-04] Job titles written with a name: never part of it, never a code. */
        val RANKS = setOf("RN", "CN", "HN", "PN", "UM", "NA", "AN", "LPN", "수간호사", "책임", "책임간호사", "주임", "간호사", "조무사", "간호조무사", "선임", "팀장", "조장", "반장")

        /**
         * [ROST-05] Leave words (상-3). 연차 is a whole day; 반차 half a day of it (오전반 off the start); 반반차 a quarter.
         * 생리휴가 is 무급: 근로기준법 제73조 "사용자는 여성 근로자가 청구하면 월 1일의 생리휴가를 주어야 한다."
         * says nothing of pay (2004년 개정으로 유급 문구가 빠짐; 출처 위키문헌 근로기준법 조문, 현행 원문 대조 확인 필요 —
         * 계획/1.24-P-개발계획.md 조사). 병가·공가·경조·특별휴가 are set by each company's rules, not the law, so the
         * user is asked once ([DutyCell.Unknown.leave]).
         */
        private val LEAVE_WORDS: Map<String, DutyCell> = buildMap {
            listOf("연", "연차", "V", "VA", "VAC", "AL", "휴가", "년", "년차", "연가").forEach { put(it, DutyCell.Leave()) }
            listOf("반", "반차", "HD", "HALF", "반일").forEach { put(it, DutyCell.Leave(quarters = 2)) }
            listOf("오전반", "오전반차", "AM", "AM반", "전반").forEach { put(it, DutyCell.Leave(quarters = 2, fromStart = true)) }
            listOf("오후반", "오후반차", "PM", "PM반", "후반").forEach { put(it, DutyCell.Leave(quarters = 2)) }
            listOf("반반", "반반차", "QD").forEach { put(it, DutyCell.Leave(quarters = 1)) }
            listOf("생", "생휴", "생리", "생리휴가", "MS").forEach { put(it, DutyCell.Leave(LeaveKind.UNPAID)) }
            listOf("병", "병가", "SL", "공가", "경조", "경", "경조사", "특휴", "특별휴가", "특", "보", "보건", "보건휴가", "청원", "포상", "위로", "하계", "하휴", "교육휴가")
                .forEach { put(it, DutyCell.Unknown(it, leave = true)) }
        }
    }
}

object DutyRoster {
    // [NURS-03] "1", "1일", "01", and a day with its weekday in one cell: "1(월)". "1월" is a month, never a day.
    // [ROST-04] A date in the row of days too, as a spreadsheet copies it (중-7): "10/1", "10.1", "10월1일", "10-1".
    private val DAY_TOKEN = Regex("""^(?:(\d{1,2})\s*[/.\-월]\s*)?(\d{1,2})일?(?:\((?:월|화|수|목|금|토|일)\))?$""")
    private val WEEKDAYS = setOf("월", "화", "수", "목", "금", "토", "일", "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    /** Marks a code token split off a name in [readText] until it becomes a [RosterWord]. */
    private const val STUCK = "\u0000"
    /** Fewest days in a row of numbers for it to be the roster's row of days. */
    const val MIN_DAYS = 5
    /** Fewest cells for a line to be someone's duties. */
    const val MIN_CELLS = 3

    /**
     * [SHFT-06] Reads a roster from recognised words with their boxes: each person's line under the
     * nearest row of day numbers, each code under the day it sits beneath. Most cells first.
     */
    fun read(words: List<RosterWord>, codes: DutyCodes): List<RosterRow> {
        val rows = lines(words.filter { it.text.isNotBlank() }).map { line -> line.flatMapIndexed { i, w -> explode(w, codes, first = i == 0) } }
        val headers = rows.withIndex().filter { (_, r) -> isDayRow(r) }
        val read = if (headers.isEmpty()) emptyList() else gridRows(rows, headers.map { it.index }, codes)
        return (merge(read) + listOfNotNull(listRow(rows, codes))).filter { it.cells.size >= MIN_CELLS }
            .sortedWith(compareByDescending<RosterRow> { it.cells.size - it.unknown }.thenBy { it.unknown })
            .distinctBy { it.label to it.cells }
    }

    /**
     * [SHFT-06] Reads text pasted by hand (from Google Lens or a messenger): one line of the table per line,
     * cells apart by spaces or tabs. A person's name at the start of a line is kept as the line's name.
     */
    fun readText(text: String, codes: DutyCodes): List<RosterRow> {
        val words = mutableListOf<RosterWord>()
        text.lines().forEachIndexed { i, line ->
            if ('\t' in line) {
                // Copied from a spreadsheet: one cell per tab, an empty cell keeping its day's place, so each
                // cell sits under its own column (a name's columns to the left of the days).
                line.trimEnd().split('\t').forEachIndexed { j, cell ->
                    if (cell.isNotBlank()) words += RosterWord(cell.trim(), j * 100, i * 100, j * 100 + 60, i * 100 + 40)
                }
                return@forEachIndexed
            }
            val raw = line.trim().split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
                // [ROST-03] A name with the first day's code stuck to it ("김민지D*") is the name and that code.
                .flatMapIndexed { j, t -> if (j > 1) listOf(t) else codes.splitName(t)?.let { (n, c) -> listOfNotNull(n, c?.let { STUCK + it }) } ?: listOf(t) }
                // [ROST-04] A job title beside the name (김민지 RN) is neither.
                .filter { DutyCodes.norm(it) !in DutyCodes.RANKS }
            // The name first, as written: a two-letter name made of code letters (주연, 이나, 연주) is not codes.
            val lead = raw.takeWhile { t -> !t.startsWith(STUCK) && dayOf(t) == null && codes.read(t) == null && DutyCodes.norm(t) !in WEEKDAYS && !codes.mergedLatin(t) }
            // Days and codes are placed by their order, the name before them to the left of the first column.
            val tokens = raw.drop(lead.size).flatMap { t -> if (t.startsWith(STUCK)) listOf(t) else codes.split(t) ?: listOf(t) }
            lead.forEachIndexed { j, t -> words += RosterWord(t, -1000 + j * 10, i * 100, -1000 + j * 10 + 8, i * 100 + 40) }
            tokens.forEachIndexed { j, t ->
                words += RosterWord(t.removePrefix(STUCK), j * 100, i * 100, j * 100 + 60, i * 100 + 40, doubt = t.startsWith(STUCK))
            }
        }
        return read(words, codes)
    }

    /** What each day of [row] does to that day of the calendar, for the days the month has. */
    fun apply(entry: DayEntry, cell: DutyCell): DayEntry = when (cell) {
        is DutyCell.Shift -> entry.copy(override = ShiftOverride.Shift(cell.shiftTypeId), leave = null)
        DutyCell.Off -> entry.copy(override = ShiftOverride.Off, leave = null)
        // [ROST-05] 반차 keeps the day's work (half of it is taken off); a whole day's leave keeps it too, as before.
        is DutyCell.Leave -> entry.copy(leave = cell.leave)
        is DutyCell.Unknown -> entry
    }

    /**
     * [ROST-02] What [cells] would overwrite on a calendar whose days are [entries]: days with leave, or with a
     * kind of work the user changed by hand, that the roster puts something else on (하 '덮어쓰기 안내').
     */
    fun overwrites(cells: Map<LocalDate, DutyCell>, entries: Map<LocalDate, DayEntry>): Set<LocalDate> =
        cells.filter { (d, c) -> c !is DutyCell.Unknown }.keys.filter { d ->
            val e = entries[d] ?: return@filter false
            val after = apply(e, cells.getValue(d))
            (e.leave != null && after.leave != e.leave) || (e.override != ShiftOverride.None && after.override != e.override)
        }.toSet()

    /**
     * [ROST-04] The month a pasted roster names (중-6): "2026년 10월", "2026-10", "2026.10", or "10월 근무표" / "10월
     * 듀티" (the year nearest [near]), or the month of the dates in its row of days ("10/1 10/2 …"). Null when it names none.
     */
    fun monthIn(text: String, near: LocalDate): YearMonth? {
        val t = DutyCodes.fold(text)
        Regex("""(20\d{2})\s*[년.\-/]\s*(\d{1,2})\s*월?""").find(t)?.let { m ->
            runCatching { YearMonth.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull()?.let { return it }
        }
        val month = Regex("""(?<!\d)(\d{1,2})\s*월\s*(?:근무|듀티|근무표|DUTY|스케줄|일정|당직)""", RegexOption.IGNORE_CASE).find(t)?.groupValues?.get(1)?.toInt()
            ?: t.lines().firstNotNullOfOrNull { line ->
                val months = line.split(Regex("""\s+""")).mapNotNull { DAY_TOKEN.matchEntire(it.trim())?.groupValues?.get(1)?.toIntOrNull() }
                months.takeIf { it.size >= MIN_DAYS }?.groupingBy { it }?.eachCount()?.maxByOrNull { it.value }?.key
            }
            ?: return null
        if (month !in 1..12) return null
        // The year that puts the month closest to [near]: a December roster read in January is last year's.
        return listOf(near.year - 1, near.year, near.year + 1).map { YearMonth.of(it, month) }
            .minBy { abs(it.atDay(1).toEpochDay() - near.toEpochDay()) }
    }

    /** [ROST-04] Days [row] has that [month] does not (31일 on a 30-day month): said before they are left out. */
    fun beyond(row: RosterRow, month: YearMonth): List<Int> = row.cells.keys.filter { it > month.lengthOfMonth() }.sorted()

    private fun dayOf(token: String): Int? =
        DAY_TOKEN.matchEntire(DutyCodes.fold(token).trim().trimEnd('.', ','))?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it in 1..31 }

    /** Words on the same height make one line, left to right. */
    private fun lines(words: List<RosterWord>): List<List<RosterWord>> {
        val rows = mutableListOf<MutableList<RosterWord>>()
        for (w in words.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            if (row != null && abs(w.centerY - row.map { it.centerY }.average()) <= minOf(w.height, row.minOf { it.height }) / 2.0) row += w
            else rows += mutableListOf(w)
        }
        return rows.map { r -> r.sortedBy { it.left } }
    }

    /**
     * A word that holds several cells ("1 2 3", "DDEN") becomes one word per cell across its box. The
     * [first] word of a line may be a name, so there only letters A–Z are taken apart (주연 stays a name).
     */
    private fun explode(w: RosterWord, codes: DutyCodes, first: Boolean = false): List<RosterWord> {
        val parts = w.text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            .flatMap { p -> (if (first && !codes.mergedLatin(p)) null else codes.split(p)) ?: listOf(p) }
        if (parts.size <= 1) return listOf(w)
        val width = (w.right - w.left).coerceAtLeast(parts.size)
        val step = width / parts.size
        return parts.mapIndexed { i, p -> RosterWord(p, w.left + i * step, w.top, w.left + (i + 1) * step, w.bottom) }
    }

    private fun isDayRow(row: List<RosterWord>): Boolean {
        val days = row.mapNotNull { dayOf(it.text) }
        if (days.size < MIN_DAYS || days.size * 10 < row.size * 7) return false
        val steps = days.zipWithNext().count { (a, b) -> b == a + 1 }
        return steps * 10 >= (days.size - 1) * 7
    }

    private fun isWeekdayRow(row: List<RosterWord>): Boolean =
        row.size >= MIN_DAYS && row.count { DutyCodes.norm(it.text) in WEEKDAYS } * 10 >= row.size * 7

    private fun gridRows(rows: List<List<RosterWord>>, headers: List<Int>, codes: DutyCodes): List<RosterRow> {
        val out = mutableListOf<RosterRow>()
        rows.forEachIndexed { i, row ->
            if (i in headers || isWeekdayRow(row)) return@forEachIndexed
            // The row of days this line belongs under: the nearest above, or below for a table drawn upside down.
            val h = headers.lastOrNull { it < i } ?: headers.first()
            val columns = monthColumns(rows[h].mapNotNull { w -> dayOf(w.text)?.let { it to w.centerX } })
            val spacing = columns.zipWithNext().map { (a, b) -> abs(b.second - a.second) }.sorted().let { it.getOrNull(it.size / 2) } ?: return@forEachIndexed
            val firstX = columns.minOf { it.second }
            val cells = LinkedHashMap<Int, DutyCell>()
            val guessed = HashSet<Int>()
            val notes = HashMap<Int, String>()
            val label = StringBuilder()
            for (w in row) {
                val near = columns.minByOrNull { abs(it.second - w.centerX) } ?: continue
                val leftOfDays = w.centerX < firstX - spacing / 2
                val aligned = !leftOfDays && abs(near.second - w.centerX) * 10 <= spacing * 6
                // [ROST-03] A name with a code stuck to it, in a photo's one word: doubtful, so the day is looked at.
                val stuck = if (leftOfDays) codes.splitName(w.text) else null
                if (stuck != null) {
                    label.append(stuck.first)
                    val first = columns.minBy { it.second }.first
                    val code = stuck.second?.let(codes::read)
                    if (code != null && cells.putIfAbsent(first, code.first) == null) {
                        guessed += first
                        code.second?.let { notes[first] = it }
                    }
                    continue
                }
                val read = codes.read(w.text)
                val cell = read?.first ?: if (codes.looksLikeCode(w.text) && aligned) DutyCell.Unknown(w.text.trim()) else null
                when {
                    cell != null && aligned -> if (cells.putIfAbsent(near.first, cell) == null) {
                        if (w.doubt || codes.guessedWork(w.text, cell)) guessed += near.first
                        read?.second?.let { notes[near.first] = it }
                    }
                    leftOfDays && dayOf(w.text) == null && DutyCodes.norm(w.text) !in DutyCodes.RANKS -> label.append(w.text.trim())
                }
            }
            if (cells.size >= MIN_CELLS) out += RosterRow(label.toString(), cells.toSortedMap(), guessed, notes)
        }
        return out
    }

    /**
     * [NURS-03] A roster that starts in the month before (…29 30 31 1 2 3…) or runs into the next: only the
     * longer run of days is one month, so the other side's numbers never land on this month's same dates.
     */
    internal fun monthColumns(cols: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val sorted = cols.sortedBy { it.second }
        val wrap = sorted.zipWithNext().indexOfFirst { (a, b) -> b.first < a.first - 20 }
        if (wrap < 0) return cols
        val before = sorted.subList(0, wrap + 1)
        val after = sorted.subList(wrap + 1, sorted.size)
        return if (after.size >= before.size) after else before
    }

    /** Lines of the same person under the 1–15 and 16–31 rows of days become one. */
    private fun merge(rows: List<RosterRow>): List<RosterRow> {
        val named = rows.filter { it.label.isNotBlank() }.groupBy { it.label }.map { (label, parts) ->
            RosterRow(label, parts.fold(sortedMapOf<Int, DutyCell>()) { acc, r -> acc.apply { r.cells.forEach { (d, c) -> putIfAbsent(d, c) } } },
                parts.flatMap { it.guessed }.toSet(), parts.reversed().fold(emptyMap()) { acc, r -> acc + r.notes })
        }
        val blank = rows.filter { it.label.isBlank() }
        // Two blank lines whose days do not overlap are one person's two halves.
        val joined = if (blank.size == 2 && blank[0].cells.keys.intersect(blank[1].cells.keys).isEmpty()) {
            listOf(RosterRow("", (blank[0].cells + blank[1].cells).toSortedMap(), blank[0].guessed + blank[1].guessed, blank[0].notes + blank[1].notes))
        } else blank
        return named + joined
    }

    /** A roster written as a list, one day per line: "1 D", "10/3 N", "3일 주간". */
    private fun listRow(rows: List<List<RosterWord>>, codes: DutyCodes): RosterRow? {
        val cells = sortedMapOf<Int, DutyCell>()
        val guessed = HashSet<Int>()
        val notes = HashMap<Int, String>()
        val dated = Regex("""^(?:\d{1,2}[/.월-])?(\d{1,2})일?(?:\([^)]*\))?$""")
        for (row in rows) {
            if (row.size < 2 || row.size > 4) continue
            val day = dated.matchEntire(row[0].text.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 } ?: continue
            val rest = row.drop(1).filter { DutyCodes.norm(it.text) !in WEEKDAYS && !it.text.trim().matches(Regex("""\([^)]*\)""")) }
            val word = rest.firstOrNull { codes.read(it.text) != null } ?: continue
            val (cell, note) = codes.read(word.text)!!
            if (cells.putIfAbsent(day, cell) == null) {
                if (codes.guessedWork(word.text, cell)) guessed += day
                note?.let { notes[day] = it }
            }
        }
        return RosterRow("", cells, guessed, notes).takeIf { cells.size >= MIN_CELLS }
    }
}
