package com.dotrino.sdk

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The accounts this phone approves for, in a [SealedFile]. Nothing secret is stored (vault
 * key, proxy, paper, name), but the list of vaults a person belongs to is theirs.
 *
 * Lives in the IDENTITY app (docs/DISENO.md §2.2); the other apps reach it through
 * [IdentityClient].
 */
class AccountStore(context: Context) {
    private val sealed = SealedFile(context, "dotrino-accounts.bin", "dotrino.accounts")
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Account.serializer())

    @Synchronized fun list(): List<Account> =
        sealed.read()?.let { json.decodeFromString(ser, String(it, Charsets.UTF_8)) } ?: emptyList()

    private fun write(accounts: List<Account>) = sealed.write(json.encodeToString(ser, accounts).toByteArray(Charsets.UTF_8))

    /** Adds or replaces the account with the same [Account.id]. */
    @Synchronized fun save(a: Account) = write(list().filter { it.id != a.id } + a)

    @Synchronized fun remove(id: String) {
        write(list().filter { it.id != id })
        KeystoreKeys.delete(id)
    }
}
