package com.dotrino.sdk

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * THE LADDER of direct roads (`dotrino-proxy-client/docs/bluetooth.md` §1): WebRTC when it goes
 * direct, else Bluetooth, else WebRTC through TURN — the proxy is what is left when this says
 * false. Both negotiate in parallel and never block. Plug it where one road went:
 * `session.useDirect(DirectLadder(WebRtcDirect(…), BluetoothDirect(context)))`.
 */
class DirectLadder(val webrtc: DirectTransport?, val bluetooth: BluetoothDirect?) : DirectTransport {
    override fun bind(selfToken: () -> String?, signalSend: (String, JsonObject) -> Unit, deliver: (String, String) -> Unit, deliverBinary: (String, ByteArray) -> Unit) {
        webrtc?.bind(selfToken, signalSend, deliver, deliverBinary)
        bluetooth?.bind(selfToken, signalSend, deliver, deliverBinary)
    }

    /** The best open road for [token], in ladder order, or null (the proxy). */
    private fun best(token: String): DirectTransport? {
        val rtcOpen = webrtc?.isOpen(token) == true
        if (rtcOpen && webrtc?.route(token) != "turn") return webrtc
        if (bluetooth?.isOpen(token) == true) return bluetooth
        if (rtcOpen) return webrtc
        return null
    }

    override fun sendBinary(token: String, bytes: ByteArray): Boolean = best(token)?.sendBinary(token, bytes) ?: false

    override fun upgrade(token: String) { webrtc?.upgrade(token); bluetooth?.upgrade(token) }

    override fun handleSignal(from: String, msg: JsonObject) {
        if ((msg["t"] as? JsonPrimitive)?.content == BleWire.TAG) bluetooth?.handleSignal(from, msg) else webrtc?.handleSignal(from, msg)
    }

    override fun isOpen(token: String): Boolean = best(token) != null

    override fun route(token: String): String? = best(token)?.route(token) ?: webrtc?.route(token) ?: bluetooth?.route(token)

    override fun close(token: String) { webrtc?.close(token); bluetooth?.close(token) }
    override fun closeAll() { webrtc?.closeAll(); bluetooth?.closeAll() }
    override fun setIceServers(servers: List<DirectTransport.IceServer>) { webrtc?.setIceServers(servers) }
}
