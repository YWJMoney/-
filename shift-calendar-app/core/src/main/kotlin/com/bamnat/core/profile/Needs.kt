package com.bamnat.core.profile

import com.bamnat.core.guide.FeatureRegistry

/**
 * [EASY-04] A fact some feature that is on waits for: [features] are the ones it brings to life, [score]
 * how much it matters now (see [Needs.score]).
 */
data class Missing(val key: FactKey, val features: List<String>, val daysUntil: Int, val score: Double)

/**
 * [EASY-04] The facts still missing for the features that are on, most worth asking first. A fact is missing
 * when it has no value, or when it is a money fact still on the app's starting value (209시간, 법대로 가정):
 * the pay is worked out meanwhile, but it is worth one question.
 */
object Needs {
    /** Days counted for a fact nothing makes urgent. */
    const val NOT_SOON = 30

    fun isMissing(fact: Fact<*>?): Boolean = fact == null || !fact.known || (fact.source == Source.DEFAULT && fact.key.money) ||
        // A store's own 모르겠어요 ("대략") reads as a half-sure guess: worth one question.
        (fact.source == Source.INFERRED && fact.confidence <= Profile.MIN_CONFIDENCE && fact.why == null)

    /**
     * [facts] the profile as it stands, [featuresOn] the registry ids that fit this calendar and are not
     * turned off, [daysUntil] when each fact is next needed (the next shift, 급여일, 연차 촉진 날; null for
     * [NOT_SOON]).
     */
    fun of(facts: Map<FactKey, Fact<*>>, featuresOn: Collection<String>, daysUntil: (FactKey) -> Int? = { null }): List<Missing> {
        val on = featuresOn.toSet()
        val byKey = HashMap<FactKey, MutableList<String>>()
        FeatureNeeds.all.forEach { (feature, keys) -> if (feature in on) keys.forEach { byKey.getOrPut(it) { mutableListOf() } += feature } }
        return byKey.filterKeys { isMissing(facts[it]) }.map { (key, features) ->
            val days = (daysUntil(key) ?: NOT_SOON).coerceAtLeast(0)
            Missing(key, features.sortedBy { FeatureRegistry.all.indexOfFirst { f -> f.id == it } }, days, score(features.size, days))
        }.sortedWith(compareByDescending<Missing> { it.score }.thenBy { it.key.ordinal })
    }

    /** Features brought to life, times how soon: tomorrow counts four times as much as a month away. */
    fun score(features: Int, daysUntil: Int): Double = features * (1.0 + 7.0 / (daysUntil + 1))

    /** "알려 주시면 급여 계산·연차 원장이 함께 맞춰져요": what an answer brings, from the features' own names. */
    fun reward(missing: Missing): String {
        val names = missing.features.mapNotNull { FeatureRegistry.get(it)?.name }.distinct().take(3)
        if (names.isEmpty()) return "알려 주시면 더 정확해져요"
        val last = names.last()
        return "알려 주시면 ${names.joinToString("·")}${subject(last)}${if (names.size > 1) " 함께" else ""} 맞춰져요"
    }

    /** 이 after a final consonant, 가 after a vowel (a word not ending in 한글 takes 이). */
    internal fun subject(word: String): String {
        val c = word.lastOrNull() ?: return "이"
        if (c !in '가'..'힣') return "이"
        return if ((c - '가') % 28 == 0) "가" else "이"
    }
}
