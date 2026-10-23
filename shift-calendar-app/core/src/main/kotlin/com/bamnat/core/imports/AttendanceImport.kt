package com.bamnat.core.imports

import com.bamnat.core.model.Attendance
import com.bamnat.core.model.DayEntry
import com.bamnat.core.model.Leave
import com.bamnat.core.model.LeaveKind
import com.bamnat.core.schedule.ResolvedDay
import com.bamnat.core.work.Commute
import java.time.LocalDate
import java.time.LocalTime

/** [DAYW-06] What a column of a 근태 export holds. */
enum class AttendanceField(val label: String) {
    DATE("날짜"),
    START("출근"),
    END("퇴근"),
    HOURS("근무시간"),
    OVERTIME("연장·야근"),
    KIND("구분·휴가"),
}

/** [DAYW-06] Which row is the header and which column holds what. */
data class ColumnMap(val headerRow: Int, val columns: Map<AttendanceField, Int>) {
    val ready: Boolean get() = AttendanceField.DATE in columns && columns.size >= 2
}

/** [DAYW-06] One day of the export as read. Times are the clock, minutes are lengths. */
data class AttendanceRow(
    val date: LocalDate,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val workMinutes: Int? = null,
    val overtimeMinutes: Int? = null,
    val leave: Leave? = null,
    val absent: Boolean = false,
    /** The 구분 cell as written, for the preview. */
    val kind: String = "",
) {
    val hasTimes: Boolean get() = start != null && end != null
    /** Nothing that changes the calendar. */
    val isEmpty: Boolean get() = !hasTimes && leave == null && !absent && (overtimeMinutes ?: 0) <= 0
}

/**
 * [DAYW-06] Days out of the table a company's 근태 system exports (시프티, 플렉스, 세콤·캡스 출입 기록, 그룹웨어 …):
 * a header row with 날짜, 출근, 퇴근, 근무시간, 연장, 구분 or similar words, one row per day. The columns are
 * guessed from the header and the user can change them before anything is written.
 */
object AttendanceImport {
    private val WORDS: Map<AttendanceField, List<String>> = mapOf(
        AttendanceField.DATE to listOf("근무일자", "근무일", "일자", "날짜", "출근일", "DATE", "일시", "기준일"),
        AttendanceField.START to listOf("출근시각", "출근시간", "출근", "시작시간", "시작", "IN", "CLOCKIN", "입실"),
        AttendanceField.END to listOf("퇴근시각", "퇴근시간", "퇴근", "종료시간", "종료", "OUT", "CLOCKOUT", "퇴실"),
        AttendanceField.OVERTIME to listOf("연장근무", "연장근로", "연장", "초과근무", "초과", "시간외", "야근", "OVERTIME", "OT"),
        AttendanceField.HOURS to listOf("총근무시간", "실근무시간", "근무시간", "근로시간", "총근무", "인정시간", "WORKHOURS", "HOURS"),
        AttendanceField.KIND to listOf("근태구분", "근태", "휴가구분", "휴가", "구분", "유형", "상태", "비고", "TYPE", "STATUS"),
    )

    /** The header row and its columns, from the first rows; null when no row names a date column. */
    fun guess(rows: List<List<String>>): ColumnMap? {
        for ((i, row) in rows.take(15).withIndex()) {
            val cols = LinkedHashMap<AttendanceField, Int>()
            // Fields with longer words first, so 출근일 is the date and not the start.
            val order = listOf(AttendanceField.DATE, AttendanceField.OVERTIME, AttendanceField.HOURS, AttendanceField.START, AttendanceField.END, AttendanceField.KIND)
            for (f in order) {
                val col = row.indices.firstOrNull { c ->
                    c !in cols.values && WORDS.getValue(f).any { w -> norm(row[c]).let { it == w || (w.length >= 2 && it.contains(w)) } }
                } ?: continue
                cols[f] = col
            }
            if (AttendanceField.DATE in cols && cols.size >= 2) return ColumnMap(i, cols)
        }
        // No header: the first column with dates in it.
        val col = (0 until (rows.maxOfOrNull { it.size } ?: 0)).firstOrNull { c -> rows.take(10).count { parseDate(it.getOrNull(c).orEmpty(), 2000) != null } >= 3 }
        return col?.let { ColumnMap(-1, mapOf(AttendanceField.DATE to it)) }
    }

