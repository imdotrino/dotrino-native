import Foundation

/// THE PEER BOOK of the profile (`vault/peerStore.js` + the peer handlers of `vault/core.js`):
/// contacts, their profile cards, my ratings and the endorsements others sent. The SAME record
/// the identity keeps (`peers:peers.<pid>.v1`), so every app of the phone sees the same address
/// book. Same piece as `PeerBook.kt`. Every change reads the record again, applies itself and
/// writes it back, one at a time: another app may have written in between.
public final class PeerBook: @unchecked Sendable {
    public protocol Storage: AnyObject {
        func load() throws -> String?
        func save(_ text: String) throws
    }

    public final class MemoryStorage: Storage {
        public var text: String?
        public init(_ text: String? = nil) { self.text = text }
        public func load() throws -> String? { text }
        public func save(_ text: String) throws { self.text = text }
    }

    /// The phone's identity store (shared by the team's apps through the App Group).
    public final class PhoneStorage: Storage {
        private let key: String
        public init(profile: Profile) { key = "peers:" + PeerBook.key(profile.pid) }
        public func load() throws -> String? { try IdentityStore.shared.fresh()[key] }
        public func save(_ text: String) throws { try IdentityStore.shared.set(key, text) }
    }

    /// Why a card was (not) adopted: `primera-vez`, `seq-mayor`, `igual` | `firma-invalida`, `otro-perfil`, `seq-menor`, `master-cambiado`.
    public struct CardAdoption: Equatable, Sendable {
        public let adopted: Bool
        public let reason: String
        public let devices: Int
    }

    /// `peersKey()` of the identity: namespaced by profile.
    public static func key(_ pid: String?) -> String { pid.map { "peers.\($0).v1" } ?? "peers.v1" }
    private static let contactFields: Set<String> = ["nickname", "encryptionPubkey", "lastToken", "contactNotes"]
    private static let maxEndorsements = 50

    private let storage: Storage
    private let profile: Profile
    private let lock = NSLock()

    public init(storage: Storage, profile: Profile) { self.storage = storage; self.profile = profile }

    private static func isPub(_ s: String?) -> Bool {
        guard let s, let j = try? JSON.parse(s) else { return false }
        return j["kty"]?.string == "EC" && j["x"] != nil && j["y"] != nil
    }

    /// `verifyProfileCard`: well formed and signed by whom it says (`sealedBy`).
    public static func verifyCard(_ card: JSON?) -> Bool {
        guard let card, var o = card.object, card["v"]?.int == 1,
              isPub(card["profileId"]?.string), let sealedBy = card["sealedBy"]?.string, isPub(sealedBy),
              card["seq"]?.int != nil, card["keys"]?.array != nil, let sig = card["sig"]?.string else { return false }
        o["sig"] = nil
        return Crypto.verify(publickey: sealedBy, data: .object(o), signature: sig)
    }

    private func read() throws -> [String: JSON] {
        guard let text = try storage.load(), let o = try JSON.parse(text).object else { return [:] }
        return o.filter { $0.value.object != nil }
    }

    private func write(_ peers: [String: JSON]) throws { try storage.save(JSON.object(peers).text) }

    private func change<T>(_ f: (inout [String: JSON]) throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        var peers = try read()
        let r = try f(&peers)
        try write(peers)
        return r
    }

    /// `upsertPeer`: merge `patch` into the record and stamp `lastSeen`.
    @discardableResult
    private func upsert(_ peers: inout [String: JSON], _ publickey: String, _ patch: [String: JSON]) -> JSON {
        let now = nowMs()
        var rec = peers[publickey]?.object ?? ["publickey": .string(publickey), "firstSeen": .int(now)]
        for (k, v) in patch { rec[k] = v }
        rec["publickey"] = .string(publickey); rec["lastSeen"] = .int(now)
        peers[publickey] = .object(rec)
        return .object(rec)
    }

