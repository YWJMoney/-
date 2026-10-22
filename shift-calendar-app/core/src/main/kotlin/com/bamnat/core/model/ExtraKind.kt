package com.bamnat.core.model

import java.time.LocalTime

/** [EXTRA-01] Where a kind of extra work sits on the clock. */
sealed interface ExtraTiming {
    /**
     * Fixed clock times, e.g. 특근 09:00–18:00 or 야간 대근 19:00–07:00. An end at or before the
     * start runs into the next day.
     */
    data class Band(
        val start: LocalTime,
        val end: LocalTime,
        val breakMinutes: Int = 0,
        val breakStart: LocalTime? = null,
    ) : ExtraTiming {
        val minutes: Int get() = WorkTime.workMinutes(start, end, breakMinutes)
    }

    /** Straight after the day's shift, e.g. 잔업 2시간. */
    data class AfterShift(val minutes: Int) : ExtraTiming

    /** Straight before the day's shift, e.g. 조출 1시간. */
    data class BeforeShift(val minutes: Int) : ExtraTiming

    /**
     * A whole shift of one of the user's shift types, worked for someone else (대근): the times
     * come from the shift picked each time. [shiftTypeIds] are the shifts it can be, every shift
     * when empty, so one 대근 holds 모닝 대근 and 나이트 대근 alike [EXTRA-07].
     */
    data class AsShift(val shiftTypeIds: Set<Long> = emptySet()) : ExtraTiming {
        constructor(shiftTypeId: Long?) : this(setOfNotNull(shiftTypeId))

        /** The one shift this kind is for, when it is for exactly one (a 야간 대근 of its own). */
        val shiftTypeId: Long? get() = shiftTypeIds.singleOrNull()

        fun covers(id: Long): Boolean = shiftTypeIds.isEmpty() || id in shiftTypeIds

        /** The shifts of [timed] it can be; all of them when none of its own is left (a deleted shift). */
        fun shifts(timed: List<ShiftType>): List<ShiftType> = timed.filter { covers(it.id) }.ifEmpty { timed }
    }
}

/** [EXTRA-02] Work of a kind on a day off: by the company's rest-day rule, or always as 휴일근로 (단체협약 등). */
enum class OffDayTreatment(val label: String) {
    BY_DAY("회사 규칙대로"),
    HOLIDAY("휴일근로로 대우"),
}

/**
 * [EXTRA-01] A kind of extra work as this person's company has it: 잔업, 조출, 특근, 주간·야간 대근 …
 * Ids 1~3 are the built-in 잔업·특근·대근 (renamable, can be hidden); kinds the user adds start at
 * [FIRST_CUSTOM_ID]. How a kind is paid is a company rule kept with the pay rules, by this id.
 */
data class ExtraKind(
    val id: Long,
    val name: String,
    /** One or two characters for calendar cells. */
    val short: String = name.take(1),
    val colorArgb: Long,
    val timing: ExtraTiming,
    val offDay: OffDayTreatment = OffDayTreatment.BY_DAY,
    /** Counts as a shift for allowances paid per shift or per night shift (식대·나이트 수당). */
    val countsAsShift: Boolean = timing is ExtraTiming.Band || timing is ExtraTiming.AsShift,
    val hidden: Boolean = false,
) {
    val isBuiltIn: Boolean get() = id < FIRST_CUSTOM_ID
    val mark: String get() = short.trim().ifEmpty { name.take(1) }

    companion object {
        const val OVERTIME_ID = 1L
        const val SPECIAL_ID = 2L
        const val COVER_ID = 3L
        const val FIRST_CUSTOM_ID = 101L
        const val MAX_NAME = 12
    }
}

/**
 * [EXTRA-01] One piece of extra work on one date; a day can hold several, e.g. 잔업 and a 야간 대근.
 * Work with clock times keeps them (a 대근 that ended at 03:00 says so); work counted from the shift
 * (잔업 after it, 조출 before it) keeps only its length.
 */
