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

    /// Opens what was sealed to me. Throws if it is not mine, not ours, or was tampered with.
    public func open(_ env: JSON) throws -> Opened {
        guard isSealed(env), let sealed = env["sealed"] else { throw SealingError(description: "not a sealed envelope of \(app)", code: "not-sealed") }
        guard let from = env["from"]?.string else { throw SealingError(description: "sealed envelope without sender key", code: "not-sealed") }
        let text = try profile.decrypt(from, sealed)
        let payload = try JSON.parse(text)
        guard payload.object != nil else { throw SealingError(description: "sealed payload is not an object", code: "bad-payload") }
        return Opened(payload: payload, senderEncPub: from)
    }
}
