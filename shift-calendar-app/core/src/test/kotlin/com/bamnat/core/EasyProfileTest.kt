package com.bamnat.core

import com.bamnat.core.guide.FeatureRegistry
import com.bamnat.core.profile.Fact
import com.bamnat.core.profile.FactKey
import com.bamnat.core.profile.FactMeta
import com.bamnat.core.profile.FeatureNeeds
import com.bamnat.core.profile.Profile
import com.bamnat.core.profile.Source
import com.bamnat.core.profile.StatusBoard
import com.bamnat.core.profile.Needs
import com.bamnat.core.work.WorkStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** [EASY-03] The fact store's rules: where a value came from, when it counts as typed, what may replace it. */
class EasyProfileTest {
    private val at = Instant.parse("2026-10-23T00:00:00Z")

    @Test fun `a value written by an inference stays inferred until it changes elsewhere`() {
        val meta = Profile.written(2_600_000L, Source.INFERRED, 0.8, at, "명세서 사진")
        val fact = Profile.read(FactKey.BASE_PAY, 2_600_000L, meta)
        assertEquals(Source.INFERRED, fact.source)
        assertEquals(0.8, fact.confidence, 0.0)
        assertEquals("명세서 사진", fact.why)
        assertTrue(fact.estimated)
        // The user fixed it on the pay screen: typed, whichever screen it was.
        val fixed = Profile.read(FactKey.BASE_PAY, 2_700_000L, meta)
        assertEquals(Source.TYPED, fixed.source)
        assertFalse(fixed.estimated)
        assertNull(fixed.why)
    }

    @Test fun `values from before the profile read by the store's own marks`() {
        // The pay setup's estimatedFields: a half-sure guess.
        val guessed = Profile.read(FactKey.MONTHLY_HOURS, 209, null, default = 209, estimatedInStore = true)
        assertEquals(Source.INFERRED, guessed.source)
        assertEquals(Profile.MIN_CONFIDENCE, guessed.confidence, 0.0)
        // The starting value is the default; anything else the user chose.
        assertEquals(Source.DEFAULT, Profile.read(FactKey.MONTHLY_HOURS, 209, null, default = 209).source)
        assertEquals(Source.TYPED, Profile.read(FactKey.MONTHLY_HOURS, 226, null, default = 209).source)
        // Nothing stored: not known.
        val none = Profile.read<Long>(FactKey.BASE_PAY, null, null)
        assertFalse(none.known)
        assertFalse(none.estimated)
    }

    @Test fun `a default counts as a guess only for money`() {
        assertTrue(Profile.read(FactKey.MONTHLY_HOURS, 209, null, default = 209).estimated)
        assertFalse(Profile.read(FactKey.SLEEP_TARGET, 420, null, default = 420).estimated)
    }

    @Test fun `a stronger source wins and a weaker one asks`() {
        fun fact(source: Source, confidence: Double = 1.0) = Fact(FactKey.HIRE_DATE, "2024-03-01", source, confidence, at, null)
        val unknown = Fact<String>(FactKey.HIRE_DATE, null, Source.DEFAULT, 0.0, null, null)
        assertEquals(Profile.Decision.WRITE, Profile.decide(unknown, Source.INFERRED, 0.7))
        assertEquals(Profile.Decision.ASK, Profile.decide(unknown, Source.INFERRED, 0.3))
        assertEquals(Profile.Decision.ASK, Profile.decide(fact(Source.TYPED), Source.INFERRED, 0.9))
        assertEquals(Profile.Decision.ASK, Profile.decide(fact(Source.TYPED), Source.SHARED))
        assertEquals(Profile.Decision.ASK, Profile.decide(fact(Source.SHARED), Source.INFERRED, 0.9))
        assertEquals(Profile.Decision.WRITE, Profile.decide(fact(Source.INFERRED, 0.6), Source.SHARED))
        assertEquals(Profile.Decision.WRITE, Profile.decide(fact(Source.DEFAULT), Source.INFERRED, 0.6))
        assertEquals(Profile.Decision.WRITE, Profile.decide(fact(Source.INFERRED, 0.6), Source.INFERRED, 0.8))
        assertEquals(Profile.Decision.KEEP, Profile.decide(fact(Source.INFERRED, 0.8), Source.INFERRED, 0.6))
        assertEquals(Profile.Decision.KEEP, Profile.decide(fact(Source.INFERRED, 0.8), Source.INFERRED, 0.2))
        // The user's own answer always goes in.
        Source.entries.forEach { assertEquals(Profile.Decision.WRITE, Profile.decide(fact(it), Source.TYPED)) }
    }

