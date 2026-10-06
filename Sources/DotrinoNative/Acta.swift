import Foundation

/// The profile record (acta), read: the port of `memberCan` / `effectiveCaps` / `sealersOf` of
/// `@dotrino/identity` (`vault/acta.js`), and — to catch up with the vault ([ActaSync]) — of
/// `verifyActa` and `canAdopt`. Never sealed here. A member is found by its exact `pub`
/// string, like the JS does. Same piece as `Acta.kt`, checked against the same vectors.
public enum Acta {
    /// The capabilities this reader knows. One it does not know is not a capability FOR IT.
    public static let caps = ["sign", "store", "read", "secrets", "admin", "approve", "passwords", "passkeys", "sealer", "unattended", "replica"]

    public static func effectiveCaps(_ acta: JSON?, _ pub: String, _ extraRenounces: [JSON] = []) -> [String] {
        guard let m = acta?["members"]?.array?.first(where: { $0["pub"]?.string == pub }) else { return [] }
        var removed = Set<String>()
        for r in (acta?["renounced"]?.array ?? []) + extraRenounces where r["member"]?.string == pub {
            for c in r["caps"]?.array ?? [] { if let c = c.string { removed.insert(c) } }
        }
        return (m["caps"]?.array ?? []).compactMap(\.string).filter { caps.contains($0) && !removed.contains($0) }
    }

    public static func memberCan(_ acta: JSON?, _ pub: String, _ cap: String, _ extraRenounces: [JSON] = []) -> Bool {
        effectiveCaps(acta, pub, extraRenounces).contains(cap)
    }

    /// Records before this version named their sealer in a field, and it sealed WITHOUT the permission.
    static let vNoSealerField: Int64 = 3

    /// `sealersOf`: who may seal this profile's record — and so, whose papers count.
    public static func sealersOf(_ acta: JSON?, _ extraRenounces: [JSON] = []) -> [String] {
        guard let acta else { return [] }
        let byCap = (acta["members"]?.array ?? []).compactMap { $0["pub"]?.string }.filter { memberCan(acta, $0, "sealer", extraRenounces) }
        if let v = acta["v"]?.int, v < vNoSealerField, let field = acta["sealer"]?.string, !byCap.contains(field) { return [field] + byCap }
        return byCap
    }

    /// `canSeal`: may [pub] seal the next record of this profile?
    public static func canSeal(_ acta: JSON?, _ pub: String?) -> Bool {
        guard let acta, let pub else { return false }
        return memberCan(acta, pub, "sealer") || ((acta["v"]?.int ?? 0) < vNoSealerField && acta["sealer"]?.string == pub)
    }

    // MARK: verifying and adopting (`verifyActa`, `canAdopt`, `actaHash`)

    static let readable: ClosedRange<Int64> = 1...5
    static let sealerLinkV: Int64 = 1

    private static func isNull(_ e: JSON?) -> Bool { e == nil || e == .null }
    private static func isPub(_ e: JSON?) -> Bool { !(e?.string ?? "").isEmpty }
    /// JavaScript's truthiness, for the one rule that reads it (`else if (acta.sealSince)`).
    private static func truthy(_ e: JSON?) -> Bool {
        switch e {
        case nil, .null?: return false
        case .bool(let b)?: return b
        case .int(let n)?: return n != 0
        case .double(let d)?: return d != 0 && !d.isNaN
        case .string(let s)?: return !s.isEmpty
        default: return true
        }
    }

    private static func isChainUrl(_ u: String) -> Bool {
        guard u.count <= 300, let c = URLComponents(string: u) else { return false }
        return c.scheme == "https" && c.host != nil && c.fragment == nil && c.user == nil && c.password == nil
    }

    private static func isEncPub(_ v: String) -> Bool {
        guard let j = try? JSON.parse(v) else { return false }
        return j["kty"]?.string == "EC" && j["crv"]?.string == "P-256" && j["x"]?.string != nil && j["y"]?.string != nil
    }

    /// What is sealed and hashed: the record without its signature and its card (`actaBody`).
    public static func body(_ acta: JSON) -> JSON {
        .object((acta.object ?? [:]).filter { $0.key != "sig" && $0.key != "card" })
    }

    /// `actaHash`: hex SHA-256 of the canonical body. It is what `prev` points to.
    public static func hash(_ acta: JSON) throws -> String {
        Crypto.sha256Hex(Data(try Canonical.stringify(body(acta)).utf8))
    }

