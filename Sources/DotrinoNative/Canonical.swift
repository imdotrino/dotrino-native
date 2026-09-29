import Foundation

/// Canonical JSON, byte-identical to `canonicalStringify` in `@dotrino/identity`
/// (`vault/core.js`): keys sorted, no whitespace, strings escaped as `JSON.stringify` does.
///
/// Todo lo que una bóveda firma o comprueba pasa por aquí: un byte distinto es una firma que
/// nunca verifica, y desde fuera se ve como «la bóveda no contesta».
///
/// Numbers: integers, and DECIMALS only as JS writes them — plain notation, shortest form (a
/// rating of 4.5 is `4.5`). Swift's `String(Double)` is also the shortest round-trip form, so in
/// plain notation both agree; an exponent (`1e-07`) is where they would not, and that throws
/// instead of producing «almost the same» text that breaks a signature silently.
public enum Canonical {
    public struct FractionError: Error, CustomStringConvertible {
        public let description: String
    }

    public static func stringify(_ v: JSON) throws -> String {
        if containsDouble(v) { throw FractionError(description: "canonical: a number that cannot be written as JS writes it") }
        var out = ""
        write(v, into: &out, allowDoubles: false)
        return out
    }

    private static func containsDouble(_ v: JSON) -> Bool {
        switch v {
        case .double(let d): return !isPlainDecimal(String(d))
        case .array(let a): return a.contains(where: containsDouble)
        case .object(let o): return o.values.contains(where: containsDouble)
        default: return false
        }
    }

    /// `4.5`, `-0.25`, `0.001`: what JS prints for a finite non-integer in its plain range.
    static func isPlainDecimal(_ s: String) -> Bool {
        s.range(of: #"^-?(0|[1-9][0-9]*)\.[0-9]*[1-9]$"#, options: .regularExpression) != nil
    }

    static func write(_ v: JSON, into out: inout String, allowDoubles: Bool) {
        switch v {
        case .null: out += "null"
        case .bool(let b): out += b ? "true" : "false"
        case .int(let n): out += String(n)
        case .double(let d): out += String(d)
        case .string(let s): quote(s, into: &out)
        case .array(let a):
            out += "["
            for (i, e) in a.enumerated() {
                if i > 0 { out += "," }
                write(e, into: &out, allowDoubles: allowDoubles)
            }
            out += "]"
        case .object(let o):
            out += "{"
            // JS `Object.keys(v).sort()` compares UTF-16 code units; Swift's `<` does not.
            let keys = o.keys.sorted { Array($0.utf16).lexicographicallyPrecedes(Array($1.utf16)) }
            for (i, k) in keys.enumerated() {
                if i > 0 { out += "," }
                quote(k, into: &out)
                out += ":"
                write(o[k]!, into: &out, allowDoubles: allowDoubles)
            }
            out += "}"
        }
    }

    /// `JSON.stringify` for a string: `\" \\ \b \f \n \r \t`, other controls as `\u00xx`, the rest raw.
    public static func quote(_ s: String, into out: inout String) {
        out += "\""
        for u in s.unicodeScalars {
            switch u {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0C}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if u.value < 0x20 { out += String(format: "\\u%04x", u.value) } else { out.unicodeScalars.append(u) }
            }
        }
        out += "\""
    }
}
