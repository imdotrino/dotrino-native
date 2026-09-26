import CryptoKit
import Foundation

/// Key ids and the device's certificate («papel»), as the pilar defines them.
public enum Delegation {
    /// `pubkeyId` (`vault/keyid.js`): sha-256 hex of `{"crv","kty","x","y"}` in that order.
    public static func pubkeyId(_ jwk: String) throws -> String {
        let o = try JSON.parse(jwk)
        var canon = "{"
        for (i, k) in ["crv", "kty", "x", "y"].enumerated() {
            guard let v = o[k]?.string else { throw CryptoError("jwk: missing \(k)") }
            if i > 0 { canon += "," }
            Canonical.quote(k, into: &canon); canon += ":"; Canonical.quote(v, into: &canon)
        }
        canon += "}"
        return SHA256.hash(data: Data(canon.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    /// The label a person reads and compares: `AB12-CD34`.
    public static func keyLabel(_ jwk: String) throws -> String {
        let id = try pubkeyId(jwk).prefix(8).uppercased()
        return id.prefix(4) + "-" + id.suffix(4)
    }

    /// Two JWK strings name the same key when x and y match (field order and extras do not matter).
    public static func samePubkey(_ a: String?, _ b: String?) -> Bool {
        guard let a, let b, let x = try? JSON.parse(a), let y = try? JSON.parse(b) else { return false }
        return x["x"] != nil && x["x"] == y["x"] && x["y"] == y["y"]
    }

    /// `delegationBody`: what the issuer signed. Old papers (`exp`) and new ones (`seq`) have different bodies.
    public static func body(_ cert: JSON) -> JSON {
        var out: [String: JSON] = [:]
        let legacy = cert["seq"]?.int == nil && cert["exp"]?.int != nil
        for k in ["v", "iss", "sub", "scope", "iat"] { if let v = cert[k] { out[k] = v } }
        if legacy { if let v = cert["exp"] { out["exp"] = v } } else if let v = cert["seq"] { out["seq"] = v }
        if let v = cert["nonce"] { out["nonce"] = v }
        return .object(out)
    }

    public static func scope(_ cert: JSON) -> [String] {
        switch cert["scope"] {
        case .array(let a)?: return a.compactMap { $0.string }
        case .string(let s)?: return [s]
        default: return []
        }
    }

    /// Is [cert] a paper for MY key, issued by the vault I enrolled with, and does it allow
    /// [expectedScope]? Returns the reason it is not, or nil when it is.
    ///
    /// Narrower than `checkVaultReply` of the pilar (which also verifies the whole record):
    /// here the issuer must be the vault pinned at enrollment, not any sealer of the record.
    /// A paper from another sealer is refused and said — never taken as good.
    public static func check(_ cert: JSON, vault: String, sub: String, expectedScope: String?) -> String? {
        guard let iss = cert["iss"]?.string, let sig = cert["sig"]?.string else { return "shape" }
        if !samePubkey(iss, vault) { return "paper-from-another-vault" }
        if !samePubkey(cert["sub"]?.string, sub) { return "sub" }
        if !Crypto.verify(publickey: iss, data: body(cert), signature: sig) { return "bad-signature" }
        if let expectedScope, !scope(cert).contains(expectedScope) { return "scope" }
        return nil
    }
}
