import Foundation

public struct ProxyError: Error, CustomStringConvertible {
    public let description: String
    public let code: String
    public init(_ d: String, code: String) { description = d; self.code = code }
}

/// A value that arrives once, awaited with a deadline. The first `finish` wins; later ones
/// (a late answer, a timeout after the answer) are ignored.
final class OneShot<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var cont: CheckedContinuation<T, Error>?
    private var result: Result<T, Error>?

    func finish(_ r: Result<T, Error>) {
        lock.lock()
        if result != nil { lock.unlock(); return }
        result = r
        let c = cont; cont = nil
        lock.unlock()
        c?.resume(with: r)
    }

    func wait(timeout: TimeInterval, onTimeout: @autoclosure @escaping () -> Error) async throws -> T {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<T, Error>) in
            lock.lock()
            if let r = result { lock.unlock(); c.resume(with: r); return }
            cont = c
            lock.unlock()
            Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                self?.finish(.failure(onTimeout()))
            }
        }
    }
}

/// ONE connection to the proxy, speaking the same frames as `@dotrino/proxy-client`
/// (`src/client.js`): `connected` gives the token, `identify` binds my key, a directed
/// message goes `{ to_publickey, message }` and arrives as `{ type:'message', from, message }`.
///
/// It does not reconnect by itself: whoever owns it (one per account, while the requests
/// screen is open) decides what a dropped connection means. A dead socket fails every
/// pending request with its reason instead of leaving it to time out.
public final class ProxyConnection: NSObject, URLSessionWebSocketDelegate, @unchecked Sendable {
    /// A directed message: who sent it (connection token and, if identified, key) and its payload.
    public struct Incoming: Sendable {
        public let from: String?
        public let fromPubkey: String?
        public let payload: JSON
        /// It waited in the proxy's offline queue (and since when).
        public var queued: Bool = false
        public var queuedAt: Int64? = nil
    }

    private let url: URL
    private let lock = NSLock()
    private var session: URLSession!
    private var ws: URLSessionWebSocketTask?
    private let connected = OneShot<String>()
    private let ended = OneShot<String>()
    private var pending: [String: OneShot<JSON>] = [:]
    private var nextId = 1
    /// What went to a token and waits to know if that token still exists (`sendToOrElse`).
    private var tokenWatch: [String: (token: String, at: Date, onGone: () -> Void)] = [:]
    private var listeners: [UUID: (Incoming) -> Void] = [:]
    private var _closed: String?
    public private(set) var token: String?
    /// The proxy node this connection lives on (12 chars), from `connected`.
    public private(set) var node: String?
    /// What this proxy says it can do (`caps`), or nil for a proxy older than that.
    public private(set) var caps: [String]?

    public static let helloTag = "__cc_hello__"

    /// Transport events besides messages: a peer left, a channel changed, a token said whose it is.
    public enum Event: Sendable {
        case peerGone(token: String, channel: String?)
        case joined(channel: String, token: String)
        case left(channel: String, token: String)
        case peerIdentity(token: String, publickey: String)
    }
    private var eventListeners: [UUID: (Event) -> Void] = [:]
    public func onEvent(_ l: @escaping (Event) -> Void) -> () -> Void {
        let key = UUID()
        lock.withLock { eventListeners[key] = l }
        return { [weak self] in self?.lock.withLock { self?.eventListeners[key] = nil } }
    }
    private func emit(_ e: Event) { lock.withLock { Array(eventListeners.values) }.forEach { $0(e) } }

    // THE GREETING (`helloTo` of the JS client): «this token is this identity». A control frame
    // of the transport, in the clear on purpose: it only carries a PUBLIC key.
    private var tokenPubkeys: [String: String] = [:]
    private var helloSent = Set<String>()
    /// The identity I identified as; the greeting says it.
    public private(set) var myPublickey: String?
    private var encPubs: [String: String] = [:]

    public var closed: String? { lock.lock(); defer { lock.unlock() }; return _closed }

    /// The audience that goes inside `identify`: the proxy URL without trailing slashes.
    public let audience: String

    /// WHICH app this connection is (`vault`, `messenger`…; websocket-proxy ≥ 1.4.0). Every
    /// Dotrino app on a phone speaks with the same key, so the proxy needs to know which one to
    /// ring and whose queued messages to hand over. Routing, not content. `nil`: everything.
    public let app: String?

