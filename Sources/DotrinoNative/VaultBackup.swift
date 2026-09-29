import Foundation

/// THE STORE'S BACKUP IN THE OWNER'S VAULT for native apps (`vault-sync.js`). Same piece as
/// `VaultBackup.kt`: reads and writes stay local; this reconciles by digest per thread,
/// uploading first and then pulling only what is missing, encrypted with the content key, and
/// only for the threads [owns] says are the app's (the vault keeps one store for every app).
public final class VaultBackup: @unchecked Sendable {
    /// What the backup needs of the store: `DotrinoStore` on a phone, memory in the tests.
    public protocol Threads: AnyObject {
        func threadKeys() throws -> [String]
        func read<T>(_ f: (StoreThreads) throws -> T) throws -> T
        func change<T>(_ f: (inout StoreThreads) throws -> T) throws -> T
    }

    public final class MemoryThreads: Threads {
        public var t = StoreThreads()
        public init() {}
        public func threadKeys() throws -> [String] { t.keys() }
        public func read<T>(_ f: (StoreThreads) throws -> T) throws -> T { try f(t) }
        public func change<T>(_ f: (inout StoreThreads) throws -> T) throws -> T { var c = t; let r = try f(&c); t = c; return r }
    }

    public struct BackupError: Error, CustomStringConvertible { public let description: String; public let code: String }
    public struct Result { public let changed: Set<String>; public let tooLarge: [String] }

    private static let pushBytes = 350_000, refBytes = 150_000, maxEntryBytes = 550_000
    private let profile: Profile
    private let store: Threads
    private let owns: (String) -> Bool
    private let gate = AsyncLock()

    public init(profile: Profile, store: Threads, owns: @escaping (String) -> Bool) {
        self.profile = profile; self.store = store; self.owns = owns
    }

    /// Reconcile now. Throws with a code: `not-paired`, `no-content-key`, or the vault's.
    public func sync() async throws -> Result {
        await gate.lock()
        do {
            guard let link = profile.vault else { throw BackupError(description: "this phone is not paired with a vault", code: "not-paired") }
            let conn = try ProxyConnection(link.proxy)
            defer { conn.close() }
            try await conn.connect()
            try? await conn.identifyAs(profile.publickey) { try self.profile.signData($0) }
            let account = Account(id: "backup", name: "", profileId: profile.profileId, vault: link.master, proxy: link.proxy, cert: link.cert, deviceId: link.deviceId)
            let vault = VaultClient(account: account, keys: profile.deviceKeys, conn: conn)
            let r = try await reconcile { m, a in try await vault.store(self.profile, m, a) }
            await gate.unlock()
            return r
        } catch { await gate.unlock(); throw error }
    }

    func reconcile(_ call: (String, JSON) async throws -> JSON) async throws -> Result {
        var tooLarge: [String] = []
        let remoteDigests = try await call("getThreadDigests", [:]).object ?? [:]
        let localKeys = try store.threadKeys().filter(owns)
        var keys: [String] = []
        for k in Array(Set(localKeys + remoteDigests.keys.filter(owns))).sorted() {
            let l = try store.read { $0.digest(of: k) }
            if l != remoteDigests[k]?["digest"]?.string { keys.append(k) }
        }
        if keys.isEmpty { return Result(changed: [], tooLarge: []) }

        var remote: [String: StoreThreads.Index] = [:]
        var cursor: JSON = .null
        repeat {
            let page = try await call("getThreadIndexes", ["keys": .array(keys.map { .string($0) }), "cursor": cursor])
            for (k, idx) in page["indexes"]?.object ?? [:] {
                var cur = remote[k] ?? StoreThreads.Index(items: [], tombs: [])
                for r in idx["items"]?.array ?? [] { if let a = r.array, let id = a[0].string { cur.items.append((id, a[1].int ?? 0)) } }
                for r in idx["tombs"]?.array ?? [] { if let a = r.array, let id = a[0].string { cur.tombs.append((id, a[1].int ?? 0, a[2].int ?? 0)) } }
                remote[k] = cur
            }
            cursor = page["next"] ?? .null
        } while cursor != .null

        var pushRefs: [String: [String]] = [:], pushTombs: [String: [String]] = [:]
        var pullRefs: [String: [String]] = [:], pullTombs: [String: [(String, Int64, Int64)]] = [:]
        for k in keys {
            let plan = StoreThreads.plan(local: try store.read { $0.index(of: k) }, remote: remote[k])
            if !plan.pushIds.isEmpty { pushRefs[k] = plan.pushIds }
            if !plan.pushTombs.isEmpty { pushTombs[k] = plan.pushTombs }
            if !plan.pullIds.isEmpty { pullRefs[k] = plan.pullIds }
            if !plan.pullTombs.isEmpty { pullTombs[k] = plan.pullTombs }
        }

        if !pushRefs.isEmpty || !pushTombs.isEmpty {
            let entries = try store.read { t in pushRefs.mapValues { t.entries($0.key, $0.value) } }
            let tombs = try store.read { t in pushTombs.mapValues { t.tombRows($0.key, $0.value) } }
            for batch in batches(entries, tombs, &tooLarge) { _ = try await call("importThreads", batch) }
        }

        var changed = Set<String>()
        if !pullTombs.isEmpty { changed.formUnion(try store.change { $0.applyTombs(pullTombs) }) }
        for chunk in chunkRefs(pullRefs) {
            var refs: JSON? = chunk
            while let r = refs {
                let page = try await call("getEntries", ["refs": r])
                var incoming: [String: [JSON]] = [:]
                for (k, v) in page["threads"]?.object ?? [:] { incoming[k] = (v.array ?? []).filter { $0.object != nil } }
                if !incoming.isEmpty { changed.formUnion(try store.change { $0.merge(incoming) }) }
                refs = page["rest"].flatMap { $0.object != nil ? $0 : nil }
            }
        }
        return Result(changed: changed, tooLarge: tooLarge)
    }

    private func batches(_ threads: [String: [JSON]], _ tombs: [String: [(String, Int64, Int64)]], _ tooLarge: inout [String]) -> [JSON] {
        var out: [([String: [JSON]], [String: [JSON]])] = []
        var bytes = 0
        func add(_ kind: Int, _ k: String, _ item: JSON, _ id: String) {
            let size = item.text.utf8.count + 1
            if size > Self.maxEntryBytes { tooLarge.append("\(k)/\(id)"); return }
            if out.isEmpty || bytes + size > Self.pushBytes { out.append(([:], [:])); bytes = 0 }
            if kind == 0 { out[out.count - 1].0[k, default: []].append(item) } else { out[out.count - 1].1[k, default: []].append(item) }
            bytes += size
        }
        for (k, rows) in tombs { for (id, ts, at) in rows { add(1, k, .array([.string(id), .int(ts), .int(at)]), id) } }
        for (k, list) in threads { for e in list { add(0, k, e, e["id"]?.string ?? "?") } }
        return out.map { ["threads": .object($0.0.mapValues { .array($0) }), "tombs": .object($0.1.mapValues { .array($0) }), "mode": "merge"] }
    }

    private func chunkRefs(_ refs: [String: [String]]) -> [JSON] {
        var out: [[String: [JSON]]] = []
        var bytes = 0
        for (k, ids) in refs { for id in ids {
            let s = id.utf8.count + k.utf8.count + 4
            if out.isEmpty || bytes + s > Self.refBytes { out.append([:]); bytes = 0 }
            out[out.count - 1][k, default: []].append(.string(id)); bytes += s
        } }
        return out.map { .object($0.mapValues { .array($0) }) }
    }
}

extension DotrinoStore: VaultBackup.Threads {}
