import Foundation

/// THE DIRECT ROAD (steps 2 and 3 of the transport), as the `WebRTCManager` of
/// `@dotrino/proxy-client`. Same piece as `DirectTransport.kt`. The core does not carry
/// libwebrtc; it is plugged into `SealedSession` from `DotrinoNativeWebRTC`.
///
/// Wire-compatible with the JS: signalling through the proxy as `{ t: "__cc_rtc__", kind: "start" | "sdp" |
/// "ice", … }`, a data channel `cc` (ordered) opened by the peer with the greater token, and the
/// same in it that would have gone through the proxy. This library sends packages in binary
/// messages, and reads text too (what an older JS client sends). Never blocks.
public protocol DirectTransport: AnyObject {
    /// Wiring: my token, how to signal, where arrivals go — text to `deliver`, a binary message
    /// (a package, `BinPack`) to `deliverBinary`.
    func bind(selfToken: @escaping () -> String?, signalSend: @escaping (String, JSON) -> Void,
              deliver: @escaping (String, String) -> Void, deliverBinary: @escaping (String, Data) -> Void)
    /// Sends a package (`BinPack`) as a binary message over the open channel. False = there is none; use the proxy.
    func sendBinary(_ token: String, _ bytes: Data) -> Bool
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

/// A radio that is also a direct road and can announce and discover nearby (`{kind, data}`). The
/// Bluetooth step (`BluetoothDirect`, product `DotrinoNativeBluetooth`) is one; the core only knows
/// this protocol, so an app that does not use Bluetooth does not link CoreBluetooth.
public protocol NearbyRadio: DirectTransport {
    /// Permission granted and the radio on; false = the step is not there.
    var available: Bool { get }
    /// Announces until the returned closure is called.
    func announce(kind: String, data: String) throws -> () -> Void
    /// What is announced nearby with `kind`, for `timeout` seconds.
    func discover(kind: String, timeout: TimeInterval, onFound: ((String) -> Void)?) async throws -> [String]
}
