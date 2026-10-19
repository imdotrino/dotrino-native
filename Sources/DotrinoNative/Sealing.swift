import Foundation

/// `identitySealing` of `@dotrino/proxy-client` (`src/sealing.js`): seal and open a directed
/// message with the IDENTITY's envelope (`encrypt`/`decrypt`, v2). Same piece as `Sealing.kt`.
///
/// Wire shape `{ app, sealed, from }`: `app` marks whose it is, `sealed` is wrapped to EVERY
/// key given (a contact's devices, from their card), and `from` is my encryption key — only its
/// holder could build the wrap, so it says WHO sealed it.
public final class IdentitySealing: @unchecked Sendable {
    public struct SealingError: Error, CustomStringConvertible {
        public let description: String
        public let code: String
    }

    /// What arrived, opened, and the encryption key of whoever sealed it.
    public struct Opened: Sendable { public let payload: JSON; public let senderEncPub: String }

    private let profile: Profile
    public let app: String

    public init(profile: Profile, app: String) { self.profile = profile; self.app = app }

    public func isSealed(_ m: JSON?) -> Bool { m?["app"]?.string == app && m?["sealed"]?.object != nil }

    /// Seals [msg] to [recipientEncPubs]. Nothing to seal to = it does not go (`unsealed`).
    public func seal(_ msg: JSON, to recipientEncPubs: [String]) throws -> JSON {
        var seen = Set<String>()
        let keys = recipientEncPubs.filter { !$0.isEmpty && seen.insert($0).inserted }
        guard !keys.isEmpty else { throw SealingError(description: "no encryption key for the other side", code: "unsealed") }
        let sealed = try profile.encrypt(keys, msg.text)
        guard let wrap = sealed["wrap"]?.object, !wrap.isEmpty else {
            throw SealingError(description: "the envelope was wrapped for nobody", code: "unsealed")
        }
        return ["app": .string(app), "sealed": sealed, "from": .string(profile.encPub)]
    }

    /// Seals `msg` as a BINARY sealed envelope (`sealing.js` of proxy-client ≥ 0.35, the way
    /// everything leaves this library): a fresh AES-256 message key sealed with `seal` as `k`, and
    /// the package of `msg` AES-GCM encrypted with it as `iv`/`ct`.
    public func sealBinary(_ msg: JSON, to recipientEncPubs: [String]) throws -> Data {
        let cek = Crypto.randomBytes(32)
        let k = try seal(["cek": .string(Crypto.b64(cek))], to: recipientEncPubs)
        let (iv, ct) = try Crypto.aesGcmSeal(key: cek, plain: BinPack.pack(msg))
        return BinPack.packSealed(k: k, iv: iv, ct: ct)
    }

    /// Opens what was sealed to me. Throws if it is not mine, not ours, or was tampered with.
    public func open(_ env: JSON) throws -> Opened {
        guard isSealed(env), let sealed = env["sealed"] else { throw SealingError(description: "not a sealed envelope of \(app)", code: "not-sealed") }
        guard let from = env["from"]?.string else { throw SealingError(description: "sealed envelope without sender key", code: "not-sealed") }
        let text = try profile.decrypt(from, sealed)
        let payload = try JSON.parse(text)
        guard payload.object != nil else { throw SealingError(description: "sealed payload is not an object", code: "bad-payload") }
        return Opened(payload: payload, senderEncPub: from)
    }

    /// Opens a BINARY sealed envelope (`{ bv: 1, k, iv, ct }`, proxy-client ≥ 0.35): `k` is the
    /// usual envelope with the message key inside, and with it the package comes out of `ct`. Its
    /// bytes reach the app as base64 (`BinPack.Packed.resolved`).
    public func openBinary(_ p: BinPack.Packed) throws -> Opened {
        guard BinPack.isBinSealed(p), let k = p.json["k"], let iv = p.bytes(of: p.json["iv"]), let ct = p.bytes(of: p.json["ct"]) else {
            throw SealingError(description: "not a binary sealed envelope", code: "not-sealed")
        }
        let key = try open(k)
        guard let cek = key.payload["cek"]?.string else { throw SealingError(description: "the sealed key of a message has no key inside", code: "bad-pack") }
        let plain: Data
        do { plain = try Crypto.aesGcmOpen(key: try Crypto.fromB64(cek), iv: iv, ct: ct) }
        catch { throw SealingError(description: "the message does not open with its key: \(error)", code: "undecipherable") }
        let payload = try BinPack.unpack(plain).resolved()
        guard payload.object != nil else { throw SealingError(description: "sealed payload is not an object", code: "bad-payload") }
        return Opened(payload: payload, senderEncPub: key.senderEncPub)
    }
}
