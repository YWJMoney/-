package com.bamnat.core

import com.bamnat.core.imports.MonthFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ROST-08] "이 순서 반복": the order typed from the 1st carried to the month's end. */
class MonthFillTest {
    @Test fun repeatsTheOrderTyped() {
        val typed = "DDEENNOO".mapIndexed { i, c -> i + 1 to c }.toMap()
        val rest = MonthFill.repeat(typed, 9, 31)!!
        assertEquals((9..31).toList(), rest.keys.sorted())
        assertEquals("DDEENNOODDEENNOODDEENNO", (9..31).joinToString("") { rest.getValue(it).toString() })
    }

    @Test fun startsWhereTheTypingStarted() {
        // Typed from the 5th: the order counts from there, and a day typed later stays.
        val typed = mapOf(5 to 'D', 6 to 'N', 7 to 'O', 12 to 'X')
        val rest = MonthFill.repeat(typed, 8, 14)!!
        assertEquals(mapOf(8 to 'D', 9 to 'N', 10 to 'O', 11 to 'D', 13 to 'O', 14 to 'D'), rest)
    }

    @Test fun nothingToRepeat() {
        assertNull(MonthFill.repeat(mapOf(1 to 'D'), 2, 30))
        assertNull(MonthFill.repeat(emptyMap<Int, Char>(), 1, 30))
        assertNull(MonthFill.repeat(mapOf(1 to 'D', 2 to 'E'), 31, 30))
    }
}
