package com.bamnat.core.pay

import com.bamnat.core.remote.RemoteFile
import com.bamnat.core.remote.RemoteFileException
import com.bamnat.core.remote.RemoteFiles
import java.time.LocalDate

/**
 * [PAYR-04] 근로소득 간이세액표 (소득세법 시행령 별표 2, 제189조 제1항), the whole official table as data in
 * `remote/v1/taxtable.json` (순번 100). The table itself is used rather than a formula that imitates it: the
 * 2026-02-27 revision (2026-03-01 이후 원천징수분, 부칙 제15조) is 646 rows × 11 family sizes, about 50 KB of
 * text and far less packed, and a March revision becomes a signed file instead of an app update. 법령 text
 * has no copyright (저작권법 제7조 제1호).
 *
 * Reading the table (별표 2 제2~4호, 제6호): find the row whose 월급여액 (비과세·학자금 제외, 천원) range holds
 * the pay and the column of 공제대상가족 수 (본인·배우자 각 1명); take off the 8~20세 자녀 amount (1명 20,830원,
 * 2명 45,830원, 3명부터 1명마다 33,330원 더), never below 0; more than 11 family members take the 11 column less
 * (10 − 11) × the extra members. Above 10,000천원 the table's own formulas start from the 10,000천원 amount.
 */
data class WithholdingYear(
    val from: LocalDate,
    val source: String,
    /** `[이상 천원, 미만 천원, 가족 1..11]`. */
    val rows: List<LongArray>,
    /** The 10,000천원 row, family 1..11. */
    val top: LongArray,
    val over: List<Over>,
    val childOne: Long,
    val childTwo: Long,
    val childMore: Long,
) {
    /** Above [above]원: top + [add] + (pay − [above]) × [factor] × [rate]. */
    data class Over(val above: Long, val add: Long, val rate: Double, val factor: Double)

    /** The month's 근로소득세 for [pay] (비과세 제외, 원). */
    fun tax(pay: Long, family: Int, children: Int = 0): Long {
        val f = family.coerceAtLeast(1)
        fun col(r: LongArray, n: Int): Long = if (n <= 11) r[n - 1] else (r[10] - (r[9] - r[10]) * (n - 11)).coerceAtLeast(0)
        val base: Long = when {
            pay < rows.first()[0] * 1_000 -> 0
            pay < TOP -> rows.lastOrNull { pay >= it[0] * 1_000 }?.let { r -> col(r.copyOfRange(2, 13), f) } ?: 0
            else -> {
                val t = col(top, f)
                val o = over.lastOrNull { pay > it.above }
                if (o == null) t else t + o.add + cut10((pay - o.above) * o.factor * o.rate)
            }
        }
        val kids = children.coerceAtLeast(0)
        val child = when (kids) {
            0 -> 0L
            1 -> childOne
            2 -> childTwo
            else -> childTwo + childMore * (kids - 2)
        }
        return (base - child).coerceAtLeast(0)
    }

    companion object {
        const val TOP = 10_000_000L
        private fun cut10(v: Double): Long = v.toLong() / 10 * 10
    }
}

object WithholdingTable {
    @Volatile private var years: List<WithholdingYear>? = null
    @Volatile private var bundledVersion = 0L

    private fun bundled(): List<WithholdingYear> = years ?: synchronized(this) {
        years ?: (RemoteFiles.bundled(RemoteFiles.TAXTABLE)?.let { f -> bundledVersion = f.version; runCatching { read(f) }.getOrNull() } ?: emptyList())
            .also { years = it }
    }

    /** The table in force on [date]; null before 2026-03-01 or if packaging lost the file. */
    fun on(date: LocalDate): WithholdingYear? = bundled().filter { !it.from.isAfter(date) }.maxByOrNull { it.from }

    /** [REMOTE-02] Lays a newer signed table file over the built-in one (same `from` replaced). */
    fun use(file: RemoteFile?) {
        val base = run { years = null; bundled() }
        if (file == null || file.version <= bundledVersion) return
        val add = read(file)
        years = (base.filter { b -> add.none { it.from == b.from } } + add).sortedBy { it.from }
    }

    fun read(f: RemoteFile): List<WithholdingYear> {
        if (f.kind != RemoteFiles.TAXTABLE) throw RemoteFileException("kind ${f.kind}")
        return f.data.objs("tables").map { o ->
            fun longs(v: Any?, n: Int): LongArray {
                val l = v as? List<*> ?: throw RemoteFileException("row")
                if (l.size != n) throw RemoteFileException("row size")
                return LongArray(n) { (l[it] as? Long) ?: throw RemoteFileException("row value") }
            }
            val rows = o.list("rows").map { longs(it, 13) }
            if (rows.isEmpty() || rows.zipWithNext().any { (a, b) -> a[1] != b[0] } || rows.last()[1] * 1_000 != WithholdingYear.TOP)
                throw RemoteFileException("rows")
            val kids = o.obj("children")
            WithholdingYear(
                from = try { LocalDate.parse(o.str("from")) } catch (e: java.time.format.DateTimeParseException) { throw RemoteFileException("from") },
                source = o.strOr("source", ""),
                rows = rows,
                top = longs(o.list("top"), 11),
                over = o.objs("over").map { WithholdingYear.Over(it.long("above"), it.long("add"), it.double("rate"), it.double("factor")) }
                    .sortedBy { it.above },
                childOne = kids.long("one"), childTwo = kids.long("two"), childMore = kids.long("more"),
            )
        }
    }
}
