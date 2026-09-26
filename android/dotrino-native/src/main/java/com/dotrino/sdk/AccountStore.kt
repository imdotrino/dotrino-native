package com.dotrino.sdk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The accounts this phone approves for, on disk. Nothing secret is stored (vault key, proxy,
 * paper, name), but the list of vaults a person belongs to is theirs, so the file is
 * encrypted with an AES key from the Keystore that never leaves it.
 *
 * A file that exists but does not open is an ERROR, not an empty list: treating it as
 * «no accounts» would silently make the phone stop approving.
 */
class AccountStore(context: Context) {
    private val file = File(context.filesDir, "dotrino-accounts.bin")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Account.serializer())

    companion object {
        private const val ALIAS = "dotrino.accounts"
        private fun key(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
        }
    }

    @Synchronized fun list(): List<Account> {
        if (!file.exists()) return emptyList()
        val raw = file.readBytes()
        require(raw.size > 12) { "accounts file is truncated" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return json.decodeFromString(ser, String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8))
    }

    @Synchronized private fun write(accounts: List<Account>) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val body = c.iv + c.doFinal(json.encodeToString(ser, accounts).toByteArray(Charsets.UTF_8))
        // Write to a sibling and rename: a half-written file must never replace a good one.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(body)
        if (!tmp.renameTo(file)) throw IllegalStateException("could not save the accounts file")
    }

    /** Adds or replaces the account with the same [Account.id]. */
    @Synchronized fun save(a: Account) = write(list().filter { it.id != a.id } + a)

    @Synchronized fun remove(id: String) {
        write(list().filter { it.id != id })
        KeystoreKeys.delete(id)
    }
}
