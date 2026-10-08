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

    /**
     * A copy to show. [routeOf]: where each token goes NOW; [pubkeyOf]: whose a token is.
     *
     * The copy is taken under the lock and [routeOf] is asked OUTSIDE it: asking WebRTC for a
     * channel's state goes through its signalling thread, and that thread counts traffic here
     * ([peer]) — with the lock held, the two waited for each other and the app froze the second
     * time the network sheet opened (0.28.1).
     */
    fun snapshot(routeOf: (String) -> String?, pubkeyOf: (String) -> String?): Pair<NetworkStats.Proxy, List<NetworkStats.Peer>> {
        val (proxy, copy) = synchronized(this) {
            NetworkStats.Proxy(proxyIn, proxyOut, framesIn, framesOut) to peers.values.map { p ->
                NetworkStats.Peer(p.token, p.pubkey, "proxy", p.bytesIn.copy(), p.bytesOut.copy(), p.msgsIn, p.msgsOut, p.lastAt)
            }
        }
        val list = copy.map { p ->
            p.copy(pubkey = p.pubkey ?: p.token?.let(pubkeyOf), route = p.token?.let(routeOf) ?: "proxy")
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

// ---- the report as text (0.29.0) ----
//
// The network sheet has a «Copy» button: this is what it copies, the same lines on the web, on
// Android and on iOS, so a stats dump can be pasted into a chat and read without the app.

/** One transport, as lines of plain text. */
fun NetworkStats.report(): String {
    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
    val sb = StringBuilder("Proxy $url")
    if (app != null) sb.append(" | app=$app")
    if (node != null) sb.append(" | node=$node")
    sb.append(if (connected) " | connected" else " | disconnected")
    sb.append(" | since ${time.format(java.util.Date(since))}\n")
    sb.append("  proxy total: in ${NetworkReport.bytes(proxy.bytesIn)} / out ${NetworkReport.bytes(proxy.bytesOut)} (frames ${proxy.framesIn}/${proxy.framesOut})\n")
    // WHO ANSWERED AND WHO DID NOT. A probe (one ping to every device of the record, to see
    // which is on) leaves an entry per recipient even when nobody replies, and twelve lines
    // read as twelve connections. The ones that talked are listed; the rest is one line.
    val talking = peers.filter { it.msgsIn > 0 }
    val silent = peers.filter { it.msgsIn == 0 }
    sb.append("  peers: ${talking.size}\n")
    for (p in talking) {
        // The device by its ID (`AB12-CD34`, the one the vault shows), never a slice of the JWK;
        // the token whole (owner, 2026-10-07: nothing to gain by cutting it).
        val who = p.pubkey?.let { runCatching { Delegation.keyLabel(it) }.getOrNull() } ?: "?"
        sb.append("  - $who")
        p.token?.let { sb.append(" (token $it)") }
        sb.append(" | route=${p.route} | in: ${NetworkReport.paths(p.bytesIn)} | out: ${NetworkReport.paths(p.bytesOut)} | ${p.msgsIn + p.msgsOut} msgs\n")
    }
    if (silent.isNotEmpty()) sb.append("  no answer: ${silent.size} (out ${NetworkReport.bytes(silent.sumOf { it.bytesOut.total })}, ${silent.sumOf { it.msgsOut }} msgs)\n")
    return sb.toString()
}

object NetworkReport {
    /** Every transport of the app, with a header line (what the sheet copies). */
    fun of(all: List<NetworkStats>): String {
        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder("Dotrino network stats · ${iso.format(java.util.Date())}\n")
        if (all.isEmpty()) sb.append("(no transports)\n")
        for (s in all) sb.append(s.report())
        return sb.toString()
    }

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
        else -> String.format(java.util.Locale.US, "%.2f MB", n / 1024.0 / 1024.0)
    }

    fun paths(b: TrafficStats.ByPath): String {
        val parts = listOf("proxy" to b.proxy, "direct" to b.direct, "turn" to b.turn, "webrtc" to b.webrtc).filter { it.second > 0 }.map { "${it.first} ${bytes(it.second)}" }
        return if (parts.isEmpty()) "0 B" else parts.joinToString(", ")
    }
}
