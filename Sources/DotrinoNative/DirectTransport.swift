import Foundation

/// THE DIRECT ROAD (steps 2 and 3 of the transport), as the `WebRTCManager` of
/// `@dotrino/proxy-client`. Same piece as `DirectTransport.kt`. The core does not carry
/// libwebrtc; it is plugged into `SealedSession` from `DotrinoNativeWebRTC`.
///
/// Wire-compatible with the JS: signalling through the proxy as `{ t: "__cc_rtc__", kind: "sdp" |
/// "ice", … }`, a data channel `cc` (ordered) opened by the peer with the greater token, and the
/// same text in it that would have gone through the proxy. Never blocks.
public protocol DirectTransport: AnyObject {
    func bind(selfToken: @escaping () -> String?, signalSend: @escaping (String, JSON) -> Void, deliver: @escaping (String, String) -> Void)
    /// Sends over the open channel. False = there is none; use the proxy.
    func send(_ token: String, _ text: String) -> Bool
    /// Starts negotiating if not already (best effort, no wait).
    func upgrade(_ token: String)
    func handleSignal(from: String, _ msg: JSON)
    func isOpen(_ token: String) -> Bool
    func close(_ token: String)
    func closeAll()
    func setIceServers(_ servers: [IceServer])
    /// Where `token` goes NOW, for the network stats: `direct` / `turn` (open channel, without or
    /// with a relay), `webrtc` (open, road not known yet), `connecting`, `failed`, or nil (the
    /// proxy). Read from what ICE chose, never guessed.
    func route(_ token: String) -> String?
}

extension DirectTransport {
    public func route(_ token: String) -> String? { isOpen(token) ? "webrtc" : nil }
}

public let rtcTag = "__cc_rtc__"

public struct IceServer: Sendable, Equatable {
    public let urls: [String]
    public let username: String?
    public let credential: String?
    public init(urls: [String], username: String? = nil, credential: String? = nil) {
        self.urls = urls; self.username = username; self.credential = credential
    }
    /// `DEFAULT_ICE_SERVERS` of the JS client.
    public static let defaultStun: [IceServer] = [
        .init(urls: ["stun:stun.l.google.com:19302"]),
        .init(urls: ["stun:stun1.l.google.com:19302"]),
        .init(urls: ["stun:global.stun.twilio.com:3478"]),
    ]
}
