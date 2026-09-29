import CryptoKit
import XCTest
@testable import DotrinoNative

/// Against the REAL registry. Same case as `ReputationLiveTest.kt`. Only with `TEST_RUNNER_DOTRINO_LIVE_REP=1`.
final class ReputationLiveTests: XCTestCase {
    struct SameKeys: DeviceKeys {
        let base: ProfileTests.SoftKeys
        let publickey: String
        var encPub: String { base.encPub }
        func signBytes(_ bytes: Data) throws -> String { try base.signBytes(bytes) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data { try base.agree(peer) }
    }

    func testRateReadAndWithdraw() async throws {
        guard ProcessInfo.processInfo.environment["DOTRINO_LIVE_REP"] == "1" else { throw XCTSkip("set TEST_RUNNER_DOTRINO_LIVE_REP=1") }
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        let ap = try XCTUnwrap(try JSON.parse(Data(contentsOf: url))["actaProfile"])
        let acta = ap["acta"]!
        let pub = acta["members"]!.array![0]["pub"]!.string!
        let s = try P256.Signing.PrivateKey(rawRepresentation: Crypto.fromB64url(ap["signPrivateJwk"]!["d"]!.string!))
        let e = try P256.KeyAgreement.PrivateKey(rawRepresentation: Crypto.fromB64url(ap["encPrivateJwk"]!["d"]!.string!))
        let me = Profile.of(SameKeys(base: .init(s: s, e: e), publickey: pub), acta: acta)
        let subject = ProfileTests.SoftKeys.fresh().publickey
        let rep = Reputation(profile: me, peers: PeerBook(storage: PeerBook.MemoryStorage(), profile: me))
        try await rep.rate(subject, ["confianza": 4, "afinidad": 2], notes: "prueba nativa")
        let mine = try await rep.myIndicatorsFor(subject)
        XCTAssertEqual(mine, ["confianza": 4, "afinidad": 2])
        let agg = await rep.aggregateTrust(subject)
        XCTAssertEqual(agg.trustedCount, 1)
        XCTAssertEqual(agg.score ?? -1, 0.8, accuracy: 1e-9)
        try await rep.removeChannel(subject, "confianza"); try await rep.removeChannel(subject, "afinidad")
    }
}