    /**
     * The days of [rows] under [map]; rows without a date are left out, and the last row of a date wins.
     * A date written without its year takes [defaultYear], or with [near] the year that puts it nearest that
     * day and not more than a month after it (December's export read in January is last year's).
     */
    fun read(rows: List<List<String>>, map: ColumnMap, defaultYear: Int, near: LocalDate? = null): List<AttendanceRow> {
        fun cell(row: List<String>, f: AttendanceField) = map.columns[f]?.let { row.getOrNull(it) }.orEmpty().trim()
        val out = LinkedHashMap<LocalDate, AttendanceRow>()
        rows.drop(map.headerRow + 1).forEach { row ->
            val date = parseDate(cell(row, AttendanceField.DATE), defaultYear, near) ?: return@forEach
            val kind = cell(row, AttendanceField.KIND)
            val k = kindOf(kind)
            val rawStart = cell(row, AttendanceField.START)
            val rawEnd = cell(row, AttendanceField.END)
            out[date] = AttendanceRow(
                date = date,
                start = parseTime(rawStart),
                end = parseTime(rawEnd),
                workMinutes = parseMinutes(cell(row, AttendanceField.HOURS)),
                overtimeMinutes = parseMinutes(cell(row, AttendanceField.OVERTIME)),
                leave = k.leave ?: kindOf(rawStart).leave,
                absent = k.absent,
                kind = kind,
            )
        }
        return out.values.sortedBy { it.date }
    }

    /** [IMPORT-05] A cell [read] could not use: the sheet's row (1 = the first), the column's field, what it held, and why. */
    data class Problem(val row: Int, val field: AttendanceField, val cell: String, val reason: String)

    /**
     * [IMPORT-05] Why rows were left out or cells not read, row by row, so the user can fix the file or the columns
     * instead of guessing from a count. Empty rows are not problems; a later row of the same date wins and says so.
     */
    fun problems(rows: List<List<String>>, map: ColumnMap, defaultYear: Int, near: LocalDate? = null): List<Problem> {
        fun cell(row: List<String>, f: AttendanceField) = map.columns[f]?.let { row.getOrNull(it) }.orEmpty().trim()
        val out = ArrayList<Problem>()
        val seen = HashMap<LocalDate, Int>()
        rows.withIndex().drop(map.headerRow + 1).forEach { (i, row) ->
            if (row.all { it.isBlank() }) return@forEach
            val n = i + 1
            val rawDate = cell(row, AttendanceField.DATE)
            val date = parseDate(rawDate, defaultYear, near)
            if (date == null) {
                out += Problem(n, AttendanceField.DATE, rawDate, if (rawDate.isEmpty()) "날짜가 비어 있어 이 줄을 건너뛰었어요" else "날짜로 읽지 못해 이 줄을 건너뛰었어요")
                return@forEach
            }
            seen.put(date, n)?.let { first -> out += Problem(n, AttendanceField.DATE, rawDate, "${first}번째 줄과 같은 날이라 이 줄로 바꿨어요") }
            for (f in listOf(AttendanceField.START, AttendanceField.END)) {
                val v = cell(row, f)
                if (v.isNotEmpty() && parseTime(v) == null && kindOf(v).leave == null) out += Problem(n, f, v, "시각으로 읽지 못했어요 (예: 09:00, 18:30)")
            }
            for (f in listOf(AttendanceField.HOURS, AttendanceField.OVERTIME)) {
                val v = cell(row, f)
                if (v.isNotEmpty() && parseMinutes(v) == null) out += Problem(n, f, v, "시간으로 읽지 못했어요 (예: 8:30, 8.5, 510분)")
            }
            val start = parseTime(cell(row, AttendanceField.START))
            val end = parseTime(cell(row, AttendanceField.END))
            if ((start == null) != (end == null) && kindOf(cell(row, AttendanceField.KIND)).leave == null) {
                out += Problem(n, if (start == null) AttendanceField.START else AttendanceField.END, "", "출근·퇴근 중 하나만 있어 근무 시각은 적지 않아요")
            }
        }
        return out
    }

