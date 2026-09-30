import CryptoKit
import Foundation

/// THE PROFILE OF THIS PHONE, as the identity (`id.dotrino.com`) keeps it: the port of what an
/// app asks of `@dotrino/identity` — who I am (`me.publickey`), my encryption key, `signData`
/// and `encrypt`/`decrypt` — for NATIVE apps, without a WebView (CONVENCIONES §16.2). Same
/// piece as `Profile.kt`.
///
/// It does not create profiles nor keys: it READS the identity's store (the one the WebView of
/// the Dotrino app writes, `vault/nativeStore.js`) and asks the chip to sign or to agree with
/// the key that store points to: one phone, one device of the acta.
///
/// It obeys the identity's rule: this device signs for the profile only if its acta says so
/// (`sign`). If not, the identity would ask the vault to sign; that path is not ported yet, and
/// here it stops with `needs-vault-signer` — it never signs anyway.
public final class Profile: @unchecked Sendable {
    public struct ProfileError: Error, CustomStringConvertible {
        public let description: String
        public let code: String
        init(_ d: String, code: String) { description = d; self.code = code }
    }

    /// `me.publickey`: the JWK string exactly as the identity writes it (the acta compares it as is).
    public let publickey: String
    /// My encryption key (JWK string): what others seal to.
    public let encPub: String
    private let acta: JSON?
    private let renounces: [JSON]
    private let keys: DeviceKeys
    /// The id of this profile in the identity's store (`dotrino.identity.current`); nil for a profile made in hand.
    public let pid: String?
    /// The last sealed actas (`dotrino.identity.acta.history`): the links of the sealer chain.
    private let history: [JSON]
    /// The link with the owner's vault, when this phone is paired (`dotrino.identity.vault.cert`).
    public let vault: VaultLink?
    /// The profile's NAME (`me.nickname`), what the web topbar and the profile list show.
    public let name: String?
    /// The photo the person uploaded (`me.avatar`, a data-URI); nil = the identicon of `avatarSeed`.
    public let avatar: String?
    /// What the identicon is drawn from: the key the profile list gives, as the web does.
    public let avatarSeed: String

    /// `{ cert, master, proxy, deviceId }`: whom to ask, through which proxy, and my paper.
    public struct VaultLink: Sendable {
        public let master: String, proxy: String, cert: JSON, deviceId: String
        public init(master: String, proxy: String, cert: JSON, deviceId: String) { self.master = master; self.proxy = proxy; self.cert = cert; self.deviceId = deviceId }
    }

    private init(publickey: String, encPub: String, acta: JSON?, renounces: [JSON], keys: DeviceKeys, pid: String? = nil, history: [JSON] = [], vault: VaultLink? = nil,
                 name: String? = nil, avatar: String? = nil, avatarSeed: String? = nil) {
        self.publickey = publickey; self.encPub = encPub; self.acta = acta; self.renounces = renounces; self.keys = keys; self.pid = pid; self.history = history; self.vault = vault
        self.name = name; self.avatar = avatar; self.avatarSeed = avatarSeed ?? publickey
    }

    /// This device's key, for the vault client (the phone talks to its vault as the profile's key).
    var deviceKeys: DeviceKeys { keys }

    /// `myContentKey`: the NEWEST generation of the content key wrapped to me. Nil = not held (yet).
    public func contentKey() -> (gen: Int, cek: String)? {
        let ring = (acta?["keyring"]?.array ?? []).sorted { ($0["gen"]?.int ?? 0) > ($1["gen"]?.int ?? 0) }
        for g in ring {
            guard let w = g["wraps"]?[publickey], let gen = g["gen"]?.int, let cek = try? Crypto.openWrap(w, keys: keys) else { continue }
            return (Int(gen), cek)
        }
        return nil
    }

    /// `decryptWithKeyring`: content encrypted with any generation this device holds.
    public func decryptWithKeyring(_ envelope: JSON) throws -> String {
        guard let g = (acta?["keyring"]?.array ?? []).first(where: { $0["gen"]?.int == envelope["gen"]?.int }), let w = g["wraps"]?[publickey] else {
            throw ProfileError("this device does not hold the key for that content generation", code: "no-content-key")
        }
        return try Crypto.decryptWithCek(try Crypto.openWrap(w, keys: keys), envelope: envelope)
    }

    /// WHO I AM to others: the acta's `profileId` (a rating from the phone and one from the PC
    /// are the same person) or, without an acta, my own key.
    public var profileId: String { acta?["profileId"]?.string ?? publickey }

