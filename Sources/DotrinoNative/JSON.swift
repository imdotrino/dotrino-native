import Foundation

/// A JSON value. The ecosystem signs JSON, so the library needs to know exactly what it holds:
/// an integer is not a double, and `true` is not `1` (Foundation's NSNumber mixes them up).
public enum JSON: Equatable, Sendable {
    case null
    case bool(Bool)
    case int(Int64)
    case double(Double)
    case string(String)
    case array([JSON])
    case object([String: JSON])

    public struct ParseError: Error, CustomStringConvertible {
        public let description: String
    }

    public static func parse(_ text: String) throws -> JSON {
        try parse(Data(text.utf8))
    }

    public static func parse(_ data: Data) throws -> JSON {
        try from(JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]))
    }

    private static func from(_ any: Any) throws -> JSON {
        switch any {
        case is NSNull: return .null
        case let s as String: return .string(s)
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            if CFNumberIsFloatType(n) {
                let d = n.doubleValue
                // 1e3 arrives as a double from JSONSerialization but it IS an integer.
                if d.rounded() == d, abs(d) < 9_007_199_254_740_992 { return .int(Int64(d)) }
                return .double(d)
            }
            return .int(n.int64Value)
        case let a as [Any]: return .array(try a.map(from))
        case let o as [String: Any]: return .object(try o.mapValues(from))
        default: throw ParseError(description: "json: unsupported value \(type(of: any))")
        }
    }

    public subscript(key: String) -> JSON? {
        if case .object(let o) = self { return o[key] }
        return nil
    }

    public var string: String? { if case .string(let s) = self { return s }; return nil }
    public var int: Int64? { if case .int(let n) = self { return n }; return nil }
    public var bool: Bool? { if case .bool(let b) = self { return b }; return nil }
    public var object: [String: JSON]? { if case .object(let o) = self { return o }; return nil }
    public var array: [JSON]? { if case .array(let a) = self { return a }; return nil }

    /// Serialized to send. Same bytes as the canonical form when there are no fractions; a
    /// fraction is written as JS would (enough for relaying a value, never for signing it).
    public var text: String {
        if let s = try? Canonical.stringify(self) { return s }
        var out = ""
        Canonical.write(self, into: &out, allowDoubles: true)
        return out
    }
}

extension JSON: ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral,
    ExpressibleByDictionaryLiteral, ExpressibleByArrayLiteral, ExpressibleByNilLiteral {
    public init(stringLiteral value: String) { self = .string(value) }
    public init(integerLiteral value: Int64) { self = .int(value) }
    public init(booleanLiteral value: Bool) { self = .bool(value) }
    public init(dictionaryLiteral elements: (String, JSON)...) {
        self = .object(Dictionary(elements, uniquingKeysWith: { _, b in b }))
    }
    public init(arrayLiteral elements: JSON...) { self = .array(elements) }
    public init(nilLiteral: ()) { self = .null }
}

/// Milliseconds since the epoch, as `Date.now()` gives them in JS.
public func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
