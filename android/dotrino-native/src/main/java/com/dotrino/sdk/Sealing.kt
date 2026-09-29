package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `identitySealing` of `@dotrino/proxy-client` (`src/sealing.js`): seal and open a directed
 * message with the IDENTITY's envelope (`encrypt`/`decrypt`, envelope v2), because the
 * encryption private key lives with the profile and not with the transport.
 *
 * The wire shape is `{ app, sealed, from }`:
 *  - `app` marks whose it is — what is not ours is dropped by it;
 *  - `sealed` is the identity envelope, wrapped to EVERY encryption key given (a contact's
 *    devices, from their profile card);
 *  - `from` is my encryption key. Only the holder of its private half could build the
 *    wrap, so it is what says WHO sealed it (the token does not, and the greeting does not
 *    authenticate).
 */
class IdentitySealing(private val profile: Profile, val app: String) {
    class SealingError(message: String, val code: String) : Exception(message)

    /** What arrived, opened, and the encryption key of whoever sealed it. */
    data class Opened(val payload: JsonObject, val senderEncPub: String)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    fun isSealed(m: JsonObject?): Boolean =
        m != null && (m["app"] as? JsonPrimitive)?.content == app && m["sealed"] is JsonObject

    /**
     * Seals [msg] to [recipientEncPubs]. Nothing to seal to = it does not go (`unsealed`):
     * an envelope wrapped for nobody is a lost message that looks sent.
     */
    suspend fun seal(msg: JsonObject, recipientEncPubs: List<String>): JsonObject {
        val keys = recipientEncPubs.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) throw SealingError("no encryption key for the other side", "unsealed")
        val sealed = profile.encrypt(keys, msg.toString())
        if ((sealed["wrap"] as? JsonObject).isNullOrEmpty()) throw SealingError("the envelope was wrapped for nobody", "unsealed")
        return buildJsonObject { put("app", app); put("sealed", sealed); put("from", profile.encPub) }
    }

    /** Opens what was sealed to me. Throws if it is not mine, not ours, or was tampered with. */
    suspend fun open(env: JsonObject): Opened {
        if (!isSealed(env)) throw SealingError("not a sealed envelope of $app", "not-sealed")
        val from = (env["from"] as? JsonPrimitive)?.content ?: throw SealingError("sealed envelope without sender key", "not-sealed")
        val text = profile.decrypt(from, env["sealed"] as JsonObject)
        val payload = json.parseToJsonElement(text) as? JsonObject ?: throw SealingError("sealed payload is not an object", "bad-payload")
        return Opened(payload, from)
    }
}
