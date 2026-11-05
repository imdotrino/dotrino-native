import CryptoKit
import Foundation

/// WHAT A DEVICE ASKS ITS VAULT GOES SEALED, AND THE ANSWER COMES BACK SEALED
/// (`dotrino-vault/docs/sealed-requests.md`; same piece as `VaultSeal.kt` and
/// `@dotrino/identity/vault-seal`).
///
/// The proxy does not encrypt: a request that is only signed showed the proxy the phone's
/// `machine`, the operation and the ids of the requests being approved. Now:
///
///   · the WHOLE request `{ type, data, signature, cert }` is sealed (the pilar's envelope,
///     `{ v: 1, sealed: wrapForMember(…) }`) to the transport key of each vault of the account
///     — the members with `cn: "vault"` of the account's record — and routed to the account,
///     so a sleeping vault is still woken. Outside the envelope: only where it goes.
///   · inside what is signed goes a ONE-USE key (`rek`), and the vault seals its answer to it.
///
/// MIGRATION — EXPIRES 2026-12-15 (`plainUntilMs`). Until then, with nobody to seal to (an old
/// vault that announces no key), the request goes in the clear, and an unsealed answer from an
/// old vault is taken; both are said through `onWarn`. From that date, neither.
public enum VaultSeal {
    public static let plainUntilMs: Int64 = 1_797_292_800_000   // 2026-12-15T00:00:00Z
    public static func plainAllowed(_ now: Int64 = nowMs()) -> Bool { now < plainUntilMs }

    /// The communication keys of the account's vaults, from a record.
    public static func vaultKeys(_ acta: JSON?) -> [String] {
        var out: [String] = []
        for m in acta?["members"]?.array ?? [] where m["cn"]?.string == "vault" {
            if let p = m["pub"]?.string, !p.isEmpty, !out.contains(p) { out.append(p) }
        }
        return out
    }

    /// The account's newest record the proxy keeps (`acta-get`) that verifies as sealed by the
    /// account. A forged one does not verify; an older genuine one only names the account's own vaults.
    /// A proxy that keeps no records (`no-acta-cache`) is not an error: there is just no record (nil).
    public static func accountRecord(_ conn: ProxyConnection, profileId: String) async throws -> JSON? {
        let actas: [JSON]
        do { actas = try await conn.getActas(profileId) } catch let e as ProxyError where e.code == "no-acta-cache" { return nil }
        return actas.filter { Acta.verify($0, expectedProfileId: profileId) == nil }
            .max { ($0["seq"]?.int ?? 0) < ($1["seq"]?.int ?? 0) }
    }

    /// The pilar's envelope: `{ v: 1, sealed: wrapForMember(JSON text, encPub) }`.
    public static func seal(_ payload: JSON, to encPub: String) throws -> JSON {
        ["v": 1, "sealed": try Crypto.wrapForMember(payload.text, memberEncPub: encPub)]
    }

    public static func isSealed(_ m: JSON) -> Bool {
        m["v"]?.int == 1 && m["sealed"]?["ct"]?.string != nil && m["sealed"]?["epk"]?.string != nil
    }

    /// The one-use key the answer is sealed to.
    public final class ReplyKey: @unchecked Sendable {
        private let key = P256.KeyAgreement.PrivateKey()
        public init() {}
        public var encPub: String { Crypto.jwk(raw: key.publicKey.rawRepresentation) }

        /// Opens an envelope sealed to this key; nil when it is not for it.
        public func open(_ envelope: JSON) -> JSON? {
            guard let w = envelope["sealed"], let epk = w["epk"]?.string, let iv = w["iv"]?.string, let ct = w["ct"]?.string,
                  let peer = try? Crypto.agreementKey(jwk: epk),
                  let shared = try? key.sharedSecretFromKeyAgreement(with: peer) else { return nil }
            let secret = shared.withUnsafeBytes { Data($0) }
            guard let plain = try? Crypto.aesGcmOpen(key: secret, iv: Crypto.fromB64(iv), ct: Crypto.fromB64(ct)) else { return nil }
            return try? JSON.parse(plain)
        }
    }

    /// Sends [payload] sealed to every vault of [acta], routed to [dest]. Returns how many
    /// envelopes went; 0 = it went in the clear (migration, said through [onWarn]).
    @discardableResult
    public static func send(_ conn: ProxyConnection, dest: String, acta: JSON?, payload: JSON,
                            onWarn: (String) -> Void = { _ in }, now: Int64 = nowMs()) async throws -> Int {
        let keys = vaultKeys(acta)
        var encPubs: [String] = []
        var lastCode = keys.isEmpty ? "no-vault-key" : "no-encpub"
        for k in keys {
            do { encPubs.append(try await conn.encPubOf(k)) } catch let e as ProxyError { lastCode = e.code } catch {}
        }
        if !encPubs.isEmpty {
            for e in encPubs { try conn.sendByPubkey(dest, try seal(payload, to: e)) }
            return encPubs.count
        }
        guard plainAllowed(now) else {
            throw VaultError("cannot seal the request to the vault (\(lastCode)): update the vault", code: "vault-unsealable")
        }
        onWarn("[vault] request \(payload["type"]?.string ?? "") sent UNSEALED (\(lastCode)): tolerated only until 2026-12-15 — update the vault")
        try conn.sendByPubkey(dest, payload)
        return 0
    }
}
