import CryptoKit
import Foundation

/// The CLIENT side of `@dotrino/remote-agent` (`src/client.js`, `e2e.js`, `protocol.js`): talk
/// to an agent — a terminal, an AI agent — running on another device of the SAME account. Same
/// piece as `RemoteAgent.kt`.
///
/// Both ends are devices the record (acta) names; neither has the master. The handshake is
/// signed by this phone's profile key with its vault paper; the agent answers with an ack signed
/// with ITS key and ITS paper, judged here against the record: whoever issued that paper must
/// be a sealer of this profile, and the paper may not name a record newer than mine. Without a
/// record there is nothing to judge with, and it is refused — never «ok».
///
/// What travels after that is a domain payload each app defines, inside a session channel
/// (ECDH P-256 → HKDF-SHA256 → AES-256-GCM): the proxy only sees `{ type, sid, env }`.
public enum RemoteAgent {
    public static let hs = "ra.hs"
    public static let ack = "ra.hs.ack"
    public static let dataType = "ra.data"
    public static let ping = "ra.ping"
    public static let pong = "ra.pong"
    public static let errorType = "ra.error"
    static let info = "dotrino-remote-agent-e2e"

    public struct RemoteAgentError: Error, CustomStringConvertible {
        public let description: String
        public let code: String
        init(_ d: String, code: String) { description = d; self.code = code }
    }

    // MARK: the session channel (e2e.js)

    /// A fresh ECDH key for one handshake: the private half, and the public one as raw base64 (what the JS exports).
    static func makeEphemeral() -> (P256.KeyAgreement.PrivateKey, String) {
        let k = P256.KeyAgreement.PrivateKey()
        return (k, Crypto.b64(k.publicKey.x963Representation))
    }