    /// `listPeers`: every record, most recently seen first.
    public func list() throws -> [JSON] {
        try lock.withLock { try read() }.values.sorted { ($0["lastSeen"]?.int ?? 0) > ($1["lastSeen"]?.int ?? 0) }
    }

    public func contacts() throws -> [JSON] { try list().filter { $0["isContact"]?.bool == true } }

    public func get(_ publickey: String) throws -> JSON? { try lock.withLock { try read() }[publickey] }

    @discardableResult
    public func addContact(_ publickey: String, nickname: String? = nil, encryptionPubkey: String? = nil, lastToken: String? = nil, notes: String? = nil) throws -> JSON {
        precondition(!publickey.isEmpty, "publickey required")
        var patch: [String: JSON] = ["isContact": true]
        if let nickname { patch["nickname"] = .string(String(nickname.prefix(40))) }
        if let encryptionPubkey, !encryptionPubkey.isEmpty { patch["encryptionPubkey"] = .string(encryptionPubkey) }
        if let lastToken, !lastToken.isEmpty { patch["lastToken"] = .string(lastToken) }
        if let notes { patch["contactNotes"] = .string(String(notes.prefix(300))) }
        return try change { upsert(&$0, publickey, patch) }
    }

    /// `updateContact`: only nickname, encryptionPubkey, lastToken and contactNotes.
    @discardableResult
    public func updateContact(_ publickey: String, _ patch: [String: String?]) throws -> JSON? {
        var allowed: [String: JSON] = [:]
        for (k, v) in patch where Self.contactFields.contains(k) { allowed[k] = v.map { .string($0) } ?? .null }
        guard !allowed.isEmpty else { return nil }
        return try change { upsert(&$0, publickey, allowed) }
    }

    /// `removeContact`: the record stays (ratings, card); it just stops being a contact.
    @discardableResult
    /// `removeContact`: the record stays; it just stops being a contact. It is a CHANGE with a
    /// date (`changedAt`), so it does not come back from another device (`PeerBookBackup`).
    public func removeContact(_ publickey: String) throws -> JSON? {
        try change { peers in
            guard var rec = peers[publickey]?.object else { return nil }
            rec["isContact"] = nil
            rec["changedAt"] = .int(nowMs())
            peers[publickey] = .object(rec)
            return .object(rec)
        }
    }

    /// Every record, by key (for `PeerBookBackup`).
    public func all() throws -> [String: JSON] { try lock.withLock { try read() } }

    /// Records from another device of the profile (the vault), MERGED like the identity does:
    /// the record changed LATER wins, also for being a contact; first/last seen and the change
    /// date keep the widest; a missing card is taken; endorsements are verified one by one. Same
    /// as `PeerBook.mergeFrom` in Kotlin. Returns how many changed here.
    public func mergeFrom(_ records: [JSON]) throws -> Int {
        var n = 0
        var endorsements: [(String, [JSON])] = []
        try change { peers in
            for b in records {
                guard let bo = b.object, let pk = bo["publickey"]?.string else { continue }
                let incoming = bo["endorsements"]?.array ?? []
                if !incoming.isEmpty { endorsements.append((pk, incoming)) }
                var base = bo; base["endorsements"] = nil
                guard let a = peers[pk]?.object else { peers[pk] = .object(base); n += 1; continue }
                var m = a
                let bNewer = PeerBookBackup.stampOf(b) > PeerBookBackup.stampOf(.object(a))
                if bNewer { for k in ["nickname", "notes", "contactNotes", "encryptionPubkey", "rating"] { if let v = bo[k] { m[k] = v } } }
                let newer = bNewer ? bo : a
                if newer["isContact"]?.bool == true { m["isContact"] = true } else { m["isContact"] = nil }
                if let f = [a["firstSeen"]?.int, bo["firstSeen"]?.int].compactMap({ $0 }).min() { m["firstSeen"] = .int(f) }
                m["lastSeen"] = .int(max(a["lastSeen"]?.int ?? 0, bo["lastSeen"]?.int ?? 0))
                let changedAt = max(a["changedAt"]?.int ?? 0, bo["changedAt"]?.int ?? 0)
                if changedAt > 0 { m["changedAt"] = .int(changedAt) }
                if m["card"] == nil, let c = bo["card"], c.object != nil { m["card"] = c }
                if let mine = bo["myRating"], mine.object != nil, (a["myRating"]?["issuedAt"]?.int ?? -1) < (mine["issuedAt"]?.int ?? 0) { m["myRating"] = mine }
                if JSON.object(m) != JSON.object(a) { peers[pk] = .object(m); n += 1 }
            }
        }
        // Others' ratings: each one verified against its signer, never taken on trust.
        for (pk, list) in endorsements { _ = try mergeEndorsements(pk, list) }
        return n
    }

