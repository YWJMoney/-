package com.bamnat.core.imports

import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.ShiftOverride
import java.time.LocalTime
import kotlin.math.abs

/*
 * [SHFT-06] A month's duty roster (듀티표) read from a photo on the phone, or from text pasted from
 * another app: a row of day numbers (1 2 3 … 31) and, under it, a row of codes for each person
 * (D E N O, 주 야 비 휴, or the calendar's own names). Codes are matched to the calendar's kinds of
 * work; what cannot be matched stays as "?" for the user to fix before anything is written.
 */

/** A word recognised in a photo with its box in pixels; text pasted by hand gets boxes by position. */
data class RosterWord(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
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
    /** 연차·휴가. */
    data object Leave : DutyCell
    /** Read, but matches nothing: the user picks. */
    data class Unknown(val raw: String) : DutyCell
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

/** One person's line of the roster: the name at its left (may be blank) and the cell of each day of the month. */
data class RosterRow(val label: String, val cells: Map<Int, DutyCell>) {
    val unknown: Int get() = cells.values.count { it is DutyCell.Unknown }
}

/**
 * [SHFT-06] The roster's codes, matched to the calendar's kinds: their own names and short names first,
 * then the codes hospitals and factories use (D/E/N/O, 데이·이브닝·나이트, 주·야·비·휴, 연차).
 */
class DutyCodes(private val types: List<DutyType>) {
    private enum class Group(val words: Set<String>) {
        DAY(setOf("D", "DAY", "DY", "데", "데이", "주", "주간", "낮")),
        EVENING(setOf("E", "EV", "EVE", "이", "이브", "이브닝", "오후", "저녁", "S")),
        NIGHT(setOf("N", "NT", "NIGHT", "나", "나이트", "야", "야간", "밤")),
        MID(setOf("M", "MD", "MID", "미", "미드")),
        DUTY(setOf("당", "당직", "당번")),
        OFF(setOf("O", "OF", "OFF", "0", "휴", "휴무", "휴일", "X", "-", "/", "비", "비번", "오프", "OH", "공", "공휴")),
        LEAVE(setOf("연", "연차", "V", "VA", "VAC", "AL", "휴가", "년", "년차")),
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
            Group.LEAVE -> DutyCell.Leave
            else -> null
        }
    }

    private val aliases: Map<String, DutyCell?> = Group.entries.flatMap { g -> g.words.map { it to g } }
        .associate { (w, g) -> w to typeFor(g) }

    /** What [token] means, or null when it is no code at all (a name, a number). */
    fun match(token: String): DutyCell? {
        val n = norm(token)
        if (n.isEmpty()) return null
        exact[n]?.let { return DutyCell.Shift(it) }
        if (aliases.containsKey(n)) return aliases[n] ?: DutyCell.Unknown(token.trim())
        return null
    }

    /** True for a token that stands in a code's place but matches nothing: short and not a number. */
    fun looksLikeCode(token: String): Boolean {
        val n = norm(token)
        if (n.isEmpty() || n.any { it.isDigit() } || n.none { it.isLetter() }) return false
        return n.length <= 2 || (n.length <= 4 && n.all { it in 'A'..'Z' })
    }

    /** [token] as single-letter codes ("DDEN" → D D E N), or null when it is not one. */
    fun split(token: String): List<String>? {
        val n = norm(token)
        if (n.length < 2 || match(token) != null) return null
        val chars = n.map { it.toString() }
        return chars.takeIf { cs -> cs.all { match(it) != null } }
    }

    companion object {
        private val STRIP = Regex("""[\s.,·:;|()\[\]{}'"`‘’“”]""")
        fun norm(s: String): String = s.replace(STRIP, "").uppercase()
    }
}

object DutyRoster {
    private val DAY_TOKEN = Regex("""^(\d{1,2})일?$""")
    private val WEEKDAYS = setOf("월", "화", "수", "목", "금", "토", "일", "MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
    /** Fewest days in a row of numbers for it to be the roster's row of days. */
    const val MIN_DAYS = 5
    /** Fewest cells for a line to be someone's duties. */
    const val MIN_CELLS = 3

    /**
     * [SHFT-06] Reads a roster from recognised words with their boxes: each person's line under the
     * nearest row of day numbers, each code under the day it sits beneath. Most cells first.
     */
    fun read(words: List<RosterWord>, codes: DutyCodes): List<RosterRow> {
        val rows = lines(words.filter { it.text.isNotBlank() }).map { line -> line.flatMap { w -> explode(w, codes) } }
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
            val tokens = line.trim().split(Regex("""[\s,]+""")).filter { it.isNotBlank() }
                .flatMap { t -> codes.split(t) ?: listOf(t) }
            // Days and codes are placed by their order, the name before them to the left of the first column.
            val lead = tokens.takeWhile { t -> dayOf(t) == null && codes.match(t) == null && DutyCodes.norm(t) !in WEEKDAYS }
            lead.forEachIndexed { j, t -> words += RosterWord(t, -1000 + j * 10, i * 100, -1000 + j * 10 + 8, i * 100 + 40) }
            tokens.drop(lead.size).forEachIndexed { j, t -> words += RosterWord(t, j * 100, i * 100, j * 100 + 60, i * 100 + 40) }
        }
        return read(words, codes)
    }

