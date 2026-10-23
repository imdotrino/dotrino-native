import Foundation

/// THE LADDER of direct roads (`dotrino-proxy-client/docs/bluetooth.md` §1): WebRTC when it goes
/// direct, else Bluetooth, else WebRTC through TURN — the proxy is what is left when this says
/// false. Both negotiate in parallel and never block. Plug it where one road went:
/// `session.useDirect(DirectLadder(webrtc: WebRTCDirect(), bluetooth: BluetoothDirect()))`.
public final class DirectLadder: DirectTransport, @unchecked Sendable {
    public let webrtc: DirectTransport?
    public let bluetooth: BluetoothDirect?

    public init(webrtc: DirectTransport?, bluetooth: BluetoothDirect?) {
        self.webrtc = webrtc
        self.bluetooth = bluetooth
    }

    public func bind(selfToken: @escaping () -> String?, signalSend: @escaping (String, JSON) -> Void,
                     deliver: @escaping (String, String) -> Void, deliverBinary: @escaping (String, Data) -> Void) {
        webrtc?.bind(selfToken: selfToken, signalSend: signalSend, deliver: deliver, deliverBinary: deliverBinary)
        bluetooth?.bind(selfToken: selfToken, signalSend: signalSend, deliver: deliver, deliverBinary: deliverBinary)
    }

    /// The best open road for `token`, in ladder order, or nil (the proxy).
    private func best(_ token: String) -> DirectTransport? {
        let rtcOpen = webrtc?.isOpen(token) ?? false
        if rtcOpen, webrtc?.route(token) != "turn" { return webrtc }
        if let b = bluetooth, b.isOpen(token) { return b }
        if rtcOpen { return webrtc }
        return nil
    }

    public func sendBinary(_ token: String, _ bytes: Data) -> Bool { best(token)?.sendBinary(token, bytes) ?? false }

    public func upgrade(_ token: String) {
        webrtc?.upgrade(token)
        bluetooth?.upgrade(token)
    }

    public func handleSignal(from: String, _ msg: JSON) {
        if msg["t"]?.string == BleWire.tag { bluetooth?.handleSignal(from: from, msg) }
        else { webrtc?.handleSignal(from: from, msg) }
    }

    public func isOpen(_ token: String) -> Bool { best(token) != nil }

    public func route(_ token: String) -> String? {
        if let b = best(token) { return b.route(token) }
        return webrtc?.route(token) ?? bluetooth?.route(token)
    }

    public func close(_ token: String) { webrtc?.close(token); bluetooth?.close(token) }
    public func closeAll() { webrtc?.closeAll(); bluetooth?.closeAll() }
    public func setIceServers(_ servers: [IceServer]) { webrtc?.setIceServers(servers) }
}
