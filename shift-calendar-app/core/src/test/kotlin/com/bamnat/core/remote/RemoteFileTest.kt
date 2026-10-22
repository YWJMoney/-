package com.bamnat.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** [REMOTE-01] The envelope, the strict reader and the signature gate a downloaded file must pass. */
class RemoteFileTest {
    private val key: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(key.public.encoded.copyOfRange(key.public.encoded.size - 32, key.public.encoded.size))

    private fun sign(b: ByteArray, k: KeyPair = key): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(k.private); update(b); sign() })

    private fun file(kind: String = "rates", version: Long = 2027010101, minApp: Int = 33, data: String = "{}") =
        """{"format": 1, "kind": "$kind", "version": $version, "issued": "2026-12-20", "minApp": $minApp, "source": "시험", "data": $data}"""
            .toByteArray()

    private fun rejects(block: () -> Unit) {
        try { block(); fail("accepted") } catch (e: RemoteFileException) { }
    }

    @Test fun signedFileOpens() {
        val b = file()
        val f = RemoteFiles.open(b, sign(b), "rates", 33, listOf(pub))
        assertEquals(2027010101L, f.version)
        assertEquals("시험", f.source)
    }

    @Test fun forgedOrWrongFilesAreRefused() {
        val b = file()
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        rejects { RemoteFiles.open(b, sign(b, other), "rates", 33, listOf(pub)) }
        val changed = file(version = 2027010102)
        rejects { RemoteFiles.open(changed, sign(b), "rates", 33, listOf(pub)) }
        rejects { RemoteFiles.open(b, "not base64!", "rates", 33, listOf(pub)) }
        rejects { RemoteFiles.open(b, sign(b), "presets", 33, listOf(pub)) }
        val newer = file(minApp = 40)
        rejects { RemoteFiles.open(newer, sign(newer), "rates", 33, listOf(pub)) }
        // The production key never signed this test file.
        assertFalse(RemoteFiles.verify(b, sign(b)))
    }

    @Test fun strictJson() {
        for (bad in listOf("{", "{}x", "{\"a\":1,}", "[1,]", "{\"a\":01}", "{'a':1}", "{\"a\":1,\"a\":2}", "{\"a\":\"\u0001\"}", "// c\n{}"))
            rejects { Json.parse(bad) }
        assertEquals(mapOf("a" to listOf(1L, 2.5, "é\n", true, null)), Json.parse("""{"a":[1, 2.5, "é\n", true, null]}"""))
        rejects { RemoteFiles.parse(file().copyOf().also { it[it.size - 2] = 0xC3.toByte() }) }
        rejects { RemoteFiles.parse("""{"format": 2, "kind": "rates", "version": 1, "issued": "2026-01-01", "minApp": 1, "data": {}}""".toByteArray()) }
    }

    @Test fun everyPublishedFileShipsInTheApp() {
        for (kind in RemoteFiles.KINDS) {
            val f = assertNotNull(kind, RemoteFiles.bundled(kind)).let { RemoteFiles.bundled(kind)!! }
            assertEquals(kind, f.kind)
            assertTrue(f.version >= 2026102201)
        }
    }
}
