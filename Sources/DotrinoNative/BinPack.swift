import Foundation

/// BYTES THAT TRAVEL AS BYTES: `binpack.js` of `@dotrino/proxy-client` (≥ 0.35). Same piece as
/// `BinPack.kt`.
///
///  · THE PACKAGE: `[0xB1][u32 length of the JSON][JSON][the bytes]`, each byte array a
///    `{"__dotrino_bin":[from,length]}` reference in the JSON.
///  · THE FRAME: `[0x01][u32 length of the header][header JSON][body]`, the usual text frame
///    without `message`, and the `message` raw.
///
/// And the binary sealed envelope (`sealing.js`): a package `{ bv: 1, k, iv, ct }` where `k` is
/// the usual sealed envelope with `{ cek }` inside and `iv`/`ct` are the package of the message,
/// AES-GCM encrypted with that key.
///
/// The apps of this library speak JSON: the bytes of a package reach them as BASE64 strings in
/// its place (`Packed.resolved`), which is how a file travelled before the transport went binary.
public enum BinPack {
    public static let binTag = "__dotrino_bin"
    public static let binSealed: Int64 = 1
    private static let packMagic: UInt8 = 0xB1
    private static let frameVersion: UInt8 = 0x01

    public struct BadPack: Error, CustomStringConvertible {
        public let description: String
        public let code: String
    }

    /// A package, opened: its JSON (with the references) and the area they point into.
    public struct Packed: Sendable {
        public let json: JSON
        public let area: Data

        /// The bytes a reference points to, or nil if `v` is not one.
        public func bytes(of v: JSON?) -> Data? {
            guard let (off, len) = BinPack.ref(of: v) else { return nil }
            return area.subdata(in: off..<(off + len))
        }

        /// The JSON with each reference replaced by its bytes in base64 (what the apps read).
        public func resolved() -> JSON { resolve(json) }

        private func resolve(_ v: JSON) -> JSON {
            switch v {
            case .array(let a): return .array(a.map(resolve))
            case .object(let o):
                if let b = bytes(of: v) { return .string(b.base64EncodedString()) }
                return .object(o.mapValues(resolve))
            default: return v
            }
        }
    }

    static func ref(of v: JSON?) -> (Int, Int)? {
        guard let r = v?[binTag]?.array, r.count == 2, let off = r[0].int, let len = r[1].int else { return nil }
        return (Int(off), Int(len))
    }

    private static func u32(_ b: Data, at i: Int) -> Int {
        b[i..<(i + 4)].reduce(0) { ($0 << 8) | Int($1) }
    }

    public static func isPacked(_ b: Data) -> Bool { b.count >= 5 && b.first == packMagic }

    /// A package → its JSON and its bytes. Throws `bad-pack` if it is not one or a reference leaves it.
    public static func unpack(_ data: Data) throws -> Packed {
        let b = Data(data)
        guard isPacked(b) else { throw BadPack(description: "not a packed message", code: "bad-pack") }
        let len = u32(b, at: 1)
        guard 5 + len <= b.count else { throw BadPack(description: "packed message is shorter than its header says", code: "bad-pack") }
        guard let obj = try? JSON.parse(b.subdata(in: 5..<(5 + len))) else {
            throw BadPack(description: "packed message header is not JSON", code: "bad-pack")
        }
        let area = b.subdata(in: (5 + len)..<b.count)
        try checkRefs(obj, area.count)
        return Packed(json: obj, area: area)
    }

    private static func checkRefs(_ v: JSON, _ size: Int) throws {
        switch v {
        case .array(let a): try a.forEach { try checkRefs($0, size) }
        case .object(let o):
            guard let r = o[binTag] else { try o.values.forEach { try checkRefs($0, size) }; return }
            guard let a = r.array, a.count == 2, let off = a[0].int, let len = a[1].int,
                  off >= 0, len >= 0, off + len <= Int64(size) else {
                throw BadPack(description: "packed message points outside itself", code: "bad-pack")
            }
        default: break
        }
    }

    private static func be32(_ n: Int) -> Data { Data([UInt8(n >> 24 & 0xFF), UInt8(n >> 16 & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)]) }

    /// An object → a package (what goes on the wire for it). `blobs` are the bytes its `ref`s
    /// point to, in order: `pack(obj, [a, b])` with `ref(0, a.count)` and `ref(a.count, b.count)`.
    public static func pack(_ obj: JSON, _ blobs: [Data] = []) -> Data {
        let head = Data(obj.text.utf8)
        return blobs.reduce(Data([packMagic]) + be32(head.count) + head, +)
    }

    /// The `{"__dotrino_bin":[from,length]}` reference to bytes in a package's area.
    public static func ref(_ from: Int, _ length: Int) -> JSON { [binTag: .array([.int(Int64(from)), .int(Int64(length))])] }

    /// The binary sealed envelope `{ bv: 1, k, iv, ct }` as a package: `k` the sealed message key.
    public static func packSealed(k: JSON, iv: Data, ct: Data) -> Data {
        pack(["bv": .int(binSealed), "k": k, "iv": ref(0, iv.count), "ct": ref(iv.count, ct.count)], [iv, ct])
    }

    /// The proxy's binary frame → its header and its body. Throws `bad-frame` if it is not one.
    public static func decodeFrame(_ data: Data) throws -> (header: JSON, body: Data) {
        let b = Data(data)
        guard b.count >= 5, b.first == frameVersion else { throw BadPack(description: "not a binary frame of a known version", code: "bad-frame") }
        let len = u32(b, at: 1)
        guard 5 + len <= b.count else { throw BadPack(description: "binary frame is shorter than its header says", code: "bad-frame") }
        guard let header = try? JSON.parse(b.subdata(in: 5..<(5 + len))), header.object != nil else {
            throw BadPack(description: "binary frame header is not a JSON object", code: "bad-frame")
        }
        return (header, b.subdata(in: (5 + len)..<b.count))
    }

    /// The other way round: a header and a body → the frame.
    public static func encodeFrame(_ header: JSON, _ body: Data) -> Data {
        let head = Data(header.text.utf8)
        return Data([frameVersion]) + be32(head.count) + head + body
    }

    /// Is this package a binary sealed envelope (`{ bv: 1, k, iv, ct }`)?
    public static func isBinSealed(_ p: Packed) -> Bool {
        p.json["bv"]?.int == binSealed && p.json["k"]?.object != nil && ref(of: p.json["iv"]) != nil && ref(of: p.json["ct"]) != nil
    }
}