    /** What each day of [row] does to that day of the calendar, for the days the month has. */
    fun apply(entry: DayEntry, cell: DutyCell): DayEntry = when (cell) {
        is DutyCell.Shift -> entry.copy(override = ShiftOverride.Shift(cell.shiftTypeId), leave = null)
        DutyCell.Off -> entry.copy(override = ShiftOverride.Off, leave = null)
        DutyCell.Leave -> entry.copy(leave = Leave())
        is DutyCell.Unknown -> entry
    }

    private fun dayOf(token: String): Int? =
        DAY_TOKEN.matchEntire(token.trim().trimEnd('.', ','))?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 }

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

    /** A word that holds several cells ("1 2 3", "DDEN") becomes one word per cell across its box. */
    private fun explode(w: RosterWord, codes: DutyCodes): List<RosterWord> {
        val parts = w.text.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.flatMap { p -> codes.split(p) ?: listOf(p) }
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
            val columns = rows[h].mapNotNull { w -> dayOf(w.text)?.let { it to w.centerX } }
            val spacing = columns.zipWithNext().map { (a, b) -> abs(b.second - a.second) }.sorted().let { it.getOrNull(it.size / 2) } ?: return@forEachIndexed
            val firstX = columns.minOf { it.second }
            val cells = LinkedHashMap<Int, DutyCell>()
            val label = StringBuilder()
            for (w in row) {
                val near = columns.minByOrNull { abs(it.second - w.centerX) } ?: continue
                val leftOfDays = w.centerX < firstX - spacing / 2
                val aligned = !leftOfDays && abs(near.second - w.centerX) * 10 <= spacing * 6
                val cell = codes.match(w.text) ?: if (codes.looksLikeCode(w.text) && aligned) DutyCell.Unknown(w.text.trim()) else null
                when {
                    cell != null && aligned -> cells.putIfAbsent(near.first, cell)
                    leftOfDays && dayOf(w.text) == null -> label.append(w.text.trim())
                }
            }
            if (cells.size >= MIN_CELLS) out += RosterRow(label.toString(), cells.toSortedMap())
        }
        return out
    }

    /** Lines of the same person under the 1–15 and 16–31 rows of days become one. */
    private fun merge(rows: List<RosterRow>): List<RosterRow> {
        val named = rows.filter { it.label.isNotBlank() }.groupBy { it.label }.map { (label, parts) ->
            RosterRow(label, parts.fold(sortedMapOf<Int, DutyCell>()) { acc, r -> acc.apply { r.cells.forEach { (d, c) -> putIfAbsent(d, c) } } })
        }
        val blank = rows.filter { it.label.isBlank() }
        // Two blank lines whose days do not overlap are one person's two halves.
        val joined = if (blank.size == 2 && blank[0].cells.keys.intersect(blank[1].cells.keys).isEmpty()) {
            listOf(RosterRow("", (blank[0].cells + blank[1].cells).toSortedMap()))
        } else blank
        return named + joined
    }

    /** A roster written as a list, one day per line: "1 D", "10/3 N", "3일 주간". */
    private fun listRow(rows: List<List<RosterWord>>, codes: DutyCodes): RosterRow? {
        val cells = sortedMapOf<Int, DutyCell>()
        val dated = Regex("""^(?:\d{1,2}[/.월-])?(\d{1,2})일?(?:\([^)]*\))?$""")
        for (row in rows) {
            if (row.size < 2 || row.size > 4) continue
            val day = dated.matchEntire(row[0].text.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..31 } ?: continue
            val rest = row.drop(1).filter { DutyCodes.norm(it.text) !in WEEKDAYS && !it.text.trim().matches(Regex("""\([^)]*\)""")) }
            val cell = rest.firstNotNullOfOrNull { codes.match(it.text) } ?: continue
            cells.putIfAbsent(day, cell)
        }
        return RosterRow("", cells).takeIf { cells.size >= MIN_CELLS }
    }
}
