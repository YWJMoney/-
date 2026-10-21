package com.bamnat.core.layout

/**
 * [CUST-01] The user's own order and on/off of a page's parts (달력 아래 카드, 통계 카드…), kept as one line
 * ("hours,-rest,memos"). A part the stored line does not know yet, one a later version added, goes in at its
 * own place and is shown, and is said to be new, so an update never hides a new part.
 */
data class PageLayout(val order: List<String>, val hidden: Set<String>) {
    /** [parts] (each page's own default order) in the user's order, each with whether it shows. */
    fun <T> arrange(parts: List<T>, id: (T) -> String): List<Placed<T>> {
        val byId = parts.associateBy(id)
        val known = order.filter { it in byId }
        val result = known.toMutableList()
        // New parts go right after the part before them in the default order, or first.
        parts.map(id).forEachIndexed { i, pid ->
            if (pid in result) return@forEachIndexed
            val before = parts.take(i).map(id).lastOrNull { it in result }
            result.add(before?.let { result.indexOf(it) + 1 } ?: 0, pid)
        }
        return result.map { pid -> Placed(byId.getValue(pid), pid !in hidden, isNew = order.isNotEmpty() && pid !in order) }
    }

    fun encode(): String = order.joinToString(",") { if (it in hidden) "-$it" else it }

    data class Placed<T>(val part: T, val shown: Boolean, val isNew: Boolean)

    companion object {
        val DEFAULT = PageLayout(emptyList(), emptySet())

        fun decode(text: String?): PageLayout {
            if (text.isNullOrBlank()) return DEFAULT
            val bits = text.split(",").map { it.trim() }.filter { it.isNotEmpty() && it != "-" }
            return PageLayout(bits.map { it.removePrefix("-") }.distinct(), bits.filter { it.startsWith("-") }.map { it.removePrefix("-") }.toSet())
        }

        /** The layout as [placed] now stands, after a move or a switch in the editor. */
        fun <T> of(placed: List<Placed<T>>, id: (T) -> String): PageLayout =
            PageLayout(placed.map { id(it.part) }, placed.filterNot { it.shown }.map { id(it.part) }.toSet())

        /** [list] with the item at [from] moved by [by] places (-1 up, +1 down), within the list. */
        fun <T> move(list: List<T>, from: Int, by: Int): List<T> {
            val to = (from + by).coerceIn(0, list.lastIndex)
            if (from !in list.indices || to == from) return list
            return list.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
}
