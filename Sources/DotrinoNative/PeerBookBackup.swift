import CryptoKit
import Foundation

/// THE CONTACT BOOK IN THE VAULT: the same contacts on every device of the profile. The same as
/// `dotrino-identity/vault/peerSync.js` and `PeerBookBackup.kt`: the book is ONE thread of the
/// vault's store (`identity.peers`), one entry per person `{ id, ts, peer }`, encrypted with the
/// profile's content key over the same road as the history. What is newer here goes up, what is
/// newer there comes down and is MERGED (`PeerBook.mergeFrom`), never overwritten.
public final class PeerBookBackup: @unchecked Sendable {
    public static let thread = "identity.peers"
    private static let pushBytes = 350_000

    private let profile: Profile
    private let book: PeerBook

    public init(profile: Profile, book: PeerBook) { self.profile = profile; self.book = book }

    /// When the record last changed: seen (`lastSeen`) or touched (`changedAt`).
    public static func stampOf(_ rec: JSON?) -> Int64 { max(rec?["lastSeen"]?.int ?? 0, rec?["changedAt"]?.int ?? 0) }

    /// The entry's id, from the person's key (sha-256, 32 hex): the same as the web's.
    public static func idOf(_ publickey: String) -> String {
        SHA256.hash(data: Data(publickey.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// Reconcile now. Throws `not-paired` without a vault; the vault's own codes otherwise.
    @discardableResult
    public func sync() async throws -> Int {
        guard let link = profile.vault else { throw VaultBackup.BackupError(description: "this phone is not paired with a vault", code: "not-paired") }
        let conn = try ProxyConnection(link.proxy)
        defer { conn.close() }
        try await conn.connect()
        try? await conn.identifyAs(profile.publickey) { try self.profile.signData($0) }
        let account = Account(id: "peers", name: "", profileId: profile.profileId, vault: link.master, proxy: link.proxy, cert: link.cert, deviceId: link.deviceId)
        let vault = VaultClient(account: account, keys: profile.deviceKeys, conn: conn)
        return try await reconcile { m, a in try await vault.store(self.profile, m, a) }
    }

    /// The algorithm, with the vault as a function (tested against a fake vault). Returns records changed here.
    func reconcile(_ call: (String, JSON) async throws -> JSON) async throws -> Int {
        let t = Self.thread
        var remote: [String: Int64] = [:]
        var cursor: JSON = .null
        repeat {
            let page = try await call("getThreadIndexes", ["keys": [.string(t)], "cursor": cursor])
            for r in page["indexes"]?[t]?["items"]?.array ?? [] {
                if let id = r.array?.first?.string { remote[id] = r.array?.dropFirst().first?.int ?? 0 }
            }
            cursor = page["next"] ?? .null
        } while cursor != .null

        var local: [String: JSON] = [:]
        for (pk, rec) in try book.all() { local[Self.idOf(pk)] = rec }

        // UP first: what is newer here, in batches.
        var batch: [JSON] = []; var bytes = 0
        func flush() async throws {
            if batch.isEmpty { return }
            _ = try await call("importThreads", ["threads": [t: .array(batch)], "tombs": [:], "mode": "merge"])
            batch = []; bytes = 0
        }
        for (id, rec) in local.sorted(by: { $0.key < $1.key }) where (remote[id] ?? -1) < Self.stampOf(rec) {
            let e: JSON = ["id": .string(id), "ts": .int(Self.stampOf(rec)), "peer": rec]
            let size = e.text.utf8.count
            if bytes + size > Self.pushBytes { try await flush() }
            batch.append(e); bytes += size
        }
        try await flush()

        // DOWN: what is newer there.
        let down = remote.filter { id, ts in local[id].map { Self.stampOf($0) < ts } ?? true }.keys.sorted()
        var incoming: [JSON] = []
        var refs: JSON? = down.isEmpty ? nil : [t: .array(down.map { .string($0) })]
        while let r = refs {
            let page = try await call("getEntries", ["refs": r])
            for e in page["threads"]?[t]?.array ?? [] { if let p = e["peer"], p.object != nil { incoming.append(p) } }
            if let rest = page["rest"], let o = rest.object, !o.isEmpty { refs = rest } else { refs = nil }
        }
        return incoming.isEmpty ? 0 : try book.mergeFrom(incoming)
    }
}
