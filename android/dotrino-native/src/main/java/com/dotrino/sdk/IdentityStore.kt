package com.dotrino.sdk

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What the identity of the WebView (the `id.dotrino.com` iframe) keeps, held natively: ONE
 * store for every page and every app of the phone (`dotrino-identity/vault/nativeStore.js`).
 * String keys to string values (`kv:…`, `key:…`, `peers:…`). No private key is ever in here.
 * Same piece as `IdentityStore.swift`; lives in the identity app.
 */
class IdentityStore(context: Context) {
    private val sealed = SealedFile(context, "dotrino-identity.bin", "dotrino.identity-store")
    private var cache: MutableMap<String, String>? = null

    private fun load(): MutableMap<String, String> {
        cache?.let { return it }
        val out = mutableMapOf<String, String>()
        sealed.read()?.let { plain ->
            val o = Json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject
                ?: throw IllegalStateException("identity store is not a map")
            for ((k, v) in o) out[k] = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalStateException("identity store: $k is not a string")
        }
        cache = out
        return out
    }

    private fun save(m: Map<String, String>) =
        sealed.write(JsonObject(m.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8))

    @Synchronized fun all(): Map<String, String> = load().toMap()

    @Synchronized fun set(k: String, v: String) {
        val m = load()
        m[k] = v
        save(m)
    }

    @Synchronized fun remove(k: String) {
        val m = load()
        if (m.remove(k) != null) save(m)
    }
}
