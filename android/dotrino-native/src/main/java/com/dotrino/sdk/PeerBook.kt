package com.dotrino.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * THE PEER BOOK of the profile (`vault/peerStore.js` + the peer handlers of `vault/core.js`):
 * contacts, their profile cards, my ratings and the endorsements others sent. It is the SAME
 * record the identity keeps (`peers:peers.<pid>.v1` in the identity's store), so every app
 * of the phone — native or web — sees the same address book.
 *
 * Every change reads the record again, applies itself and writes it back, one at a time:
 * another app of the phone may have written in between.
 */
class PeerBook(private val storage: Storage, private val profile: Profile) {
    /** Where the record lives: the identity's store of the phone, or memory in tests. */
    interface Storage {
        suspend fun load(): String?
        suspend fun save(text: String)
    }

    class MemoryStorage(var text: String? = null) : Storage {
        override suspend fun load() = text
        override suspend fun save(text: String) { this.text = text }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        /** `peersKey()` of the identity: namespaced by profile. */
        fun key(pid: String?) = if (pid != null) "peers.$pid.v1" else "peers.v1"
        private val CONTACT_FIELDS = setOf("nickname", "encryptionPubkey", "lastToken", "contactNotes")
        private const val MAX_ENDORSEMENTS = 50

        private fun isPub(s: String?): Boolean = try {
            val j = json.parseToJsonElement(s ?: "") as? JsonObject
            j?.get("kty")?.jsonPrimitive?.content == "EC" && j["x"] != null && j["y"] != null
        } catch (_: Exception) { false }

        /** `verifyProfileCard`: well formed and signed by whom it says (`sealedBy`). */
        fun verifyCard(card: JsonObject?): Boolean {
            if (card == null || (card["v"] as? JsonPrimitive)?.intOrNull != 1) return false
            val profileId = card.str("profileId"); val sealedBy = card.str("sealedBy")
            if (!isPub(profileId) || !isPub(sealedBy)) return false
            if ((card["seq"] as? JsonPrimitive)?.longOrNull == null || card["keys"] !is JsonArray) return false
            val sig = card.str("sig") ?: return false
            return Crypto.verify(sealedBy!!, JsonObject(card - "sig"), sig)
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    }

    /** Why a card was (not) adopted: `primera-vez`, `seq-mayor`, `igual` | `firma-invalida`, `otro-perfil`, `seq-menor`, `master-cambiado`. */
    data class CardAdoption(val adopted: Boolean, val reason: String, val devices: Int)

    private val lock = Mutex()

    private suspend fun read(): MutableMap<String, JsonObject> {
        val text = storage.load() ?: return linkedMapOf()
        val o = json.parseToJsonElement(text) as? JsonObject ?: return linkedMapOf()
        return o.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap(linkedMapOf())
    }

    private suspend fun write(peers: Map<String, JsonObject>) = storage.save(JsonObject(peers).toString())

    private suspend fun <T> change(f: (MutableMap<String, JsonObject>) -> T): T = lock.withLock {
        val peers = read()
        val r = f(peers)
        write(peers)
        r
    }

    /** `upsertPeer`: merge [patch] into the record and stamp `lastSeen`. */
    private fun upsert(peers: MutableMap<String, JsonObject>, publickey: String, patch: Map<String, JsonElement>): JsonObject {
        val now = System.currentTimeMillis()
        val existing = peers[publickey] ?: buildJsonObject { put("publickey", publickey); put("firstSeen", now) }
        val rec = JsonObject(existing + patch + mapOf("publickey" to JsonPrimitive(publickey), "lastSeen" to JsonPrimitive(now)))
        peers[publickey] = rec
        return rec
    }

    /** `listPeers`: every record, most recently seen first. */
    suspend fun list(): List<JsonObject> = lock.withLock { read().values.sortedByDescending { it.long("lastSeen") ?: 0 } }

    suspend fun contacts(): List<JsonObject> = list().filter { (it["isContact"] as? JsonPrimitive)?.content == "true" }

    suspend fun get(publickey: String): JsonObject? = lock.withLock { read()[publickey] }

    suspend fun addContact(publickey: String, nickname: String? = null, encryptionPubkey: String? = null, lastToken: String? = null, notes: String? = null): JsonObject {
        require(publickey.isNotEmpty()) { "publickey required" }
        val patch = linkedMapOf<String, JsonElement>("isContact" to JsonPrimitive(true))
        if (nickname != null) patch["nickname"] = JsonPrimitive(nickname.take(40))
        if (!encryptionPubkey.isNullOrEmpty()) patch["encryptionPubkey"] = JsonPrimitive(encryptionPubkey)
        if (!lastToken.isNullOrEmpty()) patch["lastToken"] = JsonPrimitive(lastToken)
        if (notes != null) patch["contactNotes"] = JsonPrimitive(notes.take(300))
        return change { upsert(it, publickey, patch) }
    }

    /** `updateContact`: only nickname, encryptionPubkey, lastToken and contactNotes. */
    suspend fun updateContact(publickey: String, patch: Map<String, String?>): JsonObject? {
        val allowed = patch.filterKeys { it in CONTACT_FIELDS }.mapValues { (_, v) -> v?.let { JsonPrimitive(it) } ?: JsonNull }
        if (allowed.isEmpty()) return null
        return change { upsert(it, publickey, allowed) }
    }

    /** `removeContact`: the record stays (ratings, card); it just stops being a contact. */
    suspend fun removeContact(publickey: String): JsonObject? = change { peers ->
        val rec = peers[publickey] ?: return@change null
        JsonObject(rec - "isContact").also { peers[publickey] = it }
    }

    /**
     * `adoptPeerCard`: keep another person's card (their devices). The first time it is
     * accepted; afterwards only if it does not go back and the same master signed it. A
     * changed master is SAID, never accepted quietly.
     */
    suspend fun adoptPeerCard(card: JsonObject): CardAdoption = change { peers ->
        val profileId = card.str("profileId") ?: return@change CardAdoption(false, "firma-invalida", 0)
        val current = peers[profileId]?.get("card") as? JsonObject
        val devices = (current?.get("keys") as? JsonArray)?.size ?: 0
        val reason = when {
            !verifyCard(card) -> "firma-invalida"
            current != null && current.str("profileId") != profileId -> "otro-perfil"
            current == null -> "primera-vez"
            (card.long("seq") ?: 0) < (current.long("seq") ?: 0) -> "seq-menor"
            card.str("sealedBy") != current.str("sealedBy") -> "master-cambiado"
            (card.long("seq") ?: 0) > (current.long("seq") ?: 0) -> "seq-mayor"
            else -> "igual"
        }
        if (reason in setOf("firma-invalida", "otro-perfil", "seq-menor", "master-cambiado")) return@change CardAdoption(false, reason, devices)
        upsert(peers, profileId, mapOf("card" to card, "profileId" to JsonPrimitive(profileId)))
        CardAdoption(true, reason, (card["keys"] as? JsonArray)?.size ?: 0)
    }

    /**
     * The card of a contact: under its own record, under its `profileId`, or the card that
     * lists this key among its devices (the pubkey you added may be one device of the person).
     */
    suspend fun cardOf(publickey: String): JsonObject? = lock.withLock {
        val peers = read()
        val rec = peers[publickey]
        (rec?.get("card") as? JsonObject)
            ?: rec?.str("profileId")?.let { peers[it]?.get("card") as? JsonObject }
            ?: peers.values.mapNotNull { it["card"] as? JsonObject }.firstOrNull { c ->
                (c["keys"] as? JsonArray).orEmpty().any { k -> Delegation.samePubkey((k as? JsonObject)?.str("pub"), publickey) }
            }
    }

    /** Every encryption key I know for a person: the one saved plus all of their card. */
    suspend fun encPubsOf(publickey: String): List<String> {
        val out = mutableListOf<String>()
        get(publickey)?.str("encryptionPubkey")?.let(out::add)
        cardOf(publickey)?.let { c -> (c["keys"] as? JsonArray).orEmpty().forEach { k -> (k as? JsonObject)?.str("encPub")?.let(out::add) } }
        return out.distinct()
    }

    // ---------- ratings (web of trust) ----------

    /** `setRating`: my rating of someone, 0–5, SIGNED by the profile so others can verify it. */
    suspend fun setRating(publickey: String, rating: Double, notes: String = ""): JsonObject {
        val r = rating.coerceIn(0.0, 5.0)
        val safeNotes = notes.take(500)
        val envelope = buildJsonObject {
            put("subject", publickey); putNumber("rating", r); put("notes", safeNotes)
            put("ratedBy", profile.publickey); put("issuedAt", System.currentTimeMillis())
        }
        val signature = profile.signData(envelope)
        val myRating = JsonObject(envelope + ("signature" to JsonPrimitive(signature)))
        return change { upsert(it, publickey, mapOf("myRating" to myRating, "rating" to numberOf(r), "notes" to JsonPrimitive(safeNotes))) }
    }

    /** `getRatingsForSubject`: what I can tell others about [subject]. */
    suspend fun ratingsFor(subject: String): Pair<JsonObject?, List<JsonObject>> {
        val r = get(subject)
        return (r?.get("myRating") as? JsonObject) to (r?.get("endorsements") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    /**
     * `mergeEndorsements`: ratings of [subject] that others signed. Each one is VERIFIED
     * against its signer; the newest per signer stays; mine and malformed ones are dropped.
     */
    suspend fun mergeEndorsements(subject: String, endorsements: List<JsonObject>): Int = change { peers ->
        val existing = peers[subject] ?: buildJsonObject { put("publickey", subject); put("firstSeen", System.currentTimeMillis()) }
        val byRater = linkedMapOf<String, JsonObject>()
        (existing["endorsements"] as? JsonArray).orEmpty().forEach { e -> (e as? JsonObject)?.str("ratedBy")?.let { byRater[it] = e as JsonObject } }
        var merged = 0
        for (env in endorsements) {
            if (env.str("subject") != subject) continue
            val ratedBy = env.str("ratedBy")?.takeIf { it.isNotEmpty() } ?: continue
            if (ratedBy == profile.publickey) continue
            val signature = env.str("signature") ?: continue
            val rating = (env["rating"] as? JsonPrimitive)?.doubleOrNull ?: continue
            if (rating < 0 || rating > 5) continue
            val issuedAt = env.long("issuedAt") ?: 0
            val prev = byRater[ratedBy]
            if (prev != null && (prev.long("issuedAt") ?: 0) >= issuedAt) continue
            val body = buildJsonObject {
                put("subject", subject); put("rating", env["rating"]!!); put("notes", env.str("notes") ?: "")
                put("ratedBy", ratedBy); put("issuedAt", env["issuedAt"] ?: JsonNull)
            }
            if (!Crypto.verify(ratedBy, body, signature)) continue
            byRater[ratedBy] = env
            merged++
        }
        val all = byRater.values.sortedByDescending { it.long("issuedAt") ?: 0 }.take(MAX_ENDORSEMENTS)
        peers[subject] = JsonObject(existing + mapOf("publickey" to JsonPrimitive(subject), "endorsements" to JsonArray(all), "lastSeen" to JsonPrimitive(System.currentTimeMillis())))
        merged
    }

    /** `recordQuery`: someone asked me about [subject]; how often they ask about people I know. */
    suspend fun recordQuery(asker: String, subject: String?) {
        if (asker == profile.publickey) return
        change { peers ->
            val rec = peers[asker] ?: buildJsonObject { put("publickey", asker); put("firstSeen", System.currentTimeMillis()) }
            val stats = rec["queryStats"] as? JsonObject
            var made = stats?.long("queriesMade") ?: 0
            var known = stats?.long("queriesKnown") ?: 0
            made++
            if (subject != null) {
                val s = peers[subject]
                if (s?.get("myRating") is JsonObject || (s?.get("endorsements") as? JsonArray).orEmpty().isNotEmpty()) known++
            }
            peers[asker] = JsonObject(rec + mapOf(
                "queryStats" to buildJsonObject { put("queriesMade", made); put("queriesKnown", known) },
                "lastSeen" to (rec["lastSeen"] ?: JsonPrimitive(System.currentTimeMillis())),
            ))
        }
    }

    /** A whole number stays whole (`5`, not `5.0`): the canonical text is what gets signed. */
    private fun numberOf(d: Double): JsonPrimitive = if (d == Math.floor(d) && !d.isInfinite()) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
    private fun kotlinx.serialization.json.JsonObjectBuilder.putNumber(k: String, d: Double) { put(k, numberOf(d)) }
}