    public init(_ urlString: String, app: String? = nil) throws {
        guard let u = URL(string: urlString) else { throw ProxyError("invalid proxy url", code: "bad-url") }
        if let app, app.range(of: "^[a-z0-9][a-z0-9-]{0,31}$", options: .regularExpression) == nil {
            throw ProxyError("app: \"\(app)\" is not a valid app name", code: "bad-app")
        }
        self.app = app
        url = u
        var a = urlString
        while a.hasSuffix("/") { a.removeLast() }
        audience = a
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
    }

    @discardableResult
    public func connect(timeout: TimeInterval = 10) async throws -> String {
        let t = session.webSocketTask(with: url)
        t.maximumMessageSize = 4 * 1024 * 1024
        lock.withLock { ws = t }
        t.resume()
        receive(t)
        ping()
        return try await connected.wait(timeout: timeout, onTimeout: ProxyError("the proxy did not greet in time", code: "timeout"))
    }

    public func onMessage(_ l: @escaping (Incoming) -> Void) -> () -> Void {
        let key = UUID()
        lock.lock(); listeners[key] = l; lock.unlock()
        return { [weak self] in self?.lock.lock(); self?.listeners[key] = nil; self?.lock.unlock() }
    }

    public func close() {
        ws?.cancel(with: .normalClosure, reason: nil)
        die("closed by us")
    }

    /// Suspends until this connection dies, and says why.
    public func awaitClosed() async -> String {
        (try? await ended.wait(timeout: 300_000_000 /* ~10 years: no deadline, and it must fit UInt64 in ns */, onTimeout: ProxyError("never", code: "timeout"))) ?? "closed"
    }

