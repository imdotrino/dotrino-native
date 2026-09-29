import Foundation

/// A connection to the proxy that STAYS UP and where EVERYTHING GOES SEALED: the native
/// counterpart of `WebSocketProxyClient` with `requireSealed: true`. Same piece as
/// `SealedSession.kt`: reconnects, re-identifies and re-announces its key every time; moves to
/// the next proxy after `failsBeforeFailover` failures; drops what arrives unsealed; delivers
/// what arrives sealed with `senderEncPub`, the key that sealed it.
public final class SealedSession: @unchecked Sendable {
    public static let failsBeforeFailover = 3
    private static let maxBackoff: TimeInterval = 30

    public struct SessionError: Error, CustomStringConvertible {
        public let description: String
        public let code: String
    }

    /// A sealed message, opened. `fromPubkey` is who the proxy or the greeting says it is —
    /// trust it only after checking `senderEncPub`.
    public struct Message: Sendable {
        public let fromToken: String?
        public let fromPubkey: String?
        public let payload: JSON
        public let senderEncPub: String
        public let queued: Bool
        public let queuedAt: Int64?
    }

    /// `connecting` | `online` | `offline` | `closed`.
    public struct Status: Sendable, Equatable {
        public let state: String
        public let url: String
        public let reason: String?
    }

    public let sealing: IdentitySealing
    private let urls: [String]
    private let profile: Profile
    private let lock = NSLock()
    private var conn: ProxyConnection?
    private var closed = false
    private var loop: Task<Void, Never>?
    private var urlIndex = 0
    private var messageListeners: [UUID: (Message) -> Void] = [:]
    private var statusListeners: [UUID: (Status) -> Void] = [:]
    private var eventListeners: [UUID: (ProxyConnection.Event) -> Void] = [:]
    private var onlineListeners: [UUID: () -> Void] = [:]
    public private(set) var status: Status

    public var onWarn: (String, Error?) -> Void = { _, _ in }
    private var direct: DirectTransport?

    /// Plugs in the DIRECT ROAD (`DotrinoNativeWebRTC`): what goes by token prefers an open
    /// channel, and the first message to someone opens one underneath. TURN is asked after
    /// every identify (signed by the profile).
    public func useDirect(_ d: DirectTransport) {
        lock.withLock { direct = d }
        d.bind(
            selfToken: { [weak self] in self?.token },
            signalSend: { [weak self] to, msg in
                guard let c = self?.lock.withLock({ self?.conn }) else { return }
                try? c.sendTo([to], msg)
            },
            deliver: { [weak self] from, text in
                guard let self, let c = self.lock.withLock({ self.conn }), let payload = try? JSON.parse(text), payload.object != nil else { return }
                Task { await self.deliver(c, ProxyConnection.Incoming(from: from, fromPubkey: nil, payload: payload)) }
            })
    }

    public init(urls: [String], profile: Profile, app: String) {
        precondition(!urls.isEmpty, "at least one proxy url")
        self.urls = urls; self.profile = profile
        sealing = IdentitySealing(profile: profile, app: app)
        status = Status(state: "connecting", url: urls[0], reason: nil)
    }

    private func add<T>(_ l: T, _ path: ReferenceWritableKeyPath<SealedSession, [UUID: T]>) -> () -> Void {
        let key = UUID()
        lock.withLock { self[keyPath: path][key] = l }
        return { [weak self] in self?.lock.withLock { self?[keyPath: path][key] = nil } }
    }
    public func onMessage(_ l: @escaping (Message) -> Void) -> () -> Void { add(l, \.messageListeners) }
    public func onStatus(_ l: @escaping (Status) -> Void) -> () -> Void { add(l, \.statusListeners) }
    public func onEvent(_ l: @escaping (ProxyConnection.Event) -> Void) -> () -> Void { add(l, \.eventListeners) }
    /// Every time the session is (again) online and identified.
    public func onOnline(_ l: @escaping () -> Void) -> () -> Void { add(l, \.onlineListeners) }

    public var url: String { lock.withLock { urls[urlIndex % urls.count] } }
    public var isOnline: Bool { lock.withLock { status.state == "online" } }
    public var token: String? { lock.withLock { conn }?.token }

    private func setStatus(_ s: Status) {
        let ls = lock.withLock { status = s; return Array(statusListeners.values) }
        ls.forEach { $0(s) }
    }

    /// Starts the loop. Returns at once; `awaitOnline` waits for the first connection.
    public func start() {
        lock.lock(); defer { lock.unlock() }
        guard loop == nil else { return }
        loop = Task { [weak self] in await self?.run() }
    }

