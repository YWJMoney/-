package com.bamnat.core.plaza

/** [COMM-10] One piece of 광장 소식 from `GET /api/plaza/news`. */
data class PlazaNewsItem(
    /**
     * changed (a rule I use has a newer version), adopted (my report), proposal (my 고치자 제안), rate (my 임금표 entry);
     * [COMM-11] flag_result, post_result and unmuted.
     */
    val kind: String,
    val company: String? = null,
    val dept: String? = null,
    val version: Int? = null,
    val trade: String? = null,
    val atMillis: Long = 0L,
    /** [COMM-11] For flag_result and post_result: hidden, kept, shown or deleted. */
    val result: String? = null,
    /** [COMM-11] The post a result is about, for 이의 제기. */
    val post: String? = null,
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
            // [COMM-11] What became of a post this phone flagged, of its own hidden post, and the end of 쓰기 막기.
            "flag_result" -> when (i.result) {
                "hidden" -> "신고한 글이 가려졌어요. 고마워요."
                "deleted" -> "신고한 글을 운영자가 지웠어요."
                "kept" -> "신고한 글은 운영자가 확인하고 그대로 두었어요."
                else -> null
            }
            "post_result" -> when (i.result) {
                "hidden" -> "내 글이 신고로 가려졌어요. 잘못 가려졌다면 이의 제기를 할 수 있어요."
                "shown" -> "가려졌던 내 글이 다시 보여요."
                "deleted" -> "운영자가 내 글을 지웠어요."
                else -> null
            }
            "unmuted" -> "글쓰기 제한이 끝났어요. 다시 글을 쓸 수 있어요."
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

/**
 * [SRV-10] 밤낮 소식: the plaza news, new answers to the user's questions and notices that began, from
 * `GET /api/digest`, in one notification a day. [plaza] is empty when 광장 소식 is off.
 */
object BamnatNews {
    data class Text(val title: String, val lines: List<String>, val onlyAnswers: Boolean)

    fun text(plaza: List<PlazaNewsItem>, answers: Int, notices: List<String>, changes: List<String> = emptyList()): Text? {
        val lines = buildList {
            if (answers > 0) add(if (answers == 1) "문의에 답이 왔어요." else "문의에 답이 ${answers}개 왔어요.")
            addAll(changes)
            addAll(plaza.sortedByDescending { it.atMillis }.mapNotNull(PlazaNews::line).distinct())
            notices.forEach { add("공지: $it") }
        }
        if (lines.isEmpty()) return null
        val title = if (lines.size == 1) "밤낮 소식" else "밤낮 소식 ${lines.size}개"
        return Text(title, lines.take(5) + listOfNotNull(if (lines.size > 5) "그 밖에 ${lines.size - 5}개 더 있어요." else null),
            onlyAnswers = answers > 0 && lines.size == 1)
    }
}