    private func die(_ reason: String) {
        lock.lock()
        if _closed != nil { lock.unlock(); return }
        _closed = reason
        let all = pending.values
        pending.removeAll()
        lock.unlock()
        let e = ProxyError(reason, code: "disconnected")
        ended.finish(.success(reason))
        connected.finish(.failure(e))
        all.forEach { $0.finish(.failure(e)) }
        // The session holds its delegate (this object) until invalidated: without this every
        // dropped connection would stay in memory.
        session.invalidateAndCancel()
    }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] r in
            guard let self else { return }
            switch r {
            case .failure(let e): self.die("transport: \(e.localizedDescription)")
            case .success(let m):
                switch m {
                case .string(let s): self.handle(s)
                case .data(let d): if let s = String(data: d, encoding: .utf8) { self.handle(s) }
                @unknown default: break
                }
                if self.closed == nil { self.receive(t) }
            }
        }
    }

    /// Keeps NATs and the proxy from dropping an idle socket (okhttp's `pingInterval` on Android).
    private func ping() {
        Task { [weak self] in
            while true {
                try? await Task.sleep(nanoseconds: 20_000_000_000)
                guard let self, self.closed == nil, let t = self.ws else { return }
                t.sendPing { e in if let e { self.die("ping: \(e.localizedDescription)") } }
            }
        }
    }

    public func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        die("closed: \(closeCode.rawValue) \(reason.flatMap { String(data: $0, encoding: .utf8) } ?? "")")
    }

    private func handle(_ text: String) {
        guard let o = try? JSON.parse(text), o.object != nil else { return }
        let type = o["type"]?.string
        let id = o["id"]?.string
        switch type {
        case "connected":
            guard let t = (o["instance"] ?? o["token"])?.string else { die("connected without a token"); return }
            lock.withLock {
                token = t
                node = o["node"]?.string
                caps = o["caps"]?.array?.compactMap(\.string)
            }
            connected.finish(.success(t))
        case "message":
            let payload: JSON?
            switch o["message"] {
            case .object?: payload = o["message"]
            case .string(let s)?: payload = (try? JSON.parse(s))?.objectValue
            default: payload = nil
            }
            guard let payload else { return }
            // The greeting is the transport's: answered here, it never reaches the app.
            if payload["t"]?.string == Self.helloTag {
                if let from = o["from"]?.string { onHello(from, payload) }
                return
            }
            let inc = Incoming(from: o["from"]?.string, fromPubkey: o["from_publickey"]?.string, payload: payload,
                               queued: o["queued"]?.bool ?? false, queuedAt: o["queued_at"]?.int)
            lock.lock(); let ls = Array(listeners.values); lock.unlock()
            ls.forEach { $0(inc) }
        case "disconnected":
            guard let t = o["token"]?.string else { return }
            // The token dies with the connection and is never reused: what it said is forgotten.
            lock.withLock { tokenPubkeys[t] = nil; helloSent.remove(t) }
            emit(.peerGone(token: t, channel: o["channel"]?.string))
            if let id, let p = take(id) { p.finish(.success(o)) }
        case "joined":
            if let c = o["channel"]?.string, let t = o["token"]?.string { emit(.joined(channel: c, token: t)) }
        case "left":
            if let c = o["channel"]?.string, let t = o["token"]?.string { emit(.left(channel: c, token: t)) }
        case "message_sent":
            let w: (token: String, at: Date, onGone: () -> Void)? = id.flatMap { i in lock.withLock { tokenWatch.removeValue(forKey: i) } }
            if let w, (o["failed"]?.array ?? []).contains(where: { $0.string == w.token }) {
                lock.withLock { tokenPubkeys[w.token] = nil; helloSent.remove(w.token) }
                emit(.peerGone(token: w.token, channel: nil))
                w.onGone()
            }
            if let id, let p = take(id) { p.finish(.success(o)) }
        case "error":
            if let id, let p = take(id) {
                p.finish(.failure(ProxyError(o["error"]?.string ?? "proxy error", code: o["code"]?.string ?? "proxy-error")))
            }
        default:
            if let id, let p = take(id) { p.finish(.success(o)) }
        }
    }

    private func take(_ id: String) -> OneShot<JSON>? {
        lock.lock(); defer { lock.unlock() }
        return pending.removeValue(forKey: id)
    }

    private func send(_ frame: JSON) throws {
        if let c = closed { throw ProxyError(c, code: "disconnected") }
        guard let t = ws else { throw ProxyError("not connected", code: "disconnected") }
        t.send(.string(frame.text)) { [weak self] e in if let e { self?.die("send: \(e.localizedDescription)") } }
    }

    /// A request with an `id` whose answer comes back with the same `id`.
    @discardableResult
    private func request(_ frame: [String: JSON], timeout: TimeInterval = 10) async throws -> JSON {
        let p = OneShot<JSON>()
        let id: String = lock.withLock {
            let id = "req_\(nextId)"; nextId += 1
            pending[id] = p
            return id
        }
        defer { _ = take(id) }
        var f = frame
        f["id"] = .string(id)
        try send(.object(f))
        return try await p.wait(timeout: timeout, onTimeout: ProxyError("the proxy did not answer \(frame["type"]?.string ?? "")", code: "timeout"))
    }

    /// Binds this connection to my key: messages written to it arrive here live, and what was
    /// queued while I was away gets delivered. The token is the challenge of this connection
    /// and goes inside what is signed; `aud` says who it is meant for.
    public func identify(_ keys: DeviceKeys) async throws {
        try await identifyAs(keys.publickey) { try keys.sign(Canonical.stringify($0)) }
    }

    /// `identifyAs` of the JS client: [publickey] signs, with [sign], that it is behind this
    /// connection. A profile signs through its own policy ([Profile.signData]).
    public func identifyAs(_ publickey: String, sign: (JSON) throws -> String) async throws {
        guard let t = token else { throw ProxyError("identify before connecting", code: "disconnected") }
        let data: JSON = ["op": "identify", "aud": .string(audience), "publickey": .string(publickey),
                          "token": .string(t), "ts": .int(nowMs())]
        var msg: [String: JSON] = ["type": "identify", "data": data, "signature": .string(try sign(data))]
        if let app { msg["app"] = .string(app) }
        try await request(msg)
        lock.withLock { myPublickey = publickey }
    }

    // MARK: the greeting

    public func helloTo(_ to: String) throws {
        guard let me = lock.withLock({ myPublickey }) else { throw ProxyError("helloTo: identify first", code: "not-identified") }
        if to == token { return }
        _ = lock.withLock { helloSent.insert(to) }
        try sendTo([to], ["t": .string(Self.helloTag), "publickey": .string(me)])
    }

    /// Whose this token is, if someone said it.
    public func pubkeyOfToken(_ t: String) -> String? { lock.withLock { tokenPubkeys[t] } }

    private func onHello(_ from: String, _ msg: JSON) {
        guard let pk = msg["publickey"]?.string, !pk.isEmpty else { return }
        let (ok, answer): (Bool, Bool) = lock.withLock {
            // A TOKEN DOES NOT CHANGE OWNER: a second greeting with another identity is ignored.
            if let before = tokenPubkeys[from], !Delegation.samePubkey(before, pk) { return (false, false) }
            tokenPubkeys[from] = pk
            return (true, !helloSent.contains(from) && myPublickey != nil)
        }
        guard ok else { return }
        if answer { try? helloTo(from) }
        emit(.peerIdentity(token: from, publickey: pk))
    }

    // MARK: by token and in channels

    /// A message to connection tokens, `{ to, message }` like `_proxySendOne` of the JS client.
    public func sendTo(_ tokens: [String], _ payload: JSON) throws {
        try send(["to": .array(tokens.map { .string($0) }), "message": .string(payload.text)])
    }

    /// `sendTo` for ONE token, but a dead token does not swallow the message. A token is a
    /// connection: when the other side restarts its app it stops existing, and the proxy answers
    /// `message_sent` with it in `failed` (it only answers when something fails). Then `onGone`
    /// runs — the caller sends the same thing by pubkey, to the queue — and `.peerGone` is
    /// emitted. The same as `sendToOrElse` in Kotlin and `sendSealedTo` in proxy-client 0.26.
    public func sendToOrElse(_ token: String, _ payload: JSON, onGone: @escaping () -> Void) throws {
        let id: String = lock.withLock {
            let now = Date()
            tokenWatch = tokenWatch.filter { now.timeIntervalSince($0.value.at) < 15 }
            let id = "msg_\(nextId)"; nextId += 1
            tokenWatch[id] = (token, now, onGone)
            return id
        }
        do {
            try send(["to": .array([.string(token)]), "message": .string(payload.text), "id": .string(id)])
        } catch {
            _ = lock.withLock { tokenWatch.removeValue(forKey: id) }
            throw error
        }
    }

    /// `buildSignedChannel`: the entry is signed by the TRANSPORT key of this app, not the identity.
    private func signedChannel(_ name: String, _ transport: DeviceKeys) throws -> JSON {
        let data: JSON = ["name": .string(name), "publickey": .string(transport.publickey)]
        return ["data": data, "signature": .string(try transport.sign(Canonical.stringify(data)))]
    }

    public func publish(_ channel: String, transport: DeviceKeys) async throws {
        try await request(["type": "publish", "channel": try signedChannel(channel, transport)])
    }

    public func unpublish(_ channel: String, transport: DeviceKeys) async throws {
        try await request(["type": "unpublish", "channel": try signedChannel(channel, transport)])
    }

    // MARK: encryption keys

    /// ANNOUNCE MY ENCRYPTION KEY, signed by the identity I identified as (`encpub.js`).
    public func announceEncPub(_ publickey: String, _ encPub: String, sign: (JSON) throws -> String) async throws {
        let data: JSON = ["v": 1, "op": "encpub", "aud": "dotrino:encpub", "publickey": .string(publickey),
                          "encpub": .string(encPub), "ts": .int(nowMs())]
        try await request(["type": "encpub", "data": data, "signature": .string(try sign(data))])
        lock.withLock { encPubs[publickey] = encPub }
    }

    /// THE ENCRYPTION KEY OF AN IDENTITY, verified against that identity — or it throws with
    /// `no-encpub`, `encpub-unverified` or `no-encpub-support`. Never nil, never «send anyway».
    public func encPubOf(_ publickey: String) async throws -> String {
        if let k = lock.withLock({ encPubs[publickey] }) { return k }
        if let c = caps, !c.contains("encpub") { throw ProxyError("this proxy does not serve encryption keys", code: "no-encpub-support") }
        let res = try await request(["type": "enc-lookup", "publickeys": [.string(publickey)]])
        guard let st = res["keys"]?.array?.first(where: { Delegation.samePubkey($0["data"]?["publickey"]?.string, publickey) }) else {
            throw ProxyError("no encryption key announced for that identity", code: "no-encpub")
        }
        guard let data = st["data"], let sig = st["signature"]?.string,
              data["v"]?.int == 1, data["op"]?.string == "encpub", data["aud"]?.string == "dotrino:encpub",
              let pk = data["publickey"]?.string, Delegation.samePubkey(pk, publickey),
              let enc = data["encpub"]?.string, (try? JSON.parse(enc))?["crv"]?.string == "P-256"
        else { throw ProxyError("encpub statement: malformed or for another identity", code: "encpub-unverified") }
        guard Crypto.verify(publickey: pk, data: data, signature: sig) else {
            throw ProxyError("encpub statement: bad signature — the key is not bound to that identity", code: "encpub-unverified")
        }
        lock.withLock { encPubs[publickey] = enc }
        return enc
    }

    /// A directed message to a key. The payload travels as a JSON string, like the JS client
    /// sends it. [quiet]: queued the same, but the proxy does not ring their phone.
    public func sendByPubkey(_ to: String, _ payload: JSON, quiet: Bool = false, toApp: String? = nil) throws {
        var f: [String: JSON] = ["to_publickey": [.string(to)], "message": .string(payload.text)]
        if quiet { f["quiet"] = true }
        // WHICH app of theirs it is for: the proxy rings and hands it only to that app.
        if let toApp { f["app"] = .string(toApp) }
        try send(.object(f))
    }

    // MARK: TURN

    /// `getTurnCredentials` of the JS client: temporary ICE servers for the direct road.
    /// Empty when the proxy has no TURN.
    /// This phone's APNs token under `publickey`, signed by whoever this connection identified
    /// as (a PROFILE signs through its own policy): the proxy rings the phone — an alert with no
    /// content, the text comes from the app's own strings — when something is queued for that
    /// identity. `topic` is the bundle id; `env` is `sandbox` (Xcode builds) or `production`
    /// (TestFlight, App Store). The same as `registerPushTokenAs` in Android, with APNs.
    public func registerApnsTokenAs(_ publickey: String, token: String, topic: String, env: String, sign: (JSON) throws -> String) async throws {
        let sub: JSON = ["kind": "apns", "token": .string(token), "topic": .string(topic), "env": .string(env)]
        var d: [String: JSON] = ["op": "push-subscribe", "publickey": .string(publickey), "subscription": .string(try Canonical.stringify(sub)), "ts": .int(nowMs())]
        if let app { d["app"] = .string(app) }   // inside what is signed: which app gets the ring
        let data = JSON.object(d)
        try await request(["type": "push-subscribe", "data": data, "signature": .string(try sign(data))])
    }

    public func turnCredentials(_ publickey: String, sign: (JSON) throws -> String) async throws -> [IceServer] {
        let data: JSON = ["op": "turn-credentials", "publickey": .string(publickey), "ts": .int(nowMs())]
        let res = try await request(["type": "turn-credentials", "data": data, "signature": .string(try sign(data))])
        guard res["enabled"]?.bool == true else { return [] }
        return (res["iceServers"]?.array ?? []).compactMap { o in
            let urls: [String]
            switch o["urls"] {
            case .string(let u)?: urls = [u]
            case .array(let a)?: urls = a.compactMap(\.string)
            default: return nil
            }
            return IceServer(urls: urls, username: o["username"]?.string, credential: o["credential"]?.string)
        }
    }

    // MARK: the short code people read out

    public struct PairingCode: Sendable {
        public let code: String; public let expiresAt: Int64
        public init(code: String, expiresAt: Int64) { self.code = code; self.expiresAt = expiresAt }
    }

    /// `requestPairingCode`: 6 characters pointing to THIS connection; they expire in minutes
    /// and burn when used.
    public func requestPairingCode(ttlMs: Int64? = nil) async throws -> PairingCode {
        var f: [String: JSON] = ["type": "pair-code"]
        if let ttlMs { f["ttlMs"] = .int(ttlMs) }
        let res = try await request(f)
        guard let code = res["code"]?.string else { throw ProxyError("pair-code: no code in the answer", code: "pair-code-failed") }
        return PairingCode(code: code, expiresAt: res["expiresAt"]?.int ?? 0)
    }

    /// `redeemPairingCode`: the token behind someone's code. It does NOT say whose it is — ask
    /// with `helloTo` and wait for `.peerIdentity`. Throws `pair-invalid` for a bad code.
    public func redeemPairingCode(_ code: String) async throws -> String {
        let res = try await request(["type": "pair-redeem", "code": .string(code)])
        guard res["ok"]?.bool == true, let instance = res["instance"]?.string else {
            throw ProxyError(res["error"]?.string ?? "that code is not valid", code: "pair-invalid")
        }
        return instance
    }
}
