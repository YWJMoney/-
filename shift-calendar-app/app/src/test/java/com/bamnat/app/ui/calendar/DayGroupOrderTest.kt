package com.bamnat.app.ui.calendar

import com.bamnat.app.data.decodeGroupOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/** [DAY-04] The user's order of the day's groups never loses or doubles a group. */
class DayGroupOrderTest {
    private val shift = listOf("work", "extra", "leave", "alarm", "memo", "money", "body", "record")

    @Test fun noOrderIsTheWorkStylesOwn() {
        assertEquals(shift, DayGroupKey.order(emptyList(), moneyFirst = false))
        assertEquals("money", DayGroupKey.order(emptyList(), moneyFirst = true).first())
    }

    @Test fun theUsersGroupsComeFirstAndTheRestKeepTheirPlace() {
        assertEquals(
            listOf("memo", "alarm", "work", "extra", "leave", "money", "body", "record"),
            DayGroupKey.order(listOf("memo", "alarm"), moneyFirst = false),
        )
    }

    @Test fun unknownAndRepeatedKeysAreDropped() {
        val order = DayGroupKey.order(listOf("memo", "gone", "memo", "work"), moneyFirst = false)
        assertEquals(shift.size, order.size)
        assertEquals(listOf("memo", "work"), order.take(2))
    }

    @Test fun aGroupMovesOnePlaceAndStopsAtTheEnds() {
        assertEquals(listOf("extra", "work", "leave"), listOf("work", "extra", "leave").moved(1, -1))
        assertEquals(listOf("work", "leave", "extra"), listOf("work", "extra", "leave").moved(1, 1))
        assertEquals(listOf("work", "extra"), listOf("work", "extra").moved(0, -1))
        assertEquals(listOf("work", "extra"), listOf("work", "extra").moved(1, 1))
    }

    @Test fun aSavedOrderReadsBack() {
        assertEquals(listOf("memo", "work"), decodeGroupOrder("memo, work,,memo"))
        assertEquals(emptyList<String>(), decodeGroupOrder(""))
    }
}
