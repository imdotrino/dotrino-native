package com.dotrino.sdk

import kotlinx.serialization.json.JsonObject

/**
 * THE DIRECT ROAD (steps 2 and 3 of the transport: WebRTC direct, WebRTC through TURN), as the
 * `WebRTCManager` of `@dotrino/proxy-client` (`src/webrtc.js`). The core does not carry
 * libwebrtc — it weighs ~10 MB per architecture and not every app wants it — so it is plugged
 * into [SealedSession] from a separate module (`dotrino-webrtc`).
 *
 * Wire-compatible with the JS: signalling goes through the proxy as `{ t: '__cc_rtc__', kind:
 * 'sdp' | 'ice', … }`; the data channel is `cc`, ordered, opened by the peer with the greater
 * token; what travels in it is the same text that would have gone through the proxy.
 *
 * Never blocks: the first message goes by the proxy and the channel is negotiated underneath;
 * from then on [send] prefers it.
 */
interface DirectTransport {
    companion object { const val RTC_TAG = "__cc_rtc__" }

    /** Wiring, set by [SealedSession.useDirect]: my token, how to signal, where arrivals go. */
    fun bind(selfToken: () -> String?, signalSend: (String, JsonObject) -> Unit, deliver: (String, String) -> Unit)

    /** Sends [text] over the open channel to [token]. False = there is none; use the proxy. */
    fun send(token: String, text: String): Boolean

    /** Starts negotiating with [token] if not already (no wait, no error: it is best effort). */
    fun upgrade(token: String)

    /** A signalling frame (`t == RTC_TAG`) that arrived from [from] through the proxy. */
    fun handleSignal(from: String, msg: JsonObject)

    fun isOpen(token: String): Boolean
    fun close(token: String)
    fun closeAll()

    /** ICE servers for the NEXT connections (TURN credentials + STUN). */
    fun setIceServers(servers: List<IceServer>)

    data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)
}
