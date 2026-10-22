package com.bamnat.core.report

import java.time.YearMonth

/**
 * [PROV-01][PROV-05] A printable document as blocks: the app draws it on A4 pages (PDF) and tests read
 * its words. Nothing here knows about Android; colours are ARGB ints.
 */
data class ReportDoc(
    val title: String,
    val blocks: List<Block>,
    /** A year's roster is one landscape page; everything else is portrait. */
    val landscape: Boolean = false,
    /** Printed at the foot of every page with the page number. */
    val footer: String = "",
) {
    sealed interface Block

    data class Heading(val text: String) : Block

    data class Paragraph(val text: String, val small: Boolean = false) : Block

    /** Label and value pairs, two to a row on paper. */
    data class Facts(val rows: List<Pair<String, String>>) : Block

    /**
     * Rows under [headers]; [weights] share the page width between the columns and the columns in
     * [right] (numbers) line up on the right. A [total] row is drawn in bold under a rule.
     */
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val weights: List<Float>,
        val right: Set<Int> = emptySet(),
        val total: List<String>? = null,
    ) : Block {
        init {
            require(weights.size == headers.size) { "weights ${weights.size} for ${headers.size} columns" }
            require(rows.all { it.size == headers.size } && (total == null || total.size == headers.size)) { "row width" }
        }
    }

    /** A short mark in a coloured box: a shift's 주, 야, 비. */
    data class Chip(val text: String, val color: Int)

    /** A month as a calendar, weeks starting on Sunday; [days] by day of month. */
    data class MonthGrid(val month: YearMonth, val days: Map<Int, Day>) : Block {
        data class Day(val chips: List<Chip> = emptyList(), val note: String? = null, val holiday: String? = null)
    }

    /** A year on one page: a row per month, a column per day of month. */
    data class YearGrid(val year: Int, val days: Map<YearMonth, Map<Int, Chip>>, val holidays: Set<java.time.LocalDate> = emptySet()) : Block

    data class Legend(val items: List<Pair<Chip, String>>) : Block

    /** Every word the document shows, in order: what the tests and the share text read. */
    fun text(): String = buildList {
        add(title)
        for (b in blocks) when (b) {
            is Heading -> add(b.text)
            is Paragraph -> add(b.text)
            is Facts -> b.rows.forEach { (k, v) -> add("$k: $v") }
            is Table -> {
                add(b.headers.joinToString(" | "))
                b.rows.forEach { add(it.joinToString(" | ")) }
                b.total?.let { add(it.joinToString(" | ")) }
            }
            is MonthGrid -> b.days.toSortedMap().forEach { (d, day) ->
                add("$d " + (day.chips.map { it.text } + listOfNotNull(day.holiday, day.note)).joinToString(" "))
            }
            is YearGrid -> b.days.toSortedMap().forEach { (m, days) ->
                add("${m.monthValue}월 " + (1..m.lengthOfMonth()).joinToString("") { days[it]?.text ?: "·" })
            }
            is Legend -> b.items.forEach { (chip, name) -> add("${chip.text} $name") }
        }
        if (footer.isNotEmpty()) add(footer)
    }.joinToString("\n")
}
