import Foundation

/// THE REPUTATION REGISTRY (`rep.dotrino.com`) for native apps: `createVaultReputation` of
/// `@dotrino/reputation`. Same piece as `Reputation.kt`: publishing is a SIGNED attestation per
/// axis (with the signing package and its chain); reading is public and weighed by MY web of
/// trust (anti-sybil). `trustOf` comes from the peer book.
public final class Reputation: @unchecked Sendable {
    public static let defaultBase = "https://rep.dotrino.com"

    public struct ReputationError: Error, CustomStringConvertible {
        public let description: String
        public let code: String
    }
    public struct Indicator: Sendable, Equatable { public let score: Double?; public let confidence: Double; public let trustedCount: Int }
    public struct Aggregate: Sendable {
        public let score: Double?; public let confidence: Double; public let trustedCount: Int; public let rawCount: Int
        public let indicators: [String: Indicator]
    }

    private let profile: Profile
    private let peers: PeerBook
    private let base: String
    private let aud: String
    private let lock = NSLock()
    private var cache: [String: (Date, Result<[JSON], Error>)] = [:]

    public init(profile: Profile, peers: PeerBook, baseUrl: String = Reputation.defaultBase) {
        self.profile = profile; self.peers = peers
        var b = baseUrl; while b.hasSuffix("/") { b.removeLast() }
        base = b
        let u = URL(string: b)!
        aud = "\(u.scheme!)://\(u.host!)" + (u.port.map { ":\($0)" } ?? "")
    }

    public static func pubkeyId(_ jwk: String) -> String {
        guard let j = try? JSON.parse(jwk) else { return jwk }
        return "\(j["crv"]?.string ?? ""):\(j["x"]?.string ?? ""):\(j["y"]?.string ?? "")"
    }

    private static func indicators(_ a: JSON) -> [String: Double] {
        if let m = a["indicators"]?.object {
            var out: [String: Double] = [:]
            for (k, v) in m { switch v { case .int(let n): out[k] = Double(n); case .double(let d): out[k] = d; default: break } }
            return out
        }
        switch a["rating"] { case .int(let n)?: return ["confianza": Double(n)]; case .double(let d)?: return ["confianza": d]; default: return [:] }
    }

    private func http(_ method: String, _ url: String, body: JSON? = nil) async throws -> JSON {
        var req = URLRequest(url: URL(string: url)!)
        req.httpMethod = method
        if let body { req.httpBody = Data(body.text.utf8); req.setValue("application/json", forHTTPHeaderField: "content-type") }
        let (data, res) = try await URLSession.shared.data(for: req)
        let o = try? JSON.parse(data)
        let code = (res as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            throw ReputationError(description: "reputation: " + (o?["error"]?.string ?? "HTTP \(code)"), code: "http-\(code)")
        }
        return o ?? [:]
    }

    /// `firmar`: data + aud + issuer (the PERSON), signed, with signer and chain alongside.
    private func signed(_ data: [String: JSON]) throws -> JSON {
        var full = data
        full["aud"] = .string(aud); full["issuer"] = .string(profile.profileId)
        let pkg = try profile.signPackage(.object(full))
        var out: [String: JSON] = ["data": .object(full), "signature": pkg["signature"]!, "signer": pkg["publickey"]!]
        if let chain = pkg["chain"]?.array, !chain.isEmpty { out["chain"] = .array(chain) }
        return .object(out)
    }

    private static let channel = try! NSRegularExpression(pattern: "^[a-z][a-z0-9_]{0,23}$")
    private static func validChannel(_ c: String) -> Bool { channel.firstMatch(in: c, range: NSRange(c.startIndex..., in: c)) != nil }

    /// `rate`: my rating of `subject`, one signed attestation per axis (integers 0..5). The
    /// `confianza` axis also goes to my local web of trust (the peer book).
    public func rate(_ subject: String, _ indicators: [String: Int], notes: String? = nil) async throws {
        if let c = indicators["confianza"] { try peers.setRating(subject, Double(c), notes: notes ?? "") }
        for (ch, value) in indicators.sorted(by: { $0.key < $1.key }) {
            guard Self.validChannel(ch) else { throw ReputationError(description: "invalid channel \(ch)", code: "bad-channel") }
            guard (0...5).contains(value) else { throw ReputationError(description: "value must be an integer 0..5", code: "bad-value") }
            var d: [String: JSON] = ["op": "rate", "subject": .string(subject), "channel": .string(ch), "value": .int(Int64(value)), "ts": .int(nowMs())]
            if let notes { d["notes"] = .string(String(notes.prefix(280))) }
            let body = try signed(d)
            _ = lock.withLock { cache.removeValue(forKey: subject) }
            _ = try await http("PUT", "\(base)/ratings", body: body)
        }
    }

