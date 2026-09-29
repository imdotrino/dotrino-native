package com.dotrino.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A connection to the proxy that STAYS UP and where EVERYTHING GOES SEALED: the native
 * counterpart of `WebSocketProxyClient` with `requireSealed: true` and `identitySealing`.
 *
 *  - reconnects by itself, re-identifies as the profile and re-announces its encryption
 *    key (signed) every time: a dropped socket never leaves the app deaf in silence;
 *  - after [FAILS_BEFORE_FAILOVER] failures in a row on one proxy it moves to the next of
 *    [urls] (they are federated: contacts on another node are still reachable);
 *  - what arrives UNSEALED is dropped and reported (`onWarn`), in both directions: sealing
 *    only on the way out would let anyone push a forged payload in;
 *  - what arrives sealed comes with `senderEncPub`, the key that sealed it: the app checks
 *    it against the contact it claims to be.
 *
 * Choosing the road (by token = live and the only road that can go direct; by pubkey = the
 * proxy's offline queue) is the caller's decision, as in the JS client.
 */
class SealedSession(
    private val urls: List<String>,
    private val profile: Profile,
    app: String,
) {
    companion object {
        const val FAILS_BEFORE_FAILOVER = 3
        /** `DEFAULT_ICE_SERVERS` of the JS client. */
        val DEFAULT_STUN = listOf(
            DirectTransport.IceServer(listOf("stun:stun.l.google.com:19302")),
            DirectTransport.IceServer(listOf("stun:stun1.l.google.com:19302")),
            DirectTransport.IceServer(listOf("stun:global.stun.twilio.com:3478")),
        )
        private const val MAX_BACKOFF_MS = 30_000L
    }

    class SessionError(message: String, val code: String) : Exception(message)

    /** A sealed message, opened. [fromPubkey] is who the proxy or the greeting says it is — trust it only after checking [senderEncPub]. */
    data class Message(
        val fromToken: String?,
        val fromPubkey: String?,
        val payload: JsonObject,
        val senderEncPub: String,
        val queued: Boolean,
        val queuedAt: Long?,
    )

    /** `connecting` | `online` | `offline` | `closed`; [url] is the proxy in use. */
    data class Status(val state: String, val url: String, val reason: String? = null)

    val sealing = IdentitySealing(profile, app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var conn: ProxyConnection? = null
    @Volatile private var closed = false
    private var loop: Job? = null
    private var urlIndex = 0

    private val messageListeners = CopyOnWriteArrayList<(Message) -> Unit>()
    private val statusListeners = CopyOnWriteArrayList<(Status) -> Unit>()
    private val eventListeners = CopyOnWriteArrayList<(ProxyConnection.Event) -> Unit>()
    private val onlineListeners = CopyOnWriteArrayList<() -> Unit>()

    var onWarn: (String, Throwable?) -> Unit = { _, _ -> }

    @Volatile private var direct: DirectTransport? = null
    @Volatile private var pushToken: String? = null

    /**
     * This phone's push token (FCM). Registered under the profile after EVERY identify (tokens
     * rotate, and a proxy change forgets them): the proxy rings the phone — a ring with no
     * content — when a message is queued for this identity while the app is closed.
     */
    fun setPushToken(token: String) {
        pushToken = token
        conn?.let { c -> scope.launch { registerPush(c) } }
    }

    private suspend fun registerPush(c: ProxyConnection) {
        val t = pushToken ?: return
        try { c.registerPushTokenAs(profile.publickey, t) { profile.signData(it) } }
        catch (e: Exception) { onWarn("could not register the push token", e) }
    }

    /**
     * Plugs in the DIRECT ROAD (WebRTC, module `dotrino-webrtc`). From then on, what goes by
     * token prefers an open channel, and the first message to someone opens one underneath.
     * TURN credentials are asked after every identify (signed by the profile).
     */
    fun useDirect(d: DirectTransport) {
        direct = d
        d.bind(
            selfToken = { conn?.token },
            signalSend = { to, msg -> runCatching { conn?.sendTo(listOf(to), msg) } },
            deliver = { from, text ->
                val c = conn ?: return@bind
                val payload = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return@bind
                scope.launch { deliver(c, ProxyConnection.Incoming(from, null, payload)) }
            },
        )
    }

    fun onMessage(l: (Message) -> Unit): () -> Unit { messageListeners.add(l); return { messageListeners.remove(l) } }
    fun onStatus(l: (Status) -> Unit): () -> Unit { statusListeners.add(l); return { statusListeners.remove(l) } }
    fun onEvent(l: (ProxyConnection.Event) -> Unit): () -> Unit { eventListeners.add(l); return { eventListeners.remove(l) } }
    /** Every time the session is (again) online and identified: re-greet, flush an outbox… */
    fun onOnline(l: () -> Unit): () -> Unit { onlineListeners.add(l); return { onlineListeners.remove(l) } }

    @Volatile var status: Status = Status("connecting", urls.first()); private set
    val url: String get() = urls[urlIndex % urls.size]
    val isOnline: Boolean get() = status.state == "online"
    val token: String? get() = conn?.token

    private fun setStatus(s: Status) { status = s; statusListeners.forEach { runCatching { it(s) } } }

    init { require(urls.isNotEmpty()) { "at least one proxy url" } }

    /** Starts the loop. Returns at once; [awaitOnline] waits for the first connection. */
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            var fails = 0
            while (isActive && !closed) {
                val u = url
                setStatus(Status("connecting", u))
                val c = ProxyConnection(u)
                try {
                    c.connect()
                    c.onMessage { inc -> scope.launch { deliver(c, inc) } }
                    c.onEvent { e ->
                        // A peer that left takes its direct channel with it (tokens are not reused).
                        if (e is ProxyConnection.Event.PeerGone) runCatching { direct?.close(e.token) }
                        eventListeners.forEach { runCatching { it(e) } }
                    }
                    c.identifyAs(profile.publickey) { profile.signData(it) }
                    conn = c
                    // Without my key announced nobody can seal to me. It is signed by the
                    // profile; a phone whose acta does not let it sign cannot announce, and
                    // that is said instead of pretending.
                    try { c.announceEncPub(profile.publickey, profile.encPub) { profile.signData(it) } }
                    catch (e: Exception) { onWarn("could not announce my encryption key", e) }
                    direct?.let { d -> scope.launch { enableTurn(c, d) } }
                    scope.launch { registerPush(c) }
                    fails = 0
                    setStatus(Status("online", u))
                    onlineListeners.forEach { runCatching { it() } }
                    val why = c.awaitClosed()
                    conn = null
                    if (closed) break
                    setStatus(Status("offline", u, why))
                } catch (e: Exception) {
                    conn = null
                    runCatching { c.close() }
                    if (closed) break
                    fails++
                    setStatus(Status("offline", u, e.message))
                    onWarn("connection to $u failed", e)
                    if (fails >= FAILS_BEFORE_FAILOVER && urls.size > 1) { urlIndex++; fails = 0 }
                }
                delay(minOf(MAX_BACKOFF_MS, 1000L shl minOf(fails, 5)))
            }
            setStatus(Status("closed", url))
        }
    }

    /** Waits until online (or [timeoutMs] passes). */
    suspend fun awaitOnline(timeoutMs: Long = 15_000): Boolean {
        if (isOnline) return true
        val d = CompletableDeferred<Unit>()
        val off = onOnline { d.complete(Unit) }
        try { return withTimeoutOrNull(timeoutMs) { d.await(); true } ?: isOnline } finally { off() }
    }

    fun close() {
        closed = true
        runCatching { direct?.closeAll() }
        runCatching { conn?.close() }
        loop?.cancel()
        scope.cancel()
    }

    private fun live(): ProxyConnection = conn ?: throw SessionError("not connected to the proxy", "disconnected")

    private suspend fun deliver(c: ProxyConnection, inc: ProxyConnection.Incoming) {
        // The signalling of the direct road is a control frame of the transport (like the
        // greeting): it carries no user content and goes to the WebRTC layer, not the app.
        if ((inc.payload["t"] as? kotlinx.serialization.json.JsonPrimitive)?.content == DirectTransport.RTC_TAG) {
            val d = direct
            if (d != null && inc.from != null) d.handleSignal(inc.from, inc.payload)
            return
        }
        if (!sealing.isSealed(inc.payload)) {
            onWarn("dropped a message that arrived unsealed", null)
            return
        }
        val opened = try { sealing.open(inc.payload) } catch (e: Exception) {
            // Sealed to somebody else, or tampered with. Staying quiet is the point.
            return
        }
        val claimed = inc.fromPubkey ?: inc.from?.let { c.pubkeyOfToken(it) }
        val m = Message(inc.from, claimed, opened.payload, opened.senderEncPub, inc.queued, inc.queuedAt)
        messageListeners.forEach { runCatching { it(m) } }
    }

    /** The encryption key of an identity, VERIFIED against its signature. Throws with `code`. */
    suspend fun encPubOf(publickey: String): String = live().encPubOf(publickey)

    /** Whose this token is, according to its transport greeting (does not authenticate). */
    fun pubkeyOfToken(token: String): String? = conn?.pubkeyOfToken(token)

    /**
     * Sealed, BY TOKEN (live, and the road that can go direct). [recipientEncPubs]: every key
     * of the person (theirs + their card). Empty = the one their identity announced, found
     * through the greeting of that token.
     */
    suspend fun sendSealedTo(token: String, payload: JsonObject, recipientEncPubs: List<String> = emptyList()) {
        val c = live()
        val keys = recipientEncPubs.ifEmpty {
            val pk = c.pubkeyOfToken(token) ?: throw SessionError("nobody has said whose this token is — greet it first", "no-peer-identity")
            listOf(c.encPubOf(pk))
        }
        val sealed = sealing.seal(payload, keys)
        val d = direct
        if (d != null && d.send(token, sealed.toString())) return
        c.sendTo(listOf(token), sealed)
        // And try to go direct for the next one, without waiting for anybody.
        d?.upgrade(token)
    }

    /**
     * TURN for the direct road: temporary credentials from the proxy (`turn-credentials`,
     * signed by the profile), put in front of the default STUN. A proxy without TURN leaves
     * STUN only; that is not an error.
     */
    private suspend fun enableTurn(c: ProxyConnection, d: DirectTransport) {
        try {
            val servers = c.turnCredentials(profile.publickey) { profile.signData(it) }
            if (servers.isNotEmpty()) d.setIceServers(servers + DEFAULT_STUN)
        } catch (e: Exception) { onWarn("could not get TURN credentials", e) }
    }

    /** Sealed, BY PUBKEY (the proxy's 24 h offline queue). [quiet]: queue without ringing. */
    suspend fun sendSealed(pubkey: String, payload: JsonObject, recipientEncPubs: List<String> = emptyList(), quiet: Boolean = false) {
        val c = live()
        val keys = recipientEncPubs.ifEmpty { listOf(c.encPubOf(pubkey)) }
        c.sendByPubkey(pubkey, sealing.seal(payload, keys), quiet)
    }

    suspend fun requestPairingCode(ttlMs: Long? = null) = live().requestPairingCode(ttlMs)
    suspend fun redeemPairingCode(code: String) = live().redeemPairingCode(code)

    /**
     * WHOSE IS THIS TOKEN: greets it and waits for its answer. Redeeming a code gives the
     * address but not the identity. It does not authenticate, and does not need to: what is
     * sent afterwards goes sealed to the key THAT identity announced signed.
     */
    suspend fun whoIs(token: String, timeoutMs: Long = 10_000): String? {
        val c = live()
        c.pubkeyOfToken(token)?.let { return it }
        val d = CompletableDeferred<String>()
        val off = c.onEvent { e -> if (e is ProxyConnection.Event.PeerIdentity && e.token == token) d.complete(e.publickey) }
        try {
            c.helloTo(token)
            return withTimeoutOrNull(timeoutMs) { d.await() }
        } finally { off() }
    }
}
