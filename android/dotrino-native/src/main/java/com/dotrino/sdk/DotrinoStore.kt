package com.dotrino.sdk

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * The native port of `@dotrino/store`: what an app keeps of the user, on the device, sealed.
 * Same shape as the web store so the same app logic reads both: THREADS of ENTRIES, each entry
 * a JSON object with its own `id` (and usually `ts` and `doc`). Appending an entry whose id is
 * already in the thread replaces it in place, like the web store.
 *
 * What it does NOT do yet (PENDIENTES.md, «Apps con versión nativa»): the per-profile space
 * (`base::<pid>`) and the backup to the vault. Until then the store is the device's.
 *
 * One sealed file per app. Same piece as `DotrinoStore.swift`.
 */
class DotrinoStore(context: Context, app: String) : VaultBackup.Threads {
    init {
        require(Regex("[a-z0-9-]+").matches(app)) { "app name must be [a-z0-9-]: $app" }
    }

    /**
     * The file, its cache and its lock are the APP's, not the instance's: two `DotrinoStore`
     * of the same app (the results screen and the tournaments one) share them. With a cache
     * each, the second to write would overwrite what the first wrote.
     */
    private class Shared(val sealed: SealedFile) { var cache: StoreThreads? = null }

    companion object {
        private val shared = HashMap<String, Shared>()
    }

    private val s: Shared = synchronized(shared) {
        shared.getOrPut(app) { Shared(SealedFile(context.applicationContext, "dotrino-store-$app.bin", "dotrino.store.$app")) }
    }

    private fun load(): StoreThreads {
        s.cache?.let { return it }
        val t = StoreThreads.decode(s.sealed.read()?.toString(Charsets.UTF_8))
        s.cache = t
        return t
    }

    private fun save(t: StoreThreads) = s.sealed.write(t.encode().toByteArray(Charsets.UTF_8))

    fun listThread(thread: String): List<JsonObject> = synchronized(s) { load().list(thread) }

    fun appendMessage(thread: String, entry: JsonObject) = synchronized(s) {
        val t = load().copy()
        t.append(thread, entry)
        save(t)
        s.cache = t
    }

    fun removeMessage(thread: String, id: String) = synchronized(s) {
        val t = load().copy()
        if (!t.remove(thread, id)) return@synchronized
        save(t)
        s.cache = t
    }

    /** The threads with entries. */
    override fun threadKeys(): List<String> = synchronized(s) { load().keys() }

    /** Read-only access to the threads (for the vault backup, which compares and plans). */
    override fun <T> read(f: (StoreThreads) -> T): T = synchronized(s) { f(load()) }

    /** A change applied as a whole and saved once (what comes from the vault). */
    override fun <T> change(f: (StoreThreads) -> T): T = synchronized(s) {
        val t = load().copy()
        val r = f(t)
        save(t)
        s.cache = t
        r
    }
}

/**
 * The threads themselves, without the file: plain logic, tested on the JVM (the Keystore of
 * [SealedFile] only exists on a device). Order inside a thread is insertion order.
 *
 * The rules are `@dotrino/store/core`'s, byte for byte, because the vault holds the same
 * threads and both sides compare DIGESTS (`threadDigest`): a different sort or a different
 * number format would make them never agree, and the sync would repeat forever in silence.
 *
 *   tombs: { threadKey: { id: [ts, at] } } — «version `ts` of `id` was deleted at `at`».
 * A tombstone keeps the ts of what was deleted, not the time of the deletion: deciding whether
 * a copy from another device is the deleted one does not depend on anybody's clock.
 */
