import CryptoKit
import Foundation

/// A file in Application Support encrypted (AES-GCM) with a key that lives in the Keychain,
/// only on this device. What the app keeps about a person is theirs even when nothing in it
/// is secret (which vaults they belong to, their profile), so nothing goes to disk in clear.
///
/// A file that exists but does not open is an ERROR, never «empty»: treating it as empty
/// would silently lose accounts or profiles.
final class SealedFile: @unchecked Sendable {
    private let file: URL
    private let keyAlias: String
    let lock = NSLock()

    init(name: String, keyAlias: String) {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        file = dir.appendingPathComponent(name)
        self.keyAlias = keyAlias
    }

    /// The file's key. A new one is made ONLY when there is no file yet: with a file on disk
    /// and no key, a fresh key would just make the file unreadable and look like corruption
    /// (it happened when the signing team changed the Keychain access group).
    private func key() throws -> SymmetricKey {
        if let d = try Keychain.get(keyAlias) { return SymmetricKey(data: d) }
        if FileManager.default.fileExists(atPath: file.path) {
            throw CryptoError("\(file.lastPathComponent) exists but its key is not in the Keychain")
        }
        let k = SymmetricKey(size: .bits256)
        try Keychain.add(keyAlias, k.withUnsafeBytes { Data($0) })
        return k
    }

    /// The plaintext, or nil when there is no file yet. Call with [lock] held.
    func read() throws -> Data? {
        guard FileManager.default.fileExists(atPath: file.path) else { return nil }
        let raw = try Data(contentsOf: file)
        guard raw.count > 28 else { throw CryptoError("\(file.lastPathComponent) is truncated") }
        return try AES.GCM.open(AES.GCM.SealedBox(combined: raw), using: key())
    }

    /// Call with [lock] held. Atomic: a half-written file never replaces a good one.
    func write(_ plain: Data) throws {
        guard let sealed = try AES.GCM.seal(plain, using: key()).combined else {
            throw CryptoError("could not seal \(file.lastPathComponent)")
        }
        try sealed.write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }
}
