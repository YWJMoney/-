package com.bamnat.core.money

import java.time.LocalDate
import java.time.YearMonth

/** [LEDG-07] Which lines the 내역 list shows; an empty set means any. */
data class LedgerFilter(
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val kinds: Set<MoneyKind> = emptySet(),
    val categories: Set<String> = emptySet(),
    /** Account ids, and [NO_ACCOUNT] for lines with none. */
    val accounts: Set<Long> = emptySet(),
    val tags: Set<String> = emptySet(),
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val query: String = "",
) {
    /** Conditions on besides the period and the words, for the filter button's badge. */
    val count: Int get() = listOf(kinds.isNotEmpty(), categories.isNotEmpty(), accounts.isNotEmpty(), tags.isNotEmpty(),
        minAmount != null || maxAmount != null).count { it }

    companion object {
        const val NO_ACCOUNT = -1L
    }
}

enum class LedgerSort(val label: String) { NEWEST("최신순"), OLDEST("오래된 순"), LARGEST("금액 큰 순"), SMALLEST("금액 작은 순") }

/** [LEDG-07] The periods 내역 offers, counted from the month on screen. */
enum class LedgerPeriod(val label: String) {
    MONTH("이 달"), THREE_MONTHS("3개월"), SIX_MONTHS("6개월"), YEAR("올해"), ALL("전체");

    fun range(month: YearMonth): Pair<LocalDate?, LocalDate?> = when (this) {
        MONTH -> month.atDay(1) to month.atEndOfMonth()
        THREE_MONTHS -> month.minusMonths(2).atDay(1) to month.atEndOfMonth()
        SIX_MONTHS -> month.minusMonths(5).atDay(1) to month.atEndOfMonth()
        YEAR -> LocalDate.of(month.year, 1, 1) to LocalDate.of(month.year, 12, 31)
        ALL -> null to null
    }
}

/** [LEDG-07] What the lines shown add up to. Transfers are counted apart and are never spending. */
data class LedgerTotals(val expense: Long, val income: Long, val transfer: Long, val count: Int) {
    val net: Long get() = income - expense
}

/** [LEDG-07] One day of the list, with that day's money in and out. */
data class LedgerDay(val date: LocalDate, val entries: List<MoneyEntry>, val expense: Long, val income: Long)

/** [LEDG-13] A place written before, offered while typing a new line. */
data class PlaceSuggestion(val merchant: String, val category: String, val amount: Long, val accountId: Long?, val count: Int)

object Ledger {
    /** [LEDG-07] The lines [filter] keeps, in [sort]'s order. */
    fun search(entries: List<MoneyEntry>, filter: LedgerFilter, sort: LedgerSort = LedgerSort.NEWEST, accounts: List<MoneyAccount> = emptyList()): List<MoneyEntry> {
        val words = words(filter.query)
        val kept = entries.filter { matches(it, filter, accounts) && words.all { w -> matchesWord(it, w, accounts) } }
        return when (sort) {
            LedgerSort.NEWEST -> kept.sortedWith(compareByDescending<MoneyEntry> { it.date }.thenByDescending { it.time }.thenByDescending { it.id })
            LedgerSort.OLDEST -> kept.sortedWith(compareBy<MoneyEntry> { it.date }.thenBy { it.time }.thenBy { it.id })
            LedgerSort.LARGEST -> kept.sortedWith(compareByDescending<MoneyEntry> { it.amount }.thenByDescending { it.date })
            LedgerSort.SMALLEST -> kept.sortedWith(compareBy<MoneyEntry> { it.amount }.thenByDescending { it.date })
        }
    }

    fun matches(e: MoneyEntry, f: LedgerFilter, accounts: List<MoneyAccount> = emptyList()): Boolean {
        if (f.from != null && e.date.isBefore(f.from)) return false
        if (f.to != null && e.date.isAfter(f.to)) return false
        if (f.kinds.isNotEmpty() && e.kind !in f.kinds) return false
        if (f.categories.isNotEmpty() && e.shares().none { it.first in f.categories }) return false
        if (f.accounts.isNotEmpty()) {
            val a = MoneyAccounts.of(e, accounts) ?: LedgerFilter.NO_ACCOUNT
            if (a !in f.accounts && (e.toAccountId == null || e.toAccountId !in f.accounts)) return false
        }
        if (f.tags.isNotEmpty() && e.tags.none { it in f.tags }) return false
        val size = kotlin.math.abs(e.amount)
        if (f.minAmount != null && size < f.minAmount) return false
        if (f.maxAmount != null && size > f.maxAmount) return false
        return true
    }

