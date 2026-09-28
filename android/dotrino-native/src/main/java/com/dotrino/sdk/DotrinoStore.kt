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
class DotrinoStore(context: Context, app: String) {
    init {
        require(Regex("[a-z0-9-]+").matches(app)) { "app name must be [a-z0-9-]: $app" }
    }

    private val sealed = SealedFile(context, "dotrino-store-$app.bin", "dotrino.store.$app")
    private var cache: StoreThreads? = null

    private fun load(): StoreThreads {
        cache?.let { return it }
        val t = StoreThreads.decode(sealed.read()?.toString(Charsets.UTF_8))
        cache = t
        return t
    }

    private fun save(t: StoreThreads) = sealed.write(t.encode().toByteArray(Charsets.UTF_8))

    @Synchronized fun listThread(thread: String): List<JsonObject> = load().list(thread)

    @Synchronized fun appendMessage(thread: String, entry: JsonObject) {
        val t = load().copy()
        t.append(thread, entry)
        save(t)
        cache = t
    }

    @Synchronized fun removeMessage(thread: String, id: String) {
        val t = load().copy()
        if (!t.remove(thread, id)) return
        save(t)
        cache = t
    }
}

/**
 * The threads themselves, without the file: plain logic, tested on the JVM (the Keystore of
 * [SealedFile] only exists on a device). Order inside a thread is insertion order.
 */
class StoreThreads private constructor(private val threads: MutableMap<String, MutableList<JsonObject>>) {
    companion object {
        fun empty() = StoreThreads(linkedMapOf())

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
            return StoreThreads(out)
        }

        private fun idOf(e: JsonObject): String {
            val id = e["id"] as? JsonPrimitive
            if (id == null || !id.isString || id.content.isEmpty()) throw IllegalArgumentException("store: entry without a string id")
            return id.content
        }
    }

    fun copy() = StoreThreads(threads.mapValuesTo(linkedMapOf()) { it.value.toMutableList() })

    fun list(thread: String): List<JsonObject> = threads[thread]?.toList() ?: emptyList()

    fun append(thread: String, entry: JsonObject) {
        val id = idOf(entry)
        val list = threads.getOrPut(thread) { mutableListOf() }
        val i = list.indexOfFirst { it["id"]!!.jsonPrimitive.content == id }
        if (i >= 0) list[i] = entry else list.add(entry)
    }

    fun remove(thread: String, id: String): Boolean {
        val list = threads[thread] ?: return false
        return list.removeAll { it["id"]!!.jsonPrimitive.content == id }
    }

    fun encode(): String = JsonObject(mapOf(
        "order" to JsonArray(threads.keys.map { JsonPrimitive(it) }),
        "threads" to JsonObject(threads.mapValues { JsonArray(it.value) }),
    )).toString()
}
