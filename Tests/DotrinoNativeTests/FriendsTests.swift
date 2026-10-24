import CryptoKit
import DotrinoNative
import XCTest

/// Los amigos con instancias REALES y sellado REAL sobre un proxio en memoria: los casos de
/// solicitud que antes probaba el messenger (`EngineTests`), ahora de la librería.
final class FriendsTests: XCTestCase {
    struct SoftKeys: DeviceKeys {
        let s = P256.Signing.PrivateKey(), e = P256.KeyAgreement.PrivateKey()
        var publickey: String { Crypto.jwk(raw: s.publicKey.rawRepresentation) }
        var encPub: String { Crypto.jwk(raw: e.publicKey.rawRepresentation) }
        func signBytes(_ bytes: Data) throws -> String { Crypto.b64(try s.signature(for: bytes).rawRepresentation) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data { try e.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) } }
    }

    final class Net: @unchecked Sendable {
        var phones: [String: Phone] = [:]
        var codes: [String: String] = [:]
    }

    final class Phone: FriendsTransport, @unchecked Sendable {
        let net: Net, name: String, token: String
        let profile: Profile
        lazy var sealing = IdentitySealing(profile: profile, app: "messenger")
        var listeners: [UUID: (SealedSession.Message) -> Void] = [:]
        /// Cada tipo de mensaje que llegó a este aparato.
        var got: [String] = []
        init(_ net: Net, _ name: String, keys: SoftKeys = SoftKeys(), acta: JSON? = nil) {
            self.net = net; self.name = name; token = "T-\(name)"; profile = Profile.of(keys, acta: acta); net.phones[token] = self
        }
        func deliver(to: Phone, _ payload: JSON, _ keys: [String]) throws {
            let env = try sealing.seal(payload, to: keys.isEmpty ? [to.profile.encPub] : keys)
            guard let o = try? to.sealing.open(env) else { return }
            to.got.append(o.payload["type"]?.string ?? "?")
            let m = SealedSession.Message(fromToken: token, fromPubkey: profile.publickey, payload: o.payload, senderEncPub: o.senderEncPub, queued: false, queuedAt: nil)
            to.listeners.values.forEach { $0(m) }
        }
        func sendSealed(toToken token: String, _ payload: JSON, recipientEncPubs: [String], peerPubkey: String?) async throws { try deliver(to: net.phones[token]!, payload, recipientEncPubs) }
        func sendSealed(toPubkey pubkey: String, _ payload: JSON, recipientEncPubs: [String], quiet: Bool) async throws {
            try deliver(to: net.phones.values.first { $0.profile.publickey == pubkey }!, payload, recipientEncPubs)
        }
        func encPubOf(_ publickey: String) async throws -> String { net.phones.values.first { $0.profile.publickey == publickey }!.profile.encPub }
        func whoIs(_ token: String) async throws -> String? { net.phones[token]?.profile.publickey }
        func requestPairingCode() async throws -> ProxyConnection.PairingCode {
            let c = "C" + String(name.uppercased().prefix(5)).padding(toLength: 5, withPad: "X", startingAt: 0)
            net.codes[c] = token
            return .init(code: c, expiresAt: nowMs() + 60_000)
        }
        func redeemPairingCode(_ code: String) async throws -> String {
            guard let t = net.codes.removeValue(forKey: code) else { throw ProxyError("bad code", code: "pair-invalid") }
            return t
        }
        var isOnline: Bool { true }
        func onMessage(_ l: @escaping (SealedSession.Message) -> Void) -> () -> Void { let k = UUID(); listeners[k] = l; return { [weak self] in self?.listeners[k] = nil } }
        func onOnline(_ l: @escaping () -> Void) -> () -> Void { {} }
        func onPeerGone(_ l: @escaping (String) -> Void) -> () -> Void { {} }
    }

    /// Una persona: su aparato, su libreta y sus amigos, escuchando lo que le llega.
    final class Person: @unchecked Sendable {
        let phone: Phone, peers: PeerBook, friends: Friends
        var requests: [Friends.Request] = []
        var others: [String] = []      // lo que no era del protocolo de amigos y llegó de un amigo
        init(_ net: Net, _ name: String, keys: SoftKeys = SoftKeys(), acta: JSON? = nil, pinned: [Friends.Pinned] = []) async {
            phone = Phone(net, name, keys: keys, acta: acta)
            peers = PeerBook(storage: PeerBook.MemoryStorage(), profile: phone.profile)
            friends = Friends(transport: phone, profile: phone.profile, peers: peers, kv: MemoryKeyValueStore(),
                              nickname: { name }, version: nil, pinned: pinned)
            var h = Friends.Hooks()
            h.onRequest = { [weak self] r in self?.requests.append(r) }
            await friends.setHooks(h)
            _ = phone.onMessage { [weak self] m in
                guard let self else { return }
                Task { if let r = await self.friends.receive(m), !r.handled, r.who.contact != nil { self.others.append(m.payload["type"]?.string ?? "?") } }
            }
        }
        var pubkey: String { phone.profile.publickey }
        func people() async -> [String] {
            await friends.contacts().compactMap { $0["publickey"]?.string }.filter { !friends.isPinned($0) }
        }
    }

    private func until(_ what: String, _ f: () async -> Bool) async {
        for _ in 0..<250 { if await f() { return }; try? await Task.sleep(nanoseconds: 20_000_000) }
        XCTFail("timed out: \(what)")
    }

    /// Una cuenta con su llave de perfil `owner` y estos aparatos: su tarjeta, firmada por el dueño.
    private func cardFor(_ owner: SoftKeys, _ devices: SoftKeys...) throws -> JSON {
        var body: [String: JSON] = ["v": 1, "profileId": .string(owner.publickey), "seq": 1, "sealedBy": .string(owner.publickey), "iat": 1]
        body["keys"] = .array(devices.map { .object(["pub": .string($0.publickey), "encPub": .string($0.encPub)]) })
        let text = try Canonical.stringify(.object(body))
        body["sig"] = .string(Crypto.b64(try owner.s.signature(for: Data(text.utf8)).rawRepresentation))
        return .object(body)
    }
    private func acta(_ card: JSON) -> JSON { .object(["card": card]) }

    func testARequestGoesToTheInboxAndAcceptingMakesFriendsOnBothSides() async throws {
        let net = Net()
        let ana = await Person(net, "Ana"), beto = await Person(net, "Beto")
        await ana.friends.refreshCode()
        let c0 = await ana.friends.code
        let code = try XCTUnwrap(c0)
        try await beto.friends.requestByCode(code.lowercased(), alias: "Anita")
        await until("request") { await ana.friends.incoming.count == 1 }
        XCTAssertEqual(ana.requests.count, 1, "the app was told")
        let none = await ana.people()
        XCTAssertTrue(none.isEmpty, "a stranger is not in the book until accepted")
        let renewed = await ana.friends.code
        XCTAssertNotEqual(renewed, nil)
        await ana.friends.accept(beto.pubkey)
        await until("beto has ana") { await !beto.people().isEmpty }
        let b = try XCTUnwrap(try beto.peers.get(ana.pubkey))
        XCTAssertEqual(b["nickname"]?.string, "Anita", "with the alias he chose")
        let left = await beto.friends.outgoing
        XCTAssertTrue(left.isEmpty)
    }

    func testCodeErrorsSayWhy() async throws {
        let net = Net()
        let ana = await Person(net, "Ana")
        await ana.friends.refreshCode()
        let c0 = await ana.friends.code
        let mine = try XCTUnwrap(c0)
        do { try await ana.friends.requestByCode(mine, alias: ""); XCTFail("own code") } catch let e as Friends.FriendsError { XCTAssertEqual(e.code, "own") }
        do { try await ana.friends.requestByCode("xx", alias: ""); XCTFail("bad code") } catch let e as Friends.FriendsError { XCTAssertEqual(e.code, "invalid") }
        do { try await ana.friends.requestByCode("N2P4QR", alias: ""); XCTFail("unknown code") } catch let e as Friends.FriendsError { XCTAssertEqual(e.code, "invalid") }
        XCTAssertEqual(Friends.codeFrom("https://messenger.dotrino.com/#add=k7m-2q9"), "K7M2Q9")
        XCTAssertEqual(Friends.normalizeCode("s0"), "5O")
    }

    func testBothAskingIsEnough() async throws {
        let net = Net()
        let ana = await Person(net, "Ana"), beto = await Person(net, "Beto")
        try await ana.friends.request(pubkey: beto.pubkey, alias: "")
        await until("beto got it") { await beto.friends.incoming.count == 1 }
        try await beto.friends.request(pubkey: ana.pubkey, alias: "")   // she had asked: this accepts
        await until("both friends") { let a = await ana.people(), b = await beto.people(); return !a.isEmpty && !b.isEmpty }
    }

    func testAStrangersMessageAndAnUnaskedAcceptGoNowhere() async throws {
        let net = Net()
        let ana = await Person(net, "Ana"), carla = await Person(net, "Carla")
        try carla.phone.deliver(to: ana.phone, ["type": "DM", "text": "hola", "mid": "m1", "ts": 1], [])
        try carla.phone.deliver(to: ana.phone, ["type": "CONTACT_ACCEPT", "nickname": "Carla"], [])
        try await Task.sleep(nanoseconds: 300_000_000)
        XCTAssertTrue(ana.others.isEmpty)
        let p = await ana.people()
        XCTAssertTrue(p.isEmpty, "nobody adds themselves")
    }

    func testABlockedOnesRequestDoesNotArrive() async throws {
        let net = Net()
        let ana = await Person(net, "Ana"), eva = await Person(net, "Eva")
        _ = try ana.peers.addContact(eva.pubkey, nickname: "Eva")
        _ = try ana.peers.setBlocked(eva.pubkey, true)
        try await eva.friends.request(pubkey: ana.pubkey, alias: "")
        try await Task.sleep(nanoseconds: 300_000_000)
        let inc = await ana.friends.incoming
        XCTAssertTrue(inc.isEmpty)
        XCTAssertFalse(eva.phone.got.contains("CONTACT_ACCEPT"), "nothing is answered")
    }

    func testAFriendIsTheirAccountAndEveryDeviceGetsWhatIsSent() async throws {
        let net = Net(), owner = SoftKeys(), k1 = SoftKeys(), k2 = SoftKeys()
        let card = try cardFor(owner, k1, k2)
        let ana = await Person(net, "Ana"), b1 = await Person(net, "Beto1", keys: k1, acta: acta(card)), b2 = await Person(net, "Beto2", keys: k2, acta: acta(card))
        await ana.friends.refreshCode()
        let c0 = await ana.friends.code
        try await b1.friends.requestByCode(try XCTUnwrap(c0), alias: "")
        await until("request") { await ana.friends.incoming.count == 1 }
        let req = await ana.friends.incoming
        XCTAssertEqual(req.first?.pubkey, owner.publickey, "kept by the account, not the device")
        await ana.friends.accept(owner.publickey)
        await until("beto sees ana") { await !b1.people().isEmpty }
        let people = await ana.people()
        XCTAssertEqual(people, [owner.publickey])
        try await ana.friends.send(owner.publickey, ["type": "DM", "text": "a los dos", "mid": "x", "ts": 1])
        await until("both devices got it") { b1.phone.got.contains("DM") && b2.phone.got.contains("DM") }
    }

    func testALinkWithTheCardReachesEveryDeviceAndEitherAccepts() async throws {
        let net = Net(), owner = SoftKeys(), k1 = SoftKeys(), k2 = SoftKeys()
        let card = try cardFor(owner, k1, k2)
        let ana = await Person(net, "Ana"), b1 = await Person(net, "Beto1", keys: k1, acta: acta(card)), b2 = await Person(net, "Beto2", keys: k2, acta: acta(card))
        try await ana.friends.request(pubkey: k1.publickey, alias: "Beto", card: card)
        await until("both devices got the request") {
            let r1 = await b1.friends.incoming, r2 = await b2.friends.incoming
            return !r1.isEmpty && !r2.isEmpty
        }
        await b2.friends.accept(ana.pubkey)                // accepted on the OTHER device
        await until("ana has beto by his account") { await ana.people() == [owner.publickey] }
        let rec = try XCTUnwrap(try ana.peers.get(owner.publickey))
        XCTAssertEqual(rec["nickname"]?.string, "Beto")
    }

    func testAPinnedAccountIsAcceptedAloneAndComesBack() async throws {
        let net = Net(), owner = SoftKeys(), d1 = SoftKeys()
        let ana = await Person(net, "Ana", pinned: [.init(publickey: owner.publickey, nickname: "Dotrino")])
        let dotrino = await Person(net, "Dotrino", keys: d1, acta: acta(try cardFor(owner, d1)))
        let cs = await ana.friends.contacts()
        XCTAssertTrue(cs.contains { ana.friends.isPinned($0["publickey"]?.string) })
        let none = await ana.friends.recipientsOf(owner.publickey)
        XCTAssertTrue(none.isEmpty, "no device of a pinned account is known before it writes")
        try await dotrino.friends.request(pubkey: ana.pubkey, alias: "Ana")
        await until("ana accepted it by herself") { await !dotrino.people().isEmpty }
        let inc = await ana.friends.incoming
        XCTAssertTrue(inc.isEmpty, "never in the inbox")
        _ = try ana.peers.removeContact(owner.publickey)
        let back = await ana.friends.contacts()
        XCTAssertTrue(back.contains { ana.friends.isPinned($0["publickey"]?.string) }, "it comes back")
    }

    func testAFriendKeptByADeviceMovesToItsAccount() async throws {
        let net = Net(), owner = SoftKeys(), k1 = SoftKeys()
        let ana = await Person(net, "Ana"), b1 = await Person(net, "Beto", keys: k1, acta: acta(try cardFor(owner, k1)))
        _ = try ana.peers.addContact(b1.pubkey, nickname: "Beto de antes")
        _ = try b1.peers.addContact(ana.pubkey, nickname: "Ana")
        let moved = Moved()
        var h = Friends.Hooks()
        h.onMoved = { from, to in moved.set(from, to) }
        await ana.friends.setHooks(h)
        try await b1.friends.send(ana.pubkey, await b1.friends.me("HELLO"))
        await until("moved to the account") { await ana.people() == [owner.publickey] }
        XCTAssertEqual(moved.pair?.0, b1.pubkey)
        XCTAssertEqual(moved.pair?.1, owner.publickey)
        let rec = try XCTUnwrap(try ana.peers.get(owner.publickey))
        XCTAssertEqual(rec["nickname"]?.string, "Beto de antes")
    }

    final class Moved: @unchecked Sendable {
        private(set) var pair: (String, String)?
        func set(_ a: String, _ b: String) { pair = (a, b) }
    }

    func testTheLinkIsParsed() throws {
        let key = #"{"kty":"EC","crv":"P-256","x":"a","y":"b"}"#
        let b64 = Data(key.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        let l = try XCTUnwrap(FriendLink.from("to=\(b64)&nick=Ana%20%C3%B1"))
        XCTAssertEqual(l.pubkey, key)
        XCTAssertEqual(l.nickname, "Ana ñ")
        XCTAssertNil(FriendLink.from("add=K7M2Q9"))
    }
}
