package com.bamnat.core.imports

import java.time.LocalDate
import java.time.YearMonth
import com.bamnat.core.time.Today

/*
 * [IMPORT-03] 불규칙 근무표 CSV·엑셀: wards and convenience stores hand out a roster that follows no cycle, as a
 * spreadsheet. Two shapes are read, from the rows [Sheets.read] gives:
 * - 세로형 (편의점·알바 앱 내보내기): a 날짜 column and a 근무 column, one day a row, maybe a 이름 column.
 * - 가로형 (병동 듀티표): a row of the month's days (1 2 3 … 31 or dates) and one row per person under it, the
 *   person's name on the left.
 * Codes are matched to the calendar's kinds of work with [DutyCodes]; a code that matches nothing stays "?" for the
 * user to pick before anything is written. The file never leaves the phone.
 */

/** One day of a roster as read: the code written and what it means on the calendar. */
data class RosterDay(val date: LocalDate, val raw: String, val cell: DutyCell, val note: String? = null)

/** One person's days. [name] is blank when the file has one person only. */
data class RosterPerson(val name: String, val days: List<RosterDay>) {
    val unknown: List<String> get() = days.mapNotNull { (it.cell as? DutyCell.Unknown)?.raw }.distinct()
}

enum class RosterLayout(val label: String) { LONG("세로형 (날짜·근무 열)"), WIDE("가로형 (날짜 줄 아래 사람마다 한 줄)") }

data class RosterRead(val layout: RosterLayout, val people: List<RosterPerson>, val month: YearMonth? = null)

object RosterSheet {
    private val DATE_HEADS = listOf("날짜", "일자", "근무일", "DATE", "DAY")
    private val CODE_HEADS = listOf("근무", "듀티", "코드", "근무유형", "근무형태", "구분", "DUTY", "SHIFT", "CODE")
    private val NAME_HEADS = listOf("이름", "성명", "직원", "사원", "NAME")
    private val MONTH = Regex("""(\d{4})\s*[년.\-/]\s*(\d{1,2})\s*월?""")
    private val DAY = Regex("""^(\d{1,2})\s*일?(?:\s*\(?[월화수목금토일]\)?)?$""")

    /**
     * [IMPORT-03] Reads [rows] either way; [month] is the month a 가로형 roster with only day numbers is for
     * (from the file when it says, e.g. "2026년 11월"), [near] picks the year of dates written without one.
     */
    fun read(rows: List<List<String>>, codes: DutyCodes, month: YearMonth? = null, near: LocalDate = Today.date()): RosterRead? =
        readWide(rows, codes, month, near) ?: readLong(rows, codes, near)

    /** The month a file names in its first rows, e.g. "2026년 11월 근무표". */
    fun monthIn(rows: List<List<String>>): YearMonth? = rows.take(6).flatten().firstNotNullOfOrNull { c ->
        MONTH.find(c)?.let { m -> runCatching { YearMonth.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }.getOrNull() }
    }

