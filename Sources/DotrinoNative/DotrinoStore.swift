import Foundation

/// The native port of `@dotrino/store`: what an app keeps of the user, on the device, sealed.
/// Same shape as the web store so the same app logic reads both: THREADS of ENTRIES, each entry
/// a JSON object with its own `id` (and usually `ts` and `doc`). Appending an entry whose id is
/// already in the thread replaces it in place, like the web store.
///
/// What it does NOT do yet (PENDIENTES.md, «Apps con versión nativa»): the per-profile space
/// (`base::<pid>`) and the backup to the vault. Until then the store is the device's.
///
/// One sealed file per app. Same piece as `DotrinoStore.kt`.
public final class DotrinoStore: @unchecked Sendable {
    /// The file, its cache and its lock are the APP's, not the instance's: two `DotrinoStore`
    /// of the same app share them. With a cache each, the second to write would overwrite
    /// what the first wrote.
    private final class Shared: @unchecked Sendable {
        let sealed: SealedFile
        var cache: StoreThreads?
        init(_ sealed: SealedFile) { self.sealed = sealed }
    }
    nonisolated(unsafe) private static var shared: [String: Shared] = [:]
    private static let sharedLock = NSLock()

    private let s: Shared
    private var sealed: SealedFile { s.sealed }
    private var cache: StoreThreads? {
        get { s.cache }
        set { s.cache = newValue }
    }

    public init(app: String) throws {
        guard app.range(of: "^[a-z0-9-]+$", options: .regularExpression) != nil else {
            throw CryptoError("app name must be [a-z0-9-]: \(app)")
        }
        Self.sharedLock.lock(); defer { Self.sharedLock.unlock() }
        if let x = Self.shared[app] {
            s = x
        } else {
            let x = Shared(SealedFile(name: "dotrino-store-\(app).bin", keyAlias: "dotrino.store.\(app)"))
            Self.shared[app] = x
            s = x
        }
    }

    private func load() throws -> StoreThreads {
        if let c = cache { return c }
        let t = try StoreThreads.decode(try sealed.read())
        cache = t
        return t
    }

    public func listThread(_ thread: String) throws -> [JSON] {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        return try load().list(thread)
    }

    public func appendMessage(_ thread: String, _ entry: JSON) throws {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        var t = try load()
        try t.append(thread, entry)
        try sealed.write(Data(t.encode().utf8))
        cache = t
    }

    public func removeMessage(_ thread: String, id: String) throws {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        var t = try load()
        guard t.remove(thread, id: id) else { return }
        try sealed.write(Data(t.encode().utf8))
        cache = t
    }

    /// The threads with entries.
    public func threadKeys() throws -> [String] {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        return try load().keys()
    }

    /// Read-only access (for the vault backup, which compares and plans).
    public func read<T>(_ f: (StoreThreads) throws -> T) throws -> T {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        return try f(try load())
    }

    /// A change applied as a whole and saved once (what comes from the vault).
    public func change<T>(_ f: (inout StoreThreads) throws -> T) throws -> T {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        var t = try load()
        let r = try f(&t)
        try sealed.write(Data(t.encode().utf8))
        cache = t
        return r
    }

    // MARK: documents — one entry `{ id, ts, doc }` per document, as the web apps keep them.

    public func listDocs<T: Decodable>(_ thread: String, as type: T.Type) throws -> [T] {
        try listThread(thread).map { e in
            guard let doc = e["doc"] else { throw CryptoError("entry \(e["id"]?.string ?? "?") in \(thread) has no document") }
            return try JSONDecoder().decode(T.self, from: Data(doc.text.utf8))
        }
    }

    public func putDoc<T: Encodable>(_ thread: String, id: String, _ doc: T) throws {
        let json = try JSON.parse(try JSONEncoder().encode(doc))
        try appendMessage(thread, .object(["id": .string(id), "ts": .int(nowMs()), "doc": json]))
    }
}

/// The threads themselves, without the file: plain logic, tested without a Keychain.
///
/// The rules are `@dotrino/store/core`'s, byte for byte (same as `StoreThreads.kt`): the vault
/// holds the same threads and both sides compare DIGESTS. Tombstones `{ key: { id: [ts, at] } }`
/// keep the ts of what was deleted, so a deletion does not come back from another device.
public struct StoreThreads: Equatable {
    public static let tombTTL: Int64 = 180 * 24 * 60 * 60 * 1000
    public static let maxPerThread = 50_000

    private var names: [String] = []
    private var threads: [String: [JSON]] = [:]
    private var tombs: [String: [String: [Int64]]] = [:]

    public init() {}