data class ExtraEntry(
    val kindId: Long,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val breakMinutes: Int = 0,
    /** When the break starts; null takes it out evenly (an estimate of night minutes). */
    val breakStart: LocalTime? = null,
    /** Length when there are no clock times: next to the shift, or an old record of hours only. */
    val minutes: Int = 0,
    /** The shift type this work covered (대근 for a 나이트), for rules and allowances per shift [PAYX-11]. */
    val coveredShiftId: Long? = null,
    /** [EXTRA-05] Moved from an older record that may hold hours nobody meant; asks the user once. */
    val needsReview: Boolean = false,
    /** [PAYX-18] Paid in time off instead of money (보상휴가, 근로기준법 제57조). */
    val compLeave: Boolean = false,
) {
    val hasTimes: Boolean get() = start != null && end != null

    /** Paid minutes as entered: the clock span less the break, or [minutes]. */
    val paidMinutes: Int
        get() = if (hasTimes) WorkTime.workMinutes(start!!, end!!, breakMinutes) else minutes.coerceAtLeast(0)
}

/**
 * [EXTRA-01] The kinds this person uses, in their order. Stored as text: "v2" then one kind per
 * line; the older "kind|name|hidden|minutes" lines of [PAYX-09] still read.
 */
data class ExtraKinds(val kinds: List<ExtraKind> = DEFAULTS) {
    fun byId(id: Long): ExtraKind? = kinds.firstOrNull { it.id == id }

    /** The kind for [id]; a kind deleted since still shows, as 추가 근무. */
    fun of(id: Long): ExtraKind = byId(id) ?: DEFAULTS.firstOrNull { it.id == id }
        ?: ExtraKind(id, "추가 근무", "추", 0xFF78909C, ExtraTiming.AfterShift(DEFAULT_AFTER_MINUTES))

    fun name(id: Long): String = of(id).name

    /** Kinds offered when marking a day; kinds already on the day show anyway so they can be cleared. */
    fun visible(current: Collection<Long> = emptyList()): List<ExtraKind> = kinds.filter { !it.hidden || it.id in current }

    /** Names by id, for pay lines. */
    val names: Map<Long, String> get() = kinds.associate { it.id to it.name }

    fun nextId(): Long = maxOf(ExtraKind.FIRST_CUSTOM_ID, (kinds.maxOfOrNull { it.id } ?: 0) + 1)

    fun put(kind: ExtraKind): ExtraKinds =
        if (byId(kind.id) != null) copy(kinds = kinds.map { if (it.id == kind.id) kind else it }) else copy(kinds = kinds + kind)

    fun encode(): String = (listOf(V2) + kinds.map { k ->
        listOf(
            k.id.toString(), clean(k.name), clean(k.short), k.colorArgb.toString(), timing(k.timing),
            k.offDay.name, if (k.countsAsShift) "1" else "0", if (k.hidden) "1" else "0",
        ).joinToString("|")
    }).joinToString("\n")

