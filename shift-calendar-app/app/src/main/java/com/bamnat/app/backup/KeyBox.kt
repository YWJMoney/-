package com.bamnat.app.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [PROV-03] Keeps a secret on the phone wrapped by a key that never leaves it. 자동 백업 keeps the key its
 * password gives this way, never the password; tests put an in-memory box in its place.
 */
interface KeyBox {
    fun wrap(secret: ByteArray): ByteArray
    /** Throws [KeyGone] when the phone's key is no longer there (a reset lock screen on some phones). */
    fun unwrap(blob: ByteArray): ByteArray
    fun forget()

    class KeyGone : Exception("key gone")
}

/** The Android key store: AES-256-GCM, no lock-screen check, since the backup runs while the phone is locked. */
class KeystoreKeyBox(private val alias: String = "bamnat_auto_backup") : KeyBox {
    private fun store() = KeyStore.getInstance(STORE).apply { load(null) }

    private fun key(create: Boolean): SecretKey? {
        (store().getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun wrap(secret: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(MODE)
        cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
        return cipher.iv + cipher.doFinal(secret)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val key = runCatching { key(create = false) }.getOrNull() ?: throw KeyBox.KeyGone()
        val cipher = Cipher.getInstance(MODE)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, IV))
            cipher.doFinal(blob, IV, blob.size - IV)
        } catch (e: java.security.GeneralSecurityException) {
            throw KeyBox.KeyGone()
        }
    }

    override fun forget() {
        runCatching { store().deleteEntry(alias) }
    }

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val MODE = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
