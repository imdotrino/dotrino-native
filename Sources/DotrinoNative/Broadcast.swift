import Foundation

/// THE BROADCAST OF `@dotrino/lobby` (`src/broadcast.js`), the HOST side, in native: one device
/// publishes its state and whoever has the link watches it — SEALED to each one and SIGNED by
/// the host's identity. Viewers watch on the web (the PWA opens the `#watch=` link). Same piece
/// as `Broadcast.kt`, wire-compatible with the JS. It reconnects by itself, with the same link.
public final class BroadcastHost: @unchecked Sendable {
    public struct Ref: Codable, Equatable, Sendable {
        public let key: String
        public let secret: String
        public init(key: String, secret: String) { self.key = key; self.secret = secret }
    }

    public struct Status: Sendable {
        public let status: String // connecting | live | offline | closed
        public let reason: String?
    }

    public static let sealApp = "dotrino-lobby"
    private static let watchKind = "bcast.watch"
    private static let bcastKind = "bcast.state"
    private static let deniedKind = "bcast.denied"
    private static let watcherTtlMs: Int64 = 150_000
    private static let republishMs: Int64 = 10 * 60_000
    private static let nodeIdLen = 12

    public static func isNodeId(_ s: String?) -> Bool {
        guard let s, s.count == nodeIdLen else { return false }
        return s.allSatisfy { ("1"..."9").contains($0) || ("A"..."Z").contains($0) }
    }

    /// `newBroadcastRef`: the key carries the proxy node in front (like a token); no node = `_`.
    public static func newRef(node: String?) -> Ref {
        Ref(key: (isNodeId(node) ? node! : "_") + Crypto.b64url(Crypto.randomBytes(16)), secret: Crypto.b64url(Crypto.randomBytes(16)))
    }

    /// `broadcastChannel`: `NODE/ccbcast/<game>/<key>` (or without the node).
    public static func channel(_ gameId: String, _ key: String) -> String {
        let id = String(key.prefix(nodeIdLen))
        let name = "ccbcast/\(gameId)/\(key)"
        return isNodeId(id) ? "\(id)/\(name)" : name
    }

