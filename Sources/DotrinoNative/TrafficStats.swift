import Foundation

/// HOW MUCH TRAFFIC AND BY WHICH ROAD, per connection: the `stats.js` of `@dotrino/proxy-client`
/// (≥ 0.28.0). Same piece as `TrafficStats.kt`. The topbar shows it, so the rule «always the
/// most direct road» can be SEEN on a phone too.
///
/// Bytes of PAYLOAD (UTF-8), not of the wire. Paths: `proxy` (signalling and greeting
/// included), `direct`, `turn`, `webrtc` (WebRTC, not known yet which).
public final class TrafficStats: @unchecked Sendable {
    public let since = Date()

    public struct ByPath: Sendable, Equatable {
        public var proxy: Int64 = 0, direct: Int64 = 0, turn: Int64 = 0, webrtc: Int64 = 0
        mutating func add(_ path: String, _ n: Int64) {
            switch path { case "direct": direct += n; case "turn": turn += n; case "webrtc": webrtc += n; default: proxy += n }
        }
        public var total: Int64 { proxy + direct + turn + webrtc }
    }

    private struct Peer {
        var token: String?, pubkey: String?
        var bytesIn = ByPath(), bytesOut = ByPath()
        var msgsIn = 0, msgsOut = 0
        var lastAt = Date.distantPast
    }

    private let lock = NSLock()
    private var proxyIn: Int64 = 0, proxyOut: Int64 = 0, framesIn = 0, framesOut = 0
    private var peers: [String: Peer] = [:]

    public init() {}

    /// A whole frame of the proxy's WebSocket.
    public func frame(incoming: Bool, bytes: Int) {
        lock.withLock {
            if incoming { proxyIn += Int64(bytes); framesIn += 1 } else { proxyOut += Int64(bytes); framesOut += 1 }
        }
    }

    /// A message addressed to (or coming from) one connection, by `token` or, failing that, `pubkey`.
    public func peer(incoming: Bool, path: String, token: String?, pubkey: String?, bytes: Int) {
        guard let key = token.map({ "t:\($0)" }) ?? pubkey.map({ "k:\($0)" }) else { return }
        lock.withLock {
            var p = peers[key] ?? Peer(token: token, pubkey: pubkey)
            if p.pubkey == nil { p.pubkey = pubkey }
            if incoming { p.bytesIn.add(path, Int64(bytes)); p.msgsIn += 1 } else { p.bytesOut.add(path, Int64(bytes)); p.msgsOut += 1 }
            p.lastAt = Date()
            peers[key] = p
        }
    }

    /// A copy to show. `routeOf`: where each token goes NOW; `pubkeyOf`: whose a token is.
    public func snapshot(routeOf: (String) -> String?, pubkeyOf: (String) -> String?) -> (NetworkStats.Proxy, [NetworkStats.Peer]) {
        let (proxy, list) = lock.withLock { (NetworkStats.Proxy(bytesIn: proxyIn, bytesOut: proxyOut, framesIn: framesIn, framesOut: framesOut), Array(peers.values)) }
        let out = list.map { p in
            NetworkStats.Peer(token: p.token, pubkey: p.pubkey ?? p.token.flatMap(pubkeyOf),
                              route: p.token.flatMap(routeOf) ?? "proxy",
                              bytesIn: p.bytesIn, bytesOut: p.bytesOut, msgsIn: p.msgsIn, msgsOut: p.msgsOut, lastAt: p.lastAt)
        }.sorted { $0.lastAt > $1.lastAt }
        return (proxy, out)
    }
}

/// What `stats()` of `@dotrino/proxy-client` gives, for one session.
public struct NetworkStats: Sendable {
    public let url: String, app: String?, node: String?, token: String?
    public let connected: Bool
    public let since: Date
    public let proxy: Proxy
    public let peers: [Peer]

    public struct Proxy: Sendable { public let bytesIn: Int64, bytesOut: Int64; public let framesIn: Int, framesOut: Int }
    /// `route`: `proxy`, `connecting` (by the proxy while WebRTC is negotiated), `failed`, `direct`,
    /// `turn` or `webrtc` (open channel, road not known).
    public struct Peer: Sendable, Identifiable {
        public let token: String?, pubkey: String?, route: String
        public let bytesIn: TrafficStats.ByPath, bytesOut: TrafficStats.ByPath
        public let msgsIn: Int, msgsOut: Int
        public let lastAt: Date
        public var id: String { token ?? pubkey ?? "" }
    }
}

