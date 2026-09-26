import CryptoKit
import Foundation

/// The two keys of ONE account on this device. The private halves never leave wherever they
/// live (the Secure Enclave on a phone): this protocol only asks them to do their job.
///
/// - `publickey`: signing key (ECDSA P-256) as the ecosystem writes it, `{"kty","crv","x","y"}`.
/// - `encPub`: encryption key (ECDH P-256), same shape. It goes into the record so the vault
///   can seal things (the command of a request) to this device.
public protocol DeviceKeys: Sendable {
    var publickey: String { get }
    var encPub: String { get }
    /// Signs [bytes] (SHA-256 inside) and returns the P1363 (r‖s) signature in base64.
    func signBytes(_ bytes: Data) throws -> String
    /// Raw ECDH shared secret (the x coordinate, 32 bytes) with [peer].
    func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data
}

public extension DeviceKeys {
    /// Signs the UTF-8 bytes of [text].
    func sign(_ text: String) throws -> String { try signBytes(Data(text.utf8)) }
}

public struct CryptoError: Error, CustomStringConvertible {
    public let description: String
    public init(_ d: String) { description = d }
}

public enum Crypto {
    /// Standard base64 with padding: what `btoa` produces in the JS pilar.
    public static func b64(_ d: Data) -> String { d.base64EncodedString() }

    public static func fromB64(_ s: String) throws -> Data {
        guard let d = Data(base64Encoded: s) else { throw CryptoError("base64: invalid") }
        return d
    }

    static func b64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func fromB64url(_ s: String) throws -> Data {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while t.count % 4 != 0 { t += "=" }
        return try fromB64(t)
    }

    /// `{"kty":"EC","crv":"P-256","x":…,"y":…}` from the raw 64 bytes x‖y — the same field
    /// order WebCrypto-derived keys use in the ecosystem.
    public static func jwk(raw: Data) -> String {
        precondition(raw.count == 64, "p-256 raw public key is 64 bytes")
        return #"{"kty":"EC","crv":"P-256","x":"\#(b64url(raw.prefix(32)))","y":"\#(b64url(raw.suffix(32)))"}"#
    }

    /// x‖y (64 bytes) of a P-256 JWK string.
    public static func rawOf(jwk: String) throws -> Data {
        let o = try JSON.parse(jwk)
        guard o["crv"]?.string == "P-256" else { throw CryptoError("jwk: only P-256 keys") }
        guard let x = o["x"]?.string, let y = o["y"]?.string else { throw CryptoError("jwk: missing x/y") }
        let xb = try fromB64url(x), yb = try fromB64url(y)
        guard xb.count == 32, yb.count == 32 else { throw CryptoError("jwk: coordinates must be 32 bytes") }
        return xb + yb
    }

    public static func agreementKey(jwk: String) throws -> P256.KeyAgreement.PublicKey {
        try P256.KeyAgreement.PublicKey(rawRepresentation: rawOf(jwk: jwk))
    }

    /// `verifyDeviceSig` of the pilar: P1363 base64 signature over the canonical text of [data].
    public static func verify(publickey: String, data: JSON, signature: String) -> Bool {
        do {
            let key = try P256.Signing.PublicKey(rawRepresentation: rawOf(jwk: publickey))
            let sig = try P256.Signing.ECDSASignature(rawRepresentation: fromB64(signature))
            return key.isValidSignature(sig, for: Data(try Canonical.stringify(data).utf8))
        } catch { return false }
    }

    /// `openWrap` of the pilar (`vault/content.js`): ECDH between my encryption key and the
    /// ephemeral `epk`, the 32 raw bytes as the AES-GCM key, and out comes the content key
    /// (a base64 string).
    public static func openWrap(_ wrap: JSON, keys: DeviceKeys) throws -> String {
        guard let epk = wrap["epk"]?.string, let iv = wrap["iv"]?.string, let ct = wrap["ct"]?.string else {
            throw CryptoError("invalid wrap")
        }
        let secret = try keys.agree(agreementKey(jwk: epk))
        return try utf8(aesGcmOpen(key: secret, iv: fromB64(iv), ct: fromB64(ct)))
    }

    /// `decryptWithCek`: the envelope `{ iv, ct }` with a content key given as base64.
    public static func decryptWithCek(_ cek: String, envelope: JSON) throws -> String {
        guard let iv = envelope["iv"]?.string, let ct = envelope["ct"]?.string else { throw CryptoError("invalid envelope") }
        return try utf8(aesGcmOpen(key: fromB64(cek), iv: fromB64(iv), ct: fromB64(ct)))
    }

    /// What the vault seals to the approver (the command of a request): a wrap plus an envelope.
    public static func openSealed(wrap: JSON, envelope: JSON, keys: DeviceKeys) throws -> String {
        try decryptWithCek(openWrap(wrap, keys: keys), envelope: envelope)
    }

    /// WebCrypto's AES-GCM output is the ciphertext with the 16-byte tag appended.
    static func aesGcmOpen(key: Data, iv: Data, ct: Data) throws -> Data {
        guard ct.count >= 16 else { throw CryptoError("aes-gcm: ciphertext too short") }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: iv), ciphertext: ct.dropLast(16), tag: ct.suffix(16))
        return try AES.GCM.open(box, using: SymmetricKey(data: key))
    }

    private static func utf8(_ d: Data) throws -> String {
        guard let s = String(data: d, encoding: .utf8) else { throw CryptoError("not utf-8") }
        return s
    }
}