    public static func decode(_ data: Data?) throws -> StoreThreads {
        var out = StoreThreads()
        guard let data else { return out }
        // Thread order is kept by writing it: a JSON object has none.
        guard let root = try JSON.parse(data).object,
              let order = root["order"]?.array,
              let body = root["threads"]?.object
        else { throw CryptoError("store: not the expected shape") }
        for n in order {
            guard let name = n.string, let arr = body[name]?.array else { throw CryptoError("store: thread list is broken") }
            for e in arr { _ = try id(of: e) }
            out.names.append(name)
            out.threads[name] = arr
        }
        for (k, v) in root["tombs"]?.object ?? [:] {
            var m: [String: [Int64]] = [:]
            for (id, row) in v.object ?? [:] {
                guard let a = row.array, a.count >= 2, let ts = a[0].int, let at = a[1].int else { throw CryptoError("store: tombstone \(k)/\(id) is broken") }
                m[id] = [ts, at]
            }
            if !m.isEmpty { out.tombs[k] = m }
        }
        return out
    }

    private static func id(of e: JSON) throws -> String {
        guard let id = e["id"]?.string, !id.isEmpty else { throw CryptoError("store: entry without a string id") }
        return id
    }

    /// `Number(e.ts) || 0`.
    public static func ts(of e: JSON) -> Int64 {
        switch e["ts"] { case .int(let n)?: return n; case .double(let d)?: return Int64(d); default: return 0 }
    }

    /// `threadDigest`: SHA-256 of «id TAB ts» per entry, sorted by UTF-16 units (as JS), joined by newlines.
    public static func digest(_ entries: [JSON]) -> String {
        let lines = entries.map { "\($0["id"]?.string ?? "")\t\(ts(of: $0))" }
            .sorted { Array($0.utf16).lexicographicallyPrecedes(Array($1.utf16)) }
        return Crypto.sha256Hex(Data(lines.joined(separator: "\n").utf8))
    }

    /// A thread's index as the vault sends it.
    public struct Index: Equatable {
        public var items: [(String, Int64)]
        public var tombs: [(String, Int64, Int64)]
        public init(items: [(String, Int64)], tombs: [(String, Int64, Int64)]) { self.items = items; self.tombs = tombs }
        public static func == (a: Index, b: Index) -> Bool {
            a.items.map { "\($0.0)|\($0.1)" } == b.items.map { "\($0.0)|\($0.1)" } && a.tombs.map { "\($0.0)|\($0.1)|\($0.2)" } == b.tombs.map { "\($0.0)|\($0.1)|\($0.2)" }
        }
    }

    public struct Plan {
        public var pushIds: [String] = [], pushTombs: [String] = [], pullIds: [String] = []
        public var pullTombs: [(String, Int64, Int64)] = []
    }

    /// `planThread` of `vault-sync.js`: what to move for one thread, from both indexes only.
    public static func plan(local: Index?, remote: Index?, max: Int = maxPerThread) -> Plan {
        let l = local ?? Index(items: [], tombs: []), r = remote ?? Index(items: [], tombs: [])
        var lItems: [String: Int64] = [:], rItems: [String: Int64] = [:], lTombs: [String: Int64] = [:]
        var rTombs: [String: (String, Int64, Int64)] = [:]
        for (id, t) in l.items { lItems[id] = t }
        for (id, t) in r.items { rItems[id] = t }
        for (id, t, _) in l.tombs { lTombs[id] = t }
        for row in r.tombs { rTombs[row.0] = row }
        var p = Plan()
        for (id, t, _) in l.tombs { if let x = rItems[id], x <= t { p.pushTombs.append(id) } }
        for row in r.tombs { if let x = lItems[row.0], x <= row.1 { p.pullTombs.append(row) } }
        for (id, t) in l.items {
            if let rt = rTombs[id], t <= rt.1 { continue }
            if let x = rItems[id], !(t > x) { continue }
            p.pushIds.append(id)
        }
        let full = lItems.count >= max
        let oldest = full ? (lItems.values.min() ?? Int64.max) : Int64.max
        for (id, t) in r.items {
            if let lt = lTombs[id], t <= lt { continue }
            if let x = lItems[id] { if t > x { p.pullIds.append(id) } } else if !(full && t < oldest) { p.pullIds.append(id) }
        }
        return p
    }

    public func list(_ thread: String) -> [JSON] { threads[thread] ?? [] }

    /// The threads with entries.
    public func keys() -> [String] { names.filter { !(threads[$0] ?? []).isEmpty } }