    /// `deriveKey`: ECDH with the other end's ephemeral key, then HKDF-SHA256 (salt = the session id) to 32 bytes.
    static func deriveKey(_ mine: P256.KeyAgreement.PrivateKey, _ otherRawB64: String, _ salt: String) throws -> Data {
        let raw = try Crypto.fromB64(otherRawB64)
        guard raw.count == 65, raw.first == 4 else { throw RemoteAgentError("bad ephemeral key", code: "bad-ack") }
        let shared = try mine.sharedSecretFromKeyAgreement(with: P256.KeyAgreement.PublicKey(x963Representation: raw))
        let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(salt.utf8), sharedInfo: Data(info.utf8), outputByteCount: 32)
        return key.withUnsafeBytes { Data($0) }
    }

    static func seal(_ key: Data, _ payload: JSON) throws -> JSON {
        let (iv, ct) = try Crypto.aesGcmSeal(key: key, plain: Data(payload.text.utf8))
        return ["iv": .string(Crypto.b64(iv)), "ct": .string(Crypto.b64(ct))]
    }

    static func open(_ key: Data, _ env: JSON) throws -> JSON {
        guard let iv = env["iv"]?.string, let ct = env["ct"]?.string else { throw RemoteAgentError("bad envelope", code: "bad-envelope") }
        return try JSON.parse(Crypto.aesGcmOpen(key: key, iv: Crypto.fromB64(iv), ct: Crypto.fromB64(ct)))
    }

    // MARK: judging the agent (verifyChain)

    /// Is this ack from a device of MY profile? The port of `verifyChain` as the client calls it:
    /// the device signed the data, the paper is for that device, a SEALER of my record issued it,
    /// and it does not name a record newer than mine. Returns the reason it is not, or nil.
    static func judge(_ data: JSON, _ signature: String, _ cert: JSON?, _ acta: JSON?) -> String? {
        guard let device = data["publickey"]?.string else { return "no-device-pubkey" }
        if !Crypto.verify(publickey: device, data: data, signature: signature) { return "bad-action-signature" }
        guard let cert, cert["sub"]?.string == device else { return "cert-device-mismatch" }
        guard let iss = cert["iss"]?.string, let sig = cert["sig"]?.string else { return "shape" }
        // A paper of the old model carried a date and no record number: retired here (as in Kotlin).
        guard let seq = cert["seq"]?.int else { return "legacy-cert-retirado" }
        if !Crypto.verify(publickey: iss, data: Delegation.body(cert), signature: sig) { return "bad-signature" }
        guard let actaSeq = acta?["seq"]?.int else { return "no-acta" }
        if seq > actaSeq { return "acta-vieja" }
        if !Acta.sealersOf(acta).contains(iss) { return "untrusted-issuer" }
        return nil
    }

    // MARK: who is there (probeAgents)

    /// Ask each key what it is, WITHOUT opening a session: the ones running an agent answer with
    /// their `kind` (`terminal-agent`, `ia-agent`). Ephemeral: who is off is neither queued nor
    /// rung. Returns key → kind for those that answered within [timeout].
    public static func probe(_ conn: ProxyConnection, _ pubkeys: [String], timeout: TimeInterval = 3) async throws -> [String: String?] {
        var byNonce: [String: String] = [:]
        for p in pubkeys { byNonce[Crypto.b64(Crypto.randomBytes(9))] = p }
        let lock = NSLock()
        var found: [String: String?] = [:]
        let off = conn.onMessage { m in
            guard m.payload["type"]?.string == pong, let n = m.payload["n"]?.string, let pub = byNonce[n] else { return }
            lock.withLock { _ = found.updateValue(m.payload["kind"]?.string, forKey: pub) }
        }
        defer { off() }
        for (n, pub) in byNonce { try conn.sendByPubkey(pub, ["type": .string(ping), "n": .string(n)], ephemeral: true) }
        let until = Date().addingTimeInterval(timeout)
        while lock.withLock({ found.count }) < pubkeys.count && Date() < until { try await Task.sleep(nanoseconds: 60_000_000) }
        return lock.withLock { found }
    }

    /// The other devices of the profile, from its record: who could be running an agent (key, label).
    public static func candidates(_ profile: Profile) -> [(String, String?)] {
        (profile.record?["members"]?.array ?? []).compactMap { m in
            guard let pub = m["pub"]?.string, pub != profile.publickey else { return nil }
            return (pub, m["label"]?.string)
        }
    }

    // MARK: a session

    /// An agent error as ONE session sees it. One that names another session (`sid`) is not for
    /// it: the tabs of a phone share one connection, and each one used to take every error as its
    /// own. The agent's `code` travels (`unknown-session`: it restarted and no longer knows this
    /// session, remote-agent ≥ 0.14.0); an agent that sends none gives `agent-error`, as before.
    static func sessionError(_ p: JSON, sid: String) -> RemoteAgentError? {
        if let other = p["sid"]?.string, other != sid { return nil }
        return RemoteAgentError(p["error"]?.string ?? "agent error", code: p["code"]?.string ?? "agent-error")
    }

    /// Open a session with the agent at [agentPubkey] over [conn] (connected and identified as
    /// the profile). Throws [RemoteAgentError]: `no-vault`, `no-reply`, `refused` (the agent said
    /// no), `not-mine` (the ack does not hold against the record, with the reason), `bad-ack`.
    ///
    /// [catchUp] puts this phone's record up to date ([ActaSync.catchUp]): called ONCE, when the
    /// agent's paper names a newer record than mine (`acta-vieja`), and the ack is judged again
    /// against what it returns. Without it, `acta-vieja` stops the session.
    public static func open(_ profile: Profile, _ conn: ProxyConnection, _ agentPubkey: String, timeout: TimeInterval = 20,
                            catchUp: (() async throws -> Profile)? = nil) async throws -> Session {
        guard let cert = profile.vault?.cert else { throw RemoteAgentError("this profile is not linked to a vault", code: "no-vault") }
        let (ephPriv, ephPub) = makeEphemeral()
        let data: JSON = ["op": .string(hs), "eph": .string(ephPub), "publickey": .string(profile.publickey), "ts": .int(nowMs())]
        let signature = try profile.signData(data)

        // The ack is matched by MY ephemeral key, so two sessions opening at once do not take each other's.
        let acked = OneShot<JSON>()
        // The agent's TOKEN comes with its ack: from then on it is spoken to BY TOKEN, which is the
        // only thing that goes up to WebRTC (the JS client does the same).
        let ackFrom = Locked<String?>(nil)
        let off = conn.onMessage { m in
            switch m.payload["type"]?.string {
            case ack: if m.payload["ack"]?["ceph"]?.string == ephPub { ackFrom.value = m.from; acked.finish(.success(m.payload)) }
            case errorType: acked.finish(.failure(RemoteAgentError(m.payload["error"]?.string ?? "the agent refused", code: "refused")))
            default: break
            }
        }
        let res: JSON
        do {
            defer { off() }
            try conn.sendByPubkey(agentPubkey, ["type": .string(hs), "data": data, "signature": .string(signature), "cert": cert])
            res = try await acked.wait(timeout: timeout, onTimeout: RemoteAgentError("the agent did not reply (is it running there?)", code: "no-reply"))
        }

        guard let a = res["ack"]?.objectValue, let sid = res["sid"]?.string else { throw RemoteAgentError("bad ack", code: "bad-ack") }
        let ackSig = res["signature"]?.string ?? ""
        let ackCert = res["cert"]?.objectValue
        var why = judge(a, ackSig, ackCert, profile.record)
        // The agent's paper is from a record newer than mine: the vault changed it and this phone
        // has not heard. Catch up and judge again — once; what still fails is said as it is.
        if why == "acta-vieja", let catchUp { why = judge(a, ackSig, ackCert, try await catchUp().record) }
        if let why { throw RemoteAgentError("that agent is not certified by your vault: \(why)", code: "not-mine") }
        if !Delegation.samePubkey(a["machine"]?.string, agentPubkey) { throw RemoteAgentError("the ack came from another agent", code: "bad-ack") }
        if a["ceph"]?.string != ephPub || a["sid"]?.string != sid { throw RemoteAgentError("the ack is not for this handshake", code: "bad-ack") }
        guard let seph = a["seph"]?.string else { throw RemoteAgentError("bad ack", code: "bad-ack") }
        return Session(conn, agentPubkey, sid, try deriveKey(ephPriv, seph, sid), agentToken: ackFrom.value)
    }

    /// An open session: domain payloads both ways, sealed with the session key.
    public final class Session: @unchecked Sendable {
        public let agentPubkey: String
        public let sid: String
        private let conn: ProxyConnection
        private let key: Data
        private let lock = NSLock()
        private var listeners: [UUID: (JSON) -> Void] = [:]
        private var errors: [UUID: (RemoteAgentError) -> Void] = [:]
        private var off: (() -> Void)?
        private var offEvent: (() -> Void)?
        /// The agent's token, from its ack; nil = not known (or dead): by pubkey, to the queue.
        public private(set) var agentToken: String?

        init(_ conn: ProxyConnection, _ agentPubkey: String, _ sid: String, _ key: Data, agentToken: String? = nil) {
            self.conn = conn; self.agentPubkey = agentPubkey; self.sid = sid; self.key = key; self.agentToken = agentToken
            // A token dies with its connection (the agent restarted): back to the pubkey until the next ack.
            offEvent = conn.onEvent { [weak self] e in
                if case .peerGone(let t, _) = e, let self, self.lock.withLock({ self.agentToken == t }) { self.lock.withLock { self.agentToken = nil } }
            }
            off = conn.onMessage { [weak self] m in
                guard let self else { return }
                switch m.payload["type"]?.string {
                case RemoteAgent.dataType:
                    guard m.payload["sid"]?.string == sid, let env = m.payload["env"] else { return }
                    // What does not open with this session's key is not for it: dropped, like the JS does.
                    guard let msg = try? RemoteAgent.open(key, env) else { return }
                    for l in self.lock.withLock({ Array(self.listeners.values) }) { l(msg) }
                // The agent no longer knows this session (it restarted, or it expired), or another error.
                case RemoteAgent.errorType:
                    guard let e = RemoteAgent.sessionError(m.payload, sid: sid) else { return }
                    for l in self.lock.withLock({ Array(self.errors.values) }) { l(e) }
                default: break
                }
            }
        }

        @discardableResult public func onMessage(_ l: @escaping (JSON) -> Void) -> () -> Void {
            let id = UUID(); lock.withLock { listeners[id] = l }
            return { [weak self] in self?.lock.withLock { _ = self?.listeners.removeValue(forKey: id) } }
        }

        @discardableResult public func onError(_ l: @escaping (RemoteAgentError) -> Void) -> () -> Void {
            let id = UUID(); lock.withLock { errors[id] = l }
            return { [weak self] in self?.lock.withLock { _ = self?.errors.removeValue(forKey: id) } }
        }

        public func send(_ payload: JSON) throws {
            let msg: JSON = ["type": .string(RemoteAgent.dataType), "sid": .string(sid), "env": try RemoteAgent.seal(key, payload)]
            // By token as soon as it is known: it goes up to the direct road, and a dead token falls
            // back to the pubkey by itself. By pubkey only while there is no token.
            if let t = lock.withLock({ agentToken }) { try conn.sendToOrUpgrade(t, msg, peerPubkey: agentPubkey) }
            else { try conn.sendByPubkey(agentPubkey, msg) }
        }

        /// Stop listening. The session on the agent expires by itself; what the app opened there is the app's to close.
        public func close() {
            off?(); off = nil; offEvent?(); offEvent = nil
            lock.withLock { listeners.removeAll(); errors.removeAll() }
        }
    }
}

/// A value behind a lock (what a closure captures and another thread sets).
final class Locked<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var v: T
    init(_ v: T) { self.v = v }
    var value: T { get { lock.withLock { v } } set { lock.withLock { v = newValue } } }
}