/// THE LIVE TRANSPORTS OF THIS APP, for whoever shows them without the app wiring it (the topbar).
/// The `Symbol.for('dotrino.transports')` registry of the JS client; same as `DotrinoNetwork.kt`.
public enum DotrinoNetwork {
    public protocol Source: AnyObject { func networkStats() -> NetworkStats }

    private final class Box { weak var s: Source?; init(_ s: Source) { self.s = s } }
    private static let lock = NSLock()
    nonisolated(unsafe) private static var boxes: [Box] = []
    /// Posted (on the main queue) when a transport comes or goes.
    public static let changed = Notification.Name("dotrino.transports")

    public static func register(_ s: Source) {
        let added = lock.withLock { () -> Bool in
            boxes.removeAll { $0.s == nil }
            if boxes.contains(where: { $0.s === s }) { return false }
            boxes.append(Box(s)); return true
        }
        if added { notify() }
    }

    public static func unregister(_ s: Source) {
        let removed = lock.withLock { () -> Bool in
            let n = boxes.count
            boxes.removeAll { $0.s == nil || $0.s === s }
            return boxes.count != n
        }
        if removed { notify() }
    }

    public static func sources() -> [Source] { lock.withLock { boxes.compactMap(\.s) } }

    private static func notify() { DispatchQueue.main.async { NotificationCenter.default.post(name: changed, object: nil) } }
}

/// UTF-8 length of a string.
func utf8Length(_ s: String) -> Int { s.utf8.count }

// MARK: the report as text (0.29.0)
//
// The network sheet has a «Copy» button: this is what it copies, the same lines on the web, on
// Android and on iOS, so a stats dump can be pasted into a chat and read without the app.

extension NetworkStats {
    /// One transport, as lines of plain text.
    public func report() -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
        var out = "Proxy \(url)"
        if let app { out += " | app=\(app)" }
        if let node { out += " | node=\(node)" }
        out += connected ? " | connected" : " | disconnected"
        out += " | since \(f.string(from: since))\n"
        out += "  proxy total: in \(NetworkStats.bytes(proxy.bytesIn)) / out \(NetworkStats.bytes(proxy.bytesOut)) (frames \(proxy.framesIn)/\(proxy.framesOut))\n"
        out += "  connections: \(peers.count)\n"
        for p in peers {
            let who = p.pubkey.map { $0.count > 14 ? "\($0.prefix(6))…\($0.suffix(6))" : $0 } ?? "?"
            out += "  - \(who)"
            if let t = p.token { out += " (token \(t.count > 10 ? String(t.prefix(8)) + "…" : t))" }
            out += " | route=\(p.route) | in: \(NetworkStats.paths(p.bytesIn)) | out: \(NetworkStats.paths(p.bytesOut)) | \(p.msgsIn + p.msgsOut) msgs\n"
        }
        return out
    }

    /// Every transport of the app, with a header line (what the sheet copies).
    public static func report(_ all: [NetworkStats]) -> String {
        let f = ISO8601DateFormatter()
        var out = "Dotrino network stats · \(f.string(from: Date()))\n"
        if all.isEmpty { out += "(no transports)\n" }
        for s in all { out += s.report() }
        return out
    }

    static func bytes(_ n: Int64) -> String {
        let v = Double(n)
        if v < 1024 { return "\(n) B" }
        if v < 1024 * 1024 { return String(format: "%.1f KB", v / 1024) }
        return String(format: "%.2f MB", v / 1024 / 1024)
    }

    static func paths(_ b: TrafficStats.ByPath) -> String {
        let parts = [("proxy", b.proxy), ("direct", b.direct), ("turn", b.turn), ("webrtc", b.webrtc)].filter { $0.1 > 0 }.map { "\($0.0) \(bytes($0.1))" }
        return parts.isEmpty ? "0 B" : parts.joined(separator: ", ")
    }
}
