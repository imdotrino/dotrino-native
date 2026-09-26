package com.dotrino.sdk

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.security.PublicKey
import java.security.interfaces.ECPublicKey

/**
 * The two keys of an account, living in the identity app: they are asked over [IdentityClient]
 * to sign or to agree, like the Keystore would be asked in-process.
 */
class RemoteKeys private constructor(
    private val client: IdentityClient,
    private val kid: String,
    override val publickey: String,
    override val encPub: String,
) : DeviceKeys {
    companion object {
        /** The existing pair [kid]; the identity app says `native-key-gone` if it is not there. */
        suspend fun open(client: IdentityClient, kid: String): RemoteKeys {
            val r = client.call("open", obj("kid" to kid))
            return RemoteKeys(client, kid, r.getValue("publickey").jsonPrimitive.content, r.getValue("encPub").jsonPrimitive.content)
        }

        private fun obj(vararg p: Pair<String, String>) = JsonObject(p.associate { it.first to JsonPrimitive(it.second) })
    }

    override suspend fun sign(text: String): String =
        client.call("sign", obj("kid" to kid, "data" to Crypto.b64(text.toByteArray(Charsets.UTF_8)))).getValue("signature").jsonPrimitive.content

    override suspend fun agree(peer: PublicKey): ByteArray =
        Crypto.fromB64(client.call("deriveBits", obj("kid" to kid, "peer" to Crypto.jwkOf(peer as ECPublicKey))).getValue("bits").jsonPrimitive.content)
}

/** The accounts of the phone, kept by the identity app. */
object RemoteAccounts {
    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Account.serializer())

    suspend fun list(client: IdentityClient): List<Account> =
        json.decodeFromJsonElement(ser, client.call("accounts").getValue("items") as JsonArray)

    /** The renewed paper, persisted where the account lives. */
    suspend fun save(client: IdentityClient, a: Account) {
        client.call("accountSave", JsonObject(mapOf("account" to json.encodeToJsonElement(Account.serializer(), a))))
    }

    /** Off this phone: the account and its keys (in the vault it stays until revoked there). */
    suspend fun remove(client: IdentityClient, id: String) {
        client.call("remove", JsonObject(mapOf("kid" to JsonPrimitive(id))))
    }
}
