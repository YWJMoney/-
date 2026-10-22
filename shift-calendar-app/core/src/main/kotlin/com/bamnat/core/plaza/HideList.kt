package com.bamnat.core.plaza

/**
 * [COMM-09] 이 사람 글 안 보기. The server gives each post a sealed mark of its writer ([mark], 64
 * characters); the phone keeps the marks the user chose and sends them back in X-Hide, so the server
 * leaves those writers out. The list stays on the phone (and in its backup), never on the server.
 */
data class HiddenWriter(val mark: String, val label: String, val atMillis: Long)

data class HideList(val writers: List<HiddenWriter> = emptyList()) {
    /** Adds a writer at the front; the oldest go past [MAX]. An already hidden writer is moved, not doubled. */
    fun hide(mark: String, label: String, at: Long): HideList {
        if (!MARK.matches(mark)) return this
        val clean = label.replace(Regex("[\\t\\n\\r]"), " ").trim().take(20).ifEmpty { "참여자" }
        return HideList((listOf(HiddenWriter(mark, clean, at)) + writers.filter { it.mark != mark }).take(MAX))
    }

    fun show(mark: String): HideList = HideList(writers.filter { it.mark != mark })

    /** The X-Hide header value, or null when nobody is hidden. */
    fun header(): String? = writers.takeIf { it.isNotEmpty() }?.joinToString(",") { it.mark }

    fun encode(): String = writers.joinToString("\n") { "${it.mark}\t${it.atMillis}\t${it.label}" }

    companion object {
        /** The server reads at most 50 marks. */
        const val MAX = 50
        val MARK = Regex("^[A-Za-z0-9_-]{64}$")

        fun decode(text: String?): HideList = HideList(
            text.orEmpty().lineSequence().mapNotNull { line ->
                val p = line.split('\t', limit = 3)
                if (p.size != 3 || !MARK.matches(p[0])) null else HiddenWriter(p[0], p[2], p[1].toLongOrNull() ?: 0L)
            }.distinctBy { it.mark }.take(MAX).toList(),
        )
    }
}

/** [COMM-08] Version of the plaza's community rules; a new version asks again before the next post. */
object PlazaRules {
    const val VERSION = 1
    const val URL = "https://ywjmoney.github.io/-/community/"

    /** The rules in short, as the sheet before the first post shows them. */
    val LINES = listOf(
        "이름, 전화번호, 사는 곳, 사진처럼 누군지 알 수 있는 것은 적지 않아요. 내 것도 남의 것도요.",
        "회사나 사람을 욕하거나 괴롭히는 글, 차별하는 글은 쓰지 않아요.",
        "광고, 같은 글 되풀이, 거짓 정보는 쓰지 않아요.",
        "금액은 임금표에만 적어요. 글과 답글에는 비율이나 방식으로 적어요.",
        "신고가 모이면 글이 먼저 가려지고, 운영자가 확인해 지우거나 다시 보여요(정보통신망법 제44조의2).",
        "규칙을 어기면 30일 동안 글쓰기가 막힐 수 있어요. 마음에 들지 않는 사람의 글은 ‘이 사람 글 안 보기’로 가릴 수 있어요.",
    )

    fun agreed(version: Int): Boolean = version >= VERSION
}
