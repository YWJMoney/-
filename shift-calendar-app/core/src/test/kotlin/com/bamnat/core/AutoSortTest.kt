package com.bamnat.core

import com.bamnat.core.money.CaptureFilter
import com.bamnat.core.money.FixedCost
import com.bamnat.core.money.MoneyEntry
import com.bamnat.core.money.MoneyKind
import com.bamnat.core.money.OtherPlaces
import com.bamnat.core.money.PaymentParser
import com.bamnat.core.money.RepeatFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** [LEDG-21][LEDG-22][LEDG-23][LEDG-24] Rules the user writes, what is left out, 기타 by place, monthly payments. */
class AutoSortTest {
    private val today = LocalDate.of(2026, 10, 20)

    private fun spend(date: LocalDate, amount: Long, place: String, category: String = "기타", fixedId: Long? = null) =
        MoneyEntry(date = date, kind = MoneyKind.EXPENSE, amount = amount, category = category, merchant = place, fixedId = fixedId)

    @Test fun aContainsRuleMatchesAnywhereAndTheLongerWins() {
        val rules = mapOf("${PaymentParser.CONTAINS}주유소" to "자동차", "${PaymentParser.CONTAINS}셀프 주유소" to "생활")
        assertEquals("자동차", PaymentParser.guessCategory("행복 주유소 역삼", rules))
        assertEquals("생활", PaymentParser.guessCategory("강남셀프주유소", rules))
        assertTrue(PaymentParser.isContainsRule("${PaymentParser.CONTAINS}주유소"))
        // A place read from a text never starts with the mark.
        val r = (PaymentParser.parse("신한카드(1234)승인 5,000원 10/15 12:00 별*빛 상회", LocalDateTime.of(2026, 10, 15, 12, 0)) as PaymentParser.Result.Ok).parsed
        assertTrue(!r.merchant.startsWith(PaymentParser.CONTAINS))
    }

    @Test fun filterLeavesOutAppsWordsAndSmallAmounts() {
        val f = CaptureFilter(blockedApps = setOf("com.bank.a"), words = listOf("법인"), minAmount = 1_000)
        val text = "신한카드(1234)승인 12,500원 10/15 13:20 스타벅스"
        assertTrue(f.drops("com.bank.a", text))
        assertTrue(f.drops("com.bank.b", "신한 법인카드(1234)승인 12,500원 스타벅스"))
        assertTrue(f.drops("com.bank.b", "신한카드(1234)승인 500원 10/15 13:20 주차"))
        assertTrue(!f.drops("com.bank.b", text))
        assertTrue(!CaptureFilter().drops("com.bank.a", text))
        assertEquals("회사 카드", CaptureFilter.clean("  회사   카드 "))
    }

    @Test fun otherPlacesGroupBranchesTogether() {
        val list = listOf(
            spend(today.minusDays(1), 9_000, "행운상회 역삼점"),
            spend(today.minusDays(2), 7_000, "행운상회 강남점"),
            spend(today.minusDays(3), 3_000, "별빛분식"),
            spend(today.minusDays(4), 4_000, "스타벅스", category = "카페"),
            spend(today.minusDays(200), 1_000, "오래된가게"),
        )
        val g = OtherPlaces.groups(list, today)
        assertEquals(listOf("행운상회 역삼점", "별빛분식"), g.map { it.place })
        assertEquals(16_000L, g[0].total)
    }

    @Test fun monthlyPaymentsAreOfferedOnce() {
        val m = { months: Long, day: Int -> today.minusMonths(months).withDayOfMonth(day) }
        val list = listOf(
            spend(m(0, 5), 13_500, "넷플릭스", "구독"), spend(m(1, 5), 13_500, "넷플릭스", "구독"), spend(m(2, 6), 13_500, "넷플릭스", "구독"),
            // A café every few days is a habit, not a bill.
            spend(m(0, 1), 4_500, "동네카페"), spend(m(0, 3), 4_500, "동네카페"), spend(m(1, 2), 4_500, "동네카페"),
            spend(m(1, 9), 4_500, "동네카페"), spend(m(2, 2), 4_500, "동네카페"),
            // Amounts too far apart.
            spend(m(0, 10), 30_000, "마트"), spend(m(1, 10), 90_000, "마트"), spend(m(2, 10), 50_000, "마트"),
            // Only two months.
            spend(m(0, 15), 50_000, "헬스장"), spend(m(1, 15), 50_000, "헬스장"),
        )
        val found = RepeatFinder.find(list, emptyList(), today)
        assertEquals(listOf("넷플릭스"), found.map { it.place })
        assertEquals(5, found[0].day)
        assertEquals("구독", found[0].toFixedCost().category)
        assertTrue(RepeatFinder.find(list, emptyList(), today, dismissed = setOf(found[0].key)).isEmpty())
        assertTrue(RepeatFinder.find(list, listOf(FixedCost(name = "넷플릭스", amount = 13_500, day = 5)), today).isEmpty())
    }
}
