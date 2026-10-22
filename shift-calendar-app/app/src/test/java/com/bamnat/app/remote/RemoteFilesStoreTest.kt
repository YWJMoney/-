package com.bamnat.app.remote

import com.bamnat.core.pay.RateTable
import com.bamnat.core.pay.TakeHome
import com.bamnat.core.pay.TaxMode
import com.bamnat.core.remote.PresetBook
import com.bamnat.core.schedule.PatternPresets
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.time.LocalDate
import java.util.Base64

/** [REMOTE-02] The phone side: daily check, signature gate, newer only, offline keeps the last good file. */
class RemoteFilesStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(key.public.encoded.copyOfRange(key.public.encoded.size - 32, key.public.encoded.size))
    private fun sign(b: ByteArray) = Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(key.private); update(b); sign() })

    private val site = mutableMapOf<String, ByteArray>()
    private var online = true
    private val fetcher = RemoteFetcher { path -> if (online) site[path] else null }
    private val now = Instant.parse("2027-01-02T00:00:00Z")
    private val feb27 = LocalDate.of(2027, 2, 1)

    private fun publish(kind: String, version: Long, data: String, signature: String? = null) {
        val b = """{"format":1,"kind":"$kind","version":$version,"issued":"2027-01-01","minApp":33,"data":$data}""".toByteArray()
        site["remote/v1/$kind.json"] = b
        site["remote/v1/$kind.json.sig"] = (signature ?: sign(b)).toByteArray()
    }

    private val rates2027 = """{"rates":[{"from":"2027-01-01","version":"2027.2","minimumHourlyWage":10700,"pensionRate":0.10,
        "healthRate":0.0719,"longTermCareRate":0.010}]}"""

    private fun store() = RemoteFilesStore(tmp.root, fetcher, 33, listOf(pub))
    private fun care() = TakeHome.estimate(3_000_000, TaxMode.INSURANCE_4, on = feb27).deductions.toMap()["장기요양"]

    @After fun reset() {
        RateTable.use(null)
        PresetBook.use(null)
    }

    @Test fun signedNewerFileIsUsedAndKeptAcrossARestart() = runBlocking {
        publish("rates", 2027010101, rates2027)
        assertEquals(listOf("rates"), store().refresh(now))
        assertEquals(15_000L, care())
        RateTable.use(null)
        assertEquals(14_170L, care())
        // Next start, offline: the saved file is checked again and laid over the built-in years.
        online = false
        store().applySaved()
        assertEquals(15_000L, care())
        assertEquals(2027010101L, store().version("rates"))
    }

    @Test fun wrongSignatureOrOlderFileChangesNothing() = runBlocking {
        publish("rates", 2027010101, rates2027, signature = sign("other".toByteArray()))
        assertTrue(store().refresh(now).isEmpty())
        assertEquals(14_170L, care())
        publish("rates", RateTable.BUILT_IN_VERSION, rates2027)
        assertTrue(store().refresh(now, force = true).isEmpty())
        assertEquals(14_170L, care())
        assertFalse(java.io.File(tmp.root, "rates.json").exists())
    }

    @Test fun offlineKeepsTheBuiltInFilesAndAsksAgainLater() = runBlocking {
        online = false
        val s = store()
        assertTrue(s.refresh(now).isEmpty())
        assertEquals(14, PatternPresets.all.size)
        assertTrue(s.due(now))
        online = true
        publish("presets", PresetBook.bundledVersion + 1, """{"patterns":[{"id":"p-new","name":"새 근무","codes":"DNO"}]}""")
        assertEquals(listOf("presets"), s.refresh(now))
        assertEquals("새 근무", PatternPresets.all.last().name)
        // Asked once a day only.
        assertFalse(s.due(now.plusSeconds(3_600)))
        assertTrue(s.refresh(now.plusSeconds(3_600)).isEmpty())
        assertTrue(s.due(now.plusSeconds(86_400)))
    }

    @Test fun aFileForANewerAppWaits() = runBlocking {
        val b = """{"format":1,"kind":"rates","version":2027010101,"issued":"2027-01-01","minApp":40,"data":$rates2027}""".toByteArray()
        site["remote/v1/rates.json"] = b
        site["remote/v1/rates.json.sig"] = sign(b).toByteArray()
        assertTrue(store().refresh(now).isEmpty())
        assertEquals(14_170L, care())
    }
}
