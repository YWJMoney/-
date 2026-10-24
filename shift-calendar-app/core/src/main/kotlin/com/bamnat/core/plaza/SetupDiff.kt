package com.bamnat.core.plaza

/**
 * [COMM-14][ADMUX-09] Telling apart two rule sets of one company and department (점검 보고서 중-11: two cards
 * of "(주)예시정밀" read the same down to the summary; the 대근 10%→12% was only inside). Each gets a name
 * ([label]: 채택 v2, or 제보 1·2 in the order they came) and the lines only it has ([diff]).
 */
object SetupDiff {
    /** One rule set as the plaza shows it: [lines] are (label, text), e.g. ("대근", "기본급의 12%"). */
    data class Item(val id: String, val dept: String, val adopted: Boolean, val version: Int?, val at: Long,
        val lines: List<Pair<String, String>> = emptyList())

    /** Names only where a company has more than one set for the same department; the rest need none. */
    fun labels(items: List<Item>): Map<String, String> {
        val out = HashMap<String, String>()
        items.groupBy { it.dept }.values.filter { it.size > 1 }.forEach { same ->
            var n = 0
            same.sortedBy { it.at }.forEach { i ->
                out[i.id] = if (i.adopted) "채택${i.version?.let { " v$it" } ?: ""}" else "제보 ${++n}"
            }
        }
        return out
    }

    /**
     * "다른 점: 대근 기본급의 12%" for each set whose department has another: its lines the others do not have,
     * at most [max]. Null where nothing differs in the lines (the times or the order differ, said elsewhere).
     */
    fun diff(items: List<Item>, max: Int = 2): Map<String, String> {
        val out = HashMap<String, String>()
        items.groupBy { it.dept }.values.filter { it.size > 1 }.forEach { same ->
            same.forEach { i ->
                val others = same.filter { it !== i }.map { it.lines.toSet() }
                val own = i.lines.filter { line -> others.all { line !in it } }
                if (own.isNotEmpty()) out[i.id] = "다른 점: " + own.take(max).joinToString(" · ") { (l, t) -> "$l $t" } +
                    if (own.size > max) " 외 ${own.size - max}개" else ""
            }
        }
        return out
    }

    /**
     * [COMM-14] Why a set whose points are all in still waits (점검 보고서 중-12: "3/3점" beside "확인 중"), in the
     * words the app and the admin app both use. Null while points are still missing or it is adopted.
     */
    fun waitReason(points: Int, threshold: Int, adopted: Boolean, agree: Int, agreeNeeded: Int = 75, rejectedBefore: Boolean = false): String? = when {
        adopted || points < threshold -> null
        rejectedBefore -> "전에 거절된 설정이라 개발자 확인 대기"
        agree < agreeNeeded -> "달라요가 많아 개발자 확인 대기"
        else -> "개발자 확인 대기"
    }
}
