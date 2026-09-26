import Foundation

/// Canonical JSON, byte-identical to `canonicalStringify` in `@dotrino/identity`
/// (`vault/core.js`): keys sorted, no whitespace, strings escaped as `JSON.stringify` does.
///
/// Todo lo que una bóveda firma o comprueba pasa por aquí: un byte distinto es una firma que
/// nunca verifica, y desde fuera se ve como «la bóveda no contesta».
///
/// Numbers: only integers. Everything this library signs (`ts`, `seq`, `iat`, `v`) is an
/// integer, and reproducing JS float formatting is exactly the kind of «almost the same» that
/// breaks a signature silently. A fraction throws instead.
public enum Canonical {
    public struct FractionError: Error, CustomStringConvertible {
        public let description: String
    }

    public static func stringify(_ v: JSON) throws -> String {
        if containsDouble(v) { throw FractionError(description: "canonical: non-integer numbers are not supported") }
        var out = ""
        write(v, into: &out, allowDoubles: false)
        return out
    }

    private static func containsDouble(_ v: JSON) -> Bool {
        switch v {
        case .double: return true
        case .array(let a): return a.contains(where: containsDouble)
        case .object(let o): return o.values.contains(where: containsDouble)
        default: return false
        }
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