    /// `checkShape`: the reason the record is malformed, or nil.
    public static func checkShape(_ acta: JSON?) -> String? {
        guard let acta, acta.object != nil else { return "no-acta" }
        guard let v = acta["v"]?.int, readable.contains(v) else { return "version" }
        if !isPub(acta["profileId"]) || !isPub(acta["sealedBy"]) { return "shape" }
        if !isNull(acta["chainUrl"]) && !(acta["chainUrl"]?.string.map(isChainUrl) ?? false) { return "chainurl" }
        let oldField = v < vNoSealerField
        if oldField && !isPub(acta["sealer"]) { return "shape" }
        if !oldField && !isNull(acta["sealer"]) { return "sealer-no-va-en-v3" }
        guard let seq = acta["seq"]?.int, seq >= 1 else { return "seq" }
        if seq > 1 && acta["prev"]?.string == nil { return "prev" }
        guard let members = acta["members"]?.array, !members.isEmpty else { return "members" }
        for m in members {
            guard m.object != nil, isPub(m["pub"]), let caps = m["caps"]?.array else { return "member" }
            // An unknown permission is ignored, not an invalid record (it grants nothing to this reader).
            if caps.contains(where: { ($0.string ?? "").isEmpty }) { return "member" }
            if !isNull(m["cn"]) {
                guard let cn = m["cn"]?.string, cn.range(of: "^[a-z0-9-]{1,32}$", options: .regularExpression) != nil else { return "cn-invalido" }
            } else if caps.contains(where: { $0.string == "secrets" }) { return "secretos-sin-cn" }
            if !isNull(m["encPub"]) && !(m["encPub"]?.string.map(isEncPub) ?? false) { return "encpub-invalido" }
        }
        if v >= 2 {
            if !isNull(acta["sealPub"]) {
                if !isPub(acta["sealPub"]) { return "sealpub-invalido" }
                guard let since = acta["sealSince"]?.int, since >= 1, since <= seq else { return "sealsince" }
            } else if truthy(acta["sealSince"]) { return "sealsince" }
            guard let keys = acta["sealKeys"]?.array else { return "sealkeys" }
            for k in keys {
                if !isPub(k["pub"]) { return "sealkey-invalida" }
                guard let from = k["from"]?.int, let to = k["to"]?.int, from >= 1, to >= from else { return "sealkey-rango" }
            }
        }
        let pubs = members.compactMap { $0["pub"]?.string }
        if Set(pubs).count != pubs.count { return "miembro-duplicado" }
        if sealersOf(acta).isEmpty { return "sin-sellador" }
        if !isNull(acta["sealerAnchor"]) {
            guard let a = acta["sealerAnchor"], a.object != nil, let aseq = a["seq"]?.int, aseq >= 1, aseq < seq,
                  let h = a["hash"]?.string, h.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { return "sealeranchor" }
        }
        if !members.contains(where: { ($0["caps"]?.array ?? []).contains(where: { $0.string == "sign" }) }) { return "sin-firmante" }
        return nil
    }

    /// `checkSealerLink`: the sealer-chain link inside the record says the same as the record.
    public static func checkSealerLink(_ acta: JSON) -> String? {
        let v = acta["v"]?.int ?? 0
        let changed = acta["sealerChanged"]?.bool == true
        if isNull(acta["sealerLink"]) { return changed && v >= 5 ? "eslabon-ausente" : nil }
        guard let l = acta["sealerLink"], l.object != nil, l["v"]?.int == sealerLinkV else { return "eslabon-version" }
        if l["sig"]?.string == nil { return "eslabon-sin-firma" }
        if l["profileId"]?.string != acta["profileId"]?.string { return "eslabon-otro-perfil" }
        guard let seq = acta["seq"]?.int else { return "eslabon-otro-seq" }
        if changed {
            if l["seq"]?.int != seq { return "eslabon-otro-seq" }
            if l["by"]?.string != acta["sealedBy"]?.string { return "eslabon-otro-sellador" }
        } else if !((l["seq"]?.int ?? Int64.max) < seq) { return "eslabon-del-futuro" }
        let says = (l["sealers"]?.array ?? []).compactMap(\.string).sorted().joined(separator: "|")
        if says != sealersOf(acta).sorted().joined(separator: "|") { return "eslabon-no-cuadra" }
        return nil
    }

    /// `verifyActa`: well formed, signed by who says it sealed it, and its link agrees. The reason it is not, or nil.
    public static func verify(_ acta: JSON?, expectedProfileId: String? = nil) -> String? {
        if let bad = checkShape(acta) { return bad }
        guard let acta, let sig = acta["sig"]?.string else { return "sin-firma" }
        if let expectedProfileId, acta["profileId"]?.string != expectedProfileId { return "otro-perfil" }
        guard let by = acta["sealedBy"]?.string, Crypto.verify(publickey: by, data: body(acta), signature: sig) else { return "firma-invalida" }
        return checkSealerLink(acta)
    }

    /// `canAdopt` (§2.4.1): may [candidate] replace [current]? A greater `seq` sealed by a sealer
    /// of mine (and chained, if it is the next one); the same `seq` only by the hash tie-break;
    /// never back. Returns (adopt, reason) with the JS reasons.
    public static func canAdopt(_ candidate: JSON, _ current: JSON?) -> (adopt: Bool, reason: String) {
        if let bad = verify(candidate, expectedProfileId: current?["profileId"]?.string) { return (false, bad) }
        guard let current else { return (true, "sin-acta-previa") }
        let cs = candidate["seq"]?.int ?? 0, ks = current["seq"]?.int ?? 0
        let by = candidate["sealedBy"]?.string
        guard let hc = try? hash(candidate), let hk = try? hash(current) else { return (false, "hash") }
        if cs > ks {
            if !canSeal(current, by) { return (false, "sellador-no-autorizado") }
            if cs == ks + 1 && candidate["prev"]?.string != hk { return (false, "no-encadena") }
            return (true, "seq-mayor")
        }
        if cs == ks {
            if hc == hk { return (false, "misma-acta") }
            if by != current["sealedBy"]?.string && !canSeal(current, by) { return (false, "otro-sellador") }
            return (hc < hk, "desempate-hash")
        }
        return (false, "seq-menor")
    }

    /// `adoptChain`: catch up link by link, each judged against the one adopted before it (never
    /// a blind jump). Returns the newest adopted record, or nil when none fits.
    public static func adoptChain(_ chain: [JSON], _ current: JSON?) -> JSON? {
        var cur = current, adopted: JSON?
        for a in chain.enumerated().sorted(by: { ($0.element["seq"]?.int ?? 0, $0.offset) < ($1.element["seq"]?.int ?? 0, $1.offset) }).map(\.element)
        where canAdopt(a, cur).adopt { cur = a; adopted = a }
        return adopted
    }
}
