package com.bamnat.core.profile

import java.time.Instant
import com.bamnat.core.time.Today

/**
 * [EASY-03] Where a fact's value came from, strongest first: the user typed it ([TYPED]), a company setup
 * they took in gave it ([SHARED]), the app read it off a photo or a file ([INFERRED]), or it is the
 * app's own starting value ([DEFAULT]). A weaker source never writes over a stronger one; it asks instead.
 */
enum class Source(val rank: Int, val label: String) {
    TYPED(3, "직접 입력"),
    SHARED(2, "회사 공유 설정"),
    INFERRED(1, "추정"),
    DEFAULT(0, "기본값"),
}

/**
 * [EASY-03] What the profile keeps about one fact. The value itself stays in the store that always held
 * it (settings, pay setup, schedule); only this is kept beside it. [fingerprint] is the value's
 * [Profile.fingerprint] when [source] wrote it, so a value changed on any other screen reads as typed.
 * [why] names where it came from in a few words ("명세서 사진", "회사 설정 ○○") without the value.
 */
data class FactMeta(
    val source: Source,
    val confidence: Double = 1.0,
    val checkedAt: Instant? = null,
    val fingerprint: String? = null,
    val why: String? = null,
)

/** [EASY-03] A fact as a screen reads it: the value (null when not known), its source and how sure the app is. */
data class Fact<T>(
    val key: FactKey,
    val value: T?,
    val source: Source,
    val confidence: Double,
    val checkedAt: Instant?,
    val why: String?,
) {
    val known: Boolean get() = value != null
    /** Shown with a '추정' badge: read off something, or the app's own guess for a value that matters. */
    val estimated: Boolean get() = known && (source == Source.INFERRED || (source == Source.DEFAULT && key.money))
}

/**
 * [EASY-03] The rules of the fact store, kept apart from any one store so they are tested once: how a stored
 * value and its meta read as a [Fact], and whether a new value from [incoming] may replace what is there.
 */
object Profile {
    /** Below this an inference is only offered as a question, never written. */
    const val MIN_CONFIDENCE = 0.5

    /**
     * [EASY-09] The [FactMeta.why] of a value 모두 처음부터 asks again: kept as it is meanwhile, read as a
     * guess, and asked one at a time until the user says it again (or changes it anywhere).
     */
    const val RECHECK = "모두 처음부터"

    /**
     * A short stable digest of a value as its store writes it, for [FactMeta.fingerprint]. Not a secret and
     * not reversible on purpose: the meta file never holds an amount or a name.
     */
    fun fingerprint(value: Any?): String? {
        if (value == null) return null
        val text = value.toString()
        var h = 1125899906842597L
        for (c in text) h = 31 * h + c.code
        return java.lang.Long.toHexString(h)
    }

    /**
     * The fact as it stands. A value with no meta is the user's own when it differs from the store's
     * default, else the default; a value that changed since [FactMeta.source] wrote it was typed somewhere.
     * [estimatedInStore] is the store's own '추정' mark (the pay setup's estimatedFields) for values from
     * before the profile, read as a half-sure guess.
     */
    fun <T> read(key: FactKey, value: T?, meta: FactMeta?, default: T? = null, estimatedInStore: Boolean = false): Fact<T> {
        if (value == null) return Fact(key, null, Source.DEFAULT, 0.0, meta?.checkedAt, null)
        val now = fingerprint(value)
        val m = when {
            meta != null && meta.fingerprint == now -> meta
            meta != null -> FactMeta(Source.TYPED, 1.0, meta.checkedAt)
            estimatedInStore -> FactMeta(Source.INFERRED, MIN_CONFIDENCE)
            value == default -> FactMeta(Source.DEFAULT, 1.0)
            else -> FactMeta(Source.TYPED, 1.0)
        }
        return Fact(key, value, m.source, m.confidence, m.checkedAt, m.why)
    }

    /**
     * Whether a value from [incoming] (at [confidence]) may replace [current]. A stronger source always may,
     * the same source may when it is a typed answer or an inference at least as sure, a weaker one never:
     * the app asks "바꿀까요?" instead ([Decision.ASK]).
     */
    fun decide(current: Fact<*>, incoming: Source, confidence: Double = 1.0): Decision = when {
        incoming == Source.TYPED -> Decision.WRITE
        incoming == Source.INFERRED && confidence < MIN_CONFIDENCE -> if (current.known) Decision.KEEP else Decision.ASK
        !current.known -> Decision.WRITE
        incoming.rank > current.source.rank -> Decision.WRITE
        incoming.rank < current.source.rank -> Decision.ASK
        incoming == Source.INFERRED -> if (confidence >= current.confidence) Decision.WRITE else Decision.KEEP
        else -> Decision.WRITE
    }

    enum class Decision { WRITE, ASK, KEEP }

    /** The meta to keep after [source] wrote [value]. */
    fun written(value: Any?, source: Source, confidence: Double = 1.0, at: Instant = Today.instant(), why: String? = null) =
        FactMeta(source, confidence.coerceIn(0.0, 1.0), at, fingerprint(value), why)
}
