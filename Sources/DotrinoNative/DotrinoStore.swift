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
    private let sealed: SealedFile
    private var cache: StoreThreads?

    public init(app: String) throws {
        guard app.range(of: "^[a-z0-9-]+$", options: .regularExpression) != nil else {
            throw CryptoError("app name must be [a-z0-9-]: \(app)")
        }
        sealed = SealedFile(name: "dotrino-store-\(app).bin", keyAlias: "dotrino.store.\(app)")
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
public struct StoreThreads: Equatable {
    private var names: [String] = []
    private var threads: [String: [JSON]] = [:]

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
        return out
    }

    private static func id(of e: JSON) throws -> String {
        guard let id = e["id"]?.string, !id.isEmpty else { throw CryptoError("store: entry without a string id") }
        return id
    }

    public func list(_ thread: String) -> [JSON] { threads[thread] ?? [] }

    public mutating func append(_ thread: String, _ entry: JSON) throws {
        let id = try Self.id(of: entry)
        if threads[thread] == nil { names.append(thread); threads[thread] = [] }
        if let i = threads[thread]!.firstIndex(where: { $0["id"]?.string == id }) {
            threads[thread]![i] = entry
        } else {
            threads[thread]!.append(entry)
        }
    }

    public mutating func remove(_ thread: String, id: String) -> Bool {
        guard let list = threads[thread] else { return false }
        let kept = list.filter { $0["id"]?.string != id }
        threads[thread] = kept
        return kept.count != list.count
    }

    public func encode() -> String {
        JSON.object([
            "order": .array(names.map { .string($0) }),
            "threads": .object(threads.mapValues { .array($0) }),
        ]).text
    }
}
