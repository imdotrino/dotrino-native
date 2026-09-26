package com.dotrino.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A file in the app's private storage, encrypted (AES-GCM) with a key of the Keystore that
 * never leaves it. What the app keeps about a person is theirs even when nothing in it is
 * secret (which vaults they belong to, their profile), so nothing goes to disk in clear.
 *
 * A file that exists but does not open is an ERROR, never «empty»: treating it as empty would
 * silently lose accounts or profiles. Same piece as `SealedFile.swift`.
 */
class SealedFile(context: Context, name: String, private val keyAlias: String) {
    private val file = File(context.filesDir, name)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        // A new key ONLY when there is no file yet: with a file and no key, a fresh key would
        // just make the file unreadable and look like corruption.
        check(!file.exists()) { "${file.name} exists but its key is not in the Keystore" }
        val spec = KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** The plaintext, or null when there is no file yet. Callers serialize access. */
    fun read(): ByteArray? {
        if (!file.exists()) return null
        val raw = file.readBytes()
        require(raw.size > 12) { "${file.name} is truncated" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return c.doFinal(raw.copyOfRange(12, raw.size))
    }

    /** Write to a sibling and rename: a half-written file must never replace a good one. */
    fun write(plain: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(c.iv + c.doFinal(plain))
        if (!tmp.renameTo(file)) throw IllegalStateException("could not save ${file.name}")
    }
}