    private func run() async {
        var fails = 0
        while !Task.isCancelled && !lock.withLock({ closed }) {
            let u = url
            setStatus(Status(state: "connecting", url: u, reason: nil))
            do {
                let c = try ProxyConnection(u)
                do {
                    try await c.connect()
                    _ = c.onMessage { [weak self, weak c] inc in
                        guard let self, let c else { return }
                        Task { await self.deliver(c, inc) }
                    }
                    _ = c.onEvent { [weak self] e in
                        guard let self else { return }
                        // A peer that left takes its direct channel with it (tokens are not reused).
                        if case .peerGone(let t, _) = e { self.lock.withLock { self.direct }?.close(t) }
                        self.lock.withLock { Array(self.eventListeners.values) }.forEach { $0(e) }
                    }
                    try await c.identifyAs(profile.publickey) { try self.profile.signData($0) }
                    lock.withLock { conn = c }
                    do { try await c.announceEncPub(profile.publickey, profile.encPub) { try self.profile.signData($0) } }
                    catch { onWarn("could not announce my encryption key", error) }
                    if let d = lock.withLock({ direct }) {
                        Task { [weak self] in await self?.enableTurn(c, d) }
                    }
                    fails = 0
                    setStatus(Status(state: "online", url: u, reason: nil))
                    lock.withLock { Array(onlineListeners.values) }.forEach { $0() }
                    let why = await c.awaitClosed()
                    lock.withLock { conn = nil }
                    if lock.withLock({ closed }) { break }
                    setStatus(Status(state: "offline", url: u, reason: why))
                } catch {
                    lock.withLock { conn = nil }
                    c.close()
                    throw error
                }
            } catch {
                if lock.withLock({ closed }) { break }
                fails += 1
                setStatus(Status(state: "offline", url: u, reason: "\(error)"))
                onWarn("connection to \(u) failed", error)
                if fails >= Self.failsBeforeFailover && urls.count > 1 { lock.withLock { urlIndex += 1 }; fails = 0 }
            }
            let backoff = min(Self.maxBackoff, TimeInterval(1 << min(fails, 5)))
            try? await Task.sleep(nanoseconds: UInt64(backoff * 1_000_000_000))
        }
        setStatus(Status(state: "closed", url: url, reason: nil))
    }

    /// Waits until online (or `timeout` passes).
    public func awaitOnline(timeout: TimeInterval = 15) async -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if isOnline { return true }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return isOnline
    }

    public func close() {
        lock.withLock { direct }?.closeAll()
        let c: ProxyConnection? = lock.withLock { closed = true; return conn }
        c?.close()
        loop?.cancel()
    }

    private func live() throws -> ProxyConnection {
        guard let c = lock.withLock({ conn }) else { throw SessionError(description: "not connected to the proxy", code: "disconnected") }
        return c
    }

    private func enableTurn(_ c: ProxyConnection, _ d: DirectTransport) async {
        do {
            let servers = try await c.turnCredentials(profile.publickey) { try self.profile.signData($0) }
            if !servers.isEmpty { d.setIceServers(servers + IceServer.defaultStun) }
        } catch { onWarn("could not get TURN credentials", error) }
    }

    private func deliver(_ c: ProxyConnection, _ inc: ProxyConnection.Incoming) async {
        // The direct road's signalling is a transport control frame: to WebRTC, not the app.
        if inc.payload["t"]?.string == rtcTag {
            if let d = lock.withLock({ direct }), let from = inc.from { d.handleSignal(from: from, inc.payload) }
            return
        }
        guard sealing.isSealed(inc.payload) else { onWarn("dropped a message that arrived unsealed", nil); return }
        // Sealed to somebody else, or tampered with: staying quiet is the point.
        guard let opened = try? sealing.open(inc.payload) else { return }
        let claimed = inc.fromPubkey ?? inc.from.flatMap { c.pubkeyOfToken($0) }
        let m = Message(fromToken: inc.from, fromPubkey: claimed, payload: opened.payload,
                        senderEncPub: opened.senderEncPub, queued: inc.queued, queuedAt: inc.queuedAt)
        lock.withLock { Array(messageListeners.values) }.forEach { $0(m) }
    }

    /// The encryption key of an identity, VERIFIED against its signature. Throws with `code`.
    public func encPubOf(_ publickey: String) async throws -> String { try await live().encPubOf(publickey) }

    /// Whose this token is, according to its transport greeting (does not authenticate).
    public func pubkeyOfToken(_ token: String) -> String? { lock.withLock { conn }?.pubkeyOfToken(token) }

    /// Sealed, BY TOKEN (live, and the road that can go direct). Empty keys = the one their
    /// identity announced, found through the greeting of that token.
    public func sendSealed(toToken token: String, _ payload: JSON, recipientEncPubs: [String] = []) async throws {
        let c = try live()
        var keys = recipientEncPubs
        if keys.isEmpty {
            guard let pk = c.pubkeyOfToken(token) else {
                throw SessionError(description: "nobody has said whose this token is — greet it first", code: "no-peer-identity")
            }
            keys = [try await c.encPubOf(pk)]
        }
        let sealed = try sealing.seal(payload, to: keys)
        let d = lock.withLock { direct }
        if let d, d.send(token, sealed.text) { return }
        try c.sendTo([token], sealed)
        // And try to go direct for the next one, without waiting for anybody.
        d?.upgrade(token)
    }

    /// Sealed, BY PUBKEY (the proxy's 24 h offline queue). `quiet`: queue without ringing.
    public func sendSealed(toPubkey pubkey: String, _ payload: JSON, recipientEncPubs: [String] = [], quiet: Bool = false) async throws {
        let c = try live()
        let keys = recipientEncPubs.isEmpty ? [try await c.encPubOf(pubkey)] : recipientEncPubs
        try c.sendByPubkey(pubkey, try sealing.seal(payload, to: keys), quiet: quiet)
    }

    public func requestPairingCode(ttlMs: Int64? = nil) async throws -> ProxyConnection.PairingCode {
        try await live().requestPairingCode(ttlMs: ttlMs)
    }

    public func redeemPairingCode(_ code: String) async throws -> String { try await live().redeemPairingCode(code) }

    /// WHOSE IS THIS TOKEN: greets it and waits for its answer (does not authenticate — what
    /// is sent afterwards goes sealed to the key THAT identity announced signed).
    public func whoIs(_ token: String, timeout: TimeInterval = 10) async throws -> String? {
        let c = try live()
        if let pk = c.pubkeyOfToken(token) { return pk }
        try c.helloTo(token)
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let pk = c.pubkeyOfToken(token) { return pk }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        return nil
    }
}
