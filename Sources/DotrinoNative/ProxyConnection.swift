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
    }

    private let url: URL
    private let lock = NSLock()
    private var session: URLSession!
    private var ws: URLSessionWebSocketTask?
    private let connected = OneShot<String>()
    private var pending: [String: OneShot<JSON>] = [:]
    private var nextId = 1
    private var listeners: [UUID: (Incoming) -> Void] = [:]
    private var _closed: String?
    public private(set) var token: String?

    public var closed: String? { lock.lock(); defer { lock.unlock() }; return _closed }

    /// The audience that goes inside `identify`: the proxy URL without trailing slashes.
    public let audience: String

    public init(_ urlString: String) throws {
        guard let u = URL(string: urlString) else { throw ProxyError("invalid proxy url", code: "bad-url") }
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

    private func die(_ reason: String) {
        lock.lock()
        if _closed != nil { lock.unlock(); return }
        _closed = reason
        let all = pending.values
        pending.removeAll()
        lock.unlock()
        let e = ProxyError(reason, code: "disconnected")
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
            lock.lock(); token = t; lock.unlock()
            connected.finish(.success(t))
        case "message":
            let payload: JSON?
            switch o["message"] {
            case .object?: payload = o["message"]
            case .string(let s)?: payload = (try? JSON.parse(s)).flatMap { $0.object != nil ? $0 : nil }
            default: payload = nil
            }
            guard let payload else { return }
            let inc = Incoming(from: o["from"]?.string, fromPubkey: o["from_publickey"]?.string, payload: payload)
            lock.lock(); let ls = Array(listeners.values); lock.unlock()
            ls.forEach { $0(inc) }
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
        guard let t = token else { throw ProxyError("identify before connecting", code: "disconnected") }
        let data: JSON = ["op": "identify", "aud": .string(audience), "publickey": .string(keys.publickey),
                          "token": .string(t), "ts": .int(nowMs())]
        try await request(["type": "identify", "data": data, "signature": .string(keys.sign(Canonical.stringify(data)))])
    }

    /// A directed message to a key. The payload travels as a JSON string, like the JS client sends it.
    public func sendByPubkey(_ to: String, _ payload: JSON) throws {
        try send(["to_publickey": [.string(to)], "message": .string(payload.text)])
    }
}