    /// `sealerChain` of the identity: the actas where the sealer changed, oldest first, with the
    /// current one at the end. What proves THIS device speaks for `profileId`.
    public func sealerChain() -> [JSON] {
        guard let cur = acta else { return [] }
        var bySeq: [Int64: JSON] = [:]
        for a in history + [cur] { if let s = a["seq"]?.int { bySeq[s] = a } }
        let links = bySeq.values.filter { $0["sealerChanged"]?.bool == true }.sorted { ($0["seq"]?.int ?? 0) < ($1["seq"]?.int ?? 0) }
        if let last = links.last, last["seq"]?.int == cur["seq"]?.int { return links }
        return links + [cur]
    }

    /// The whole signing package of the identity's `signData`: `{ signature, publickey,
    /// profileId, chain }` — what a registry needs to check this device signs for the person.
    public func signPackage(_ data: JSON) throws -> JSON {
        ["signature": .string(try signData(data)), "publickey": .string(publickey),
         "profileId": .string(profileId), "chain": .array(sealerChain())]
    }

    /// `profileCard` of the identity: the SIGNED list of this person's devices, from the acta.
    /// Others use it to seal to every device and not just this one. Nil without an acta.
    public var card: JSON? { acta?["card"]?.objectValue }

    public static let current = "dotrino.identity.current"

    private static func scoped(_ pid: String, _ k: String) -> String {
        k.replacingOccurrences(of: "dotrino.identity.", with: "dotrino.identity.p.\(pid).", options: .anchored)
    }

    /// The text of the object field `name` of a JSON record, AS WRITTEN (key order kept): the
    /// identity compares public keys by that exact string. A JWK has no nested objects.
    static func rawObject(_ text: String, _ name: String) -> String? {
        guard let r = text.range(of: "\"\(name)\":"), let open = text[r.upperBound...].firstIndex(of: "{"),
              let close = text[open...].firstIndex(of: "}") else { return nil }
        return String(text[open...close])
    }

    /// The active profile from the identity's store items (`kv:…`, `key:…`). [keysFor] gives the
    /// chip key of a `kid`. Stops with `no-profile` or `no-profile-keys`.
    public static func load(_ items: [String: String], keysFor: (String) throws -> DeviceKeys) throws -> Profile {
        guard let pid = items["kv:\(current)"] else { throw ProfileError("this phone has no Dotrino profile yet", code: "no-profile") }
        let signText = items["key:" + scoped(pid, "dotrino.identity.keypair")]
        let encText = items["key:" + scoped(pid, "dotrino.identity.enc-keypair")]
        guard let signText, let encText,
              let sign = try? JSON.parse(signText), let enc = try? JSON.parse(encText),
              let kid = sign["external"]?.string, enc["external"]?.string == kid,
              let pub = rawObject(signText, "publicJwk"), let encPub = rawObject(encText, "publicJwk")
        else { throw ProfileError("the active profile has no keys in this phone's chip", code: "no-profile-keys") }
        let acta = items["kv:" + scoped(pid, "dotrino.identity.acta")].flatMap { try? JSON.parse($0) }
        let renounces = items["kv:" + scoped(pid, "dotrino.identity.renounced")].flatMap { try? JSON.parse($0) }?.array ?? []
        let history = items["kv:" + scoped(pid, "dotrino.identity.acta.history")].flatMap { try? JSON.parse($0) }?.array ?? []
        // The vault link, only when the device that talks to the vault IS this profile's key.
        var link: VaultLink?
        if let v = items["kv:" + scoped(pid, "dotrino.identity.vault.cert")].flatMap({ try? JSON.parse($0) }),
           let master = v["master"]?.string, let proxy = v["proxy"]?.string, let cert = v["cert"], cert.object != nil {
            let d = items["kv:" + scoped(pid, "dotrino.identity.vault.device")].flatMap { try? JSON.parse($0) }
            let same = d == nil || d?["useIdentityKey"]?.bool == true || (Delegation.samePubkey(d?["publickey"]?.string, pub) && d?["privateJwk"] == nil)
            if same { link = VaultLink(master: master, proxy: proxy, cert: cert, deviceId: v["deviceId"]?.string ?? "") }
        }
        // WHO IT LOOKS LIKE: the same name and picture as the web topbar (`me` of this profile,
        // and the key of its entry in the profile list for the identicon).
        let me = items["kv:" + scoped(pid, "dotrino.identity.me")].flatMap { try? JSON.parse($0) }
        let entry = items["kv:dotrino.identity.profiles"].flatMap { try? JSON.parse($0) }?.array?.first { $0["id"]?.string == pid }
        let name = [me?["nickname"]?.string, entry?["name"]?.string].compactMap { $0 }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let avatar = me?["avatar"]?.string.flatMap { $0.hasPrefix("data:image/") ? $0 : nil }
        return Profile(publickey: pub, encPub: encPub, acta: acta, renounces: renounces, keys: try keysFor(kid), pid: pid, history: history, vault: link,
                       name: name, avatar: avatar, avatarSeed: entry?["pubkey"]?.string)
    }

