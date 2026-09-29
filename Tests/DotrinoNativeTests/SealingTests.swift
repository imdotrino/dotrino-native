import CryptoKit
import XCTest
@testable import DotrinoNative

/// `identitySealing` of the transport pillar, both ways. The same cases as `SealingTest.kt`.
final class SealingTests: XCTestCase {
    typealias SoftKeys = ProfileTests.SoftKeys

    private func vectors() throws -> JSON {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        return try JSON.parse(Data(contentsOf: url))
    }

    func testOpensWhatTheJsPillarSealed() throws {
        let v = try vectors()
        let p = try XCTUnwrap(v["profile"]), s = try XCTUnwrap(v["appSealed"])
        let e = try P256.KeyAgreement.PrivateKey(rawRepresentation: Crypto.fromB64url(p["encPrivateJwk"]!["d"]!.string!))
        let phone = IdentitySealing(profile: Profile.of(SoftKeys(s: .init(), e: e)), app: "messenger")
        let opened = try phone.open(s["envelope"]!)
        XCTAssertEqual(opened.payload, s["msg"])
        XCTAssertEqual(opened.senderEncPub, s["senderEncPub"]?.string)
        XCTAssertFalse(IdentitySealing(profile: Profile.of(SoftKeys.fresh()), app: "dotrino-lobby").isSealed(s["envelope"]))
    }

    func testSealsToEveryDeviceGivenAndSaysWhoSealed() throws {
        let ana = Profile.of(SoftKeys.fresh()), b1 = Profile.of(SoftKeys.fresh()), b2 = Profile.of(SoftKeys.fresh())
        let msg: JSON = ["type": "HELLO", "nickname": "Ana"]
        let env = try IdentitySealing(profile: ana, app: "messenger").seal(msg, to: [b1.encPub, b2.encPub])
        XCTAssertFalse(env.text.contains("HELLO"))
        for b in [b1, b2] {
            let o = try IdentitySealing(profile: b, app: "messenger").open(env)
            XCTAssertEqual(o.payload, msg); XCTAssertEqual(o.senderEncPub, ana.encPub)
        }
        XCTAssertThrowsError(try IdentitySealing(profile: Profile.of(SoftKeys.fresh()), app: "messenger").open(env))
        XCTAssertThrowsError(try IdentitySealing(profile: ana, app: "messenger").seal(msg, to: [])) {
            XCTAssertEqual(($0 as? IdentitySealing.SealingError)?.code, "unsealed")
        }
    }
}
