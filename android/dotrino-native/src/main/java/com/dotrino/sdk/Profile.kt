package com.dotrino.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * THE PROFILE OF THIS PHONE, as the identity (`id.dotrino.com`) keeps it: the port of what an
 * app asks of `@dotrino/identity` — who I am (`me.publickey`), my encryption key, `signData`
 * and `encrypt`/`decrypt` — for NATIVE apps, without a WebView (CONVENCIONES §16.2).
 *
 * It does not create profiles nor keys: it READS the identity's store (the same one the
 * WebView of the Dotrino app writes, `vault/nativeStore.js`) and asks the phone's chip to sign
 * or to agree with the key that store points to. So a native app speaks as the SAME identity
 * as the web: one phone, one device of the acta.
 *
 * And it obeys the same rule as the identity: this device signs for the profile only if its
 * acta says so (`sign`). If not, the identity would ask the vault to sign; that path is not
 * ported yet, and here it stops with `needs-vault-signer` — never signs anyway.
 */
class Profile private constructor(
    /** `me.publickey`: the JWK string exactly as the identity writes it (the acta compares it as is). */
    val publickey: String,
    /** My encryption key (JWK string): what others seal to. */
    val encPub: String,
    private val acta: JsonObject?,
    private val renounces: JsonArray,
    private val keys: DeviceKeys,
    /** The id of this profile in the identity's store (`dotrino.identity.current`); null for a profile made in hand. */
    val pid: String? = null,
    /** The last sealed actas (`dotrino.identity.acta.history`): the links of the sealer chain. */
    private val history: JsonArray = JsonArray(emptyList()),
) {
    class ProfileError(message: String, val code: String) : Exception(message)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        const val CURRENT = "dotrino.identity.current"
        private fun scoped(pid: String, k: String) = k.replace(Regex("^dotrino\\.identity\\."), "dotrino.identity.p.$pid.")

        /**
         * The active profile from the identity's store items (`storeLoad`: `kv:…`, `key:…`).
         * [keysFor] gives the chip key of a `kid`. Stops with a code when there is none:
         * `no-profile` (the phone has no profile yet) or `no-profile-keys` (a profile whose
         * keys are not in the chip — made in a browser, adopted…).
         */
        suspend fun load(items: Map<String, String>, keysFor: suspend (String) -> DeviceKeys): Profile {
            val pid = items["kv:$CURRENT"] ?: throw ProfileError("this phone has no Dotrino profile yet", "no-profile")
            fun record(name: String): JsonObject? = items["key:" + scoped(pid, name)]?.let { json.parseToJsonElement(it) as? JsonObject }
            val sign = record("dotrino.identity.keypair")
            val enc = record("dotrino.identity.enc-keypair")
            val kid = (sign?.get("external") as? JsonPrimitive)?.content
            if (sign == null || enc == null || kid == null || (enc["external"] as? JsonPrimitive)?.content != kid) {
                throw ProfileError("the active profile has no keys in this phone's chip", "no-profile-keys")
            }
            // The SAME string the identity uses (`JSON.stringify(publicJwk)`): the acta finds
            // its members by exact string.
            val publickey = sign.getValue("publicJwk").jsonObject.toString()
            val encPub = enc.getValue("publicJwk").jsonObject.toString()
            val acta = items["kv:" + scoped(pid, "dotrino.identity.acta")]?.let { json.parseToJsonElement(it) as? JsonObject }
            val renounces = items["kv:" + scoped(pid, "dotrino.identity.renounced")]?.let { json.parseToJsonElement(it) as? JsonArray } ?: JsonArray(emptyList())
            val history = items["kv:" + scoped(pid, "dotrino.identity.acta.history")]?.let { json.parseToJsonElement(it) as? JsonArray } ?: JsonArray(emptyList())
            return Profile(publickey, encPub, acta, renounces, keysFor(kid), pid, history)
        }

        /** For tests and headless tools: a profile from keys in hand. */
        fun of(keys: DeviceKeys, acta: JsonObject? = null) = Profile(keys.publickey, keys.encPub, acta, JsonArray(emptyList()), keys)

        /** `encKeyId` of the identity: the first 16 hex of the key's id. */
        fun encKeyId(encPub: String) = Delegation.pubkeyId(encPub).substring(0, 16)
    }

    /**
     * `profileCard` of the identity: the SIGNED list of this person's devices (their keys),
     * from the acta. Others use it to seal to every device and not just this one. Null for a
     * profile of one device, with no acta.
     */
    val card: JsonObject? get() = acta?.get("card") as? JsonObject

    /**
     * WHO I AM to others: the `profileId` of the acta (the genesis key, which never changes) —
     * so a rating from the phone and one from the PC are the same person — or, without an
     * acta, my own key.
     */
    val profileId: String get() = (acta?.get("profileId") as? JsonPrimitive)?.content ?: publickey

    /**
     * `sealerChain` of the identity: the actas where the sealer changed, oldest first, with the
     * current one at the end. What proves THIS device speaks for [profileId], without asking
     * anybody. Empty without an acta.
     */
    fun sealerChain(): JsonArray {
        val cur = acta ?: return JsonArray(emptyList())
        fun seq(o: JsonObject?) = (o?.get("seq") as? JsonPrimitive)?.content?.toLongOrNull()
        val bySeq = linkedMapOf<Long, JsonObject>()
        (history.mapNotNull { it as? JsonObject } + cur).forEach { a -> seq(a)?.let { bySeq[it] = a } }
        val links = bySeq.values.filter { (it["sealerChanged"] as? JsonPrimitive)?.content == "true" }.sortedBy { seq(it) }
        return JsonArray(if (links.isNotEmpty() && seq(links.last()) == seq(cur)) links else links + cur)
    }

    /**
     * The whole signing package of the identity's `signData`: `{ signature, publickey,
     * profileId, chain }`. What a registry needs to check that this device signs for the
     * person (`@dotrino/reputation` refuses a bare signature).
     */
    suspend fun signPackage(data: JsonObject): JsonObject {
        val signature = signData(data)
        return kotlinx.serialization.json.buildJsonObject {
            put("signature", JsonPrimitive(signature)); put("publickey", JsonPrimitive(publickey))
            put("profileId", JsonPrimitive(profileId)); put("chain", sealerChain())
        }
    }

    /** May this device sign for the profile? No acta = a profile of one device, which signs. */
    val canSign: Boolean get() = acta == null || Acta.memberCan(acta, publickey, "sign", renounces)

    /**
     * `signData` of the identity: P1363 base64 signature over the canonical text of [data].
     * `identify` is always signed here — it identifies this connection, it is not a
     * signature on your behalf (same rule as the identity).
     */
    suspend fun signData(data: JsonObject): String {
        val op = (data["op"] as? JsonPrimitive)?.content
        if (!canSign && op != "identify") {
            throw ProfileError("this device does not sign for your profile; the vault would have to (not supported in native apps yet)", "needs-vault-signer")
        }
        return keys.sign(Canonical.stringify(data))
    }

    /**
     * `encrypt` of the identity (envelope v2): a fresh AES key for the text, and that key
     * wrapped to each recipient with the ECDH between MY encryption key and theirs, under the
     * id of their key. It is what `decrypt` of any identity opens.
     */
    suspend fun encrypt(recipientEncPubs: List<String>, plaintext: String): JsonObject {
        require(recipientEncPubs.isNotEmpty()) { "recipients required" }
        val k = Crypto.randomBytes(32)
        val (iv, ct) = Crypto.aesGcmSeal(k, plaintext.toByteArray(Charsets.UTF_8))
        val wrap = LinkedHashMap<String, JsonObject>()
        for (encPub in recipientEncPubs.distinct()) {
            val shared = keys.agree(Crypto.publicKeyOf(encPub))
            val (wiv, wct) = Crypto.aesGcmSeal(shared, k)
            wrap[encKeyId(encPub)] = buildJsonObject { put("iv", Crypto.b64(wiv)); put("ct", Crypto.b64(wct)) }
        }
        return buildJsonObject {
            put("v", 2); put("iv", Crypto.b64(iv)); put("ct", Crypto.b64(ct)); put("wrap", JsonObject(wrap))
        }
    }

    /** `decrypt` of the identity: my wrap (by the id of my key), then the text. Throws if not mine. */
    suspend fun decrypt(senderEncPub: String, envelope: JsonObject): String {
        val v = (envelope["v"] as? JsonPrimitive)?.content
        if (v != "1" && v != "2") throw ProfileError("unsupported envelope", "bad-envelope")
        val mine = (envelope["wrap"] as? JsonObject)?.get(encKeyId(encPub)) as? JsonObject
            ?: throw ProfileError("this device is not among the message recipients", "not-for-me")
        val shared = keys.agree(Crypto.publicKeyOf(senderEncPub))
        val k = Crypto.aesGcmOpen(shared, Crypto.fromB64(mine.getValue("iv").jsonPrimitive.content), Crypto.fromB64(mine.getValue("ct").jsonPrimitive.content))
        val pt = Crypto.aesGcmOpen(k, Crypto.fromB64(envelope.getValue("iv").jsonPrimitive.content), Crypto.fromB64(envelope.getValue("ct").jsonPrimitive.content))
        return String(pt, Charsets.UTF_8)
    }
}
