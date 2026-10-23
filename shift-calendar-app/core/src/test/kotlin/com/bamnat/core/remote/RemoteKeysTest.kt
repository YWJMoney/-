package com.bamnat.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** [REMOTE-04] Two trusted keys (키 교체 대비), and a file of a kind the app does not ask for is never used. */
class RemoteKeysTest {
    private fun pair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun pub(k: KeyPair) = Base64.getEncoder().encodeToString(k.public.encoded.copyOfRange(k.public.encoded.size - 32, k.public.encoded.size))
    private fun sign(b: ByteArray, k: KeyPair): String =
        Base64.getEncoder().encodeToString(Signature.getInstance("Ed25519").run { initSign(k.private); update(b); sign() })
    private fun file(kind: String) =
        """{"format": 1, "kind": "$kind", "version": 2027010101, "issued": "2026-12-20", "minApp": 33, "source": "시험", "data": {"x": 1}}"""
            .toByteArray()

    @Test fun twoDistinctPublicKeysAreTrusted() {
        assertEquals(2, RemoteKeys.TRUSTED.size)
        assertEquals(2, RemoteKeys.TRUSTED.toSet().size)
        RemoteKeys.TRUSTED.forEach { assertEquals(32, Base64.getDecoder().decode(it).size) }
    }

    @Test fun aFileSignedWithTheSecondKeyOpens() {
        val first = pair(); val second = pair()
        val b = file("rates")
        val keys = listOf(pub(first), pub(second))
        assertEquals("rates", RemoteFiles.open(b, sign(b, second), "rates", 33, keys).kind)
        assertEquals("rates", RemoteFiles.open(b, sign(b, first), "rates", 33, keys).kind)
    }

    @Test fun aKindTheAppDoesNotKnowIsIgnored() {
        val k = pair()
        val b = file("someday")
        // Asked for as one of the kinds it knows, a newer kind's file is refused, so the phone keeps its data.
        for (kind in RemoteFiles.KINDS) {
            try { RemoteFiles.open(b, sign(b, k), kind, 33, listOf(pub(k))); fail("accepted as $kind") } catch (e: RemoteFileException) { }
        }
        // Read on its own it is a well formed file; only the app's list decides what is fetched.
        assertEquals("someday", RemoteFiles.parse(b).kind)
        assertTrue("someday" !in RemoteFiles.KINDS)
    }
}
