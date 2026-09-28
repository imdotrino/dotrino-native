import CryptoKit
import XCTest
@testable import DotrinoNative

/// The profile, the acta and the broadcast link against what the JS pilar makes (vectors.json).
/// The same cases as `ProfileTest.kt`.
final class ProfileTests: XCTestCase {
    struct SoftKeys: DeviceKeys {
        let s: P256.Signing.PrivateKey
        let e: P256.KeyAgreement.PrivateKey
        var publickey: String { Crypto.jwk(raw: s.publicKey.rawRepresentation) }
        var encPub: String { Crypto.jwk(raw: e.publicKey.rawRepresentation) }
        func signBytes(_ bytes: Data) throws -> String { Crypto.b64(try s.signature(for: bytes).rawRepresentation) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data {
            try e.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
        }
        static func fresh() -> SoftKeys { SoftKeys(s: .init(), e: .init()) }
    }

    private func vectors() throws -> JSON {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        return try JSON.parse(Data(contentsOf: url))
    }

    func testOpensWhatAJsIdentityEncrypted() throws {
        let p = try XCTUnwrap(try vectors()["profile"])
        let e = try P256.KeyAgreement.PrivateKey(rawRepresentation: Crypto.fromB64url(p["encPrivateJwk"]!["d"]!.string!))
        let profile = Profile.of(SoftKeys(s: .init(), e: e))
        XCTAssertEqual(try Profile.encKeyId(profile.encPub), p["encKeyId"]?.string)
        XCTAssertEqual(try profile.decrypt(p["senderEncPub"]!.string!, p["envelope"]!), p["plain"]?.string)
    }

    func testEncryptsWhatAnotherProfileOpens() throws {
        let a = Profile.of(SoftKeys.fresh()), b = Profile.of(SoftKeys.fresh())
        let env = try a.encrypt([b.encPub], "hola ñandú")
        XCTAssertEqual(try b.decrypt(a.encPub, env), "hola ñandú")
        XCTAssertThrowsError(try a.decrypt(b.encPub, env)) { XCTAssertEqual(($0 as? Profile.ProfileError)?.code, "not-for-me") }
    }

    func testActaMatchesTheJs() throws {
        let a = try XCTUnwrap(try vectors()["acta"])
        for c in a["cases"]!.array! {
            XCTAssertEqual(Acta.memberCan(a["acta"], c["pub"]!.string!, c["cap"]!.string!, c["extra"]!.array!), c["can"]!.bool!, "\(c["pub"]!.string!) \(c["cap"]!.string!)")
        }
    }

    func testBroadcastLinkAndChannelMatchTheJs() throws {
        for b in try vectors()["broadcast"]!.array! {
            let ref = BroadcastHost.Ref(key: b["key"]!.string!, secret: b["secret"]!.string!)
            XCTAssertEqual(try BroadcastHost.encodeRef(ref, hostPubkey: b["hostPubkey"]!.string!), b["encoded"]!.string!)
            XCTAssertEqual(BroadcastHost.channel("padel", ref.key), b["channel"]!.string!)
        }
        XCTAssertTrue(BroadcastHost.newRef(node: "ABCDEFGH1234").key.hasPrefix("ABCDEFGH1234"))
        XCTAssertTrue(BroadcastHost.newRef(node: nil).key.hasPrefix("_"))
    }

    private func items(_ k: SoftKeys, acta: String? = nil) -> [String: String] {
        var m = [
            "kv:dotrino.identity.current": "p1",
            "key:dotrino.identity.p.p1.keypair": #"{"external":"kid-1","kind":"sign","publicJwk":\#(k.publickey)}"#,
            "key:dotrino.identity.p.p1.enc-keypair": #"{"external":"kid-1","kind":"enc","publicJwk":\#(k.encPub)}"#,
        ]
        if let acta { m["kv:dotrino.identity.p.p1.acta"] = acta }
        return m
    }

    func testLoadsTheActiveProfileAndObeysItsActa() throws {
        let k = SoftKeys.fresh()
        let free = try Profile.load(items(k)) { _ in k }
        XCTAssertEqual(free.publickey, k.publickey)
        XCTAssertTrue(free.canSign)
        let data: JSON = ["op": "x", "n": 1]
        XCTAssertTrue(Crypto.verify(publickey: free.publickey, data: data, signature: try free.signData(data)))

        let acta = #"{"members":[{"pub":\#(JSON.string(k.publickey).text),"caps":["read"]}]}"#
        let bound = try Profile.load(items(k, acta: acta)) { _ in k }
        XCTAssertFalse(bound.canSign)
        XCTAssertThrowsError(try bound.signData(data)) { XCTAssertEqual(($0 as? Profile.ProfileError)?.code, "needs-vault-signer") }
        let identify: JSON = ["op": "identify"]
        XCTAssertTrue(Crypto.verify(publickey: bound.publickey, data: identify, signature: try bound.signData(identify)))
    }

    func testNoProfileSaysSo() {
        XCTAssertThrowsError(try Profile.load([:]) { _ in SoftKeys.fresh() }) { XCTAssertEqual(($0 as? Profile.ProfileError)?.code, "no-profile") }
        XCTAssertThrowsError(try Profile.load(["kv:dotrino.identity.current": "p1"]) { _ in SoftKeys.fresh() }) {
            XCTAssertEqual(($0 as? Profile.ProfileError)?.code, "no-profile-keys")
        }
    }
}