    private fun words(q: String): List<String> = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /**
     * One searched word: "#여행" is a tag; digits find the amount (4500 finds 4,500원); anything else
     * is looked for in the place, notes, memo, category, tags and the account's name.
     */
    private fun matchesWord(e: MoneyEntry, word: String, accounts: List<MoneyAccount>): Boolean {
        if (word.startsWith("#") && word.length > 1) return e.tags.any { it.startsWith(word.drop(1), ignoreCase = true) }
        val digits = word.replace(",", "").removeSuffix("원")
        if (digits.isNotEmpty() && digits.all(Char::isDigit) && kotlin.math.abs(e.amount).toString().contains(digits)) return true
        val account = MoneyAccounts.of(e, accounts)?.let { id -> accounts.firstOrNull { it.id == id }?.name }.orEmpty()
        return listOf(e.merchant, e.note, e.memo, e.category, account, e.tags.joinToString(" ") + " " + e.parts.joinToString(" ") { it.category })
            .any { it.contains(word, ignoreCase = true) }
    }

    /** [LEDG-07] What [shown] adds up to; with categories picked, only those categories' parts of each line. */
    fun totals(shown: List<MoneyEntry>, filter: LedgerFilter = LedgerFilter()): LedgerTotals {
        fun part(e: MoneyEntry): Long = if (filter.categories.isEmpty()) e.amount else e.shares().filter { it.first in filter.categories }.sumOf { it.second }
        return LedgerTotals(
            expense = shown.filter { it.kind == MoneyKind.EXPENSE }.sumOf(::part),
            income = shown.filter { it.kind == MoneyKind.INCOME }.sumOf(::part),
            transfer = shown.filter { it.kind == MoneyKind.TRANSFER }.sumOf(::part),
            count = shown.size,
        )
    }

    /** [LEDG-07] Lines in date order as days, each with its own money in and out. */
    fun days(shown: List<MoneyEntry>): List<LedgerDay> {
        val out = ArrayList<LedgerDay>()
        var i = 0
        while (i < shown.size) {
            val date = shown[i].date
            var j = i
            while (j < shown.size && shown[j].date == date) j++
            val list = shown.subList(i, j).toList()
            out += LedgerDay(date, list, list.filter { it.kind == MoneyKind.EXPENSE }.sumOf { it.amount },
                list.filter { it.kind == MoneyKind.INCOME }.sumOf { it.amount })
            i = j
        }
        return out
    }