    /// `encodeBroadcastRef`: `key.secret.x.y` — what goes in the `#fragment` of the link.
    public static func encodeRef(_ ref: Ref, hostPubkey: String) throws -> String {
        guard let jwk = try? JSON.parse(hostPubkey), jwk["crv"]?.string == "P-256",
              let x = jwk["x"]?.string, let y = jwk["y"]?.string else { throw CryptoError("the host key is not P-256") }
        let parts = [ref.key, ref.secret, x, y]
        guard parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy { $0.isLetter || $0.isNumber || $0 == "_" || $0 == "-" } }) else {
            throw CryptoError("incomplete broadcast ref")
        }
        return parts.joined(separator: ".")
    }

    static func envelope(_ gameId: String, _ room: String, _ kind: String, _ data: JSON) -> JSON {
        ["__ccl": 1, "g": .string(gameId), "r": .string(room), "k": .string(kind), "d": data]
    }

    private let url: String
    private let gameId: String
    private let profile: Profile
    private let transport: DeviceKeys
    private let maxViewers: Int
    private let lock = NSLock()
    private var current: Ref?
    private var conn: ProxyConnection?
    private var watchers: [String: Int64] = [:]
    private var latest: JSON?
    private var at: Int64 = 0
    private var seq = 0
    private var closed = false
    private var loop: Task<Void, Never>?

    public var onViewers: (Int) -> Void = { _ in }
    public var onStatus: (Status) -> Void = { _ in }
    public var onWarn: (String, Error?) -> Void = { _, _ in }

    public var viewers: Int { lock.withLock { watchers.count } }
    /// The link reference (after start, with the node's key if it was new).
    public var linkRef: Ref? { lock.withLock { current } }

    public init(url: String, gameId: String, profile: Profile, transport: DeviceKeys, ref: Ref? = nil, maxViewers: Int = 50) {
        self.url = url; self.gameId = gameId; self.profile = profile; self.transport = transport
        self.current = ref; self.maxViewers = maxViewers
    }

    /// The link to watch: `<base>#watch=<ref>`.
    public func link(base: String) throws -> String {
        guard let r = linkRef else { throw CryptoError("the broadcast has not started") }
        return base + "#watch=" + (try Self.encodeRef(r, hostPubkey: profile.publickey))
    }

    /// Connect, identify as the profile, announce my encryption key and publish the channel.
    /// Keeps doing it (reconnecting) until [close].
    public func start() async throws {
        guard profile.canSign else { throw Profile.ProfileError("this device does not sign for your profile", code: "needs-vault-signer") }
        try await connectOnce()
        loop = Task { [weak self] in await self?.keepAlive() }
    }

    private func connectOnce() async throws {
        onStatus(Status(status: "connecting", reason: nil))
        let c = try ProxyConnection(url)
        try await c.connect()
        let ref = lock.withLock { () -> Ref in
            if current == nil { current = Self.newRef(node: c.node) }
            return current!
        }
        try await c.identifyAs(profile.publickey) { try self.profile.signData($0) }
        try await c.announceEncPub(profile.publickey, profile.encPub) { try self.profile.signData($0) }
        _ = c.onMessage { [weak self, weak c] inc in
            guard let self, let c else { return }
            Task { await self.onMessage(c, inc) }
        }
        _ = c.onEvent { [weak self] e in
            guard let self, case .peerGone(let token, _) = e else { return }
            let n: Int? = self.lock.withLock { self.watchers.removeValue(forKey: token) != nil ? self.watchers.count : nil }
            if let n { self.onViewers(n) }
        }
        try await c.publish(Self.channel(gameId, ref.key), transport: transport)
        let old = lock.withLock { () -> ProxyConnection? in
            let o = conn
            conn = c
            watchers.removeAll()
            return o
        }
        old?.close()
        onViewers(0)
        onStatus(Status(status: "live", reason: nil))
    }

    /// Republish before the proxy forgets the channel, forget silent viewers, and reconnect.
    private func keepAlive() async {
        var lastPublish = nowMs()
        var backoff: UInt64 = 2
        while !Task.isCancelled && !lock.withLock({ closed }) {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            let c = lock.withLock { conn }
            if c == nil || c?.closed != nil {
                onStatus(Status(status: "offline", reason: c?.closed))
                do {
                    try await connectOnce()
                    lastPublish = nowMs()
                    backoff = 2
                } catch {
                    onWarn("reconnect", error)
                    try? await Task.sleep(nanoseconds: backoff * 1_000_000_000)
                    backoff = min(backoff * 2, 60)
                }
                continue
            }
            let now = nowMs()
            if now - lastPublish > Self.republishMs, let c, let ref = linkRef {
                do { try await c.publish(Self.channel(gameId, ref.key), transport: transport) } catch { onWarn("republish", error) }
                lastPublish = now
            }
            let limit = now - Self.watcherTtlMs
            let pruned: Int? = lock.withLock {
                let before = watchers.count
                watchers = watchers.filter { $0.value >= limit }
                return watchers.count != before ? watchers.count : nil
            }
            if let pruned { onViewers(pruned) }
        }
    }

    /// What arrives: only SEALED envelopes of this broadcast; anything else is dropped.
    private func onMessage(_ c: ProxyConnection, _ inc: ProxyConnection.Incoming) async {
        guard let from = inc.from, inc.payload["app"]?.string == Self.sealApp,
              let senderEnc = inc.payload["from"]?.string, let sealed = inc.payload["sealed"],
              let text = try? profile.decrypt(senderEnc, sealed), let env = try? JSON.parse(text),
              let ref = linkRef,
              env["__ccl"]?.int == 1, env["g"]?.string == gameId, env["r"]?.string == ref.key,
              env["k"]?.string == Self.watchKind
        else { return } // not ours, sealed to somebody else or tampered with: staying quiet is the point
        let d = env["d"] ?? .object([:])
        guard d["secret"]?.string == ref.secret else {
            await send(c, from, Self.deniedKind, ["reason": "bad-secret"]); return
        }
        let (isNew, full, count): (Bool, Bool, Int) = lock.withLock {
            let isNew = watchers[from] == nil
            if isNew && watchers.count >= maxViewers { return (true, true, watchers.count) }
            watchers[from] = nowMs()
            return (isNew, false, watchers.count)
        }
        if full { await send(c, from, Self.deniedKind, ["reason": "full"]); return }
        if isNew { onViewers(count) }
        // Only what is missing: on joining, or if what it has is older than mine.
        guard let l = lock.withLock({ latest }), let mine = l["payload"]?["at"]?.int else { return }
        if let theirs = d["at"]?.int, theirs >= mine { return }
        await send(c, from, Self.bcastKind, l)
    }

    /// The new state, signed ONCE by the host's identity and sealed to each viewer. `at` always
    /// grows, also after a restart: the viewer uses it to drop old or repeated states.
    public func publish(_ state: JSON) async throws {
        guard !lock.withLock({ closed }), let ref = linkRef else { throw CryptoError("this broadcast is closed or not started") }
        let (atNow, seqNow): (Int64, Int) = lock.withLock {
            at = max(nowMs(), at + 1); seq += 1
            return (at, seq)
        }
        let payload: JSON = ["v": 1, "g": .string(gameId), "k": .string(ref.key), "at": .int(atNow), "seq": .int(Int64(seqNow)), "state": state]
        let l: JSON = ["payload": payload, "signature": .string(try profile.signData(payload))]
        let (c, tokens) = lock.withLock { () -> (ProxyConnection?, [String]) in
            latest = l
            return (conn, Array(watchers.keys))
        }
        guard let c else { return }
        for t in tokens { await send(c, t, Self.bcastKind, l) }
    }

    /// EVERYTHING GOES SEALED; if it cannot be sealed, it does not go, and it is said.
    private func send(_ c: ProxyConnection, _ token: String, _ kind: String, _ data: JSON) async {
        do {
            guard let ref = linkRef, let peer = c.pubkeyOfToken(token) else {
                throw ProxyError("\(token) never said whose it is", code: "no-peer-identity")
            }
            let peerEnc = try await c.encPubOf(peer)
            let sealed = try profile.encrypt([peerEnc], Self.envelope(gameId, ref.key, kind, data).text)
            try c.sendTo([token], ["app": .string(Self.sealApp), "sealed": sealed, "from": .string(profile.encPub)])
        } catch {
            onWarn("sealed \(kind) to \(token)", error)
        }
    }

    /// Stop broadcasting. The link stops working; who was watching keeps the last state.
    public func close() async {
        let c: ProxyConnection? = lock.withLock {
            if closed { return nil }
            closed = true
            let c = conn
            conn = nil
            watchers.removeAll()
            return c
        }
        loop?.cancel()
        if let c, let ref = linkRef {
            try? await c.unpublish(Self.channel(gameId, ref.key), transport: transport)
            c.close()
        }
        onStatus(Status(status: "closed", reason: nil))
    }
}
