package com.dotrino.sdk

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * THE STORE'S BACKUP IN THE OWNER'S VAULT, for native apps: `vault-sync.js` of
 * `@dotrino/store`. Reads and writes stay LOCAL (instant, offline); this reconciles with the
 * vault underneath, so what is written on the phone reaches the PWA of the same account and
 * the other way round.
 *
 *  - It compares a DIGEST per thread and only works the threads that differ: first their
 *    index (id and ts), then only the entries missing on each side. UPLOAD first: if the
 *    local side trims on download, what only lived here is already safe.
 *  - Deletions travel as tombstones, so they do not come back from another device.
 *  - Everything goes encrypted with the profile's content key; pages stay under 1 MB.
 *  - The vault keeps ONE store for every app of the profile; a native app keeps its own. So it
 *    only syncs the threads [owns] says are the app's (the messenger: contacts' keys).
 *
 * One reconciliation at a time. It opens its own connection to the proxy, like `vaultRpc`.
 */
class VaultBackup(
    private val profile: Profile,
    private val store: Threads,
    private val owns: (String) -> Boolean,
) {
    /** What the backup needs of the store: `DotrinoStore` on a phone, memory in the JVM tests. */
    interface Threads {
        fun threadKeys(): List<String>
        fun <T> read(f: (StoreThreads) -> T): T
        fun <T> change(f: (StoreThreads) -> T): T
    }

    class MemoryThreads(var t: StoreThreads = StoreThreads.empty()) : Threads {
        override fun threadKeys() = t.keys()
        override fun <T> read(f: (StoreThreads) -> T) = f(t)
        override fun <T> change(f: (StoreThreads) -> T): T { val c = t.copy(); val r = f(c); t = c; return r }
    }

    companion object {
        private const val PUSH_BYTES = 350_000
        private const val REF_BYTES = 150_000
        private const val MAX_ENTRY_BYTES = 550_000
    }

    class BackupError(message: String, val code: String) : Exception(message)

    /** What the last reconciliation did: threads that changed HERE (to repaint), and entries too large to travel. */
    data class Result(val changed: Set<String>, val tooLarge: List<String>)

    private val lock = Mutex()

    /** Reconcile now. Throws with a code: `not-paired`, `no-content-key`, or the vault's. */
    suspend fun sync(): Result = lock.withLock {
        val link = profile.vault ?: throw BackupError("this phone is not paired with a vault", "not-paired")
        val conn = ProxyConnection(link.proxy)
        try {
            conn.connect()
            runCatching { conn.identifyAs(profile.publickey) { profile.signData(it) } }
            val account = Account(id = "backup", name = "", profileId = profile.profileId, vault = link.master, proxy = link.proxy, cert = link.cert, deviceId = link.deviceId)
            val vault = VaultClient(account, profile.deviceKeys, conn)
            reconcile { method, args -> vault.store(profile, method, args) }
        } finally { conn.close() }
    }

    /** The algorithm, with the vault as a function: tested on the JVM against a fake vault. */
    internal suspend fun reconcile(call: suspend (String, JsonObject) -> JsonElement): Result {
        val tooLarge = mutableListOf<String>()
        val remoteDigests = (call("getThreadDigests", JsonObject(emptyMap())) as? JsonObject).orEmpty()
        val localKeys = store.threadKeys().filter(owns)
        val keys = (localKeys + remoteDigests.keys.filter(owns)).distinct().filter { k ->
            val l = store.read { it.digestOf(k) }
            val r = ((remoteDigests[k] as? JsonObject)?.get("digest") as? JsonPrimitive)?.content
            l != r
        }
        if (keys.isEmpty()) return Result(emptySet(), tooLarge)

        // The remote index, by pages.
        val remote = HashMap<String, Pair<MutableList<Pair<String, Long>>, MutableList<Triple<String, Long, Long>>>>()
        var cursor: JsonElement = JsonNull
        do {
            val page = call("getThreadIndexes", buildJsonObject {
                put("keys", buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } }); put("cursor", cursor)
            }) as? JsonObject ?: throw BackupError("the vault sent no index", "bad-reply")
            for ((k, idx) in (page["indexes"] as? JsonObject).orEmpty()) {
                val o = idx as? JsonObject ?: continue
                val (items, tombs) = remote.getOrPut(k) { mutableListOf<Pair<String, Long>>() to mutableListOf() }
                (o["items"] as? JsonArray).orEmpty().forEach { r -> (r as? JsonArray)?.let { items.add(it[0].jsonPrimitive.content to it[1].jsonPrimitive.content.toLong()) } }
                (o["tombs"] as? JsonArray).orEmpty().forEach { r -> (r as? JsonArray)?.let { tombs.add(Triple(it[0].jsonPrimitive.content, it[1].jsonPrimitive.content.toLong(), it[2].jsonPrimitive.content.toLong())) } }
            }
            cursor = page["next"] ?: JsonNull
        } while (cursor !is JsonNull)

        val pushRefs = linkedMapOf<String, List<String>>(); val pushTombs = linkedMapOf<String, List<String>>()
        val pullRefs = linkedMapOf<String, List<String>>(); val pullTombs = linkedMapOf<String, List<Triple<String, Long, Long>>>()
        for (k in keys) {
            val r = remote[k]?.let { StoreThreads.Index(it.first, it.second) }
            val plan = StoreThreads.plan(store.read { it.index(k) }, r)
            if (plan.pushIds.isNotEmpty()) pushRefs[k] = plan.pushIds
            if (plan.pushTombs.isNotEmpty()) pushTombs[k] = plan.pushTombs
            if (plan.pullIds.isNotEmpty()) pullRefs[k] = plan.pullIds
            if (plan.pullTombs.isNotEmpty()) pullTombs[k] = plan.pullTombs
        }

        // UPLOAD first, in batches under 1 MB.
        if (pushRefs.isNotEmpty() || pushTombs.isNotEmpty()) {
            val entries = store.read { t -> pushRefs.mapValues { (k, ids) -> t.entries(k, ids) } }
            val tombs = store.read { t -> pushTombs.mapValues { (k, ids) -> t.tombRows(k, ids) } }
            for (batch in batches(entries, tombs, tooLarge)) call("importThreads", batch)
        }

        val changed = linkedSetOf<String>()
        if (pullTombs.isNotEmpty()) changed += store.change { it.applyTombs(pullTombs) }
        for (chunk in chunkRefs(pullRefs)) {
            var refs: JsonObject? = chunk
            while (refs != null) {
                val page = call("getEntries", buildJsonObject { put("refs", refs!!) }) as? JsonObject
                    ?: throw BackupError("the vault sent no entries", "bad-reply")
                val incoming = (page["threads"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } }
                if (incoming.isNotEmpty()) changed += store.change { it.merge(incoming) }
                refs = page["rest"] as? JsonObject
            }
        }
        return Result(changed, tooLarge)
    }

    private fun size(e: JsonElement) = e.toString().toByteArray(Charsets.UTF_8).size + 1

    /** `batches`: entries and tombstones in batches of [PUSH_BYTES]; what does not fit alone goes to [tooLarge]. */
    private fun batches(threads: Map<String, List<JsonObject>>, tombs: Map<String, List<Triple<String, Long, Long>>>, tooLarge: MutableList<String>): List<JsonObject> {
        val out = mutableListOf<Pair<MutableMap<String, MutableList<JsonElement>>, MutableMap<String, MutableList<JsonElement>>>>()
        var bytes = 0
        fun add(kind: Int, k: String, item: JsonElement, id: String) {
            val s = size(item)
            if (s > MAX_ENTRY_BYTES) { tooLarge.add("$k/$id"); return }
            if (out.isEmpty() || bytes + s > PUSH_BYTES) { out.add(linkedMapOf<String, MutableList<JsonElement>>() to linkedMapOf()); bytes = 0 }
            val m = if (kind == 0) out.last().first else out.last().second
            m.getOrPut(k) { mutableListOf() }.add(item); bytes += s
        }
        for ((k, rows) in tombs) for ((id, ts, at) in rows) add(1, k, buildJsonArray { add(JsonPrimitive(id)); add(JsonPrimitive(ts)); add(JsonPrimitive(at)) }, id)
        for ((k, list) in threads) for (e in list) add(0, k, e, (e["id"] as? JsonPrimitive)?.content ?: "?")
        return out.map { (th, tb) -> buildJsonObject {
            put("threads", JsonObject(th.mapValues { JsonArray(it.value) })); put("tombs", JsonObject(tb.mapValues { JsonArray(it.value) })); put("mode", "merge")
        } }
    }

    /** The ids to download, in lists under [REF_BYTES]. */
    private fun chunkRefs(refs: Map<String, List<String>>): List<JsonObject> {
        val out = mutableListOf<MutableMap<String, MutableList<JsonPrimitive>>>()
        var bytes = 0
        for ((k, ids) in refs) for (id in ids) {
            val s = id.length + k.length + 4
            if (out.isEmpty() || bytes + s > REF_BYTES) { out.add(linkedMapOf()); bytes = 0 }
            out.last().getOrPut(k) { mutableListOf() }.add(JsonPrimitive(id)); bytes += s
        }
        return out.map { m -> JsonObject(m.mapValues { JsonArray(it.value) }) }
    }
}