class StoreThreads private constructor(
    private val threads: MutableMap<String, MutableList<JsonObject>>,
    private val tombs: MutableMap<String, MutableMap<String, LongArray>>,
) {
    /** A thread's index as the vault sends it: items [[id, ts]], tombs [[id, ts, at]]. */
    data class Index(val items: List<Pair<String, Long>>, val tombs: List<Triple<String, Long, Long>>)

    data class Plan(val pushIds: List<String>, val pushTombs: List<String>, val pullIds: List<String>, val pullTombs: List<Triple<String, Long, Long>>)

    companion object {
        /** `TOMB_TTL_MS`: a tombstone is forgotten after 180 days. */
        const val TOMB_TTL_MS = 180L * 24 * 60 * 60 * 1000
        const val MAX_PER_THREAD = 50_000

        fun empty() = StoreThreads(linkedMapOf(), linkedMapOf())

        /** null or no file = empty; anything that is not the expected shape is an error. */
        fun decode(text: String?): StoreThreads {
            if (text == null) return empty()
            // Same file shape as DotrinoStore.swift: the thread order is written, since a JSON
            // object has none on the Swift side.
            val root = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalStateException("store: root is not an object")
            val order = root["order"] as? JsonArray ?: throw IllegalStateException("store: no thread order")
            val body = root["threads"] as? JsonObject ?: throw IllegalStateException("store: no threads")
            val out = linkedMapOf<String, MutableList<JsonObject>>()
            for (n in order) {
                val name = (n as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalStateException("store: thread list is broken")
                val arr = body[name] as? JsonArray ?: throw IllegalStateException("store: thread $name is not a list")
                out[name] = arr.map { e ->
                    val o = e as? JsonObject ?: throw IllegalStateException("store: an entry of $name is not an object")
                    idOf(o)
                    o
                }.toMutableList()
            }
            // Tombstones: optional (files from before they existed have none).
            val tb = linkedMapOf<String, MutableMap<String, LongArray>>()
            (root["tombs"] as? JsonObject)?.forEach { (k, v) ->
                val m = linkedMapOf<String, LongArray>()
                (v as? JsonObject)?.forEach { (id, row) ->
                    val a = row as? JsonArray ?: throw IllegalStateException("store: tombstone $k/$id is broken")
                    m[id] = longArrayOf(a[0].jsonPrimitive.content.toLong(), a[1].jsonPrimitive.content.toLong())
                }
                if (m.isNotEmpty()) tb[k] = m
            }
            return StoreThreads(out, tb)
        }

        private fun idOf(e: JsonObject): String {
            val id = e["id"] as? JsonPrimitive
            if (id == null || !id.isString || id.content.isEmpty()) throw IllegalArgumentException("store: entry without a string id")
            return id.content
        }

        /** `Number(e.ts) || 0`. */
        fun tsOf(e: JsonObject): Long = (e["ts"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toLong() ?: 0L

        /**
         * `threadDigest`: SHA-256 of «id TAB ts» per entry, sorted (UTF-16 units, as JS
         * compares), joined by newlines, in hex. Tombstones do not count.
         */
        fun digest(entries: List<JsonObject>): String {
            val lines = entries.map { "${idOf(it)}	${tsOf(it)}" }.sorted()
            val h = java.security.MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
            return h.joinToString("") { "%02x".format(it) }
        }

        /** `planThread` of `vault-sync.js`: what to move for one thread, from both indexes only. */
        fun plan(local: Index?, remote: Index?, max: Int = MAX_PER_THREAD): Plan {
            val l = local ?: Index(emptyList(), emptyList()); val r = remote ?: Index(emptyList(), emptyList())
            val lItems = l.items.associate { it.first to it.second }; val rItems = r.items.associate { it.first to it.second }
            val lTombs = l.tombs.associate { it.first to it.second }; val rTombs = r.tombs.associateBy { it.first }
            val pushIds = mutableListOf<String>(); val pushTombs = mutableListOf<String>()
            val pullIds = mutableListOf<String>(); val pullTombs = mutableListOf<Triple<String, Long, Long>>()
            for ((id, ts) in lTombs) { val x = rItems[id]; if (x != null && x <= ts) pushTombs.add(id) }
            for ((id, row) in rTombs) { val x = lItems[id]; if (x != null && x <= row.second) pullTombs.add(row) }
            for ((id, ts) in lItems) {
                val rt = rTombs[id]
                if (rt != null && ts <= rt.second) continue
                val x = rItems[id]
                if (x == null || ts > x) pushIds.add(id)
            }
            val full = lItems.size >= max
            val oldest = if (full) lItems.values.minOrNull() ?: Long.MAX_VALUE else Long.MAX_VALUE
            for ((id, ts) in rItems) {
                val lt = lTombs[id]
                if (lt != null && ts <= lt) continue
                val x = lItems[id]
                if (if (x != null) ts > x else !(full && ts < oldest)) pullIds.add(id)
            }
            return Plan(pushIds, pushTombs, pullIds, pullTombs)
        }
    }

    fun copy() = StoreThreads(
        threads.mapValuesTo(linkedMapOf()) { it.value.toMutableList() },
        tombs.mapValuesTo(linkedMapOf()) { it.value.mapValuesTo(linkedMapOf()) { e -> e.value.copyOf() } },
    )

    fun list(thread: String): List<JsonObject> = threads[thread]?.toList() ?: emptyList()

    /** The threads with entries. */
    fun keys(): List<String> = threads.filterValues { it.isNotEmpty() }.keys.toList()

    /** Writing an entry again says it is back: its tombstone goes. */
    fun append(thread: String, entry: JsonObject) {
        val id = idOf(entry)
        val list = threads.getOrPut(thread) { mutableListOf() }
        val i = list.indexOfFirst { it["id"]!!.jsonPrimitive.content == id }
        if (i >= 0) list[i] = entry else list.add(entry)
        tombs[thread]?.let { t -> t.remove(id); if (t.isEmpty()) tombs.remove(thread) }
    }

    /** Deletes and leaves a tombstone, so the entry does not come back from another device. */
    fun remove(thread: String, id: String, now: Long = System.currentTimeMillis()): Boolean {
        val list = threads[thread] ?: return false
        val gone = list.filter { it["id"]!!.jsonPrimitive.content == id }
        if (gone.isEmpty()) return false
        list.removeAll(gone)
        bury(thread, id, tsOf(gone.first()), now)
        if (list.isEmpty()) threads.remove(thread)
        return true
    }

    private fun bury(k: String, id: String, ts: Long, at: Long): Boolean {
        val t = tombs.getOrPut(k) { linkedMapOf() }
        val prev = t[id]
        val next = if (prev != null) longArrayOf(maxOf(prev[0], ts), minOf(prev[1], at)) else longArrayOf(ts, at)
        if (prev != null && prev.contentEquals(next)) return false
        t[id] = next
        return true
    }

    private fun isBuried(k: String, e: JsonObject): Boolean = tombs[k]?.get(idOf(e))?.let { tsOf(e) <= it[0] } ?: false

    fun digestOf(k: String): String? = threads[k]?.takeIf { it.isNotEmpty() }?.let { digest(it) }

    fun index(k: String): Index = Index(
        (threads[k] ?: emptyList()).map { idOf(it) to tsOf(it) },
        (tombs[k] ?: emptyMap<String, LongArray>()).map { (id, v) -> Triple(id, v[0], v[1]) },
    )

    fun entries(k: String, ids: Collection<String>): List<JsonObject> {
        val want = ids.toSet()
        return (threads[k] ?: emptyList()).filter { idOf(it) in want }
    }

    fun tombRows(k: String, ids: Collection<String>): List<Triple<String, Long, Long>> =
        ids.mapNotNull { id -> tombs[k]?.get(id)?.let { Triple(id, it[0], it[1]) } }

    /** `mergeEntries`, mode `merge`: the greater ts wins; what is buried does not enter. Returns the threads that changed. */
    fun merge(incoming: Map<String, List<JsonObject>>, max: Int = MAX_PER_THREAD): Set<String> {
        val changed = linkedSetOf<String>()
        for ((k, arr) in incoming) {
            if (arr.isEmpty()) continue
            val byId = linkedMapOf<String, JsonObject>()
            for (e in threads[k] ?: emptyList()) byId[idOf(e)] = e
            var touched = false
            for (e in arr) {
                if (isBuried(k, e)) continue
                val prev = byId[idOf(e)]
                if (prev == null || tsOf(e) > tsOf(prev)) { byId[idOf(e)] = e; touched = true }
            }
            if (!touched) continue
            val merged = byId.values.sortedBy { tsOf(it) }.toMutableList()
            if (merged.size > max) repeat(merged.size - max) { merged.removeAt(0) }
            threads[k] = merged
            changed.add(k)
        }
        return changed
    }

    /** `applyTombs`: note tombstones from elsewhere and drop what they bury. Returns the threads whose entries changed. */
    fun applyTombs(incoming: Map<String, List<Triple<String, Long, Long>>>): Set<String> {
        val changed = linkedSetOf<String>()
        for ((k, rows) in incoming) {
            for ((id, ts, at) in rows) bury(k, id, ts, at)
            val list = threads[k] ?: continue
            val kept = list.filterNot { isBuried(k, it) }
            if (kept.size == list.size) continue
            if (kept.isEmpty()) threads.remove(k) else threads[k] = kept.toMutableList()
            changed.add(k)
        }
        return changed
    }

    /** `pruneTombs`: forget tombstones older than [TOMB_TTL_MS]. */
    fun pruneTombs(now: Long): Boolean {
        var changed = false
        for (k in tombs.keys.toList()) {
            val t = tombs[k]!!
            if (t.entries.removeIf { it.value[1] < now - TOMB_TTL_MS }) changed = true
            if (t.isEmpty()) { tombs.remove(k); changed = true }
        }
        return changed
    }

    fun encode(): String = JsonObject(mapOf(
        "order" to JsonArray(threads.keys.map { JsonPrimitive(it) }),
        "threads" to JsonObject(threads.mapValues { JsonArray(it.value) }),
        "tombs" to JsonObject(tombs.mapValues { (_, t) -> JsonObject(t.mapValues { JsonArray(listOf(JsonPrimitive(it.value[0]), JsonPrimitive(it.value[1]))) }) }),
    )).toString()
}
