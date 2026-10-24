package com.bamnat.core.plaza

import java.time.Duration
import java.time.Instant

/**
 * [COMM-13] 광장 운영 규칙 3번 "같은 글 되풀이 금지" on the phone (점검 보고서 하 '광장': the same body three times
 * in a few minutes all went up). Two sends of the same words to one talk inside [WINDOW] are let through (a
 * retry after a bad connection looks the same); a third is held back with [WHY]. The server's own limits stay.
 * Spaces, case and marks do not make a body new. Kept in memory only: nothing about what was written is stored.
 */
object RepeatGuard {
    val WINDOW: Duration = Duration.ofMinutes(10)
    const val MAX_SAME = 2
    const val WHY = "같은 글을 방금 두 번 올렸어요. 같은 글을 되풀이해 올리지 않는 게 광장 규칙이에요. 조금 뒤에 다른 말로 써 주세요"

    data class Sent(val talk: String, val body: String, val at: Instant)

    fun normal(body: String): String = body.lowercase().filter { it.isLetterOrDigit() }

    /** Whether [body] may go to [talk] now, given what this phone sent ([sent]). */
    fun allowed(sent: List<Sent>, talk: String, body: String, now: Instant): Boolean {
        val key = normal(body)
        if (key.isEmpty()) return true
        return sent.count { it.talk == talk && normal(it.body) == key && Duration.between(it.at, now) < WINDOW } < MAX_SAME
    }

    /** [sent] with [body] added, dropping what is older than [WINDOW]. */
    fun record(sent: List<Sent>, talk: String, body: String, now: Instant): List<Sent> =
        sent.filter { Duration.between(it.at, now) < WINDOW } + Sent(talk, body, now)
}
