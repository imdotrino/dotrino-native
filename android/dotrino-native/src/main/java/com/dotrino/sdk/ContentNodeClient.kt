package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A CONTENT NODE of this account, as `@dotrino/content-client` speaks to it: a device of the
 * record, over a [RemoteAgent] session. What it adds is the big-file upload (`up.*`), which
 * resumes where it stopped, and the rule of whose network the bytes may cross ([bytesAllowed]).
 */
class ContentNodeClient private constructor(
    private val conn: ProxyConnection,
    private val session: RemoteAgent.Session,
    private val proxyUrl: String,
) {
    class ContentError(message: String, val code: String) : Exception(message)
    enum class Path { YES, ASK, NO }

    companion object {
        /** What a content node answers to a probe (its `label` in `dotrino-content/src/agent.js`). */
        const val KIND = "content"
        const val PART_BYTES = 96 * 1024
        private const val ASK_TIMEOUT_MS = 60_000L

        /**
         * WHOSE NETWORK IT IS (the owner, 2026-11-01). The proxy and TURN of Dotrino are for the
         * content of Dotrino; each person may run their own for their own. A TURN is administered
         * by the proxy connected to, so the proxy's host says it all. Same as `lib/network.js`.
         */
        fun isDotrinoNetwork(proxyUrl: String): Boolean {
            val host = URI(proxyUrl).host?.lowercase() ?: throw IllegalArgumentException("no host in $proxyUrl")
            return host == "dotrino.com" || host.endsWith(".dotrino.com")
        }

        /** Direct goes by itself; on one's own network the owner decides; on Dotrino's, it does not go. */
        fun bytesAllowed(route: String?, proxyUrl: String): Path = when {
            route == "direct" -> Path.YES
            isDotrinoNetwork(proxyUrl) -> Path.NO
            else -> Path.ASK
        }

        /** The content nodes of the account that are ON. Empty is normal: most people have none. */
        suspend fun find(profile: Profile, conn: ProxyConnection): List<String> {
            val all = RemoteAgent.candidates(profile).map { it.first }
            val found = RemoteAgent.probe(conn, all)
            return all.filter { found[it] == KIND }
        }

        suspend fun open(profile: Profile, conn: ProxyConnection, proxyUrl: String, agentPubkey: String, catchUp: (suspend () -> Profile)? = null): ContentNodeClient =
            ContentNodeClient(conn, RemoteAgent.open(profile, conn, agentPubkey, catchUp = catchUp), proxyUrl)
    }

    private val rid = AtomicLong(0)
    private val waiting = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    private val offMessage = session.onMessage { m ->
        m["rid"]?.jsonPrimitive?.longOrNull?.let { waiting.remove(it)?.complete(m) }
    }
    private val offError = session.onError { e -> waiting.values.forEach { it.completeExceptionally(ContentError(e.message ?: "agent error", e.code)) }; waiting.clear() }

    private suspend fun ask(op: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}): JsonObject {
        val id = rid.incrementAndGet()
        val reply = CompletableDeferred<JsonObject>()
        waiting[id] = reply
        session.send(buildJsonObject { put("op", op); put("rid", id); fill() })
        val m = try { withTimeout(ASK_TIMEOUT_MS) { reply.await() } }
        catch (e: TimeoutCancellationException) { waiting.remove(id); throw ContentError("the node did not answer", "timeout") }
        if (m["ok"]?.jsonPrimitive?.contentOrNull == "false") {
            throw ContentError(m["error"]?.jsonPrimitive?.contentOrNull ?: "the node refused", m["code"]?.jsonPrimitive?.contentOrNull ?: "failed")
        }
        return m
    }

    /** Where this session goes NOW: `direct`, `turn`, `webrtc`, `connecting`, `failed` or `proxy`. */
    fun route(): String = session.agentToken?.let { conn.routeOf(it) } ?: "proxy"

    /**
     * Says hello, which is what starts negotiating the channel, and waits for it to settle. Never
     * longer than [timeoutMs]: then the answer is whatever road there is.
     */
    suspend fun settle(timeoutMs: Long = 12_000): String {
        ask("hello")
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val r = route()
            if (r == "direct" || r == "turn" || r == "failed") return r
            delay(150)
        }
        return route()
    }

    fun allowed(): Path = bytesAllowed(route(), proxyUrl)

    /** Uploads [file], whose hash is [cid]; picks up where a previous attempt stopped. */
    suspend fun upload(file: File, cid: String, mime: String, tag: String? = null, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val size = file.length()
        val begin = ask("up.begin") {
            put("cid", cid); put("size", size); put("mime", mime); put("enc", 1)
            if (tag != null) put("tag", tag)
        }
        if (begin["existed"]?.jsonPrimitive?.contentOrNull == "true") { onProgress(size, size); return }
        var at = begin["at"]?.jsonPrimitive?.longOrNull ?: throw ContentError("the node did not say where to go on", "bad-reply")
        RandomAccessFile(file, "r").use { f ->
            val buf = ByteArray(PART_BYTES)
            while (at < size) {
                f.seek(at)
                val n = f.read(buf)
                if (n <= 0) throw ContentError("${file.name} ended before its size", "short-file")
                val data = Base64.getEncoder().encodeToString(if (n == buf.size) buf else buf.copyOf(n))
                val r = ask("up.part") { put("cid", cid); put("at", at); put("data", data) }
                at = r["at"]?.jsonPrimitive?.longOrNull ?: throw ContentError("the node did not say where to go on", "bad-reply")
                onProgress(at, size)
            }
        }
        ask("up.end") { put("cid", cid) }
    }

    /** Something small (a card): one message, as in `ContentClient.put`. */
    suspend fun put(bytes: ByteArray, mime: String, tag: String?): String {
        val r = ask("put") {
            put("data", Base64.getEncoder().encodeToString(bytes)); put("mime", mime); put("enc", 1)
            if (tag != null) put("tag", tag)
        }
        return r["cid"]?.jsonPrimitive?.contentOrNull ?: throw ContentError("the node did not give a cid", "bad-reply")
    }

    fun close() { offMessage(); offError(); session.close() }
}
