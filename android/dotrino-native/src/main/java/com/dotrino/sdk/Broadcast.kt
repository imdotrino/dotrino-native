package com.dotrino.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * THE BROADCAST OF `@dotrino/lobby` (`src/broadcast.js`), the HOST side, in native: one
 * device publishes its state and whoever has the link watches it — SEALED to each one and
 * SIGNED by the host's identity. Those who watch do it on the web (the PWA opens the
 * `#watch=` link), so the viewer side is not ported.
 *
 * Wire-compatible with the JS: the same channel name, the same envelopes (`__ccl`), the same
 * sealed wrapper (`{ app: 'dotrino-lobby', sealed, from }`, `identitySealing` of
 * proxy-client) and the same signed payload `{ v, g, k, at, seq, state }`.
 *
 * It reconnects by itself (with the same link: the key is stable, the token is not).
 */
class BroadcastHost(
    private val url: String,
    private val gameId: String,
    private val profile: Profile,
    /** The app's transport key: signs its channel entries (`proxy-client`'s own keypair). */
    private val transport: DeviceKeys,
    /** A link that already existed (the key survives restarts), or null for a new one. */
    ref: Ref? = null,
    private val maxViewers: Int = 50,
) {
    data class Ref(val key: String, val secret: String)

    companion object {
        const val SEAL_APP = "dotrino-lobby"
        private const val ENVELOPE_TAG = 1
        private const val WATCH = "bcast.watch"
        private const val BCAST = "bcast.state"
        private const val DENIED = "bcast.denied"
        private const val REFRESH_MS = 60_000L
        private const val WATCHER_TTL_MS = 150_000L
        private const val REPUBLISH_MS = 10 * 60_000L
        private const val NODE_ID_LEN = 12
        private val json = Json { ignoreUnknownKeys = true }

        fun isNodeId(s: String?) = s != null && Regex("^[1-9A-Z]{$NODE_ID_LEN}$").matches(s)

        private fun b64url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** `newBroadcastRef`: the key carries the proxy node in front (like a token); no node = `_`. */
        fun newRef(node: String?) = Ref((if (isNodeId(node)) node!! else "_") + b64url(Crypto.randomBytes(16)), b64url(Crypto.randomBytes(16)))

        /** `broadcastChannel`: `NODE/ccbcast/<game>/<key>` (or without the node). */
        fun channel(gameId: String, key: String): String {
            val id = key.take(NODE_ID_LEN)
            val name = "ccbcast/$gameId/$key"
            return if (isNodeId(id)) "$id/$name" else name
        }

        /** `encodeBroadcastRef`: `key.secret.x.y` — what goes in the `#fragment` of the link. */
        fun encodeRef(ref: Ref, hostPubkey: String): String {
            val jwk = json.parseToJsonElement(hostPubkey).jsonObject
            require(jwk["crv"]?.jsonPrimitive?.content == "P-256") { "the host key is not P-256" }
            val parts = listOf(ref.key, ref.secret, jwk.getValue("x").jsonPrimitive.content, jwk.getValue("y").jsonPrimitive.content)
            require(parts.all { Regex("^[A-Za-z0-9_-]+$").matches(it) }) { "incomplete broadcast ref" }
            return parts.joinToString(".")
        }

        fun envelope(gameId: String, room: String, kind: String, data: JsonObject) = buildJsonObject {
            put("__ccl", ENVELOPE_TAG); put("g", gameId); put("r", room); put("k", kind); put("d", data)
        }
    }

    /** `'connecting' | 'live' | 'offline' | 'closed'`, and why when it is not live. */
    data class Status(val status: String, val reason: String? = null)

    // Without a ref, the key is made in start(): it carries the proxy node in front.
    private var current: Ref = ref ?: Ref("", "")
    private val channel get() = channel(gameId, current.key)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var conn: ProxyConnection? = null
    private val watchers = ConcurrentHashMap<String, Long>()
    private var latest: JsonObject? = null
    private var at = 0L
    private var seq = 0
    private var closed = false
    private var loop: Job? = null

    var onViewers: (Int) -> Unit = {}
    var onStatus: (Status) -> Unit = {}
    var onWarn: (String, Throwable?) -> Unit = { _, _ -> }

    val viewers get() = watchers.size
    /** The link reference now (after start, with the node's key if it was new). */
    val linkRef: Ref get() = current

    /** The link to watch: `<base>#watch=<ref>`. */
    fun link(base: String) = "$base#watch=" + encodeRef(current, profile.publickey)

    /**
     * Connect, identify as the profile, announce my encryption key and publish the channel.
     * Keeps doing it (reconnecting) until [close]. Throws only what is not about the network:
     * a profile that cannot sign.
     */
    suspend fun start() {
        if (!profile.canSign) throw Profile.ProfileError("this device does not sign for your profile", "needs-vault-signer")
        connectOnce()
        loop = scope.launch { keepAlive() }
    }

    private suspend fun connectOnce() = lock.withLock {
        onStatus(Status("connecting"))
        val c = ProxyConnection(url)
        c.connect()
        if (current.key.isEmpty()) current = newRef(c.node)
        c.identifyAs(profile.publickey) { profile.signData(it) }
        c.announceEncPub(profile.publickey, profile.encPub) { profile.signData(it) }
        c.onMessage { inc -> scope.launch { onMessage(c, inc) } }
        c.onEvent { e -> if (e is ProxyConnection.Event.PeerGone && watchers.remove(e.token) != null) onViewers(watchers.size) }
        c.publish(channel, transport)
        conn?.close()
        conn = c
        watchers.clear()
        onViewers(0)
        onStatus(Status("live"))
    }

    /** Republish before the proxy forgets the channel, forget silent viewers, and reconnect. */
    private suspend fun keepAlive() {
        var lastPublish = System.currentTimeMillis()
        var backoff = 2_000L
        while (scope.isActive && !closed) {
            delay(5_000)
            val c = conn
            if (c == null || c.closed != null) {
                onStatus(Status("offline", c?.closed))
                try {
                    connectOnce()
                    lastPublish = System.currentTimeMillis()
                    backoff = 2_000
                } catch (e: Exception) {
                    onWarn("reconnect", e)
                    delay(backoff)
                    backoff = minOf(backoff * 2, 60_000)
                }
                continue
            }
            val now = System.currentTimeMillis()
            if (now - lastPublish > REPUBLISH_MS) {
                runCatching { c.publish(channel, transport) }.onFailure { onWarn("republish", it) }
                lastPublish = now
            }
            val limit = now - WATCHER_TTL_MS
            if (watchers.entries.removeIf { it.value < limit }) onViewers(watchers.size)
        }
    }

    /** What arrives: only SEALED envelopes of this broadcast; anything else is dropped. */
    private suspend fun onMessage(c: ProxyConnection, inc: ProxyConnection.Incoming) {
        val from = inc.from ?: return
        val p = inc.payload
        if ((p["app"] as? JsonPrimitive)?.content != SEAL_APP) return // unsealed or not ours: dropped
        val env = try {
            val text = profile.decrypt(p.getValue("from").jsonPrimitive.content, p.getValue("sealed").jsonObject)
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            return // sealed to somebody else, or tampered with: staying quiet is the point
        } ?: return
        if ((env["__ccl"] as? JsonPrimitive)?.content != ENVELOPE_TAG.toString()) return
        if ((env["g"] as? JsonPrimitive)?.content != gameId || (env["r"] as? JsonPrimitive)?.content != current.key) return
        if ((env["k"] as? JsonPrimitive)?.content != WATCH) return
        val d = env["d"] as? JsonObject ?: JsonObject(emptyMap())
        if ((d["secret"] as? JsonPrimitive)?.content != current.secret) {
            send(c, from, DENIED, buildJsonObject { put("reason", "bad-secret") })
            return
        }
        val isNew = !watchers.containsKey(from)
        if (isNew && watchers.size >= maxViewers) {
            send(c, from, DENIED, buildJsonObject { put("reason", "full") })
            return
        }
        watchers[from] = System.currentTimeMillis()
        if (isNew) onViewers(watchers.size)
        // Only what is missing: on joining, or if what it has is older than mine.
        val l = latest ?: return
        val theirs = (d["at"] as? JsonPrimitive)?.content?.toDoubleOrNull()
        val mine = l.getValue("payload").jsonObject.getValue("at").jsonPrimitive.content.toDouble()
        if (theirs == null || theirs < mine) send(c, from, BCAST, l)
    }

    /**
     * The new state. Signed ONCE by the host's identity and sealed to each viewer. `at` always
     * grows, also after a restart: it is what the viewer uses to drop old or repeated states.
     */
    suspend fun publish(state: JsonElement) {
        check(!closed) { "this broadcast is closed" }
        val payload = buildJsonObject {
            put("v", 1); put("g", gameId); put("k", current.key)
            put("at", maxOf(System.currentTimeMillis(), at + 1)); put("seq", ++seq); put("state", state)
        }
        val signature = profile.signData(payload)
        at = payload.getValue("at").jsonPrimitive.content.toLong()
        val l = buildJsonObject { put("payload", payload); put("signature", signature) }
        latest = l
        val c = conn ?: return
        for (t in watchers.keys) send(c, t, BCAST, l)
    }

    /** EVERYTHING GOES SEALED; if it cannot be sealed, it does not go, and it is said. */
    private suspend fun send(c: ProxyConnection, token: String, kind: String, data: JsonObject) {
        try {
            val peer = c.pubkeyOfToken(token) ?: throw ProxyConnection.ProxyError("$token never said whose it is", "no-peer-identity")
            val peerEncPub = c.encPubOf(peer)
            val text = envelope(gameId, current.key, kind, data).toString()
            val sealed = profile.encrypt(listOf(peerEncPub), text)
            c.sendTo(listOf(token), buildJsonObject { put("app", SEAL_APP); put("sealed", sealed); put("from", profile.encPub) })
        } catch (e: Exception) {
            onWarn("sealed $kind to $token", e)
        }
    }

    /** Stop broadcasting. The link stops working; who was watching keeps the last state. */
    suspend fun close() {
        if (closed) return
        closed = true
        loop?.cancel()
        conn?.let { c -> runCatching { c.unpublish(channel, transport) }; c.close() }
        conn = null
        watchers.clear()
        onStatus(Status("closed"))
        scope.cancel()
    }
}
