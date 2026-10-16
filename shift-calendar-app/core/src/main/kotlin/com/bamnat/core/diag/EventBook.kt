package com.bamnat.core.diag

/**
 * One line of the on-device event log [MEAS-03, MEAS-04].
 * [detail] is short free text such as "08:00 D" or "exact=false"; never memo text or names.
 */
data class DiagEvent(val atMillis: Long, val name: String, val detail: String = "")

/** Event names. Kept in one place so the diagnostics screen and later analytics agree. */
object DiagEvents {
    const val APP_OPEN = "app_open"
    const val ALARM_SCHEDULED = "alarm_scheduled"
    const val ALARM_CLEARED = "alarm_cleared"
    const val ALARM_FIRED = "alarm_fired"
    const val ALARM_LATE_SKIPPED = "alarm_late_skipped"
    const val ALARM_FALLBACK = "alarm_fallback"
    const val ALARM_DISMISSED = "alarm_dismissed"
    const val ALARM_SNOOZED = "alarm_snoozed"
    const val SNOOZE_FIRED = "snooze_fired"
    const val SYSTEM_EVENT = "system_event"
    const val BACKUP_EXPORT = "backup_export"
    const val BACKUP_RESTORE = "backup_restore"
    const val ICS_EXPORT = "ics_export"
    const val SCREEN = "screen"
}

/** Text codec, trimming and summaries for the event log. No Android code, so it is unit tested. */
object EventBook {
    /** The log is cut back to [KEEP] lines once it grows past [MAX]. */
    const val MAX = 1200
    const val KEEP = 1000

    fun encode(e: DiagEvent): String =
        "${e.atMillis}\t${clean(e.name)}\t${clean(e.detail)}"

    fun decode(line: String): DiagEvent? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        if (parts[1].isEmpty()) return null
        return DiagEvent(at, parts[1], parts.getOrElse(2) { "" })
    }

    fun decodeAll(text: String): List<DiagEvent> = text.lineSequence().mapNotNull(::decode).toList()

    /** The newest [KEEP] lines when the log is over [MAX], otherwise null (nothing to do). */
    fun trimmed(lines: List<String>): List<String>? =
        if (lines.size > MAX) lines.takeLast(KEEP) else null

    /** How often each event happened since [sinceMillis], most frequent first. */
    fun counts(events: List<DiagEvent>, sinceMillis: Long): List<Pair<String, Int>> =
        events.asSequence()
            .filter { it.atMillis >= sinceMillis }
            .groupingBy { if (it.name == DiagEvents.SCREEN) "screen:${it.detail}" else it.name }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /**
     * Alarms that were planned but have no fired, fallback or late record within [graceMillis]
     * of their time. Each one is a possible missed alarm worth a look [OPS-19].
     */
    fun possiblyMissed(events: List<DiagEvent>, nowMillis: Long, graceMillis: Long = 10 * 60_000L): List<Long> {
        val rang = events.filter {
            it.name == DiagEvents.ALARM_FIRED || it.name == DiagEvents.ALARM_FALLBACK || it.name == DiagEvents.ALARM_LATE_SKIPPED
        }.mapNotNull { triggerOf(it.detail) }.toSet()
        val plans = events.filter { it.name == DiagEvents.ALARM_SCHEDULED }
        return plans.mapNotNull { triggerOf(it.detail) }.distinct().sorted().filter { t ->
            if (t + graceMillis >= nowMillis || t in rang) return@filter false
            val lastPlanAt = plans.last { triggerOf(it.detail) == t }.atMillis
            // A plan replaced or cleared before its time (schedule edited, alarms off) is not missed.
            val superseded = events.any { e ->
                e.atMillis > lastPlanAt && e.atMillis < t &&
                    (e.name == DiagEvents.ALARM_CLEARED || (e.name == DiagEvents.ALARM_SCHEDULED && triggerOf(e.detail) != t))
            }
            !superseded
        }
    }

    /** Alarm events carry "at=<epoch millis>" so plans and deliveries can be matched. */
    fun triggerDetail(triggerAtMillis: Long, extra: String = ""): String =
        if (extra.isEmpty()) "at=$triggerAtMillis" else "at=$triggerAtMillis $extra"

    fun triggerOf(detail: String): Long? =
        detail.split(' ').firstOrNull { it.startsWith("at=") }?.removePrefix("at=")?.toLongOrNull()

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(200)
}
