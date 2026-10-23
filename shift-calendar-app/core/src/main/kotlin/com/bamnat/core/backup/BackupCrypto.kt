package com.bamnat.core.backup

import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * [FEAT-08] Password-protected backups: AES-256-GCM with a key from PBKDF2-HMAC-SHA256 (OWASP 2023
 * guidance: 600,000 iterations). The password never leaves the phone and is not stored; a wrong
 * password or a changed file fails the GCM tag check instead of giving garbage.
 */
object BackupCrypto {
    const val ITERATIONS = 600_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    class Sealed(val salt: ByteArray, val iv: ByteArray, val iterations: Int, val data: ByteArray)

    class WrongPassword : Exception("암호가 맞지 않거나 파일이 바뀌었어요")

    fun seal(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS, random: SecureRandom = SecureRandom()): Sealed {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        return Sealed(salt, iv, iterations, cipher.doFinal(plain))
    }

    /**
     * [PROV-03] The key a password gives with [salt]: 자동 백업 keeps it (wrapped by the phone's key store)
     * instead of the password, so a file made with [sealWithKey] opens with the password like any other.
     */
    fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray = key(password, salt, iterations).encoded

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(16).also(random::nextBytes)

    /** [PROV-03] [seal] with a key from [deriveKey]: no PBKDF2 each time, a fresh IV each time. */
    fun sealWithKey(plain: ByteArray, key: ByteArray, salt: ByteArray, iterations: Int, random: SecureRandom = SecureRandom()): Sealed {
        require(key.size == KEY_BITS / 8) { "key" }
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return Sealed(salt.copyOf(), iv, iterations, cipher.doFinal(plain))
    }

    /** [PROV-03] Opens a file sealed with [key], to check what was written. */
    fun openWithKey(sealed: Sealed, key: ByteArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.doFinal(sealed.data)
    } catch (e: javax.crypto.AEADBadTagException) {
        throw WrongPassword()
    }

    fun open(sealed: Sealed, password: CharArray): ByteArray = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, sealed.salt, sealed.iterations), GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.doFinal(sealed.data)
    } catch (e: javax.crypto.AEADBadTagException) {
        throw WrongPassword()
    }

    /** [REL-15] A fresh 12-byte IV for [encrypting]. */
    fun newIv(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(12).also(random::nextBytes)

    /**
     * [REL-15] 327: the backup file of format 3 sealed as it is written, without the whole file in memory.
     * Closing the stream writes the GCM tag.
     */
    fun encrypting(out: OutputStream, key: ByteArray, iv: ByteArray): OutputStream {
        require(key.size == KEY_BITS / 8) { "key" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return CipherOutputStream(out, cipher)
    }

    /**
     * [REL-15] Opens what [encrypting] wrote into [out]. Nothing reaches [out] unless the tag checks out: a wrong
     * password or a changed file throws [WrongPassword] and [out] stays empty.
     */
    fun decrypt(input: InputStream, key: ByteArray, iv: ByteArray, out: OutputStream) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        // GCM gives the plain bytes only after the tag is checked, so they are collected first.
        val plain = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                cipher.update(buf, 0, n)?.let { plain.write(it) }
            }
            plain.write(cipher.doFinal())
        } catch (e: javax.crypto.AEADBadTagException) {
            throw WrongPassword()
        }
        plain.writeTo(out)
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
