package com.dotrino.sdk.webrtc

import android.content.Context
import com.dotrino.sdk.DirectTransport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * THE DIRECT ROAD on Android: the `WebRTCManager` of `@dotrino/proxy-client` (`src/webrtc.js`)
 * over Google's libwebrtc. One peer connection + one data channel (`cc`, ordered) per remote
 * token; the peer with the GREATER token opens the channel and makes the offer, the one with
 * the smaller token is the «polite» one (it yields on an offer collision), exactly as the JS.
 *
 * Everything runs on ONE thread ([exec]): libwebrtc calls back from its own threads, and the
 * negotiation state of a peer must not be touched from two at once.
 */
class WebRtcDirect(context: Context) : DirectTransport {
    companion object {
        @Volatile private var initialized = false
        private fun init(ctx: Context) = synchronized(this) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
                initialized = true
            }
        }
    }

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "dotrino-webrtc") }
    private val factory: PeerConnectionFactory
    private var iceServers: List<DirectTransport.IceServer> = com.dotrino.sdk.SealedSession.DEFAULT_STUN
    private var selfToken: () -> String? = { null }
    private var signalSend: (String, JsonObject) -> Unit = { _, _ -> }
    private var deliver: (String, String) -> Unit = { _, _ -> }
    private val peers = ConcurrentHashMap<String, Peer>()

    /** Who may make me negotiate (null = anyone who can reach me, like the JS default). */
    @Volatile var acceptFrom: ((String) -> Boolean)? = null
    var onWarn: (String, Throwable?) -> Unit = { _, _ -> }
    var onOpen: (String) -> Unit = {}

    init {
        init(context)
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private inner class Peer(val remote: String) {
        var pc: PeerConnection? = null
        var dc: DataChannel? = null
        var makingOffer = false
        var ignoreOffer = false
        var negotiating = false
        var failed = false
        /** `direct` or `turn`, from the candidate pair ICE chose; null = not known yet. */
        @Volatile var route: String? = null
        val pending = mutableListOf<IceCandidate>()
        val polite: Boolean get() = selfToken()?.let { it < remote } ?: false
    }

    override fun bind(selfToken: () -> String?, signalSend: (String, JsonObject) -> Unit, deliver: (String, String) -> Unit) {
        this.selfToken = selfToken; this.signalSend = signalSend; this.deliver = deliver
    }

    override fun setIceServers(servers: List<DirectTransport.IceServer>) { if (servers.isNotEmpty()) exec.execute { iceServers = servers } }

    override fun isOpen(token: String): Boolean = peers[token]?.dc?.state() == DataChannel.State.OPEN

    override fun route(token: String): String? {
        val p = peers[token] ?: return null
        if (p.dc?.state() == DataChannel.State.OPEN) { probe(p); return p.route ?: "webrtc" }
        return when { p.failed -> "failed"; p.negotiating -> "connecting"; else -> null }
    }

    /** Asks ICE which pair it chose and notes it in [Peer.route] (the answer comes later). */
    private fun probe(p: Peer) {
        val pc = p.pc ?: return
        runCatching { pc.getStats { report -> routeOf(report.statsMap)?.let { p.route = it } } }
    }

    override fun send(token: String, text: String): Boolean {
        val dc = peers[token]?.dc ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false)) }.getOrDefault(false)
    }

    override fun upgrade(token: String) = exec.execute {
        val p = peer(token)
        if (p.negotiating || p.failed || isOpen(token)) return@execute
        p.negotiating = true
        try {
            if (p.pc == null) createPc(p)
            // The side with the greater token opens the channel; the other gets it (`onDataChannel`).
            val self = selfToken()
            if (self != null && self > token && p.dc == null) attach(p, p.pc!!.createDataChannel("cc", DataChannel.Init().apply { ordered = true }))
        } catch (e: Exception) { onWarn("webrtc start with $token", e); fail(p) }
    }

    override fun handleSignal(from: String, msg: JsonObject) = exec.execute {
        if (acceptFrom?.invoke(from) == false) return@execute
        val p = peer(from)
        try { onSignal(p, msg) } catch (e: Exception) { onWarn("webrtc signal from $from", e) }
    }

    override fun close(token: String) = exec.execute {
        val p = peers.remove(token) ?: return@execute
        runCatching { p.dc?.close() }; runCatching { p.pc?.close() }
    }

    override fun closeAll() { peers.keys.toList().forEach { close(it) } }

    // ---------- internals (all on exec) ----------

    private fun peer(token: String) = peers.getOrPut(token) { Peer(token) }

    private fun signal(p: Peer, body: JsonObject) = signalSend(p.remote, JsonObject(mapOf("t" to JsonPrimitive(DirectTransport.RTC_TAG)) + body))

    private fun sdpJson(d: SessionDescription) = buildJsonObject {
        put("kind", "sdp")
        put("sdp", buildJsonObject { put("type", d.type.canonicalForm()); put("sdp", d.description) })
    }

    private fun createPc(p: Peer): PeerConnection {
        val cfg = PeerConnection.RTCConfiguration(iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }; s.credential?.let { setPassword(it) }
            }.createIceServer()
        }).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val pc = factory.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) = exec.execute {
                signal(p, buildJsonObject {
                    put("kind", "ice")
                    put("candidate", buildJsonObject { put("candidate", c.sdp); put("sdpMid", c.sdpMid); put("sdpMLineIndex", c.sdpMLineIndex) })
                })
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                if (s == PeerConnection.PeerConnectionState.FAILED || s == PeerConnection.PeerConnectionState.CLOSED) exec.execute { fail(p) }
            }
            override fun onDataChannel(dc: DataChannel) = exec.execute { attach(p, dc) }
            override fun onRenegotiationNeeded() = exec.execute { makeOffer(p) }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: throw IllegalStateException("libwebrtc could not create a peer connection")
        p.pc = pc
        return pc
    }

    private fun makeOffer(p: Peer) {
        val pc = p.pc ?: return
        p.makingOffer = true
        pc.createOffer(sdp(onOk = { d ->
            pc.setLocalDescription(sdp(onOk = { exec.execute { p.makingOffer = false; pc.localDescription?.let { signal(p, sdpJson(it)) } } },
                onFail = { exec.execute { p.makingOffer = false } }), d)
        }, onFail = { exec.execute { p.makingOffer = false } }), MediaConstraints())
    }

    private fun onSignal(p: Peer, msg: JsonObject) {
        val pc = p.pc ?: createPc(p)
        when ((msg["kind"] as? JsonPrimitive)?.content) {
            "sdp" -> {
                val o = msg["sdp"] as? JsonObject ?: return
                val type = o["type"]?.jsonPrimitive?.content ?: return
                val text = o["sdp"]?.jsonPrimitive?.content ?: return
                val collision = type == "offer" && (p.makingOffer || pc.signalingState() != PeerConnection.SignalingState.STABLE)
                p.ignoreOffer = !p.polite && collision
                if (p.ignoreOffer) return
                val desc = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), text)
                pc.setRemoteDescription(sdp(onOk = {
                    exec.execute {
                        p.pending.forEach { pc.addIceCandidate(it) }; p.pending.clear()
                        if (type == "offer") pc.createAnswer(sdp(onOk = { a ->
                            pc.setLocalDescription(sdp(onOk = { exec.execute { pc.localDescription?.let { signal(p, sdpJson(it)) } } }), a)
                        }), MediaConstraints())
                    }
                }), desc)
            }
            "ice" -> {
                val c = msg["candidate"] as? JsonObject ?: return
                val cand = IceCandidate(
                    c["sdpMid"]?.jsonPrimitive?.content ?: "0",
                    (c["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
                    c["candidate"]?.jsonPrimitive?.content ?: return,
                )
                if (pc.remoteDescription == null) p.pending.add(cand) else pc.addIceCandidate(cand)
            }
        }
    }

    private fun attach(p: Peer, dc: DataChannel) {
        p.dc = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() {
                when (dc.state()) {
                    DataChannel.State.OPEN -> { probe(p); onOpen(p.remote) }
                    // A channel that closes is tried again with the next message (JS: webrtc_close).
                    DataChannel.State.CLOSED -> exec.execute { if (peers[p.remote] === p) { peers.remove(p.remote); runCatching { p.pc?.close() } } }
                    else -> {}
                }
            }
            override fun onMessage(b: DataChannel.Buffer) {
                if (b.binary) return
                val bytes = ByteArray(b.data.remaining()).also { b.data.get(it) }
                deliver(p.remote, String(bytes, Charsets.UTF_8))
            }
        })
    }

    private fun fail(p: Peer) { p.failed = true; p.negotiating = false }

    private fun sdp(onOk: (SessionDescription) -> Unit = {}, onFail: () -> Unit = {}): SdpObserver = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) = onOk(d)
        override fun onSetSuccess() = onOk(SessionDescription(SessionDescription.Type.OFFER, ""))
        override fun onCreateFailure(e: String) { onWarn("webrtc sdp: $e", null); onFail() }
        override fun onSetFailure(e: String) { onWarn("webrtc sdp: $e", null); onFail() }
    }
}

/**
 * BY WHICH ROAD a peer connection goes: `turn` if either end of the candidate pair ICE chose is a
 * `relay`, `direct` otherwise; null when there is no chosen pair yet (not guessed). Same rule as
 * `routeOf` in `@dotrino/proxy-client`.
 */
internal fun routeOf(stats: Map<String, org.webrtc.RTCStats>): String? {
    fun m(s: org.webrtc.RTCStats?, k: String) = s?.members?.get(k)
    var pair = stats.values.firstOrNull { it.type == "transport" && m(it, "selectedCandidatePairId") != null }
        ?.let { stats[m(it, "selectedCandidatePairId").toString()] }
    if (pair == null) pair = stats.values.firstOrNull {
        it.type == "candidate-pair" && (m(it, "selected") == true || (m(it, "nominated") == true && m(it, "state") == "succeeded"))
    }
    pair ?: return null
    val local = stats[m(pair, "localCandidateId")?.toString()]
    val remote = stats[m(pair, "remoteCandidateId")?.toString()]
    if (local == null && remote == null) return null
    return if (m(local, "candidateType") == "relay" || m(remote, "candidateType") == "relay") "turn" else "direct"
}