    /// `adoptPeerCard`: first time accepted; afterwards only if it does not go back and the
    /// same master signed it. A changed master is SAID, never accepted quietly.
    @discardableResult
    public func adoptPeerCard(_ card: JSON) throws -> CardAdoption {
        try change { peers in
            guard let profileId = card["profileId"]?.string else { return CardAdoption(adopted: false, reason: "firma-invalida", devices: 0) }
            let current = peers[profileId]?["card"]?.objectValue
            let devices = current?["keys"]?.array?.count ?? 0
            let seq = card["seq"]?.int ?? 0, curSeq = current?["seq"]?.int ?? 0
            let reason: String
            if !Self.verifyCard(card) { reason = "firma-invalida" }
            else if let current, current["profileId"]?.string != profileId { reason = "otro-perfil" }
            else if current == nil { reason = "primera-vez" }
            else if seq < curSeq { reason = "seq-menor" }
            else if card["sealedBy"]?.string != current?["sealedBy"]?.string { reason = "master-cambiado" }
            else if seq > curSeq { reason = "seq-mayor" }
            else { reason = "igual" }
            if ["firma-invalida", "otro-perfil", "seq-menor", "master-cambiado"].contains(reason) {
                return CardAdoption(adopted: false, reason: reason, devices: devices)
            }
            upsert(&peers, profileId, ["card": card, "profileId": .string(profileId)])
            return CardAdoption(adopted: true, reason: reason, devices: card["keys"]?.array?.count ?? 0)
        }
    }

    /// The card of a contact: under its record, under its `profileId`, or the card that lists
    /// this key among its devices.
    public func cardOf(_ publickey: String) throws -> JSON? {
        let peers = try lock.withLock { try read() }
        let rec = peers[publickey]
        if let c = rec?["card"], c.object != nil { return c }
        if let pid = rec?["profileId"]?.string, let c = peers[pid]?["card"], c.object != nil { return c }
        return peers.values.compactMap { $0["card"] }.first { c in
            c.object != nil && (c["keys"]?.array ?? []).contains { Delegation.samePubkey($0["pub"]?.string, publickey) }
        }
    }

    /// Every encryption key I know for a person: the saved one plus all of their card.
    public func encPubsOf(_ publickey: String) throws -> [String] {
        var out: [String] = []
        if let k = try get(publickey)?["encryptionPubkey"]?.string { out.append(k) }
        for k in try cardOf(publickey)?["keys"]?.array ?? [] { if let e = k["encPub"]?.string { out.append(e) } }
        var seen = Set<String>()
        return out.filter { seen.insert($0).inserted }
    }

    // MARK: ratings (web of trust)

    private static func number(_ d: Double) -> JSON { d.rounded() == d ? .int(Int64(d)) : .double(d) }

