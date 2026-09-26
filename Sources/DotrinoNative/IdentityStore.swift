import Foundation

/// What the identity of the WebView (the `id.dotrino.com` iframe) keeps, held by the app: ONE
/// store for every page. WebKit keeps a cross-origin iframe's localStorage and IndexedDB
/// apart per page, so without this `dotrino.com`, `profile.dotrino.com` and
/// `vault.dotrino.com` each saw a different, freshly made profile. The JS side is
/// `dotrino-identity/vault/nativeStore.js`.
///
/// String keys to string values (`kv:…`, `key:…`, `peers:…`). No private key is ever in
/// here: key records only name a key of the enclave.
public final class IdentityStore: @unchecked Sendable {
    public static let shared = IdentityStore()
    private let sealed = SealedFile(name: "dotrino-identity.bin", keyAlias: "dotrino.identity-store")
    private var cache: [String: String]?

    public init() {}

    private func load() throws -> [String: String] {
        if let cache { return cache }
        var out: [String: String] = [:]
        if let plain = try sealed.read() {
            guard let o = try JSON.parse(plain).object else { throw CryptoError("identity store is not a map") }
            for (k, v) in o {
                guard let s = v.string else { throw CryptoError("identity store: \(k) is not a string") }
                out[k] = s
            }
        }
        cache = out
        return out
    }

    private func save(_ m: [String: String]) throws {
        try sealed.write(Data(JSON.object(m.mapValues { .string($0) }).text.utf8))
        cache = m
    }

    public func all() throws -> [String: String] {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        return try load()
    }

    public func set(_ k: String, _ v: String) throws {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        var m = try load()
        m[k] = v
        try save(m)
    }

    public func remove(_ k: String) throws {
        sealed.lock.lock(); defer { sealed.lock.unlock() }
        var m = try load()
        guard m.removeValue(forKey: k) != nil else { return }
        try save(m)
    }
}
