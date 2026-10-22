package com.bamnat.core.plaza

/** [COMM-10] One piece of 광장 소식 from `GET /api/plaza/news`. */
data class PlazaNewsItem(
    /** changed (a rule I use has a newer version), adopted (my report), proposal (my 고치자 제안), rate (my 임금표 entry). */
    val kind: String,
    val company: String? = null,
    val dept: String? = null,
    val version: Int? = null,
    val trade: String? = null,
    val atMillis: Long = 0L,
)

/**
 * [COMM-10] 광장 소식: at most one notification a day that bundles what happened to the user's own
 * plaza things. Plain words, no amounts.
 */
object PlazaNews {
    data class Text(val title: String, val lines: List<String>)

    fun line(i: PlazaNewsItem): String? {
        val where = listOfNotNull(i.company, i.dept?.takeIf { it.isNotBlank() }).joinToString(" ")
        return when (i.kind) {
            "changed" -> "$where 규칙이 ${i.version?.let { "v$it" } ?: "새 버전"}으로 바뀌었어요. 눌러서 바꿀지 골라요."
            "adopted" -> "보낸 $where 규칙이 채택됐어요."
            "proposal" -> "$where 규칙에 낸 고치자 제안이 반영됐어요."
            "rate" -> "올린 ${i.trade ?: ""} 임금표가 시세에 들어갔어요.".replace("  ", " ")
            else -> null
        }
    }

    /** The notification for [items], or null when there is nothing to tell. */
    fun text(items: List<PlazaNewsItem>): Text? {
        val lines = items.sortedByDescending { it.atMillis }.mapNotNull(::line).distinct()
        if (lines.isEmpty()) return null
        val title = if (lines.size == 1) "광장 소식" else "광장 소식 ${lines.size}개"
        return Text(title, lines.take(5) + listOfNotNull(if (lines.size > 5) "그 밖에 ${lines.size - 5}개 더 있어요." else null))
    }
}