    companion object {
        private const val V2 = "v2"
        const val DEFAULT_AFTER_MINUTES = 2 * 60

        val DEFAULTS: List<ExtraKind> = listOf(
            ExtraKind(ExtraKind.OVERTIME_ID, "잔업", "잔", 0xFFE8900C, ExtraTiming.AfterShift(DEFAULT_AFTER_MINUTES)),
            ExtraKind(ExtraKind.SPECIAL_ID, "특근", "특", 0xFFE03131,
                ExtraTiming.Band(LocalTime.of(9, 0), LocalTime.of(18, 0), 60, LocalTime.of(12, 0))),
            ExtraKind(ExtraKind.COVER_ID, "대근", "대", 0xFF0E9F6E, ExtraTiming.AsShift()),
        )

        fun decode(text: String?): ExtraKinds {
            if (text.isNullOrBlank()) return ExtraKinds()
            val lines = text.lines()
            if (lines.first().trim() != V2) return decodeV1(lines)
            val kinds = lines.drop(1).mapNotNull { line ->
                val f = line.split('|')
                val id = f.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val name = f.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.take(ExtraKind.MAX_NAME) ?: return@mapNotNull null
                val timing = f.getOrNull(4)?.let(::timingFrom) ?: return@mapNotNull null
                ExtraKind(
                    id = id, name = name,
                    short = f.getOrNull(2)?.trim()?.take(2)?.ifEmpty { null } ?: name.take(1),
                    colorArgb = f.getOrNull(3)?.toLongOrNull() ?: 0xFF78909C,
                    timing = timing,
                    offDay = OffDayTreatment.entries.firstOrNull { it.name == f.getOrNull(5) } ?: OffDayTreatment.BY_DAY,
                    countsAsShift = f.getOrNull(6)?.let { it == "1" } ?: (timing is ExtraTiming.Band || timing is ExtraTiming.AsShift),
                    hidden = f.getOrNull(7) == "1",
                )
            }.distinctBy { it.id }
            return if (kinds.isEmpty()) ExtraKinds() else ExtraKinds(kinds)
        }

        /**
         * [PAYX-09] The first format: names, hidden kinds and one length per kind. 잔업 keeps its length
         * after the shift; a 특근 length becomes a band from 09:00; 대근 asks for the shift it covered.
         */
        private fun decodeV1(lines: List<String>): ExtraKinds {
            val old = listOf("OVERTIME", "SPECIAL", "COVER")
            val kinds = DEFAULTS.map { k ->
                val f = lines.map { it.split('|') }.firstOrNull { it.getOrNull(0) == old[(k.id - 1).toInt()] } ?: return@map k
                val name = f.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.take(ExtraKind.MAX_NAME)
                val minutes = f.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
                val timing = when {
                    minutes == null -> k.timing
                    k.id == ExtraKind.OVERTIME_ID -> ExtraTiming.AfterShift(minutes)
                    k.id == ExtraKind.SPECIAL_ID -> bandFrom9(minutes)
                    else -> k.timing
                }
                k.copy(name = name ?: k.name, short = (name ?: k.name).take(1), timing = timing, hidden = f.getOrNull(2) == "1")
            }
            return ExtraKinds(kinds)
        }

        /** A band of [minutes] work from 09:00, with an hour's break at 12:00 for 8 hours or more. */
        fun bandFrom9(minutes: Int): ExtraTiming.Band {
            val m = minutes.coerceIn(30, 24 * 60 - 60)
            val brk = if (m >= 8 * 60) 60 else 0
            val start = LocalTime.of(9, 0)
            return ExtraTiming.Band(start, start.plusMinutes((m + brk).toLong()), brk, if (brk > 0) LocalTime.of(12, 0) else null)
        }

        private fun clean(s: String) = s.replace('|', ' ').replace('\n', ' ').trim()

        private fun timing(t: ExtraTiming): String = when (t) {
            is ExtraTiming.Band -> listOf("B", t.start.toString(), t.end.toString(), t.breakMinutes.toString(), t.breakStart?.toString() ?: "-").joinToString(",")
            is ExtraTiming.AfterShift -> "A,${t.minutes}"
            is ExtraTiming.BeforeShift -> "P,${t.minutes}"
            is ExtraTiming.AsShift -> "S," + t.shiftTypeIds.sorted().joinToString(";").ifEmpty { "-" }
        }

        private fun timingFrom(s: String): ExtraTiming? {
            val f = s.split(',')
            fun time(i: Int) = f.getOrNull(i)?.takeIf { it != "-" }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            return when (f.getOrNull(0)) {
                "B" -> {
                    val start = time(1) ?: return null
                    val end = time(2) ?: return null
                    ExtraTiming.Band(start, end, f.getOrNull(3)?.toIntOrNull()?.coerceIn(0, 12 * 60) ?: 0, time(4))
                }
                "A" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { ExtraTiming.AfterShift(it.coerceAtMost(24 * 60)) }
                "P" -> f.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }?.let { ExtraTiming.BeforeShift(it.coerceAtMost(24 * 60)) }
                // One id, several joined by ';' [EXTRA-07], or '-' for every shift.
                "S" -> ExtraTiming.AsShift(f.getOrNull(1)?.split(';')?.mapNotNull { it.toLongOrNull() }?.toSet().orEmpty())
                else -> null
            }
        }
    }
}