    /// For tests and headless tools: a profile from keys in hand.
    public static func of(_ keys: DeviceKeys, acta: JSON? = nil, vault: VaultLink? = nil) -> Profile {
        Profile(publickey: keys.publickey, encPub: keys.encPub, acta: acta, renounces: [], keys: keys, vault: vault)
    }

    /// `encKeyId` of the identity: the first 16 hex of the key's id.
    public static func encKeyId(_ encPub: String) throws -> String { String(try Delegation.pubkeyId(encPub).prefix(16)) }

    /// May this device sign for the profile? No acta = a profile of one device, which signs.
    public var canSign: Bool { acta == nil || Acta.memberCan(acta, publickey, "sign", renounces) }

    /// `signData` of the identity: P1363 base64 signature over the canonical text of [data].
    /// `identify` is always signed here — it identifies this connection (same rule as the identity).
    public func signData(_ data: JSON) throws -> String {
        if !canSign && data["op"]?.string != "identify" {
            throw ProfileError("this device does not sign for your profile; the vault would have to (not supported in native apps yet)", code: "needs-vault-signer")
        }
        return try keys.sign(Canonical.stringify(data))
    }

    /// `encrypt` of the identity (envelope v2): a fresh AES key for the text, wrapped to each
    /// recipient with the ECDH between MY encryption key and theirs, under the id of their key.
    public func encrypt(_ recipientEncPubs: [String], _ plaintext: String) throws -> JSON {
        guard !recipientEncPubs.isEmpty else { throw ProfileError("recipients required", code: "no-recipients") }
        let k = Crypto.randomBytes(32)
        let (iv, ct) = try Crypto.aesGcmSeal(key: k, plain: Data(plaintext.utf8))
        var wrap: [String: JSON] = [:]
        for encPub in Set(recipientEncPubs) {
            let shared = try keys.agree(Crypto.agreementKey(jwk: encPub))
            let (wiv, wct) = try Crypto.aesGcmSeal(key: shared, plain: k)
            wrap[try Self.encKeyId(encPub)] = ["iv": .string(Crypto.b64(wiv)), "ct": .string(Crypto.b64(wct))]
        }
        return ["v": 2, "iv": .string(Crypto.b64(iv)), "ct": .string(Crypto.b64(ct)), "wrap": .object(wrap)]
    }

    /// `decrypt` of the identity: my wrap (by the id of my key), then the text. Throws if not mine.
    public func decrypt(_ senderEncPub: String, _ envelope: JSON) throws -> String {
        guard let v = envelope["v"]?.int, v == 1 || v == 2 else { throw ProfileError("unsupported envelope", code: "bad-envelope") }
        guard let mine = envelope["wrap"]?[try Self.encKeyId(encPub)],
              let wiv = mine["iv"]?.string, let wct = mine["ct"]?.string,
              let iv = envelope["iv"]?.string, let ct = envelope["ct"]?.string
        else { throw ProfileError("this device is not among the message recipients", code: "not-for-me") }
        let shared = try keys.agree(Crypto.agreementKey(jwk: senderEncPub))
        let k = try Crypto.aesGcmOpen(key: shared, iv: Crypto.fromB64(wiv), ct: Crypto.fromB64(wct))
        let pt = try Crypto.aesGcmOpen(key: k, iv: Crypto.fromB64(iv), ct: Crypto.fromB64(ct))
        guard let s = String(data: pt, encoding: .utf8) else { throw ProfileError("not utf-8", code: "bad-envelope") }
        return s
    }
}

extension Profile {
    /// The profile of THIS phone: the identity's store and its chip keys, shared by every
    /// Dotrino app of the team (`SharedStorage.share` first). `no-profile` when there is none.
    public static func fromPhone() throws -> Profile {
        try load(try IdentityStore.shared.all()) { try EnclaveKeys.open($0) }
    }

    /// The app's TRANSPORT key: it signs this app's channel entries (`proxy-client`'s own
    /// keypair). It is the app's, not the person's: no profile needed.
    public static func transportKey(app: String) throws -> DeviceKeys {
        let id = "transport.\(app)"
        return EnclaveKeys.exists(id) ? try EnclaveKeys.open(id) : try EnclaveKeys.create(id)
    }
}