    /// Writing an entry again says it is back: its tombstone goes.
    public mutating func append(_ thread: String, _ entry: JSON) throws {
        let id = try Self.id(of: entry)
        if threads[thread] == nil { names.append(thread); threads[thread] = [] }
        if let i = threads[thread]!.firstIndex(where: { $0["id"]?.string == id }) {
            threads[thread]![i] = entry
        } else {
            threads[thread]!.append(entry)
        }
        tombs[thread]?[id] = nil
        if tombs[thread]?.isEmpty == true { tombs[thread] = nil }
    }

    /// Deletes and leaves a tombstone.
    public mutating func remove(_ thread: String, id: String, now: Int64 = nowMs()) -> Bool {
        guard let list = threads[thread], let gone = list.first(where: { $0["id"]?.string == id }) else { return false }
        threads[thread] = list.filter { $0["id"]?.string != id }
        bury(thread, id, Self.ts(of: gone), now)
        if threads[thread]!.isEmpty { threads[thread] = nil; names.removeAll { $0 == thread } }
        return true
    }

    @discardableResult
    private mutating func bury(_ k: String, _ id: String, _ ts: Int64, _ at: Int64) -> Bool {
        let prev = tombs[k]?[id]
        let next = prev.map { [Swift.max($0[0], ts), Swift.min($0[1], at)] } ?? [ts, at]
        if prev == next { return false }
        tombs[k, default: [:]][id] = next
        return true
    }

    private func isBuried(_ k: String, _ e: JSON) -> Bool {
        guard let id = e["id"]?.string, let t = tombs[k]?[id] else { return false }
        return Self.ts(of: e) <= t[0]
    }

    public func digest(of k: String) -> String? { (threads[k]?.isEmpty ?? true) ? nil : Self.digest(threads[k]!) }

    public func index(of k: String) -> Index {
        Index(items: (threads[k] ?? []).map { ($0["id"]?.string ?? "", Self.ts(of: $0)) },
              tombs: (tombs[k] ?? [:]).map { ($0.key, $0.value[0], $0.value[1]) })
    }

    public func entries(_ k: String, _ ids: [String]) -> [JSON] {
        let want = Set(ids); return (threads[k] ?? []).filter { want.contains($0["id"]?.string ?? "") }
    }

    public func tombRows(_ k: String, _ ids: [String]) -> [(String, Int64, Int64)] {
        ids.compactMap { id in tombs[k]?[id].map { (id, $0[0], $0[1]) } }
    }

    /// `mergeEntries`, mode `merge`: the greater ts wins; what is buried does not enter.
    @discardableResult
    public mutating func merge(_ incoming: [String: [JSON]], max: Int = maxPerThread) -> Set<String> {
        var changed = Set<String>()
        for (k, arr) in incoming where !arr.isEmpty {
            var byId: [String: JSON] = [:]; var order: [String] = []
            for e in threads[k] ?? [] { if let id = e["id"]?.string { if byId[id] == nil { order.append(id) }; byId[id] = e } }
            var touched = false
            for e in arr {
                guard let id = e["id"]?.string, !isBuried(k, e) else { continue }
                if let prev = byId[id], !(Self.ts(of: e) > Self.ts(of: prev)) { continue }
                if byId[id] == nil { order.append(id) }
                byId[id] = e; touched = true
            }
            if !touched { continue }
            var merged = order.compactMap { byId[$0] }.enumerated().sorted { (Self.ts(of: $0.element), $0.offset) < (Self.ts(of: $1.element), $1.offset) }.map(\.element)
            if merged.count > max { merged.removeFirst(merged.count - max) }
            if threads[k] == nil { names.append(k) }
            threads[k] = merged
            changed.insert(k)
        }
        return changed
    }

    /// `applyTombs`: note tombstones from elsewhere and drop what they bury.
    @discardableResult
    public mutating func applyTombs(_ incoming: [String: [(String, Int64, Int64)]]) -> Set<String> {
        var changed = Set<String>()
        for (k, rows) in incoming {
            for (id, ts, at) in rows { bury(k, id, ts, at) }
            guard let list = threads[k] else { continue }
            let kept = list.filter { !isBuried(k, $0) }
            if kept.count == list.count { continue }
            if kept.isEmpty { threads[k] = nil; names.removeAll { $0 == k } } else { threads[k] = kept }
            changed.insert(k)
        }
        return changed
    }

    public static func == (a: StoreThreads, b: StoreThreads) -> Bool { a.encode() == b.encode() }

    public func encode() -> String {
        JSON.object([
            "order": .array(names.map { .string($0) }),
            "threads": .object(threads.mapValues { .array($0) }),
            "tombs": .object(tombs.mapValues { t in .object(t.mapValues { .array([.int($0[0]), .int($0[1])]) }) }),
        ]).text
    }
}
