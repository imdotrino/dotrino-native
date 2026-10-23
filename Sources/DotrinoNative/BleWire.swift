import CryptoKit
import Foundation

/// THE BLUETOOTH WIRE (`dotrino-proxy-client/docs/bluetooth.md`): the same bytes as
/// `src/ble/wire.js`, checked against its golden vector (`vectors.json` → `ble`). Pure: no
/// radio here, only CryptoKit. `BluetoothDirect` carries it over CoreBluetooth.
public enum BleWire {
    public static let serviceUUID = "0be25149-664d-4d28-b496-3ca98a63fb00"
    public static let helloUUID = "0be25149-664d-4d28-b496-3ca98a63fb01"
    public static let beaconUUID = "0be25149-664d-4d28-b496-3ca98a63fb02"
    public static let rxUUID = "0be25149-664d-4d28-b496-3ca98a63fb03"
    public static let txUUID = "0be25149-664d-4d28-b496-3ca98a63fb04"

    /// Control frame through the proxy, sibling of WebRTC's `__cc_rtc__`.
    public static let tag = "__cc_ble__"

    public static let bidBytes = 8
    public static let bidRotateSeconds: TimeInterval = 15 * 60
    public static let beaconMaxBytes = 512
    public static let recordMaxBytes = 1024 * 1024
    public static let answerTimeout: TimeInterval = 10
    public static let findTimeout: TimeInterval = 20
    public static let tagBytes = 4

    public static let dirToPeripheral: UInt8 = 0
    public static let dirToCentral: UInt8 = 1

    public static let typeText: UInt8 = 0
    public static let typeBinary: UInt8 = 1
    public static let typeHello: UInt8 = 2

    private static let last: UInt8 = 0x01

    public struct WireError: Error, CustomStringConvertible {
        public let code: String
        public let description: String
    }

    public static func b64u(_ d: Data) -> String { Crypto.b64url(d) }
    public static func unb64u(_ s: String) throws -> Data { try Crypto.fromB64url(s) }

    public static func randomBid() -> String { b64u(Crypto.randomBytes(bidBytes)) }

    // MARK: read-only characteristics

    public static func encodeHello(_ bid: String) -> Data {
        Data(#"{"v":1,"bid":"\#(bid)"}"#.utf8)
    }

    /// The `bid` of a `hello` read, or nil when it is not this version.
    public static func decodeHello(_ d: Data) -> String? {
        guard let o = try? JSON.parse(d), o["v"]?.int == 1 else { return nil }
        return o["bid"]?.string
    }

    public static func encodeBeacon(kind: String, data: String) throws -> Data {
        guard !kind.isEmpty else { throw WireError(code: "bad-beacon", description: "bluetooth: announce needs a kind") }
        let bytes = Data(JSON.object(["kind": .string(kind), "data": .string(data)]).text.utf8)
        guard bytes.count <= beaconMaxBytes else {
            throw WireError(code: "beacon-too-big", description: "bluetooth: what is announced takes \(bytes.count) bytes; at most \(beaconMaxBytes)")
        }
        return bytes
    }

    /// `(kind, data)` of a `beacon` read, or nil when it is empty or not understood.
    public static func decodeBeacon(_ d: Data) -> (kind: String, data: String)? {
        guard !d.isEmpty, let o = try? JSON.parse(d), let k = o["kind"]?.string, let v = o["data"]?.string else { return nil }
        return (k, v)
    }

    // MARK: contact card (docs §3)

    public static let kindPair = "pair"
    public static let kindContact = "contact"
    public static let contactNickMax = 40
    public static let contactNickMaxBytes = 60

    /// A contact card that was announced: the identity's JWK string and the alias.
    public struct Contact: Equatable {
        public let publickey: String
        public let nickname: String
    }

    /// The alias cut to 40 UTF-16 units and 60 UTF-8 bytes, by whole code points, so it fits in the beacon.
    public static func trimNick(_ nickname: String) -> String {
        var out = String.UnicodeScalarView()
        var units = 0, bytes = 0
        for sc in nickname.unicodeScalars {
            let u = sc.utf16.count, b = String(sc).utf8.count
            if units + u > contactNickMax || bytes + b > contactNickMaxBytes { break }
            out.append(sc); units += u; bytes += b
        }
        return String(out)
    }

    private static let unreserved = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()".utf8)

    /// `encodeURIComponent`, byte for byte.
    private static func uriComponent(_ s: String) -> String {
        var out = ""
        for b in s.utf8 { if unreserved.contains(b) { out.append(Character(UnicodeScalar(b))) } else { out += String(format: "%%%02X", b) } }
        return out
    }

    /// The card announced with `kind: contact`: the messenger's `#to=` fragment, `to=<publickey b64url>&nick=<alias>`.
    public static func encodeContact(publickey: String, nickname: String = "") throws -> String {
        guard !publickey.isEmpty else { throw WireError(code: "bad-contact", description: "bluetooth: a contact needs its publickey") }
        let nick = trimNick(nickname)
        return "to=" + b64u(Data(publickey.utf8)) + (nick.isEmpty ? "" : "&nick=" + uriComponent(nick))
    }

    /// The card of an announced `contact`, or nil when it is not understood.
    public static func decodeContact(_ data: String) -> Contact? {
        var params: [String: String] = [:]
        for part in data.split(separator: "&", omittingEmptySubsequences: false) {
            let kv = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            if kv.count == 2, params[String(kv[0])] == nil { params[String(kv[0])] = String(kv[1]) }
        }
        guard let to = params["to"], !to.isEmpty, let raw = try? unb64u(to), let publickey = String(data: raw, encoding: .utf8),
              let jwk = try? JSON.parse(publickey), jwk["x"]?.string != nil, jwk["y"]?.string != nil else { return nil }
        let nick = params["nick"].flatMap { $0.removingPercentEncoding }.map(trimNick) ?? ""
        return Contact(publickey: publickey, nickname: nick)
    }

    // MARK: channel key

    public static func makeEphemeral() -> (privateKey: P256.KeyAgreement.PrivateKey, epub: String) {
        let k = P256.KeyAgreement.PrivateKey()
        return (k, b64u(k.publicKey.x963Representation))
    }

    /// The channel's AES key (docs §4). The order of the tokens does not depend on who calls.
    public static func deriveKey(_ mine: P256.KeyAgreement.PrivateKey, theirEpub: String, _ tokenA: String, _ tokenB: String) throws -> SymmetricKey {
        let pub = try P256.KeyAgreement.PublicKey(x963Representation: try unb64u(theirEpub))
        let secret = try mine.sharedSecretFromKeyAgreement(with: pub)
        let (lo, hi) = tokenA < tokenB ? (tokenA, tokenB) : (tokenB, tokenA)
        return secret.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data((lo + "|" + hi).utf8),
                                              sharedInfo: Data("dotrino-ble-v1".utf8), outputByteCount: 32)
    }

