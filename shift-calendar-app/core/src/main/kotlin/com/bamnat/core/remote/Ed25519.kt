package com.bamnat.core.remote

import java.math.BigInteger
import java.security.MessageDigest

/**
 * [REMOTE-01] Ed25519 signature check (RFC 8032 section 5.1.7), verify only. Android has no Ed25519 before
 * API 33 and the app starts at 26, so this is the RFC's reference arithmetic on [BigInteger]: slow next to
 * a real library but a few milliseconds for the handful of remote files checked once a day. Signing never
 * happens on the phone (tools/remote_sign.py does it with the private key kept outside the repository).
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val L: BigInteger = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = BigInteger.valueOf(-121665) * BigInteger.valueOf(121666).modInverse(P) % P
    private val SQRT_M1: BigInteger = BigInteger.valueOf(2).modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)
    private val TWO_D: BigInteger = D.shiftLeft(1).mod(P)

    /** A point in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val ZERO = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
    private val BASE: Point = run {
        val y = BigInteger.valueOf(4) * BigInteger.valueOf(5).modInverse(P) % P
        val x = recoverX(y, 0) ?: error("base point")
        Point(x, y, BigInteger.ONE, x * y % P)
    }

    private fun add(a: Point, b: Point): Point {
        val pa = (a.y - a.x) * (b.y - b.x) % P
        val pb = (a.y + a.x) * (b.y + b.x) % P
        val pc = a.t * TWO_D % P * b.t % P
        val pd = a.z.shiftLeft(1) * b.z % P
        val e = pb - pa
        val f = pd - pc
        val g = pd + pc
        val h = pb + pa
        return Point(e * f % P, g * h % P, f * g % P, e * h % P)
    }

    private fun mul(scalar: BigInteger, point: Point): Point {
        var q = ZERO
        var p = point
        for (i in 0 until scalar.bitLength()) {
            if (scalar.testBit(i)) q = add(q, p)
            p = add(p, p)
        }
        return q
    }

    private fun same(a: Point, b: Point): Boolean =
        (a.x * b.z - b.x * a.z).mod(P).signum() == 0 && (a.y * b.z - b.y * a.z).mod(P).signum() == 0

    private fun recoverX(y: BigInteger, sign: Int): BigInteger? {
        if (y >= P) return null
        val x2 = (y * y - BigInteger.ONE) * (D * y * y + BigInteger.ONE).mod(P).modInverse(P) % P
        if (x2.mod(P).signum() == 0) return if (sign == 1) null else BigInteger.ZERO
        var x = x2.mod(P).modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).mod(P).signum() != 0) x = x * SQRT_M1 % P
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != (sign == 1)) x = P - x
        return x
    }

    private fun littleEndian(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun decompress(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val n = littleEndian(bytes)
        val sign = if (n.testBit(255)) 1 else 0
        val y = n.clearBit(255)
        val x = recoverX(y, sign) ?: return null
        return Point(x, y, BigInteger.ONE, x * y % P)
    }

    /** True when [signature] (64 bytes) is [publicKey]'s (32 bytes) signature of [message]. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decompress(publicKey) ?: return false
        val rBytes = signature.copyOfRange(0, 32)
        val r = decompress(rBytes) ?: return false
        val s = littleEndian(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val digest = MessageDigest.getInstance("SHA-512").apply {
            update(rBytes)
            update(publicKey)
            update(message)
        }.digest()
        val h = littleEndian(digest).mod(L)
        return same(mul(s, BASE), add(r, mul(h, a)))
    }
}