    /** [LEDG-11] Tags used, most used first. */
    fun tags(entries: List<MoneyEntry>): List<String> =
        entries.flatMap { it.tags }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }

    /** [LEDG-11] "#여행 회식, 출장" as tags: no '#', no repeats, short. */
    fun parseTags(text: String): List<String> =
        text.split(Regex("[\\s,#]+")).map { it.trim().take(MAX_TAG) }.filter { it.isNotEmpty() }.distinct().take(MAX_TAGS)

    /** [LEDG-07] A copy of [e] as a new line on [date], as typed by hand. */
    fun duplicate(e: MoneyEntry, date: LocalDate): MoneyEntry =
        e.copy(id = 0, date = date, source = MoneySource.MANUAL, reviewed = true, time = null, fixedId = null)

    /** [LEDG-07] [e] moved wholly to [category]; a split payment becomes one line again. */
    fun recategorize(e: MoneyEntry, category: String): MoneyEntry = e.copy(category = category, parts = emptyList(), reviewed = true)

    /** [LEDG-13] The category of the last line of [kind] the user wrote by hand, for a new one. */
    fun lastCategory(entries: List<MoneyEntry>, kind: MoneyKind): String? =
        entries.asSequence().filter { it.kind == kind && it.source == MoneySource.MANUAL && it.parts.isEmpty() }
            .maxWithOrNull(compareBy<MoneyEntry> { it.date }.thenBy { it.id })?.category

    /**
     * [LEDG-13] Places written before that match what is typed, for one tap: those starting with
     * it first, then the most used. Each brings its usual category, last amount and account.
     */
    fun suggest(entries: List<MoneyEntry>, typed: String, kind: MoneyKind, limit: Int = 4): List<PlaceSuggestion> {
        val t = typed.trim()
        return entries.asSequence().filter { it.kind == kind && it.merchant.isNotBlank() && it.amount > 0 }
            .groupBy { it.merchant.trim() }
            .filter { (m, _) -> m != t && (t.isEmpty() || m.contains(t, ignoreCase = true)) }
            .map { (m, list) ->
                val last = list.maxWith(compareBy<MoneyEntry> { it.date }.thenBy { it.id })
                PlaceSuggestion(m, list.groupingBy { it.category }.eachCount().maxBy { it.value }.key, last.amount, last.accountId, list.size) to last.date
            }
            .sortedWith(compareByDescending<Pair<PlaceSuggestion, LocalDate>> { t.isNotEmpty() && it.first.merchant.startsWith(t, ignoreCase = true) }
                .thenByDescending { it.first.count }.thenByDescending { it.second })
            .take(limit).map { it.first }
    }

    const val MAX_TAG = 12
    const val MAX_TAGS = 5
}

/**
 * [LEDG-12] The ledger as a CSV file for a spreadsheet, made on the phone: one row per category
 * of each line (a split payment is several rows), so the 금액 column adds up. Text that a
 * spreadsheet would run as a formula (=, +, -, @ at the start) is quoted with ' first.
 */
object LedgerCsv {
    val HEADER = listOf("날짜", "시각", "구분", "분류", "금액", "내용", "결제수단", "받는 곳", "메모", "태그", "할부", "적은 방법")

    fun write(entries: List<MoneyEntry>, accounts: List<MoneyAccount>): String {
        val names = accounts.associate { it.id to it.label }
        val sb = StringBuilder("﻿")
        line(sb, HEADER)
        for (e in entries) {
            val shares = e.shares()
            for ((i, share) in shares.withIndex()) {
                val (category, amount) = share
                val account = MoneyAccounts.of(e, accounts)?.let { names[it] }.orEmpty()
                line(sb, listOf(
                    e.date.toString(), e.time?.toString().orEmpty(), e.kind.label, category, amount.toString(),
                    text(e.title + if (shares.size > 1) " (나눔 ${i + 1}/${shares.size})" else ""),
                    text(account), text(e.toAccountId?.let { names[it] }.orEmpty()), text(e.memo.ifBlank { e.note.takeIf { e.merchant.isNotBlank() }.orEmpty() }),
                    text(e.tags.joinToString(" ") { "#$it" }), if (e.installments > 1) "${e.installments}개월" else "", source(e.source),
                ))
            }
        }
        return sb.toString()
    }

    private fun source(s: MoneySource) = when (s) {
        MoneySource.MANUAL -> "직접"
        MoneySource.NOTIFICATION -> "결제 알림"
        MoneySource.PASTE -> "문자 붙여넣기"
        MoneySource.MEMO -> "메모"
        MoneySource.FIXED -> "반복"
    }

    /** Text a spreadsheet must not treat as a formula. */
    private fun text(v: String): String = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v

    private fun line(sb: StringBuilder, cells: List<String>) {
        cells.forEachIndexed { i, c ->
            if (i > 0) sb.append(',')
            if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) sb.append('"').append(c.replace("\"", "\"\"")).append('"') else sb.append(c)
        }
        sb.append("\r\n")
    }

    /** "밤낮_가계부_2026-10.csv" or with a range, safe as a file name. */
    fun fileName(from: LocalDate?, to: LocalDate?): String = "밤낮_가계부_" + when {
        from == null || to == null -> "전체"
        YearMonth.from(from) == YearMonth.from(to) && from.dayOfMonth == 1 && to == YearMonth.from(to).atEndOfMonth() -> YearMonth.from(from).toString()
        else -> "${from}_$to"
    } + ".csv"
}
