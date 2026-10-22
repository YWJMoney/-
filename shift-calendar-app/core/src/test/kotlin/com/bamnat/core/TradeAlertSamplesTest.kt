package com.bamnat.core

import com.bamnat.core.invest.Market
import com.bamnat.core.invest.Side
import com.bamnat.core.invest.TradeAlertParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [JOB-05] Real 체결 알림 samples (순번 274), one per line of `trade-alerts/samples.tsv`: adding a line is all it
 * takes to test a broker's wording. The file says how to fill it and keeps personal data out.
 */
class TradeAlertSamplesTest {
    private data class Sample(val line: Int, val pkg: String, val text: String, val side: String, val name: String,
        val symbol: String, val quantity: String, val price: String, val market: String)

    private fun samples(): List<Sample> {
        val stream = javaClass.classLoader.getResourceAsStream("trade-alerts/samples.tsv") ?: return emptyList()
        return stream.bufferedReader(Charsets.UTF_8).readLines().mapIndexedNotNull { i, raw ->
            if (raw.isBlank() || raw.startsWith("#")) return@mapIndexedNotNull null
            val c = raw.split('\t')
            require(c.size >= 8) { "samples.tsv ${i + 1}번째 줄: 칸이 ${c.size}개예요 (8개여야 해요)" }
            Sample(i + 1, c[0], c[1].replace("\\n", "\n"), c[2].trim(), c[3].trim(), c[4].trim(), c[5].trim(), c[6].trim(), c[7].trim())
        }
    }

    @Test fun everySampleReadsAsWritten() {
        for (s in samples()) {
            val a = TradeAlertParser.parse(s.text, s.pkg)
            val at = "samples.tsv ${s.line}번째 줄"
            if (s.side == "NONE") { assertNull(at, a); continue }
            assertNotNull(at, a)
            a!!
            assertEquals(at, Side.valueOf(s.side), a.side)
            if (s.name != "-") assertEquals(at, s.name, a.name)
            if (s.symbol != "-") assertEquals(at, s.symbol, a.symbol)
            if (s.quantity != "-") assertEquals(at, s.quantity.toDouble(), a.quantity, 1e-9)
            if (s.price != "-") assertEquals(at, s.price.toDouble(), a.price, 1e-9)
            if (s.market != "-") assertEquals(at, Market.valueOf(s.market), a.market)
        }
    }
}
