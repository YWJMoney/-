package com.bamnat.core.money

import java.time.LocalDate
import java.time.LocalTime

/**
 * [LEDG-25] A receipt photo's text, read on the phone (ML Kit), turned into a spending line to confirm:
 * the amount paid, the day and time, and the shop. Nothing here is written by itself; the ledger sheet
 * opens filled in and the user checks it ([Draft.sure] false says the amount was a guess).
 */
object ReceiptReader {
    data class Draft(
        val amount: Long?,
        val date: LocalDate?,
        val time: LocalTime?,
        val merchant: String,
        /** The amount came from a line that says it is the total (합계, 결제금액…), not the largest number seen. */
        val sure: Boolean,
    ) {
        val isEmpty: Boolean get() = amount == null && date == null && merchant.isEmpty()
    }

    /** Words a total's line carries, strongest first. */
    private val TOTAL_WORDS = listOf(
        "결제금액", "승인금액", "받을금액", "청구금액", "총결제", "합계금액", "총합계", "총액", "총금액", "합계", "판매금액", "결제", "금액",
    )

    /** Lines whose number is not what was paid. */
    private val NOT_TOTAL = listOf(
        "부가세", "부가가치세", "과세", "면세", "공급가", "공급가액", "세액", "할인", "포인트", "받은금액", "받은돈", "거스름", "잔돈", "잔액",
        "누적", "적립", "단가", "수량", "봉사료", "카드번호", "승인번호", "사업자", "전화", "TEL", "가맹점번호", "할부",
    )

    /** Lines that are about the paper, not the shop. */
    private val NOT_SHOP = listOf(
        "영수증", "매출전표", "신용카드", "카드전표", "현금영수증", "고객용", "가맹점용", "승인", "거래", "일시", "사업자", "대표", "주소", "전화",
        "TEL", "합계", "금액", "부가세", "감사", "환불", "교환", "품명", "단가", "수량", "POS", "NO.", "No.",
    )

    private val AMOUNT = Regex("""(?<![\d.])(\d{1,3}(?:,\d{3})+|\d{3,9})(?:\s*원)?(?![\d,])""")
    private val DATE_YMD = Regex("""(20\d{2}|\d{2})\s*[-./년]\s*(\d{1,2})\s*[-./월]\s*(\d{1,2})""")
    private val TIME = Regex("""(?<!\d)([01]?\d|2[0-3])\s*:\s*([0-5]\d)(?::[0-5]\d)?(?!\d)""")
    private val LABELLED_SHOP = Regex("""(?:가맹점명|가맹점|상\s*호(?:명)?|매장명|점포명)\s*[:：]?\s*(.+)""")

    fun read(text: String, today: LocalDate): Draft {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val (amount, sure) = amountOf(lines)
        val date = lines.firstNotNullOfOrNull { dateOf(it, today) }
        val time = lines.firstNotNullOfOrNull { l -> if (DATE_YMD.containsMatchIn(l) || l.contains("시각") || l.contains("일시")) timeOf(l) else null }
            ?: lines.firstNotNullOfOrNull { timeOf(it) }
        return Draft(amount, date, time, shopOf(lines), sure)
    }

    private fun numbers(line: String): List<Long> =
        AMOUNT.findAll(line.replace(" ,", ",")).mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }
            .filter { it in 100..99_999_999 }.toList()

    private fun clean(line: String) = line.replace(" ", "")

    /** The total and whether a line named it so. */
    private fun amountOf(lines: List<String>): Pair<Long?, Boolean> {
        // A word on one row and its number on the next is common on narrow receipts.
        val rows = lines.mapIndexed { i, l -> if (numbers(l).isEmpty() && i + 1 < lines.size && numbers(lines[i + 1]).isNotEmpty()) l + " " + lines[i + 1] else l }
        val named = rows.mapNotNull { row ->
            val c = clean(row)
            if (NOT_TOTAL.any { c.contains(it, ignoreCase = true) } || DATE_YMD.containsMatchIn(row)) return@mapNotNull null
            val rank = TOTAL_WORDS.indexOfFirst { c.contains(it) }.takeIf { it >= 0 } ?: return@mapNotNull null
            numbers(row).lastOrNull()?.let { rank to it }
        }
        named.minWithOrNull(compareBy<Pair<Int, Long>> { it.first }.thenByDescending { it.second })?.let { return it.second to true }
        val any = rows.filter { r -> val c = clean(r); NOT_TOTAL.none { c.contains(it, ignoreCase = true) } && !DATE_YMD.containsMatchIn(r) && !TIME.containsMatchIn(r) }
            .flatMap(::numbers).filter { it >= 500 }
        return any.maxOrNull() to false
    }

    private fun dateOf(line: String, today: LocalDate): LocalDate? {
        val m = DATE_YMD.find(line) ?: return null
        val (y, mo, d) = m.destructured
        val year = y.toInt().let { if (it < 100) 2000 + it else it }
        val date = runCatching { LocalDate.of(year, mo.toInt(), d.toInt()) }.getOrNull() ?: return null
        // A receipt is from the past; anything far off is a misread number.
        return date.takeIf { !it.isAfter(today.plusDays(1)) && it.isAfter(today.minusYears(2)) }
    }

    private fun timeOf(line: String): LocalTime? = TIME.find(line)?.destructured?.let { (h, m) -> LocalTime.of(h.toInt(), m.toInt()) }

    private fun shopOf(lines: List<String>): String {
        lines.firstNotNullOfOrNull { l -> LABELLED_SHOP.find(l)?.groupValues?.get(1)?.let(::shopName)?.takeIf { it.length >= 2 } }?.let { return it }
        return lines.take(6).firstOrNull { l ->
            val c = clean(l)
            c.length in 2..30 && NOT_SHOP.none { c.contains(it, ignoreCase = true) } && c.count(Char::isDigit) <= c.length / 3 &&
                c.any { it in '가'..'힣' || it.isLetter() }
        }?.let(::shopName).orEmpty()
    }

    /** "스타벅스 강남점 (T.02-123)" → "스타벅스 강남점". */
    private fun shopName(raw: String): String =
        raw.replace(Regex("""\(.*?\)"""), " ").replace(Regex("""(TEL|Tel|전화|☎).*$"""), " ").replace(Regex("""\s+"""), " ").trim().take(30)
}
