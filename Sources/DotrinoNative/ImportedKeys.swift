import CryptoKit
import Foundation

/// The keys of an account opened with user and password. That key lives in the VAULT, which
/// hands it over at sign-in: it cannot be put in the Secure Enclave, so it is kept in the
/// Keychain sealed under an enclave key of its own, and opened only to sign or to agree.
///
/// The special case, and the only one: every other key of the phone is born in the enclave
/// (`EnclaveKeys`) and never leaves it.
public final class ImportedKeys: DeviceKeys, @unchecked Sendable {
    public let publickey: String
    public let encPub: String
    private let signKey: P256.Signing.PrivateKey
    private let agreeKey: P256.KeyAgreement.PrivateKey

    private static func alias(_ id: String) -> String { "dotrino.\(id).imported" }
    /// The enclave key that seals this account's keys (`EnclaveKeys` under its own id).
    private static func wrapId(_ id: String) -> String { "\(id).wrap" }
    private static let info = Data("dotrino-imported-key".utf8)

    private init(sign: JSON, enc: JSON, publickey: String, encPub: String) throws {
        signKey = try P256.Signing.PrivateKey(rawRepresentation: try Self.scalar(sign))
        agreeKey = try P256.KeyAgreement.PrivateKey(rawRepresentation: try Self.scalar(enc))
        // Each private half must be the pair of the public one the account record names.
        guard signKey.publicKey.rawRepresentation == (try Crypto.rawOf(jwk: publickey)) else {
            throw CryptoError("the signing key is not the pair of its public key")
        }
        guard agreeKey.publicKey.rawRepresentation == (try Crypto.rawOf(jwk: encPub)) else {
            throw CryptoError("the encryption key is not the pair of its public key")
        }
        self.publickey = publickey
        self.encPub = encPub
    }

    private static func scalar(_ jwk: JSON) throws -> Data {
        guard jwk["crv"]?.string == "P-256", let d = jwk["d"]?.string else { throw CryptoError("jwk: not a P-256 private key") }
        return try Crypto.fromB64url(d)
    }

    private static func sealKey(_ wrap: EnclaveKeys, _ eph: P256.KeyAgreement.PublicKey) throws -> Data {
        let shared = try wrap.agree(eph)
        return HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: shared), info: info, outputByteCount: 32)
            .withUnsafeBytes { Data($0) }
    }

    public static func exists(_ id: String) -> Bool { Keychain.exists(alias(id)) }

    /// Keeps the pair for [id]. `sign` and `enc` are the private JWKs; `publickey` and `encPub`
    /// are the public halves EXACTLY as the account record writes them.
    @discardableResult
    public static func `import`(_ id: String, sign: JSON, enc: JSON, publickey: String, encPub: String) throws -> ImportedKeys {
        if exists(id) || EnclaveKeys.exists(id) { throw CryptoError("keys for \(id) already exist") }
        let k = try ImportedKeys(sign: sign, enc: enc, publickey: publickey, encPub: encPub)
        let plain: JSON = ["sign": sign, "enc": enc, "publickey": .string(publickey), "encPub": .string(encPub)]
        let wrap = try EnclaveKeys.create(wrapId(id))
        do {
            let eph = P256.KeyAgreement.PrivateKey()
            let (iv, ct) = try Crypto.aesGcmSeal(key: try sealKey(wrap, eph.publicKey), plain: Data(plain.text.utf8))
            try Keychain.add(alias(id), eph.publicKey.rawRepresentation + iv + ct)
        } catch { EnclaveKeys.delete(wrapId(id)); throw error }
        return k
    }

    /// The existing pair for [id]; fails loudly if it is not there or does not open.
    public static func open(_ id: String) throws -> ImportedKeys {
        guard let raw = try Keychain.get(alias(id)), raw.count > 64 + 12 else { throw CryptoError("no imported keys for \(id) on this device") }
        let eph = try P256.KeyAgreement.PublicKey(rawRepresentation: raw.prefix(64))
        let key = try sealKey(try EnclaveKeys.open(wrapId(id)), eph)
        let body = raw.dropFirst(64)
        let plain = try Crypto.aesGcmOpen(key: key, iv: Data(body.prefix(12)), ct: Data(body.dropFirst(12)))
        let o = try JSON.parse(String(decoding: plain, as: UTF8.self))
        guard let sign = o["sign"], let enc = o["enc"], let pub = o["publickey"]?.string, let encPub = o["encPub"]?.string else {
            throw CryptoError("imported keys for \(id) are damaged")
        }
        return try ImportedKeys(sign: sign, enc: enc, publickey: pub, encPub: encPub)
    }

    public static func delete(_ id: String) {
        Keychain.delete(alias(id))
        EnclaveKeys.delete(wrapId(id))
    }

    public func signBytes(_ bytes: Data) throws -> String {
        Crypto.b64(try signKey.signature(for: bytes).rawRepresentation)
    }

    public func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data {
        try agreeKey.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
    }
}

/// An account's keys by id: the enclave's, or the ones a vault handed over at sign-in.
public enum PhoneKeys {
    public static func exists(_ id: String) -> Bool { EnclaveKeys.exists(id) || ImportedKeys.exists(id) }

    public static func open(_ id: String) throws -> DeviceKeys {
        ImportedKeys.exists(id) ? try ImportedKeys.open(id) : try EnclaveKeys.open(id)
    }

    public static func delete(_ id: String) {
        EnclaveKeys.delete(id)
        ImportedKeys.delete(id)
    }
}
