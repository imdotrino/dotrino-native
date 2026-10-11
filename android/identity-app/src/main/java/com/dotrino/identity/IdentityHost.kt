package com.dotrino.identity

import android.content.Context
import com.dotrino.sdk.Account
import com.dotrino.sdk.AccountStore
import com.dotrino.sdk.Crypto
import com.dotrino.sdk.Delegation
import com.dotrino.sdk.IdentityStore
import com.dotrino.sdk.ImportedKeys
import com.dotrino.sdk.KeystoreKeys
import com.dotrino.sdk.PhoneKey
import com.dotrino.sdk.PhoneKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Lo que la app de identidad sabe hacer, método a método: el MISMO protocolo que el puente del
 * WebView (`dotrino-identity/vault/externalKeys.js` + `nativeStore.js`), más las cuentas de
 * Pedidos. Las privadas nunca salen del Keystore: se firma o se acuerda aquí y sale el
 * resultado.
 */
class IdentityHost(context: Context) {
    class HostError(message: String, val code: String) : Exception(message)

    private val app = context.applicationContext
    private val accounts by lazy { AccountStore(app) }
    private val store by lazy { IdentityStore(app) }
    private val json = Json { ignoreUnknownKeys = true }

    /** The key [kid], or the error the caller shows: no other key is ever made in its place. */
    private fun keys(kid: String): PhoneKey {
        if (!PhoneKeys.exists(app, kid)) throw HostError("that key is not on this phone", "native-key-gone")
        return PhoneKeys.open(app, kid)
    }

    private fun JsonObject.str(k: String): String =
        this[k]?.jsonPrimitive?.content ?: throw HostError("missing $k", "native-bad-request")

    suspend fun call(method: String?, p: JsonObject): JsonObject = when (method) {
        "create" -> {
            val kid = UUID.randomUUID().toString()
            val k = KeystoreKeys.create(kid)
            buildJsonObject { put("kid", kid); put("publickey", k.publickey); put("encPub", k.encPub) }
        }
        "import" -> {
            // An account opened with user and password: its key lives in the vault, which hands
            // it over. It cannot go in the chip, so it is kept sealed under a chip key.
            val kid = UUID.randomUUID().toString()
            val sign = p["sign"] as? JsonObject ?: throw HostError("import: missing sign", "native-bad-request")
            val enc = p["enc"] as? JsonObject ?: throw HostError("import: missing enc", "native-bad-request")
            val k = try { ImportedKeys.import(app, kid, sign, enc, p.str("publickey"), p.str("encPub")) }
            catch (e: IllegalArgumentException) { throw HostError("import: ${e.message}", "native-bad-key") }
            buildJsonObject { put("kid", kid); put("publickey", k.publickey); put("encPub", k.encPub) }
        }
        "open" -> {
            val kid = p.str("kid"); val k = keys(kid)
            buildJsonObject { put("kid", kid); put("publickey", k.publickey); put("encPub", k.encPub) }
        }
        "sign" -> buildJsonObject { put("signature", keys(p.str("kid")).signBytes(Crypto.fromB64(p.str("data")))) }
        "deriveBits" -> buildJsonObject {
            put("bits", Crypto.b64(keys(p.str("kid")).agree(Crypto.publicKeyOf(p.str("peer")))))
        }
        "save" -> {
            // After pairing: the account for Pedidos, with the SAME paper the identity got.
            val kid = p.str("kid"); val k = keys(kid)
            val cert = p["cert"] as? JsonObject ?: throw HostError("save: missing cert", "native-bad-request")
            val vault = p.str("vault")
            Delegation.check(cert, vault, k.publickey, null)?.let { throw HostError("the paper does not check out: $it", "bad-paper") }
            val account = Account(
                id = kid, name = (p["name"]?.jsonPrimitive?.content).orEmpty().ifBlank { Delegation.keyLabel(vault) },
                profileId = p["profileId"]?.jsonPrimitive?.content, vault = vault,
                proxy = p.str("proxy"), cert = cert, deviceId = Delegation.keyLabel(k.publickey),
            )
            accounts.save(account)
            buildJsonObject { put("deviceId", account.deviceId) }
        }
        "remove" -> {
            // The profile (or the account) goes: its keys go with it.
            accounts.remove(p.str("kid"))
            buildJsonObject { put("ok", true) }
        }
        // The identity's storage: ONE for every page and every app of the phone.
        "storeLoad" -> buildJsonObject { put("items", JsonObject(store.all().mapValues { JsonPrimitive(it.value) })) }
        "storeSet" -> { store.set(p.str("k"), p.str("v")); buildJsonObject { put("ok", true) } }
        "storeRemove" -> { store.remove(p.str("k")); buildJsonObject { put("ok", true) } }
        // The accounts Pedidos shows, and the renewed paper it persists.
        "accounts" -> buildJsonObject { put("items", JsonArray(accounts.list().map { json.encodeToJsonElement(Account.serializer(), it) })) }
        "accountSave" -> {
            val a = json.decodeFromJsonElement(Account.serializer(), p["account"] ?: throw HostError("missing account", "native-bad-request"))
            if (!PhoneKeys.exists(app, a.id)) throw HostError("that account's key is not on this phone", "native-key-gone")
            accounts.save(a)
            buildJsonObject { put("ok", true) }
        }
        else -> throw HostError("unknown method: $method", "native-bad-request")
    }
}