    // MARK: records

    private static func nonce(_ dir: UInt8, _ counter: UInt64) throws -> AES.GCM.Nonce {
        var n = Data(count: 12)
        n[0] = dir
        for i in 0..<8 { n[4 + i] = UInt8((counter >> (8 * UInt64(7 - i))) & 0xff) }
        return try AES.GCM.Nonce(data: n)
    }

    /// `AES-256-GCM(key, dir‖000‖counter, type‖body)` as WebCrypto writes it: ciphertext ‖ tag.
    public static func sealRecord(_ key: SymmetricKey, dir: UInt8, counter: UInt64, type: UInt8, body: Data) throws -> Data {
        var plain = Data([type]); plain.append(body)
        let box = try AES.GCM.seal(plain, using: key, nonce: try nonce(dir, counter))
        return box.ciphertext + box.tag
    }

    /// `(type, body)`, or throws when that key did not seal it with that counter.
    public static func openRecord(_ key: SymmetricKey, dir: UInt8, counter: UInt64, record: Data) throws -> (type: UInt8, body: Data) {
        guard record.count >= 17 else { throw WireError(code: "bad-record", description: "bluetooth: record too short") }
        let ct = record.prefix(record.count - 16), tag = record.suffix(16)
        let plain = try AES.GCM.open(try AES.GCM.SealedBox(nonce: try nonce(dir, counter), ciphertext: ct, tag: tag), using: key)
        guard let t = plain.first else { throw WireError(code: "bad-record", description: "bluetooth: empty record") }
        return (t, Data(plain.dropFirst()))
    }

    // MARK: chunks

    /// The tag of the chunks towards a central: the first 4 bytes of its `bid` (docs §5).
    public static func tagOf(_ bid: String) throws -> Data { Data(try unb64u(bid).prefix(tagBytes)) }

    public static func hasTag(_ chunk: Data, _ tag: Data) -> Bool {
        chunk.count >= 1 + tagBytes && Data(chunk.dropFirst().prefix(tagBytes)) == tag
    }

    /// Splits a record into GATT values of `chunk` bytes, header included. With `tag` (towards a
    /// central, `tx`) the header carries the tag after the flags.
    public static func toChunks(_ record: Data, chunk: Int, tag: Data? = nil) -> [Data] {
        let head = 1 + (tag == nil ? 0 : tagBytes)
        let room = max(1, chunk - head)
        let bytes = [UInt8](record)
        var out: [Data] = []
        var i = 0
        repeat {
            let part = bytes[i..<min(i + room, bytes.count)]
            i += room
            var c = Data([i >= bytes.count ? last : 0])
            if let tag { c.append(tag) }
            c.append(contentsOf: part)
            out.append(c)
        } while i < bytes.count
        return out
    }

    /// Rebuilds records from chunks that arrive in order. `push` gives the whole record, or nil.
    public final class Reassembler {
        private let skip: Int
        private var buf = Data()
        /// - Parameter skip: header bytes after the flags (the tag, on `tx`).
        public init(skip: Int = 0) { self.skip = skip }
        public func push(_ chunk: Data) throws -> Data? {
            let c = [UInt8](chunk)
            guard c.count >= 1 + skip else { return nil }
            buf.append(contentsOf: c[(1 + skip)...])
            if buf.count > BleWire.recordMaxBytes + 64 {
                buf = Data()
                throw WireError(code: "record-too-big", description: "bluetooth: record larger than 1 MiB")
            }
            guard c[0] & BleWire.last != 0 else { return nil }
            defer { buf = Data() }
            return buf
        }
    }
}