    /** [DAYW-06] What a 구분 cell says: leave (연차·반차·반반차·병가·경조·공가), 결근, or nothing to write. */
    data class Kind(val leave: Leave? = null, val absent: Boolean = false)

    fun kindOf(text: String): Kind {
        val t = norm(text)
        if (t.isEmpty()) return Kind()
        val quarters = when {
            "반반차" in t || "QUARTER" in t -> 1
            "반차" in t || "HALF" in t -> 2
            else -> 4
        }
        return when {
            "결근" in t || "무단" in t -> Kind(absent = true)
            // 유급휴일·주휴일·공휴일 are days off, not leave taken.
            "휴일" in t || "주휴" in t -> Kind()
            "무급" in t -> Kind(Leave(LeaveKind.UNPAID, quarters))
            listOf("반반차", "반차", "연차", "월차", "ANNUAL").any { it in t } -> Kind(Leave(LeaveKind.ANNUAL, quarters))
            listOf("병가", "경조", "공가", "특별휴가", "출산", "육아", "보건", "유급", "휴가", "VACATION", "LEAVE").any { it in t } -> Kind(Leave(LeaveKind.PAID, quarters))
            else -> Kind()
        }
    }

    /** A date as 근태 exports write it: 2026-10-01, 2026.10.01(수), 2026/10/1, 10/01, 10월 1일, 20261001, or Excel's day number. */
    fun parseDate(text: String, defaultYear: Int, near: LocalDate? = null): LocalDate? {
        val s = text.trim().replace(Regex("""\([^)]*\)"""), " ").trim()
        if (s.isEmpty()) return null
        Regex("""^(\d{4})\s*[-./년]\s*(\d{1,2})\s*[-./월]\s*(\d{1,2})""").find(s)?.let { m ->
            return date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("""^(\d{2})\s*[-./]\s*(\d{1,2})\s*[-./]\s*(\d{1,2})(?:\s|$)""").find(s)?.let { m ->
            return date(2000 + m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("""^(\d{4})(\d{2})(\d{2})$""").find(s)?.let { m ->
            return date(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }
        Regex("""^(\d{1,2})\s*[-./월]\s*(\d{1,2})\s*일?(?:\s|$)""").find(s)?.let { m ->
            val month = m.groupValues[1].toInt()
            val day = m.groupValues[2].toInt()
            if (near == null) return date(defaultYear, month, day)
            return listOf(near.year - 1, near.year, near.year + 1).mapNotNull { date(it, month, day) }
                .filter { !it.isAfter(near.plusMonths(1)) }
                .minByOrNull { kotlin.math.abs(it.toEpochDay() - near.toEpochDay()) }
        }
        // Excel keeps a date as days since 1899-12-30; 30000..60000 is 1982..2064.
        s.toDoubleOrNull()?.takeIf { it in 30000.0..60000.0 }?.let { return LocalDate.of(1899, 12, 30).plusDays(it.toLong()) }
        return null
    }

    /** A clock time: 09:02, 9:02:31, 오후 6:30, PM 6:30, 18시 30분, 0902, a date with a time, or Excel's fraction of a day. */
    fun parseTime(text: String): LocalTime? {
        var s = text.trim().uppercase()
        if (s.isEmpty() || s == "-") return null
        val pm = "오후" in s || "PM" in s
        val am = "오전" in s || "AM" in s
        s = s.replace("오후", " ").replace("오전", " ").replace("PM", " ").replace("AM", " ").trim()
        fun at(h: Int, m: Int): LocalTime? {
            val hour = when {
                pm && h in 1..11 -> h + 12
                am && h == 12 -> 0
                else -> h
            }
            return if (hour in 0..24 && m in 0..59) LocalTime.of(hour % 24, m) else null
        }
        // The time after a date ("2026-10-01 09:02").
        Regex("""(?:^|\s|T)(\d{1,2}):(\d{2})(?::\d{2})?(?:\s|$)""").findAll(s).lastOrNull()?.let { return at(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("""^(\d{1,2})\s*시(?:\s*(\d{1,2})\s*분)?$""").find(s)?.let { return at(it.groupValues[1].toInt(), it.groupValues[2].toIntOrNull() ?: 0) }
        Regex("""^(\d{2})(\d{2})$""").find(s)?.let { return at(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        s.toDoubleOrNull()?.let { v ->
            val frac = v - kotlin.math.floor(v)
            if (v < 0 || (v >= 1 && v < 30000)) return null
            val minutes = Math.round(frac * 24 * 60).toInt() % (24 * 60)
            return LocalTime.of(minutes / 60, minutes % 60)
        }
        return null
    }

    /**
     * A length: 8:30, 8.5 (hours), 8시간 30분, 90분. A plain number is hours (0.5 is 30분); Excel's fraction
     * of a day in a cell shown as a time already comes as h:mm from [Sheets].
     */
    fun parseMinutes(text: String): Int? {
        val s = text.trim().replace(" ", "")
        if (s.isEmpty() || s == "-") return null
        Regex("""^(\d{1,3}):(\d{2})(?::\d{2})?$""").find(s)?.let { return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
        Regex("""^(?:(\d{1,3})(?:시간|H))?(?:(\d{1,3})(?:분|M))?$""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            if (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty()) {
                return (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
            }
        }
        val v = s.removeSuffix("시간").removeSuffix("h").toDoubleOrNull() ?: return null
        if (v < 0) return null
        return Math.round(v * 60).toInt().coerceAtMost(24 * 60)
    }

    /**
     * [DAYW-06] What one day of the export writes on [before], the day as stored, given [day] as the calendar
     * resolves it: leave as leave, 결근 as 결근, and the clock times as a later start, an earlier end or
     * 잔업 after the day's end ([Commute.arrive], [Commute.leave] decide the same way). On a 반차 day only
     * 잔업 past the full day's end. On a day off, times are 특근. Overtime given only as a length is 잔업 of that length.
     */
    fun apply(day: ResolvedDay, before: DayEntry, row: AttendanceRow): DayEntry {
        var e = before
        if (row.leave != null) e = e.copy(leave = row.leave)
        if (row.absent) e = e.copy(attendance = Attendance.ABSENT)
        if (row.leave?.isFullDay == true || row.absent) return e
        if (row.hasTimes) {
            val start = row.start!!
            val end = row.end!!
            // A 반차 row's times are the half worked: with the leave on the day, Commute writes only 잔업 past
            // the full day's end, never a late start or early end the leave already takes off.
            val worked = if (row.leave != null && day.isWorkDay) day.copy(leave = row.leave) else day
            e = Commute.apply(e, Commute.arrive(worked, row.date.atTime(start), rounding = false))
            // Read the day again with its new start, so an early end keeps the late start.
            val after = Commute.withStart(worked, e)
            val endAt = if (end.isAfter(start)) row.date.atTime(end) else row.date.plusDays(1).atTime(end)
            e = Commute.apply(e, Commute.leave(after, row.date.atTime(start), endAt, rounding = false))
        } else if ((row.overtimeMinutes ?: 0) > 0) {
            e = Commute.apply(e, Commute.Write.Overtime(com.bamnat.core.model.ExtraEntry(com.bamnat.core.model.ExtraKind.OVERTIME_ID,
                minutes = row.overtimeMinutes!!.coerceAtMost(16 * 60)), row.overtimeMinutes.coerceAtMost(16 * 60)))
        }
        return e
    }

    private fun date(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.of(y, m, d) }.getOrNull()

    private fun norm(s: String) = s.replace(Regex("""[\s_()\[\]./·-]"""), "").uppercase()
}