    private fun readWide(rows: List<List<String>>, codes: DutyCodes, month: YearMonth?, near: LocalDate): RosterRead? {
        val ym = month ?: monthIn(rows) ?: YearMonth.from(near)
        // The row with the most day numbers in a row, at least five.
        fun dayOf(cell: String): LocalDate? {
            val t = cell.trim()
            if (t.isEmpty()) return null
            DAY.matchEntire(t)?.let { m -> return m.groupValues[1].toInt().takeIf { it in 1..ym.lengthOfMonth() }?.let(ym::atDay) }
            // A full date in the header ("11/3", "2026-11-03") but not a lone month name.
            return if (t.any { it == '/' || it == '-' || it == '.' } || t.length >= 8) AttendanceImport.parseDate(t, ym.year, near) else null
        }
        val header = rows.withIndex().map { (i, r) -> i to r.mapIndexedNotNull { c, cell -> dayOf(cell)?.let { c to it } } }
            .filter { it.second.size >= DutyRoster.MIN_DAYS }
            .maxByOrNull { it.second.size } ?: return null
        val columns = header.second.toMap()
        val firstDayCol = columns.keys.min()
        val people = rows.drop(header.first + 1).mapNotNull { r ->
            // [ROST-04] A job title in its own column or after the name (김민지RN) is not part of the name.
            val name = r.take(firstDayCol).map { it.trim() }.filter { it.isNotEmpty() && it.none(Char::isDigit) && DutyCodes.norm(it) !in DutyCodes.RANKS }
                .map { n -> codes.splitName(n)?.first ?: n }.joinToString(" ")
            val days = columns.mapNotNull { (c, date) ->
                val raw = r.getOrNull(c)?.trim().orEmpty()
                // [ROST-03] "D*", "N-O", "11D" as on the photo: the code, and the rest kept as the day's note.
                val read = codes.read(raw)
                if (raw.isEmpty()) null else RosterDay(date, raw, read?.first ?: DutyCell.Unknown(raw), read?.second)
            }.sortedBy { it.date }
            // A row of weekdays or totals is no one's duties.
            val known = days.count { it.cell !is DutyCell.Unknown }
            if (days.size < DutyRoster.MIN_CELLS || known * 2 < days.size) null else RosterPerson(name, days)
        }
        if (people.isEmpty()) return null
        return RosterRead(RosterLayout.WIDE, people, YearMonth.from(columns.values.min()))
    }

    private fun readLong(rows: List<List<String>>, codes: DutyCodes, near: LocalDate): RosterRead? {
        fun norm(s: String) = s.replace(Regex("""[\s_()\[\]./·-]"""), "").uppercase()
        val headerAt = rows.take(10).indexOfFirst { r -> r.any { norm(it) in DATE_HEADS } && r.any { c -> CODE_HEADS.any { norm(c) == it || norm(c).startsWith(it) } } }
        val (dateCol, codeCol, nameCol, start) = if (headerAt >= 0) {
            val h = rows[headerAt].map(::norm)
            listOf(h.indexOfFirst { it in DATE_HEADS }, h.indexOfFirst { c -> CODE_HEADS.any { c == it || c.startsWith(it) } },
                h.indexOfFirst { it in NAME_HEADS }, headerAt + 1)
        } else {
            // No header: the column of dates, and the next one with codes.
            val width = rows.maxOfOrNull { it.size } ?: return null
            val d = (0 until width).maxByOrNull { c -> rows.count { AttendanceImport.parseDate(it.getOrNull(c).orEmpty(), near.year, near) != null } } ?: return null
            val c = (0 until width).filter { it != d }.maxByOrNull { c -> rows.count { r -> r.getOrNull(c)?.let { codes.match(it) } != null } } ?: return null
            listOf(d, c, -1, 0)
        }
        if (dateCol < 0 || codeCol < 0) return null
        val read = rows.drop(start).mapNotNull { r ->
            val date = AttendanceImport.parseDate(r.getOrNull(dateCol).orEmpty(), near.year, near) ?: return@mapNotNull null
            val raw = r.getOrNull(codeCol)?.trim().orEmpty()
            if (raw.isEmpty()) return@mapNotNull null
            val name = if (nameCol >= 0) r.getOrNull(nameCol)?.trim().orEmpty() else ""
            val read = codes.read(raw)
            name to RosterDay(date, raw, read?.first ?: DutyCell.Unknown(raw), read?.second)
        }
        if (read.size < DutyRoster.MIN_CELLS) return null
        val people = read.groupBy({ it.first }, { it.second }).map { (n, d) -> RosterPerson(n, d.distinctBy { it.date }.sortedBy { it.date }) }
        return RosterRead(RosterLayout.LONG, people)
    }

    /** [IMPORT-03] [person]'s days with each unknown code given the meaning the user picked in [picked]. */
    fun resolve(person: RosterPerson, picked: Map<String, DutyCell>): Map<LocalDate, DutyCell> =
        person.days.associate { d -> d.date to ((d.cell as? DutyCell.Unknown)?.let { picked[it.raw] } ?: d.cell) }
            .filterValues { it !is DutyCell.Unknown }
}
