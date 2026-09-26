import CryptoKit
import Foundation
import Security

/// The account's two keys in the Secure Enclave. Created there and never exportable: what
/// the Keychain keeps is the enclave's own encrypted handle (`dataRepresentation`), which
/// only THIS chip can use. No code in the app — nor anything that gets into it — can read the
/// private halves. They are asked to sign or to agree, and that is all.
///
/// One pair per account, named after it (`dotrino.<id>.sign` / `.enc`), so accounts never
/// share a key: removing one account deletes exactly its keys.
///
/// EL SIMULADOR NO TIENE ENCLAVE. Ahí, y solo ahí (`targetEnvironment(simulator)`, decidido
/// al compilar), las llaves son de software guardadas en el llavero. No es un repliegue en
/// marcha: un build para un iPhone no contiene ese camino, y si el enclave no respondiera
/// fallaría con su error en vez de hacerse una llave más débil.
public final class EnclaveKeys: DeviceKeys, @unchecked Sendable {
    private let id: String
    public let publickey: String
    public let encPub: String

    private static func signAlias(_ id: String) -> String { "dotrino.\(id).sign" }
    private static func encAlias(_ id: String) -> String { "dotrino.\(id).enc" }

    #if targetEnvironment(simulator)
    private typealias SignKey = P256.Signing.PrivateKey
    private typealias AgreeKey = P256.KeyAgreement.PrivateKey
    private static func newSign() throws -> SignKey { SignKey() }
    private static func newAgree() throws -> AgreeKey { AgreeKey() }
    private static func loadSign(_ d: Data) throws -> SignKey { try SignKey(rawRepresentation: d) }
    private static func loadAgree(_ d: Data) throws -> AgreeKey { try AgreeKey(rawRepresentation: d) }
    private static func blob(_ k: SignKey) -> Data { k.rawRepresentation }
    private static func blob(_ k: AgreeKey) -> Data { k.rawRepresentation }
    #else
    private typealias SignKey = SecureEnclave.P256.Signing.PrivateKey
    private typealias AgreeKey = SecureEnclave.P256.KeyAgreement.PrivateKey
    private static func newSign() throws -> SignKey { try SignKey() }
    private static func newAgree() throws -> AgreeKey { try AgreeKey() }
    private static func loadSign(_ d: Data) throws -> SignKey { try SignKey(dataRepresentation: d) }
    private static func loadAgree(_ d: Data) throws -> AgreeKey { try AgreeKey(dataRepresentation: d) }
    private static func blob(_ k: SignKey) -> Data { k.dataRepresentation }
    private static func blob(_ k: AgreeKey) -> Data { k.dataRepresentation }
    #endif

    private let signKey: SignKey
    private let agreeKey: AgreeKey

    private init(id: String, sign: SignKey, agree: AgreeKey) {
        self.id = id
        signKey = sign
        agreeKey = agree
        publickey = Crypto.jwk(raw: sign.publicKey.rawRepresentation)
        encPub = Crypto.jwk(raw: agree.publicKey.rawRepresentation)
    }

    /// Creates the pair for [id]. Fails if it already exists: overwriting would orphan an enrolled device.
    public static func create(_ id: String) throws -> EnclaveKeys {
        if Keychain.exists(signAlias(id)) || Keychain.exists(encAlias(id)) { throw CryptoError("keys for \(id) already exist") }
        let s = try newSign(), a = try newAgree()
        try Keychain.add(signAlias(id), blob(s))
        do { try Keychain.add(encAlias(id), blob(a)) } catch { Keychain.delete(signAlias(id)); throw error }
        return EnclaveKeys(id: id, sign: s, agree: a)
    }

    /// The existing pair for [id]; fails loudly if it is not there.
    public static func open(_ id: String) throws -> EnclaveKeys {
        guard let s = try Keychain.get(signAlias(id)), let a = try Keychain.get(encAlias(id)) else {
            throw CryptoError("no keys for \(id) on this device")
        }
        return EnclaveKeys(id: id, sign: try loadSign(s), agree: try loadAgree(a))
    }

    public static func exists(_ id: String) -> Bool { Keychain.exists(signAlias(id)) && Keychain.exists(encAlias(id)) }

    public static func delete(_ id: String) {
        Keychain.delete(signAlias(id))
        Keychain.delete(encAlias(id))
    }

    public func signBytes(_ bytes: Data) throws -> String {
        Crypto.b64(try signKey.signature(for: bytes).rawRepresentation)
    }

    public func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data {
        try agreeKey.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
    }
}

/// Generic-password items of this app, only on this device and readable after the first
/// unlock (a ring arriving with the phone locked still has to reach the vault).
enum Keychain {
    private static let service = "com.dotrino.app.keys"

    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    static func add(_ account: String, _ data: Data) throws {
        var q = query(account)
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let st = SecItemAdd(q as CFDictionary, nil)
        guard st == errSecSuccess else { throw CryptoError("keychain add \(account): \(st)") }
    }

    static func get(_ account: String) throws -> Data? {
        var q = query(account)
        q[kSecReturnData as String] = true
        var out: AnyObject?
        let st = SecItemCopyMatching(q as CFDictionary, &out)
        if st == errSecItemNotFound { return nil }
        guard st == errSecSuccess, let d = out as? Data else { throw CryptoError("keychain read \(account): \(st)") }
        return d
    }

    static func exists(_ account: String) -> Bool { SecItemCopyMatching(query(account) as CFDictionary, nil) == errSecSuccess }

    static func delete(_ account: String) { SecItemDelete(query(account) as CFDictionary) }
}
