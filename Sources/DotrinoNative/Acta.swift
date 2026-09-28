import Foundation

/// What a member of a profile may do, read from its acta: the port of `memberCan` /
/// `effectiveCaps` of `@dotrino/identity` (`vault/acta.js`). Only the reading. A member is
/// found by its exact `pub` string, like the JS does. Same piece as `Acta.kt`.
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
}
