package com.bamnat.core.backup

import java.security.SecureRandom
import javax.crypto.Cipher
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
