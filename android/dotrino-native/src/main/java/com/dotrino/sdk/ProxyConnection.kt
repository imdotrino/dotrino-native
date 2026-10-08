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
/**
 * [app]: WHICH app this connection is (`vault`, `messenger`…; websocket-proxy ≥ 1.4.0). On a
 * phone every Dotrino app speaks with the same key (the phone's profile), so the proxy needs
 * to know which one to ring and whose queued messages to hand over: without it, the last app
 * to subscribe got every ring and the first to connect drained everyone's queue. Routing, not
 * content. `null`: as before, everything.
 */
class ProxyConnection(
    private val url: String,
    val app: String? = null,
    /** Where the traffic is counted ([TrafficStats]). A session passes ONE for all its reconnections. */
    val traffic: TrafficStats = TrafficStats(),
) {
    init { require(app == null || Regex("^[a-z0-9][a-z0-9-]{0,31}$").matches(app)) { "app: \"$app\" is not a valid app name" } }

    companion object {
        const val HELLO_TAG = "__cc_hello__"
        private val http: OkHttpClient by lazy {
            OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
        }
        private val json = Json { ignoreUnknownKeys = true }
    }

    /** A directed message: who sent it (connection token and, if identified, key) and its payload. */
    data class Incoming(
        val from: String?,
        val fromPubkey: String?,
        val payload: JsonObject,
        /** It waited in the proxy's offline queue (and since when). */
        val queued: Boolean = false,
        val queuedAt: Long? = null,
    )

    class ProxyError(message: String, val code: String) : Exception(message)

    private var ws: WebSocket? = null
    private val connected = CompletableDeferred<String>()
    private val ended = CompletableDeferred<String>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicInteger(1)
    private val listeners = CopyOnWriteArrayList<(Incoming) -> Unit>()
    @Volatile var closed: String? = null; private set
    var token: String? = null; private set
    /** The proxy node this connection lives on (12 chars), from `connected`. */
    var node: String? = null; private set
    /** What this proxy says it can do (`caps`), or null for a proxy older than that. */
    var caps: List<String>? = null; private set

    /** Transport events besides messages: a peer left (`disconnected`), a channel changed. */
    sealed class Event {
        data class PeerGone(val token: String, val channel: String?) : Event()
        data class Joined(val channel: String, val token: String) : Event()
        data class Left(val channel: String, val token: String) : Event()
        data class PeerIdentity(val token: String, val publickey: String) : Event()
    }
    private val events = CopyOnWriteArrayList<(Event) -> Unit>()
    fun onEvent(l: (Event) -> Unit): () -> Unit { events.add(l); return { events.remove(l) } }
    private fun emit(e: Event) = events.forEach { runCatching { it(e) } }

    // THE GREETING (`helloTo` of the JS client): «this token is this identity». A control
    // frame of the transport, in the clear on purpose: it only carries a PUBLIC key the proxy
    // already bound to the connection at `identify`.
    private val tokenPubkeys = ConcurrentHashMap<String, String>()
    /** The direct road ([useDirect]), for a connection that no [SealedSession] wraps. */
    @Volatile private var direct: DirectTransport? = null
    private val helloSent = ConcurrentHashMap.newKeySet<String>()
    /** The identity I identified as; the greeting says it. */
    @Volatile var myPublickey: String? = null; private set

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

    /** Suspends until this connection dies, and says why. */
    suspend fun awaitClosed(): String = ended.await()

    private fun die(reason: String) {
        if (closed != null) return
        closed = reason
        ended.complete(reason)
        runCatching { direct?.closeAll() }
        val e = ProxyError(reason, "disconnected")
        connected.completeExceptionally(e)
        pending.values.forEach { it.completeExceptionally(e) }
        pending.clear()
    }

    private fun handle(text: String) {
        traffic.frame(true, utf8Length(text))
        val o = try { json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null } ?: return
        val type = o["type"]?.jsonPrimitive?.content
        val id = o["id"]?.jsonPrimitive?.content
        when (type) {
            "connected" -> {
                val t = (o["instance"] ?: o["token"])?.jsonPrimitive?.content
                if (t == null) { die("connected without a token"); return }
                token = t
                node = o["node"]?.jsonPrimitive?.content
                caps = (o["caps"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }
                connected.complete(t)
            }
            "message" -> {
                val raw = o["message"] ?: return
                val payload = when (raw) {
                    is JsonObject -> raw
                    is JsonPrimitive -> try { json.parseToJsonElement(raw.content) as? JsonObject } catch (_: Exception) { null }
                    else -> null
                } ?: return
                val from = o["from"]?.jsonPrimitive?.content
                traffic.peer(true, "proxy", from, o["from_publickey"]?.jsonPrimitive?.content ?: from?.let { tokenPubkeys[it] },
                    utf8Length(if (raw is JsonPrimitive) raw.content else raw.toString()))
                // The greeting is the transport's: it is answered here and never reaches the app.
                if ((payload["t"] as? JsonPrimitive)?.content == HELLO_TAG) {
                    if (from != null) onHello(from, payload)
                    return
                }
                // The direct road's signalling is a transport control frame: to WebRTC, not the app.
                if ((payload["t"] as? JsonPrimitive)?.content == DirectTransport.RTC_TAG) {
                    val d = direct
                    if (d != null && from != null) d.handleSignal(from, payload)
                    return
                }
                val inc = Incoming(
                    from, o["from_publickey"]?.jsonPrimitive?.content, payload,
                    queued = o["queued"]?.jsonPrimitive?.content == "true",
                    queuedAt = o["queued_at"]?.jsonPrimitive?.content?.toLongOrNull(),
                )
                listeners.forEach { runCatching { it(inc) } }
            }
            "disconnected" -> {
                val t = o["token"]?.jsonPrimitive?.content ?: return
                // The token dies with the connection and is never reused: what it said is forgotten.
                tokenPubkeys.remove(t); helloSent.remove(t)
                emit(Event.PeerGone(t, o["channel"]?.jsonPrimitive?.content))
                if (id != null) pending.remove(id)?.complete(o)
            }
            "joined" -> emit(Event.Joined(o["channel"]?.jsonPrimitive?.content ?: return, o["token"]?.jsonPrimitive?.content ?: return))
            "left" -> emit(Event.Left(o["channel"]?.jsonPrimitive?.content ?: return, o["token"]?.jsonPrimitive?.content ?: return))
            "message_sent" -> {
                val w = id?.let { tokenWatch.remove(it) }
                val failed = (o["failed"] as? kotlinx.serialization.json.JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                if (w != null && w.first in failed) {
                    tokenPubkeys.remove(w.first); helloSent.remove(w.first)
                    emit(Event.PeerGone(w.first, null))
                    runCatching { w.third() }
                }
                if (id != null) pending.remove(id)?.complete(o)
            }
            "error" -> if (id != null) pending.remove(id)?.completeExceptionally(
                ProxyError(o["error"]?.jsonPrimitive?.content ?: "proxy error", o["code"]?.jsonPrimitive?.content ?: "proxy-error"))
            else -> if (id != null) pending.remove(id)?.complete(o)
        }
    }

    private fun send(frame: JsonObject) {
        closed?.let { throw ProxyError(it, "disconnected") }
        val w = ws ?: throw ProxyError("not connected", "disconnected")
        val text = frame.toString()
        if (!w.send(text)) throw ProxyError("could not send: socket closing", "disconnected")
        countOut(frame, text)
    }

    /** Counts a frame that left through the proxy, and whom it was for. */
    private fun countOut(frame: JsonObject, text: String) {
        traffic.frame(false, utf8Length(text))
        val msg = (frame["message"] as? JsonPrimitive)?.content ?: return
        val n = utf8Length(msg)
        (frame["to"] as? kotlinx.serialization.json.JsonArray)?.forEach { t ->
            val tk = t.jsonPrimitive.content
            traffic.peer(false, "proxy", tk, tokenPubkeys[tk], n)
        }
        (frame["to_publickey"] as? kotlinx.serialization.json.JsonArray)?.forEach { k ->
            traffic.peer(false, "proxy", null, k.jsonPrimitive.content, n)
        }
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
    suspend fun identify(keys: DeviceKeys) = identifyAs(keys.publickey) { keys.sign(Canonical.stringify(it)) }

    /**
     * `identifyAs` of the JS client: [publickey] signs (with [sign], over the canonical data)
     * that it is behind this connection. A profile signs through its own policy
     * ([Profile.signData]), so the signer is given, not the keys.
     */
    suspend fun identifyAs(publickey: String, sign: suspend (JsonObject) -> String) {
        val t = token ?: throw ProxyError("identify before connecting", "disconnected")
        val data = buildJsonObject {
            put("op", "identify"); put("aud", audience); put("publickey", publickey)
            put("token", t); put("ts", System.currentTimeMillis())
        }
        request(buildJsonObject {
            put("type", "identify"); put("data", data); put("signature", sign(data))
            if (app != null) put("app", app)
        })
        myPublickey = publickey
    }

    // ---------- the greeting ----------

    fun helloTo(to: String) {
        val me = myPublickey ?: throw ProxyError("helloTo: identify first", "not-identified")
        if (to == token) return
        helloSent.add(to)
        sendTo(listOf(to), buildJsonObject { put("t", HELLO_TAG); put("publickey", me) })
    }

    /** Whose this token is, if someone said it. */
    fun pubkeyOfToken(t: String): String? = tokenPubkeys[t]

    // ---- the direct road for a BARE connection (0.28.0) ----
    //
    // The transport always prefers the most direct road (CLAUDE.md, 2026-09-03): WebRTC direct,
    // WebRTC through TURN, and the proxy last. [SealedSession] has this for its own; a connection
    // used on its own (the terminal's, which carries [RemoteAgent] sessions) gets it here, the same
    // way: plug the road in, send BY TOKEN, and the first message opens the channel underneath.

    /**
     * Plugs in the direct road (`dotrino-webrtc`). What [sendToOrUpgrade] sends by token prefers an
     * open channel; what arrives through one is delivered like a proxy message.
     */
    fun useDirect(d: DirectTransport) {
        direct = d
        d.bind(
            selfToken = { token },
            signalSend = { to, msg -> runCatching { sendTo(listOf(to), msg) } },
            deliver = { from, text ->
                traffic.peer(true, d.route(from) ?: "webrtc", from, pubkeyOfToken(from), utf8Length(text))
                val payload = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@bind
                val inc = Incoming(from, pubkeyOfToken(from), payload)
                listeners.forEach { runCatching { it(inc) } }
            },
        )
    }

    /** Whether a direct road is plugged in. */
    val hasDirect: Boolean get() = direct != null

    /** Where [token] goes NOW (`direct` / `turn` / `webrtc` / `connecting` / `failed`), or null: the proxy. */
    fun routeOf(token: String): String? = direct?.route(token)

    /**
     * To ONE token by the best road there is NOW: the open channel, or the proxy — and, knowing
     * whose the token is, a dead token (they restarted) sends the same thing by pubkey, to the
     * queue. Then it tries to go direct for the next one, without waiting for anybody. The
     * `sendToOrQueue` of the JS client.
     */
    fun sendToOrUpgrade(token: String, payload: JsonObject, peerPubkey: String? = null) {
        val d = direct
        val text = payload.toString()
        if (d != null && d.send(token, text)) {
            traffic.peer(false, d.route(token) ?: "webrtc", token, peerPubkey ?: pubkeyOfToken(token), utf8Length(text))
            return
        }
        if (peerPubkey != null) sendToOrElse(token, payload) { runCatching { sendByPubkey(peerPubkey, payload, toApp = app) } }
        else sendTo(listOf(token), payload)
        d?.upgrade(token)
    }

    /**
     * TURN for the direct road: the proxy's credentials for this identity (signed), plus STUN.
     * Call after [identifyAs]; without a direct road it does nothing.
     */
    suspend fun enableTurn(publickey: String, sign: suspend (JsonObject) -> String) {
        val d = direct ?: return
        val servers = runCatching { turnCredentials(publickey, sign) }.getOrNull() ?: return
        if (servers.isNotEmpty()) d.setIceServers(servers + SealedSession.DEFAULT_STUN)
    }

    /**
     * A BARE connection shows its traffic in the topbar too (0.27.0), with the road each token goes
     * by (0.28.0: [useDirect]). The app registers [statsSource] with [DotrinoNetwork] once it is
     * identified, and unregisters it when it closes — [SealedSession] does that by itself for its own.
     */
    fun networkStats(): NetworkStats {
        val (proxy, peers) = traffic.snapshot({ routeOf(it) }, { pubkeyOfToken(it) })
        return NetworkStats(url, app, node, token, !ended.isCompleted && token != null, traffic.since, proxy, peers)
    }
    /** The same object every time, so it can be unregistered. */
    val statsSource: DotrinoNetwork.Source = DotrinoNetwork.Source { networkStats() }

    private fun onHello(from: String, msg: JsonObject) {
        val pk = (msg["publickey"] as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } ?: return
        val before = tokenPubkeys[from]
        // A TOKEN DOES NOT CHANGE OWNER: a second greeting with another identity is an attempt
        // to get sealed to someone else. The first one stands.
        if (before != null && !Delegation.samePubkey(before, pk)) return
        tokenPubkeys[from] = pk
        if (from !in helloSent && myPublickey != null) runCatching { helloTo(from) }
        emit(Event.PeerIdentity(from, pk))
    }

    // ---------- by token and in channels ----------

    /** A message to connection tokens, `{ to, message }` like `_proxySendOne` of the JS client. */
    fun sendTo(tokens: List<String>, payload: JsonObject) {
        send(buildJsonObject {
            put("to", buildJsonArray { tokens.forEach { add(JsonPrimitive(it)) } })
            put("message", payload.toString())
        })
    }

    // What went to a token and waits to know if that token still exists (see [sendToOrElse]).
    private val tokenWatch = java.util.concurrent.ConcurrentHashMap<String, Triple<String, Long, () -> Unit>>()

    /**
     * [sendTo] for ONE token, but a dead token does not swallow the message. A token is a
     * connection: when the other side restarts its app it stops existing, and the proxy answers
     * `message_sent` with it in `failed` (it only answers when something fails). Then [onGone]
     * runs — the caller sends the same thing by pubkey, to the queue — and `PeerGone` is emitted
     * so nobody keeps writing there. The same as `sendSealedTo` in `@dotrino/proxy-client` 0.26.
     */
    fun sendToOrElse(token: String, payload: JsonObject, onGone: () -> Unit) {
        val now = System.currentTimeMillis()
        tokenWatch.entries.removeIf { now - it.value.second > 15_000 }
        val id = "msg_${nextId.getAndIncrement()}"
        tokenWatch[id] = Triple(token, now, onGone)
        try {
            send(buildJsonObject {
                put("to", buildJsonArray { add(JsonPrimitive(token)) })
                put("message", payload.toString())
                put("id", id)
            })
        } catch (e: Exception) { tokenWatch.remove(id); throw e }
    }

    /**
     * `buildSignedChannel`: the channel entry is signed by the TRANSPORT key of this app
     * (`proxy-client`'s own keypair), not by the identity.
     */
    private suspend fun signedChannel(name: String, transport: DeviceKeys): JsonObject {
        val data = buildJsonObject { put("name", name); put("publickey", transport.publickey) }
        return buildJsonObject { put("data", data); put("signature", transport.sign(Canonical.stringify(data))) }
    }

    suspend fun publish(channel: String, transport: DeviceKeys) {
        request(buildJsonObject { put("type", "publish"); put("channel", signedChannel(channel, transport)) })
    }

    suspend fun unpublish(channel: String, transport: DeviceKeys) {
        request(buildJsonObject { put("type", "unpublish"); put("channel", signedChannel(channel, transport)) })
    }

    // ---------- encryption keys ----------

    private val encPubs = ConcurrentHashMap<String, String>()

    /**
     * ANNOUNCE MY ENCRYPTION KEY: a short statement signed by the identity I identified as;
     * the proxy keeps it and hands it to whoever asks, who checks the signature. The proxy is
     * the mailbox, not the authority (`encpub.js` of the JS client).
     */
    suspend fun announceEncPub(publickey: String, encPub: String, sign: suspend (JsonObject) -> String) {
        val data = buildJsonObject {
            put("v", 1); put("op", "encpub"); put("aud", "dotrino:encpub")
            put("publickey", publickey); put("encpub", encPub); put("ts", System.currentTimeMillis())
        }
        request(buildJsonObject { put("type", "encpub"); put("data", data); put("signature", sign(data)) })
        encPubs[publickey] = encPub
    }

    /**
     * THE ENCRYPTION KEY OF AN IDENTITY, verified against that identity — or it throws with
     * `no-encpub`, `encpub-unverified` or `no-encpub-support`. Never null, never «send anyway».
     */
    suspend fun encPubOf(publickey: String): String {
        encPubs[publickey]?.let { return it }
        caps?.let { if ("encpub" !in it) throw ProxyError("this proxy does not serve encryption keys", "no-encpub-support") }
        val res = request(buildJsonObject {
            put("type", "enc-lookup"); put("publickeys", buildJsonArray { add(JsonPrimitive(publickey)) })
        })
        val statement = (res["keys"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { Delegation.samePubkey((it["data"] as? JsonObject)?.get("publickey")?.jsonPrimitive?.content, publickey) }
            ?: throw ProxyError("no encryption key announced for that identity", "no-encpub")
        val encPub = readEncPubStatement(statement, publickey)
        encPubs[publickey] = encPub
        return encPub
    }

    private fun readEncPubStatement(statement: JsonObject, publickey: String): String {
        val data = statement["data"] as? JsonObject ?: throw ProxyError("encpub statement: malformed", "encpub-unverified")
        val sig = (statement["signature"] as? JsonPrimitive)?.content ?: throw ProxyError("encpub statement: malformed", "encpub-unverified")
        fun f(k: String) = (data[k] as? JsonPrimitive)?.content
        if (f("v") != "1" || f("op") != "encpub") throw ProxyError("encpub statement: not an announcement", "encpub-unverified")
        if (f("aud") != "dotrino:encpub") throw ProxyError("encpub statement: wrong audience", "encpub-unverified")
        if (!Delegation.samePubkey(f("publickey"), publickey)) throw ProxyError("encpub statement: announces another identity", "encpub-unverified")
        val encPub = f("encpub") ?: throw ProxyError("encpub statement: no key", "encpub-unverified")
        val jwk = runCatching { json.parseToJsonElement(encPub) as? JsonObject }.getOrNull()
        if (jwk?.get("kty")?.jsonPrimitive?.content != "EC" || jwk["crv"]?.jsonPrimitive?.content != "P-256") {
            throw ProxyError("encpub statement: not a P-256 public JWK", "encpub-unverified")
        }
        if (!Crypto.verify(f("publickey")!!, data, sig)) {
            throw ProxyError("encpub statement: bad signature — the key is not bound to that identity", "encpub-unverified")
        }
        return encPub
    }

    /**
     * The same, signed by whoever this connection identified as (a PROFILE signs through its
     * own policy): the proxy rings this phone when something is queued for that identity.
     */
    suspend fun registerPushTokenAs(publickey: String, fcmToken: String, sign: suspend (JsonObject) -> String) {
        val sub = buildJsonObject { put("kind", "fcm"); put("token", fcmToken) }.toString()
        val data = buildJsonObject {
            put("op", "push-subscribe"); put("publickey", publickey); put("subscription", sub)
            put("ts", System.currentTimeMillis())
            if (app != null) put("app", app)   // inside what is signed: which app gets the ring
        }
        request(buildJsonObject { put("type", "push-subscribe"); put("data", data); put("signature", sign(data)) })
    }

    /** Registers this phone's FCM token under my key: the proxy rings it when something is queued for me. */
    suspend fun registerPushToken(keys: DeviceKeys, fcmToken: String) {
        val sub = buildJsonObject { put("kind", "fcm"); put("token", fcmToken) }.toString()
        val data = buildJsonObject {
            put("op", "push-subscribe"); put("publickey", keys.publickey); put("subscription", sub)
            put("ts", System.currentTimeMillis())
            if (app != null) put("app", app)
        }
        request(buildJsonObject {
            put("type", "push-subscribe"); put("data", data); put("signature", keys.sign(Canonical.stringify(data)))
        })
    }

    /**
     * A directed message to a key. The payload travels as a JSON string, like the JS client
     * sends it. [quiet]: it is queued the same, but the proxy does not ring their phone —
     * for what can wait until they open the app (presence, an ack).
     */
    fun sendByPubkey(to: String, payload: JsonObject, quiet: Boolean = false, toApp: String? = null, ephemeral: Boolean = false) {
        send(buildJsonObject {
            put("to_publickey", buildJsonArray { add(JsonPrimitive(to)) })
            put("message", payload.toString())
            if (quiet) put("quiet", true)
            // REAL TIME only: if they are not connected it is neither queued nor rung (a presence probe).
            if (ephemeral) put("ephemeral", true)
            // WHICH app of theirs it is for: the proxy rings and hands it only to that app.
            if (toApp != null) put("app", toApp)
        })
    }

    // ---------- TURN ----------

    /**
     * `getTurnCredentials` of the JS client: temporary ICE servers from the proxy, for the
     * direct road. Empty when the proxy has no TURN.
     */
    suspend fun turnCredentials(publickey: String, sign: suspend (JsonObject) -> String): List<DirectTransport.IceServer> {
        val data = buildJsonObject { put("op", "turn-credentials"); put("publickey", publickey); put("ts", System.currentTimeMillis()) }
        val res = request(buildJsonObject { put("type", "turn-credentials"); put("data", data); put("signature", sign(data)) })
        if (res["enabled"]?.jsonPrimitive?.content != "true") return emptyList()
        return (res["iceServers"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val urls = when (val u = o["urls"]) {
                is JsonPrimitive -> listOf(u.content)
                is kotlinx.serialization.json.JsonArray -> u.map { it.jsonPrimitive.content }
                else -> return@mapNotNull null
            }
            DirectTransport.IceServer(urls, o["username"]?.jsonPrimitive?.content, o["credential"]?.jsonPrimitive?.content)
        }
    }

    // ---------- the short code people read out ("give me your code") ----------

    data class PairingCode(val code: String, val expiresAt: Long)

    /**
     * `requestPairingCode`: 6 characters that point to THIS connection, expire in minutes
     * and burn when used. What a person reads out, types or scans — the token is too long.
     */
    suspend fun requestPairingCode(ttlMs: Long? = null): PairingCode {
        val res = request(buildJsonObject { put("type", "pair-code"); if (ttlMs != null) put("ttlMs", ttlMs) })
        val code = res["code"]?.jsonPrimitive?.content ?: throw ProxyError("pair-code: no code in the answer", "pair-code-failed")
        return PairingCode(code, res["expiresAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L)
    }

    /**
     * `redeemPairingCode`: the connection behind someone's code (its token), on whatever
     * proxy. It does NOT say whose it is — ask with [helloTo] and wait for [Event.PeerIdentity].
     * Throws `pair-invalid` when the code is not valid (typo, expired, used).
     */
    suspend fun redeemPairingCode(code: String): String {
        val res = request(buildJsonObject { put("type", "pair-redeem"); put("code", code) })
        val instance = res["instance"]?.jsonPrimitive?.content
        if (res["ok"]?.jsonPrimitive?.content != "true" || instance == null) {
            throw ProxyError(res["error"]?.jsonPrimitive?.content ?: "that code is not valid", "pair-invalid")
        }
        return instance
    }
}
