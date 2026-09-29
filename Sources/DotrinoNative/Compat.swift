import Foundation

/// WHICH VERSION I AM AND WHETHER WE CAN TALK (CONVENCIONES §14): `declare` / `check` of
/// `@dotrino/compat`. Same piece as `Compat.kt`. It never blocks: it is SHOWN.
public enum Compat {
    public struct Declaration: Sendable, Equatable {
        public let product: String, version: String, protocolVersion: Int, speaks: [Int]
        public var json: JSON {
            ["product": .string(product), "version": .string(version), "protocol": .int(Int64(protocolVersion)),
             "speaks": .array(speaks.map { .int(Int64($0)) })]
        }
    }
    /// A version known broken, by EXACT version (never a range).
    public struct Broken: Sendable {
        public let product: String, versions: [String], why: String?, fix: String?
        public init(product: String, versions: [String], why: String? = nil, fix: String? = nil) {
            self.product = product; self.versions = versions; self.why = why; self.fix = fix
        }
    }
    /// `ok` | `incompatible-protocol` | `broken-peer` | `undeclared`.
    public struct Verdict: Sendable, Equatable { public let compatible: Bool, code: String, reason: String }

    public static func declare(product: String, version: String, protocolVersion: Int, speaks: [Int]? = nil) -> Declaration {
        let s = Array(Set(speaks ?? [protocolVersion])).sorted()
        precondition(s.contains(protocolVersion), "compat: speaks must include protocol")
        return Declaration(product: product, version: version, protocolVersion: protocolVersion, speaks: s)
    }

    public static func parse(_ o: JSON?) -> Declaration? {
        guard let p = o?["product"]?.string, !p.isEmpty, let v = o?["version"]?.string, !v.isEmpty,
              let pr = o?["protocol"]?.int, let sp = o?["speaks"]?.array?.compactMap(\.int), !sp.isEmpty else { return nil }
        return Declaration(product: p, version: v, protocolVersion: Int(pr), speaks: sp.map { Int($0) })
    }

    public static func check(mine: Declaration, theirs: JSON?, broken: [Broken] = []) -> Verdict {
        guard let t = parse(theirs) else { return Verdict(compatible: false, code: "undeclared", reason: "the other side does not say what it is or which version it runs") }
        if let b = broken.first(where: { $0.product == t.product && $0.versions.contains(t.version) }) {
            return Verdict(compatible: false, code: "broken-peer", reason: "\(t.product) \(t.version) is known to be broken: \(b.why ?? "no reason recorded")" + (b.fix.map { " — \($0)" } ?? ""))
        }
        if !mine.speaks.contains(t.protocolVersion) {
            return Verdict(compatible: false, code: "incompatible-protocol", reason: "\(mine.product) \(mine.version) speaks protocol \(mine.speaks.map(String.init).joined(separator: ", ")) and \(t.product) \(t.version) speaks \(t.protocolVersion): update \(t.product)")
        }
        return Verdict(compatible: true, code: "ok", reason: "")
    }
}
