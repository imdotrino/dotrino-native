package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ONE connection to the proxy, speaking the same frames as `@dotrino/proxy-client`
 * (`src/client.js`): `connected` gives the token, `identify` binds my key, a directed
 * message goes `{ to_publickey, message }` and arrives as `{ type:'message', from, message }`.
 *
 * It does not reconnect by itself: whoever owns it (one per account, while the approvals
 * screen is open) decides what a dropped connection means. A dead socket fails every
 * pending request with its reason instead of leaving it to time out.
 */
class ProxyConnection(private val url: String) {
    companion object {
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** A directed message: who sent it (connection token and, if identified, key) and its payload. */
    data class Incoming(val from: String?, val fromPubkey: String?, val payload: JsonObject)

    class ProxyError(message: String, val code: String) : Exception(message)

    private var ws: WebSocket? = null
    private val connected = CompletableDeferred<String>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicInteger(1)
    private val listeners = CopyOnWriteArrayList<(Incoming) -> Unit>()
    @Volatile var closed: String? = null; private set
    var token: String? = null; private set

    /** The audience that goes inside `identify`: the proxy URL without trailing slashes. */
    val audience: String get() = url.trimEnd('/')

    suspend fun connect(timeoutMs: Long = 10_000): String {
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = die("transport: ${t.message}")
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = die("closed: $code $reason")
        })
        return withTimeout(timeoutMs) { connected.await() }
    }

    fun onMessage(l: (Incoming) -> Unit): () -> Unit { listeners.add(l); return { listeners.remove(l) } }

    fun close() { ws?.close(1000, "bye"); die("closed by us") }

    private fun die(reason: String) {
        if (closed != null) return
        closed = reason
        val e = ProxyError(reason, "disconnected")
        connected.completeExceptionally(e)
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
    }

    private fun handle(text: String) {
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return
        val type = o["type"]?.jsonPrimitive?.content
        val id = o["id"]?.jsonPrimitive?.content
        when (type) {
            "connected" -> {
                val t = (o["instance"] ?: o["token"])?.jsonPrimitive?.content
                if (t == null) { die("connected without a token"); return }
                token = t; connected.complete(t)
            }
            "message" -> {
                val raw = o["message"] ?: return
                val payload = when (raw) {
                    is JsonObject -> raw
                    is JsonPrimitive -> try { json.parseToJsonElement(raw.content) as? JsonObject } catch (_: Exception) { null }
                    else -> null
                } ?: return
                val inc = Incoming(o["from"]?.jsonPrimitive?.content, o["from_publickey"]?.jsonPrimitive?.content, payload)
                listeners.forEach { runCatching { it(inc) } }
            }
            "error" -> if (id != null) pending.remove(id)?.completeExceptionally(
                ProxyError(o["error"]?.jsonPrimitive?.content ?: "proxy error", o["code"]?.jsonPrimitive?.content ?: "proxy-error"))
            else -> if (id != null) pending.remove(id)?.complete(o)
        }
    }

    private fun send(frame: JsonObject) {
        closed?.let { throw ProxyError(it, "disconnected") }
        val w = ws ?: throw ProxyError("not connected", "disconnected")
        if (!w.send(frame.toString())) throw ProxyError("could not send: socket closing", "disconnected")
    }

    /** A request with an `id` whose answer comes back with the same `id`. */
    private suspend fun request(frame: JsonObject, timeoutMs: Long = 10_000): JsonObject {
        val id = "req_${nextId.getAndIncrement()}"
        val d = CompletableDeferred<JsonObject>()
        pending[id] = d
        try {
            send(JsonObject(frame + ("id" to JsonPrimitive(id))))
            return withTimeout(timeoutMs) { d.await() }
        } finally { pending.remove(id) }
    }

    /**
     * Binds this connection to my key: messages written to it arrive here live, and what was
     * queued while I was away gets delivered. The token is the challenge of this connection
     * and goes inside what is signed; `aud` says who it is meant for.
     */
    suspend fun identify(keys: DeviceKeys) {
        val t = token ?: throw ProxyError("identify before connecting", "disconnected")
        val data = buildJsonObject {
            put("op", "identify"); put("aud", audience); put("publickey", keys.publickey)
            put("token", t); put("ts", System.currentTimeMillis())
        }
        request(buildJsonObject {
            put("type", "identify"); put("data", data); put("signature", keys.sign(Canonical.stringify(data)))
        })
    }

    /** Registers this phone's FCM token under my key: the proxy rings it when something is queued for me. */
    suspend fun registerPushToken(keys: DeviceKeys, fcmToken: String) {
        val sub = buildJsonObject { put("kind", "fcm"); put("token", fcmToken) }.toString()
        val data = buildJsonObject {
            put("op", "push-subscribe"); put("publickey", keys.publickey); put("subscription", sub)
            put("ts", System.currentTimeMillis())
        }
        request(buildJsonObject {
            put("type", "push-subscribe"); put("data", data); put("signature", keys.sign(Canonical.stringify(data)))
        })
    }

    /** A directed message to a key. The payload travels as a JSON string, like the JS client sends it. */
    fun sendByPubkey(to: String, payload: JsonObject) {
        send(buildJsonObject {
            put("to_publickey", buildJsonArray { add(JsonPrimitive(to)) })
            put("message", payload.toString())
        })
    }
}
