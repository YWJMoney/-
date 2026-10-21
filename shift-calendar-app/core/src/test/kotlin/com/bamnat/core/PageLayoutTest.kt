package com.bamnat.core

import com.bamnat.core.layout.PageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageLayoutTest {
    private val parts = listOf("day", "hours", "rest", "memos")

    @Test fun defaultKeepsOrderAndShowsAll() {
        val placed = PageLayout.DEFAULT.arrange(parts) { it }
        assertEquals(parts, placed.map { it.part })
        assertTrue(placed.all { it.shown && !it.isNew })
    }

    @Test fun userOrderAndHidden() {
        val layout = PageLayout.decode("memos,-rest,hours,day")
        val placed = layout.arrange(parts) { it }
        assertEquals(listOf("memos", "rest", "hours", "day"), placed.map { it.part })
        assertFalse(placed.first { it.part == "rest" }.shown)
        assertEquals("memos,-rest,hours,day", layout.encode())
    }

    @Test fun newPartComesInAfterItsNeighbourAndShows() {
        val layout = PageLayout.decode("hours,day,memos")
        val placed = layout.arrange(listOf("day", "hours", "rest", "memos")) { it }
        // Right after hours, the part before it by default.
        assertEquals(listOf("hours", "rest", "day", "memos"), placed.map { it.part })
        val rest = placed.first { it.part == "rest" }
        assertTrue(rest.shown)
        assertTrue(rest.isNew)
    }

    @Test fun goneParts() {
        val placed = PageLayout.decode("old,hours").arrange(listOf("hours", "day")) { it }
        assertEquals(listOf("hours", "day"), placed.map { it.part })
    }

    @Test fun moveWithinBounds() {
        assertEquals(listOf("b", "a", "c"), PageLayout.move(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "b", "c"), PageLayout.move(listOf("a", "b", "c"), 0, -1))
        assertEquals(listOf("a", "c", "b"), PageLayout.move(listOf("a", "b", "c"), 2, -1))
    }

    @Test fun roundTripFromPlaced() {
        val placed = PageLayout.decode("rest,-day").arrange(parts) { it }
        assertEquals(PageLayout.decode("rest,memos,-day,hours"), PageLayout.of(placed) { it })
    }
}