    /// `removeChannel`: withdraw MY attestation of one axis.
    public func removeChannel(_ subject: String, _ ch: String) async throws {
        guard Self.validChannel(ch) else { throw ReputationError(description: "invalid channel \(ch)", code: "bad-channel") }
        let body = try signed(["op": "unrate", "subject": .string(subject), "channel": .string(ch), "ts": .int(nowMs())])
        _ = lock.withLock { cache.removeValue(forKey: subject) }
        _ = try await http("DELETE", "\(base)/ratings", body: body)
    }

    /// Every attestation about `subject` (raw). Public read, cached 30 s (5 s after an error).
    public func ratings(_ subject: String) async throws -> [JSON] {
        if let (ts, r) = lock.withLock({ cache[subject] }) {
            let ttl: TimeInterval = (try? r.get()) != nil ? 30 : 5
            if Date().timeIntervalSince(ts) < ttl { return try r.get() }
        }
        var comps = URLComponents(string: "\(base)/ratings")!
        comps.queryItems = [URLQueryItem(name: "subject", value: subject)]
        let r: Result<[JSON], Error>
        do { r = .success((try await http("GET", comps.url!.absoluteString)["attestations"]?.array ?? []).filter { $0.object != nil }) }
        catch { r = .failure(error) }
        lock.withLock { cache[subject] = (Date(), r) }
        return try r.get()
    }

    /// `trustOf`: my direct trust in `pk`, 0..1, from my own rating; nil = no opinion.
    public func trustOf(_ pk: String) throws -> Double? {
        guard let r = try peers.ratingsFor(pk).mine?["rating"] else { return nil }
        let v: Double
        switch r { case .int(let n): v = Double(n); case .double(let d): v = d; default: return nil }
        return min(1, max(0, v / 5))
    }

    /// `myIndicatorsFor`: what I rated `subject`, merged across axes.
    public func myIndicatorsFor(_ subject: String) async throws -> [String: Double] {
        var out: [String: Double] = [:]
        for a in try await ratings(subject) where Delegation.samePubkey(a["issuer"]?.string, profile.profileId) {
            out.merge(Self.indicators(a)) { _, b in b }
        }
        return out
    }

    /// `aggregateTrust` / `reputationOf`: weighed by the credibility I give each issuer.
    public func aggregateTrust(_ subject: String, maxDepth: Int = 2, decay: Double = 0.5, minCredibility: Double = 0.05) async -> Aggregate {
        var credCache: [String: Double] = [:]
        func credibility(_ pk: String, _ depth: Int, _ visited: Set<String>) async -> Double {
            let id = Self.pubkeyId(pk)
            if let c = credCache[id] { return c }
            if Delegation.samePubkey(pk, profile.profileId) { credCache[id] = 1; return 1 }
            if let t = (try? trustOf(pk)) ?? nil, t > 0 { credCache[id] = t; return t }
            if depth >= maxDepth { credCache[id] = 0; return 0 }
            var best = 0.0
            for a in (try? await ratings(pk)) ?? [] {
                guard let issuer = a["issuer"]?.string else { continue }
                let iid = Self.pubkeyId(issuer)
                if iid == id || visited.contains(iid) { continue }
                let c = await credibility(issuer, depth + 1, visited.union([iid]))
                if c <= 0 { continue }
                best = max(best, c * min(1, max(0, (Self.indicators(a)["confianza"] ?? 0) / 5)))
            }
            let v = decay * best
            credCache[id] = v
            return v
        }
        let atts = (try? await ratings(subject)) ?? []
        var acc: [String: (sum: Double, w: Double, n: Int)] = [:]
        for a in atts {
            guard let issuer = a["issuer"]?.string, !Delegation.samePubkey(issuer, subject) else { continue }
            let ind = Self.indicators(a)
            if ind.isEmpty { continue }
            let cred = await credibility(issuer, 1, [Self.pubkeyId(subject)])
            if cred >= minCredibility { for (k, v) in ind { var x = acc[k] ?? (0, 0, 0); x.sum += cred * v; x.w += cred; x.n += 1; acc[k] = x } }
        }
        let round3 = { (d: Double) in (d * 1000).rounded() / 1000 }
        let indicators = acc.mapValues { a in Indicator(score: a.w > 0 ? min(1, max(0, (a.sum / a.w) / 5)) : nil, confidence: round3(1 - exp(-a.w)), trustedCount: a.n) }
        let c = indicators["confianza"] ?? Indicator(score: nil, confidence: 0, trustedCount: 0)
        return Aggregate(score: c.score, confidence: c.confidence, trustedCount: c.trustedCount, rawCount: atts.count, indicators: indicators)
    }
}
