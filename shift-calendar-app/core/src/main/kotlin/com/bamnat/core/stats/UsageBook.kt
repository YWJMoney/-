package com.bamnat.core.stats

/**
 * [STAT-01] What "사용 통계 보내기" keeps on the phone until it is sent: per day, how often each
 * [UsageEvent] happened, and per event the days it happened in the last weeks (for "used in the last
 * 28 days"). No times of day, no details, no phone id. Days are epoch days in the phone's time zone.
 */
data class UsageBook(
    /** Days not sent yet: day → event key → count. */
    val days: Map<Long, Map<String, Int>> = emptyMap(),
    /** Event key → the days it happened, kept for [RECENT_DAYS] before the oldest unsent day (sent or not). */
    val seen: Map<String, Set<Long>> = emptyMap(),
) {
    fun record(event: UsageEvent, day: Long): UsageBook {
        val today = days[day].orEmpty()
        val n = ((today[event.key] ?: 0) + 1).coerceAtMost(MAX_COUNT)
        return copy(
            days = (days + (day to today + (event.key to n))).filterKeys { it > day - KEEP_DAYS },
            seen = (seen + (event.key to (seen[event.key].orEmpty() + day))).mapValues { (_, d) -> d.filterTo(HashSet()) { it > day - RECENT_DAYS - MAX_DAYS } },
        )
    }

    /** The finished days (before [today]) to send, oldest first, at most [MAX_DAYS] of the newest. */
    fun due(today: Long): List<Long> = days.keys.filter { it < today && it >= today - MAX_DAYS }.sorted()

    /**
     * The body of `POST /api/events` for [due] days, or null when there is nothing to send. [app] is the
     * versionName ("1.22.0"). Each day carries the events that happened in the 28 days up to it.
     */
    fun bundle(today: Long, app: String, isoDay: (Long) -> String): String? {
        val send = due(today)
        if (send.isEmpty()) return null
        val sb = StringBuilder("{\"v\":1,\"app\":\"").append(app.filter { it.isDigit() || it == '.' }).append("\",\"days\":[")
        send.forEachIndexed { i, day ->
            if (i > 0) sb.append(',')
            sb.append("{\"day\":\"").append(isoDay(day)).append("\",\"events\":{")
            days[day].orEmpty().entries.sortedBy { it.key }.forEachIndexed { j, (k, n) ->
                if (j > 0) sb.append(',')
                sb.append('"').append(k).append("\":").append(n)
            }
            sb.append("},\"recent\":[")
            sb.append(recent(day).joinToString(",") { "\"$it\"" })
            sb.append("]}")
        }
        return sb.append("]}").toString()
    }

    /** Events done at least once in the [RECENT_DAYS] days ending on [day]. */
    fun recent(day: Long): List<String> =
        seen.filterValues { d -> d.any { it in (day - RECENT_DAYS + 1)..day } }.keys.sorted()

    /** After a send: the sent days go, older ones too. */
    fun sent(today: Long): UsageBook = copy(days = days.filterKeys { it >= today })

    fun encode(): String = buildString {
        days.toSortedMap().forEach { (day, events) ->
            append("d\t").append(day).append('\t').append(events.entries.joinToString(",") { "${it.key}=${it.value}" }).append('\n')
        }
        seen.toSortedMap().forEach { (event, d) -> append("s\t").append(event).append('\t').append(d.sorted().joinToString(",")).append('\n') }
    }

    companion object {
        /** "used in the last 28 days" (the 지표 screen's share). */
        const val RECENT_DAYS = 28L
        /** The server takes up to 7 days at once; older unsent days are dropped. */
        const val MAX_DAYS = 7L
        const val KEEP_DAYS = 8L
        /** The server refuses more than 1000 of one event in a day. */
        const val MAX_COUNT = 1000

        fun decode(text: String?): UsageBook {
            if (text.isNullOrBlank()) return UsageBook()
            val days = HashMap<Long, Map<String, Int>>()
            val seen = HashMap<String, Set<Long>>()
            for (line in text.lineSequence()) {
                val p = line.split('\t')
                if (p.size != 3) continue
                when (p[0]) {
                    "d" -> p[1].toLongOrNull()?.let { day ->
                        days[day] = p[2].split(',').mapNotNull { kv ->
                            val (k, v) = kv.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                            if (UsageEvent.of(k) == null) null else v.toIntOrNull()?.let { k to it.coerceIn(0, MAX_COUNT) }
                        }.toMap()
                    }
                    "s" -> if (UsageEvent.of(p[1]) != null) seen[p[1]] = p[2].split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }
                }
            }
            return UsageBook(days, seen)
        }
    }
}
