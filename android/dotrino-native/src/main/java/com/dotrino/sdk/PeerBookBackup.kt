package com.dotrino.sdk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

/**
 * THE CONTACT BOOK IN THE VAULT: the same contacts on every device of the profile. The same as
 * `dotrino-identity/vault/peerSync.js` (and its iOS twin): the book is ONE thread of the vault's
 * store (`identity.peers`), one entry per person `{ id, ts, peer }`, encrypted with the profile's
 * content key over the same road as the history ([VaultClient.store]). What is newer here goes
 * up, what is newer there comes down and is MERGED ([PeerBook.mergeFrom]), never overwritten.
 */
class PeerBookBackup(private val profile: Profile, private val book: PeerBook) {
    companion object {
        const val THREAD = "identity.peers"
        private const val PUSH_BYTES = 350_000

        /** When the record last changed: seen (`lastSeen`) or touched (`changedAt`). */
        fun stampOf(rec: JsonObject?): Long = maxOf(
            (rec?.get("lastSeen") as? JsonPrimitive)?.longOrNull ?: 0,
            (rec?.get("changedAt") as? JsonPrimitive)?.longOrNull ?: 0,
        )

        /** The entry's id, from the person's key (sha-256, 32 hex): the same as the web's. */
        fun idOf(publickey: String): String =
            MessageDigest.getInstance("SHA-256").digest(publickey.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }
    }

    /** Reconcile now. Throws `not-paired` without a vault; the vault's own codes otherwise. */
    suspend fun sync(): Int {
        val link = profile.vault ?: throw VaultBackup.BackupError("this phone is not paired with a vault", "not-paired")
        val conn = ProxyConnection(link.proxy)
        try {
            conn.connect()
            runCatching { conn.identifyAs(profile.publickey) { profile.signData(it) } }
            val account = Account(id = "peers", name = "", profileId = profile.profileId, vault = link.master, proxy = link.proxy, cert = link.cert, deviceId = link.deviceId)
            val vault = VaultClient(account, profile.deviceKeys, conn)
            return reconcile { method, args -> vault.store(profile, method, args) }
        } finally { conn.close() }
    }

    /** The algorithm, with the vault as a function: tested on the JVM against a fake vault. Returns records changed here. */
    internal suspend fun reconcile(call: suspend (String, JsonObject) -> JsonElement): Int {
        val remote = HashMap<String, Long>()
        var cursor: JsonElement = JsonNull
        do {
            val page = call("getThreadIndexes", buildJsonObject { put("keys", buildJsonArray { add(JsonPrimitive(THREAD)) }); put("cursor", cursor) }) as? JsonObject
                ?: throw VaultBackup.BackupError("the vault sent no index", "bad-reply")
            ((page["indexes"] as? JsonObject)?.get(THREAD) as? JsonObject)?.get("items")?.let { it as? JsonArray }.orEmpty().forEach { r ->
                (r as? JsonArray)?.let { remote[it[0].jsonPrimitive.content] = it[1].jsonPrimitive.longOrNull ?: 0 }
            }
            cursor = page["next"] ?: JsonNull
        } while (cursor !is JsonNull)

        val local = book.all().mapKeys { idOf(it.key) }.mapValues { (_, rec) -> rec }
        // UP first: what is newer here, in batches.
        val up = local.filter { (id, rec) -> (remote[id] ?: -1) < stampOf(rec) }
        var batch = mutableListOf<JsonObject>(); var bytes = 0
        suspend fun flush() {
            if (batch.isEmpty()) return
            call("importThreads", buildJsonObject {
                put("threads", buildJsonObject { put(THREAD, JsonArray(batch)) }); put("tombs", JsonObject(emptyMap())); put("mode", "merge")
            })
            batch = mutableListOf(); bytes = 0
        }
        for ((id, rec) in up) {
            val e = buildJsonObject { put("id", id); put("ts", stampOf(rec)); put("peer", rec) }
            val size = e.toString().length
            if (bytes + size > PUSH_BYTES) flush()
            batch.add(e); bytes += size
        }
        flush()

        // DOWN: what is newer there.
        val down = remote.filter { (id, ts) -> local[id]?.let { stampOf(it) < ts } ?: true }.keys.toList()
        val incoming = mutableListOf<JsonObject>()
        var refs: JsonObject? = if (down.isEmpty()) null else buildJsonObject { put(THREAD, JsonArray(down.map { JsonPrimitive(it) })) }
        while (refs != null) {
            val page = call("getEntries", buildJsonObject { put("refs", refs!!) }) as? JsonObject
                ?: throw VaultBackup.BackupError("the vault sent no entries", "bad-reply")
            ((page["threads"] as? JsonObject)?.get(THREAD) as? JsonArray).orEmpty().forEach { e -> ((e as? JsonObject)?.get("peer") as? JsonObject)?.let { incoming.add(it) } }
            refs = (page["rest"] as? JsonObject)?.takeIf { it.isNotEmpty() }
        }
        return if (incoming.isEmpty()) 0 else book.mergeFrom(incoming)
    }
}
