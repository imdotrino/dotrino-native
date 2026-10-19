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

        /**
         * A card signed by the profile's OWN key. Only that proves the `profileId` belongs to whoever
         * signed it: anyone can write somebody else's `profileId` in a card signed with their key.
         */
        fun ownCard(card: JsonObject?): Boolean =
            card != null && Delegation.samePubkey(card.str("sealedBy"), card.str("profileId"))

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
    }

    /** Why a card was (not) adopted: `first-time`, `higher-seq`, `same` | `invalid-signature`, `other-profile`, `lower-seq`, `master-changed`. */
    data class CardAdoption(val adopted: Boolean, val reason: String, val devices: Int)

    private val lock = Mutex()

    private suspend fun read(): MutableMap<String, JsonObject> {
        val text = storage.load() ?: return linkedMapOf()
        val o = json.parseToJsonElement(text) as? JsonObject ?: return linkedMapOf()
        return o.entries.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }.toMap(linkedMapOf())
    }

    private suspend fun write(peers: Map<String, JsonObject>) = storage.save(JsonObject(peers).toString())

    /**
     * Something in the book changed HERE (a contact added, removed, renamed…): the app backs it up
     * right away ([PeerBookBackup]) instead of waiting for its next round. Not called for what
     * came FROM the vault ([mergeFrom]): that would only upload it back.
     */
    @Volatile var onChange: (() -> Unit)? = null

    private suspend fun <T> change(notify: Boolean = true, f: (MutableMap<String, JsonObject>) -> T): T = lock.withLock {
        val peers = read()
        val r = f(peers)
        write(peers)
        r
    }.also { if (notify) onChange?.invoke() }

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

    /**
     * `removeContact`: the record stays (ratings, card); it just stops being a contact. It is a
     * CHANGE with a date (`changedAt`), so it does not come back from another device ([PeerBookBackup]).
     */
    suspend fun removeContact(publickey: String): JsonObject? = change { peers ->
        val rec = peers[publickey] ?: return@change null
        JsonObject(rec - "isContact" + ("changedAt" to JsonPrimitive(System.currentTimeMillis()))).also { peers[publickey] = it }
    }

    /**
     * `setBlocked` (@dotrino/identity ≥ 0.108): BLOCK someone — a PRIVATE flag, apart from the
     * rating, never signed nor published. It travels with the book to the vault and the other
     * devices; a CHANGE with a date, and unblocking leaves `false` so it travels too.
     */
    suspend fun setBlocked(publickey: String, blocked: Boolean): JsonObject = change {
        upsert(it, publickey, mapOf("blocked" to JsonPrimitive(blocked), "changedAt" to JsonPrimitive(System.currentTimeMillis())))
    }

    /** I blocked them (the record says `blocked: true`). */
    suspend fun isBlocked(publickey: String): Boolean = (get(publickey)?.get("blocked") as? JsonPrimitive)?.content == "true"

    /** Every record, by key (for [PeerBookBackup]). */
    suspend fun all(): Map<String, JsonObject> = lock.withLock { read() }

    /**
     * Records from another device of the profile (the vault), MERGED with these like the identity
     * does (`mergePeerMaps`): the record changed LATER wins, also for being a contact or not; the
     * first and last seen and the change date keep the widest; a card missing here is taken; its
     * endorsements are verified one by one ([mergeEndorsements]). Returns how many changed here.
     */
    suspend fun mergeFrom(records: List<JsonObject>): Int {
        var n = 0
        val endorsements = mutableListOf<Pair<String, List<JsonObject>>>()
        change(notify = false) { peers ->
            for (b in records) {
                val pk = b.str("publickey") ?: continue
                val a = peers[pk]
                val incomingEnd = (b["endorsements"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                if (incomingEnd.isNotEmpty()) endorsements += pk to incomingEnd
                val base = b - "endorsements"
                if (a == null) { peers[pk] = JsonObject(base); n++; continue }
                val m = a.toMutableMap()
                val newer = if (PeerBookBackup.stampOf(b) > PeerBookBackup.stampOf(a)) b else a
                if (newer === b) {
                    for (k in listOf("nickname", "notes", "contactNotes", "encryptionPubkey", "rating", "blocked")) b[k]?.let { m[k] = it }
                }
                if ((newer["isContact"] as? JsonPrimitive)?.content == "true") m["isContact"] = JsonPrimitive(true) else m.remove("isContact")
                val first = listOfNotNull(a.long("firstSeen"), b.long("firstSeen")).minOrNull()
                if (first != null) m["firstSeen"] = JsonPrimitive(first)
                m["lastSeen"] = JsonPrimitive(maxOf(a.long("lastSeen") ?: 0, b.long("lastSeen") ?: 0))
                val changedAt = maxOf(a.long("changedAt") ?: 0, b.long("changedAt") ?: 0)
                if (changedAt > 0) m["changedAt"] = JsonPrimitive(changedAt)
                if (m["card"] == null && b["card"] is JsonObject) m["card"] = b["card"]!!
                val mine = b["myRating"] as? JsonObject
                if (mine != null && ((a["myRating"] as? JsonObject)?.long("issuedAt") ?: -1) < (mine.long("issuedAt") ?: 0)) m["myRating"] = mine
                val rec = JsonObject(m)
                if (rec != a) { peers[pk] = rec; n++ }
            }
        }
        // Others' ratings of that person: each one verified against its signer, never taken on trust.
        for ((pk, list) in endorsements) mergeEndorsements(pk, list)
        return n
    }

    /**
     * `adoptPeerCard`: keep another person's card (their devices). The first time it is
     * accepted; afterwards only if it does not go back and the same master signed it. A
     * changed master is SAID, never accepted quietly.
     */
    suspend fun adoptPeerCard(card: JsonObject): CardAdoption = change { peers ->
        val profileId = card.str("profileId") ?: return@change CardAdoption(false, "invalid-signature", 0)
        val current = peers[profileId]?.get("card") as? JsonObject
        val devices = (current?.get("keys") as? JsonArray)?.size ?: 0
        val reason = when {
            !verifyCard(card) -> "invalid-signature"
            current != null && current.str("profileId") != profileId -> "other-profile"
            current == null -> "first-time"
            (card.long("seq") ?: 0) < (current.long("seq") ?: 0) -> "lower-seq"
            card.str("sealedBy") != current.str("sealedBy") -> "master-changed"
            (card.long("seq") ?: 0) > (current.long("seq") ?: 0) -> "higher-seq"
            else -> "same"
        }
        if (reason in setOf("invalid-signature", "other-profile", "lower-seq", "master-changed")) return@change CardAdoption(false, reason, devices)
        upsert(peers, profileId, mapOf("card" to card, "profileId" to JsonPrimitive(profileId)))
        CardAdoption(true, reason, (card["keys"] as? JsonArray)?.size ?: 0)
    }

    /**
     * The card of a person kept by their ACCOUNT (`publickey` is their `profileId`), and only if
     * that profile's own key signed it ([ownCard]).
     *
     * It used to also take ANY card that listed this key among its devices, and that was a hole:
     * anyone can sign a card of THEIR profile that lists somebody else's key, and with it pass for
     * that contact. A contact kept by one device's key has no card until it moves to its account
     * ([accountFromCard]).
     */
    suspend fun cardOf(publickey: String): JsonObject? = lock.withLock {
        val peers = read()
        val card = (peers[publickey]?.get("card") as? JsonObject)
            ?: peers.values.mapNotNull { it["card"] as? JsonObject }.firstOrNull { Delegation.samePubkey(it.str("profileId"), publickey) }
        card?.takeIf { ownCard(it) && Delegation.samePubkey(it.str("profileId"), publickey) }
    }

    /**
     * The account of the device that delivered [card]. It takes both ends: the profile signed the
     * card ([ownCard]) AND the device that brought it is in it. A profile listing a key proves
     * nothing (it can list somebody else's); that device bringing it does. It reads the card KEPT
     * here ([adoptPeerCard] already decided whether to take the one that arrived).
     */
    suspend fun accountFromCard(card: JsonObject?, device: String): String? {
        if (!ownCard(card)) return null
        val kept = cardOf(card!!.str("profileId") ?: return null) ?: return null
        val listed = (kept["keys"] as? JsonArray).orEmpty().any { k -> Delegation.samePubkey((k as? JsonObject)?.str("pub"), device) }
        return if (listed) kept.str("profileId") else null
    }

    /** The devices of a person's account, with their encryption keys (empty without a card). */
    suspend fun devicesOf(publickey: String): List<Pair<String, String>> =
        (cardOf(publickey)?.get("keys") as? JsonArray).orEmpty().mapNotNull { k ->
            val o = k as? JsonObject ?: return@mapNotNull null
            val pub = o.str("pub") ?: return@mapNotNull null
            val enc = o.str("encPub") ?: return@mapNotNull null
            pub to enc
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