    @Test fun `the order of sources`() {
        assertEquals(listOf(Source.TYPED, Source.SHARED, Source.INFERRED, Source.DEFAULT), Source.entries.sortedByDescending { it.rank })
    }

    @Test fun `the fingerprint tells values apart and keeps nothing readable`() {
        val a = Profile.fingerprint(2_600_000L)!!
        assertEquals(a, Profile.fingerprint(2_600_000L))
        assertNotEquals(a, Profile.fingerprint(2_600_001L))
        assertFalse(a.contains("2600000"))
        assertNull(Profile.fingerprint(null))
        val m = FactMeta(Source.TYPED)
        assertEquals(1.0, m.confidence, 0.0)
    }

    @Test fun `every fact names features that exist, and every need is a reader`() {
        val ids = FeatureRegistry.all.map { it.id }.toSet()
        FactKey.entries.forEach { k ->
            assertTrue("${k.id} 읽는 기능이 없어요", k.usedBy.isNotEmpty())
            k.usedBy.forEach { assertTrue("${k.id}: $it 없음", it in ids) }
        }
        FeatureNeeds.all.forEach { (feature, keys) ->
            assertTrue("$feature 없음", feature in ids)
            keys.forEach { assertTrue("${it.id}의 usedBy에 $feature 없음", feature in it.usedBy) }
        }
        assertEquals(FactKey.entries.size, FactKey.entries.map { it.id }.distinct().size)
        FactKey.entries.forEach { assertEquals(it, FactKey.byId(it.id)) }
    }

    @Test fun `facts fit their kinds of work`() {
        assertTrue(FactKey.BASE_PAY in FactKey.of(WorkStyle.HOURLY))
        assertFalse(FactKey.BASE_PAY in FactKey.of(WorkStyle.BUSINESS))
        assertTrue(FactKey.SHOP_RULES in FactKey.of(WorkStyle.BUSINESS))
        assertTrue(FactKey.WORK_STYLE in FactKey.of(WorkStyle.INVEST))
        // A calendar from before kinds of work were asked is a shift calendar.
        assertTrue(FactKey.TEAM in FactKey.of(null))
    }

    @Test fun `the status board counts known, guessed and waiting`() {
        val facts = mapOf(
            FactKey.SHIFTS to Profile.read(FactKey.SHIFTS, "주야휴", null),
            FactKey.BASE_PAY to Profile.read(FactKey.BASE_PAY, 2_600_000L, Profile.written(2_600_000L, Source.INFERRED, 0.8, at)),
            FactKey.MONTHLY_HOURS to Profile.read(FactKey.MONTHLY_HOURS, 209, null, default = 209),
            FactKey.HIRE_DATE to Profile.read<String>(FactKey.HIRE_DATE, null, null),
        )
        val missing = Needs.of(facts, listOf("leave.ledger", "pay.gap", "pay.calc"))
        val board = StatusBoard.of(facts, missing)
        assertEquals(1, board.known)
        assertEquals(1, board.estimated)
        assertTrue(FactKey.HIRE_DATE in board.unknown)
        assertTrue(FactKey.MONTHLY_HOURS in board.unknown)
        assertTrue(board.line.startsWith("알고 있는 것 1 · 추정 1 · 모르는 것"))
    }
}
