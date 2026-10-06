package com.dotrino.sdk

import java.util.concurrent.CopyOnWriteArrayList

/**
 * HOW MUCH TRAFFIC AND BY WHICH ROAD, per connection: the `stats.js` of `@dotrino/proxy-client`
 * (≥ 0.28.0). The topbar shows it ([com.dotrino.sdk.ui.DotrinoTopbar]), so the rule «always the
 * most direct road» can be SEEN on a phone too.
 *
 * Bytes of PAYLOAD (UTF-8), not of the wire: TLS/DTLS/SCTP headers are not visible from here.
 * For the proxy the whole frame counts; per connection, what was addressed to it.
 *
 * Paths: `proxy` (signalling and greeting included), `direct` (WebRTC without relay), `turn`
 * (WebRTC through a TURN relay), `webrtc` (WebRTC, not known yet which).
 */
class TrafficStats {
    val since: Long = System.currentTimeMillis()

    class ByPath { var proxy = 0L; var direct = 0L; var turn = 0L; var webrtc = 0L
        fun add(path: String, n: Long) = when (path) { "direct" -> direct += n; "turn" -> turn += n; "webrtc" -> webrtc += n; else -> proxy += n }
        fun copy() = ByPath().also { it.proxy = proxy; it.direct = direct; it.turn = turn; it.webrtc = webrtc }
        val total: Long get() = proxy + direct + turn + webrtc
    }

    private class Peer(var token: String?, var pubkey: String?) {
        val bytesIn = ByPath(); val bytesOut = ByPath()
        var msgsIn = 0; var msgsOut = 0; var lastAt = 0L
    }

    private var proxyIn = 0L; private var proxyOut = 0L; private var framesIn = 0; private var framesOut = 0
    private val peers = LinkedHashMap<String, Peer>()

    /** A whole frame of the proxy's WebSocket. */
    @Synchronized fun frame(incoming: Boolean, bytes: Int) {
        if (incoming) { proxyIn += bytes; framesIn++ } else { proxyOut += bytes; framesOut++ }
    }

    /** A message addressed to (or coming from) one connection, by [token] or, failing that, [pubkey]. */
    @Synchronized fun peer(incoming: Boolean, path: String, token: String?, pubkey: String?, bytes: Int) {
        val key = token?.let { "t:$it" } ?: pubkey?.let { "k:$it" } ?: return
        val p = peers.getOrPut(key) { Peer(token, pubkey) }
        if (pubkey != null && p.pubkey == null) p.pubkey = pubkey
        if (incoming) { p.bytesIn.add(path, bytes.toLong()); p.msgsIn++ } else { p.bytesOut.add(path, bytes.toLong()); p.msgsOut++ }
        p.lastAt = System.currentTimeMillis()
    }

    /** A copy to show. [routeOf]: where each token goes NOW; [pubkeyOf]: whose a token is. */
    @Synchronized fun snapshot(routeOf: (String) -> String?, pubkeyOf: (String) -> String?): Pair<NetworkStats.Proxy, List<NetworkStats.Peer>> {
        val proxy = NetworkStats.Proxy(proxyIn, proxyOut, framesIn, framesOut)
        val list = peers.values.map { p ->
            NetworkStats.Peer(
                token = p.token,
                pubkey = p.pubkey ?: p.token?.let(pubkeyOf),
                route = p.token?.let(routeOf) ?: "proxy",
                bytesIn = p.bytesIn.copy(), bytesOut = p.bytesOut.copy(),
                msgsIn = p.msgsIn, msgsOut = p.msgsOut, lastAt = p.lastAt,
            )
        }.sortedByDescending { it.lastAt }
        return proxy to list
    }
}

/** UTF-8 length of a string without allocating a buffer per message. */
internal fun utf8Length(s: String): Int {
    var n = 0; var i = 0
    while (i < s.length) {
        val c = s[i].code
        n += when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            Character.isHighSurrogate(s[i]) && i + 1 < s.length -> { i++; 4 }
            else -> 3
        }
        i++
    }
    return n
}

/** What `stats()` of `@dotrino/proxy-client` gives, for one session. */
data class NetworkStats(
    val url: String,
    val app: String?,
    val node: String?,
    val token: String?,
    val connected: Boolean,
    val since: Long,
    val proxy: Proxy,
    val peers: List<Peer>,
) {
    data class Proxy(val bytesIn: Long, val bytesOut: Long, val framesIn: Int, val framesOut: Int)
    /**
     * [route]: `proxy`, `connecting` (by the proxy while WebRTC is negotiated), `failed` (WebRTC
     * did not come out), `direct`, `turn` or `webrtc` (open channel, road not known).
     */
    data class Peer(
        val token: String?, val pubkey: String?, val route: String,
        val bytesIn: TrafficStats.ByPath, val bytesOut: TrafficStats.ByPath,
        val msgsIn: Int, val msgsOut: Int, val lastAt: Long,
    )
}

/**
 * THE LIVE TRANSPORTS OF THIS APP, for whoever shows them without the app wiring it (the
 * topbar). The `Symbol.for('dotrino.transports')` registry of the JS client: a session goes in
 * when it starts and out when it closes.
 */
object DotrinoNetwork {
    fun interface Source { suspend fun networkStats(): NetworkStats }

    private val sources = CopyOnWriteArrayList<Source>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun register(s: Source) { if (sources.addIfAbsent(s)) listeners.forEach { runCatching { it() } } }
    fun unregister(s: Source) { if (sources.remove(s)) listeners.forEach { runCatching { it() } } }
    fun sources(): List<Source> = sources.toList()
    /** Called when a transport comes or goes. */
    fun onChange(l: () -> Unit): () -> Unit { listeners.add(l); return { listeners.remove(l) } }
}
