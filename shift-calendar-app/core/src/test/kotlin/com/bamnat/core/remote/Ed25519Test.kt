package com.bamnat.core.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.random.Random

/** [REMOTE-01] The pure Kotlin check against RFC 8032 and against the JDK's own Ed25519 signer. */
class Ed25519Test {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // RFC 8032 section 7.1, TEST 1–3.
    private val vectors = listOf(
        Triple("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
        Triple("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        Triple("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
            "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
    )

    @Test fun rfc8032Vectors() {
        for ((pub, msg, sig) in vectors) assertTrue(Ed25519.verify(hex(pub), hex(msg), hex(sig)))
    }

    @Test fun anyChangeIsRejected() {
        val (pub, msg, sig) = vectors[2]
        val s = hex(sig)
        for (i in s.indices step 7) assertFalse(Ed25519.verify(hex(pub), hex(msg), s.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }))
        assertFalse(Ed25519.verify(hex(pub), hex("af83"), s))
        assertFalse(Ed25519.verify(hex(vectors[1].first), hex(msg), s))
        assertFalse(Ed25519.verify(hex(pub), hex(msg), s.copyOf(63)))
        assertFalse(Ed25519.verify(ByteArray(32) { 0xFF.toByte() }, hex(msg), s))
    }

    @Test fun agreesWithTheJdkSigner() {
        val gen = KeyPairGenerator.getInstance("Ed25519")
        val rnd = Random(22)
        repeat(12) { n ->
            val kp = gen.generateKeyPair()
            val raw = kp.public.encoded.copyOfRange(kp.public.encoded.size - 32, kp.public.encoded.size)
            val msg = rnd.nextBytes(n * 37)
            val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(msg); sign() }
            assertTrue(Ed25519.verify(raw, msg, sig))
            if (msg.isNotEmpty()) assertFalse(Ed25519.verify(raw, msg.copyOf().also { it[0] = (it[0] + 1).toByte() }, sig))
        }
    }
}