    /// `setRating`: my rating of someone, 0–5, SIGNED by the profile so others can verify it.
    @discardableResult
    public func setRating(_ publickey: String, _ rating: Double, notes: String = "") throws -> JSON {
        let r = min(5, max(0, rating))
        let safeNotes = String(notes.prefix(500))
        let envelope: [String: JSON] = ["subject": .string(publickey), "rating": Self.number(r), "notes": .string(safeNotes),
                                        "ratedBy": .string(profile.publickey), "issuedAt": .int(nowMs())]
        var myRating = envelope
        myRating["signature"] = .string(try profile.signData(.object(envelope)))
        return try change { upsert(&$0, publickey, ["myRating": .object(myRating), "rating": Self.number(r), "notes": .string(safeNotes)]) }
    }

    /// `getRatingsForSubject`: what I can tell others about `subject`.
    public func ratingsFor(_ subject: String) throws -> (mine: JSON?, endorsements: [JSON]) {
        let r = try get(subject)
        let mine = r?["myRating"]?.objectValue
        return (mine, (r?["endorsements"]?.array ?? []).filter { $0.object != nil })
    }

    /// `mergeEndorsements`: ratings of `subject` that others signed. Each one is VERIFIED; the
    /// newest per signer stays; mine and malformed ones are dropped.
    @discardableResult
    public func mergeEndorsements(_ subject: String, _ endorsements: [JSON]) throws -> Int {
        try change { peers in
            var existing = peers[subject]?.object ?? ["publickey": .string(subject), "firstSeen": .int(nowMs())]
            var byRater: [String: JSON] = [:]
            var order: [String] = []
            for e in existing["endorsements"]?.array ?? [] { if let rb = e["ratedBy"]?.string { if byRater[rb] == nil { order.append(rb) }; byRater[rb] = e } }
            var merged = 0
            for env in endorsements {
                guard env["subject"]?.string == subject, let ratedBy = env["ratedBy"]?.string, !ratedBy.isEmpty,
                      ratedBy != profile.publickey, let signature = env["signature"]?.string, let rating = env["rating"] else { continue }
                let value: Double
                switch rating { case .int(let n): value = Double(n); case .double(let d): value = d; default: continue }
                guard value >= 0, value <= 5 else { continue }
                let issuedAt = env["issuedAt"]?.int ?? 0
                if let prev = byRater[ratedBy], (prev["issuedAt"]?.int ?? 0) >= issuedAt { continue }
                let body: JSON = ["subject": .string(subject), "rating": rating, "notes": .string(env["notes"]?.string ?? ""),
                                  "ratedBy": .string(ratedBy), "issuedAt": env["issuedAt"] ?? .null]
                guard Crypto.verify(publickey: ratedBy, data: body, signature: signature) else { continue }
                if byRater[ratedBy] == nil { order.append(ratedBy) }
                byRater[ratedBy] = env
                merged += 1
            }
            let all = order.compactMap { byRater[$0] }.sorted { ($0["issuedAt"]?.int ?? 0) > ($1["issuedAt"]?.int ?? 0) }.prefix(Self.maxEndorsements)
            existing["publickey"] = .string(subject)
            existing["endorsements"] = .array(Array(all))
            existing["lastSeen"] = .int(nowMs())
            peers[subject] = .object(existing)
            return merged
        }
    }

    /// `recordQuery`: someone asked me about `subject`; how often they ask about people I know.
    public func recordQuery(asker: String, subject: String?) throws {
        guard asker != profile.publickey else { return }
        try change { peers in
            var rec = peers[asker]?.object ?? ["publickey": .string(asker), "firstSeen": .int(nowMs())]
            var made = rec["queryStats"]?["queriesMade"]?.int ?? 0
            var known = rec["queryStats"]?["queriesKnown"]?.int ?? 0
            made += 1
            if let subject, let s = peers[subject], s["myRating"]?.object != nil || !(s["endorsements"]?.array ?? []).isEmpty { known += 1 }
            rec["queryStats"] = ["queriesMade": .int(made), "queriesKnown": .int(known)]
            if rec["lastSeen"] == nil { rec["lastSeen"] = .int(nowMs()) }
            peers[asker] = .object(rec)
        }
    }
}
