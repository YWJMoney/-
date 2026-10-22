package com.bamnat.core.schedule

/**
 * [DAILY-10] The long-press menu's shift types, those the user picked most in the last [DAYS] days first.
 * Ties keep the user's own order (근무 유형 화면), so a calendar with no changes looks as before.
 */
object QuickOrder {
    const val DAYS = 60

    fun <T, K> byUse(items: List<T>, key: (T) -> K, used: List<K>): List<T> {
        if (used.isEmpty()) return items
        val counts = used.groupingBy { it }.eachCount()
        return items.sortedByDescending { counts[key(it)] ?: 0 }
    }
}
