import XCTest
@testable import DotrinoNative

/// Catching up with the vault's record: the port of `verifyActa`, `canAdopt` and `actaHash`
/// against a REAL chain sealed by `@dotrino/identity` (`test-vectors/gen.mjs`, block 8). Same
/// checks as `ActaSyncTest.kt`: the reasons must be the pilar's, one by one.
final class ActaSyncTests: XCTestCase {
    private func v() throws -> JSON {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        return try XCTUnwrap(JSON.parse(Data(contentsOf: url))["actaSync"])
    }
    private func actas() throws -> [String: JSON] { try XCTUnwrap(try v()["actas"]?.object) }

    func testTheHashIsThePilars() throws {
        let a = try actas()
        for (name, h) in try XCTUnwrap(try v()["hashes"]?.object) { XCTAssertEqual(try Acta.hash(a[name]!), h.string, name) }
    }

    func testVerifyGivesThePilarsReasons() throws {
        let a = try actas()
        for c in try XCTUnwrap(try v()["verify"]?.array) {
            let name = c["name"]!.string!
            XCTAssertEqual(Acta.verify(a[name]!), c["reason"]?.string, name)
        }
    }

    func testAdoptGivesThePilarsDecisionAndReason() throws {
        let a = try actas()
        for c in try XCTUnwrap(try v()["adopt"]?.array) {
            let cur = c["current"]?.string.flatMap { a[$0] }
            let r = Acta.canAdopt(a[c["candidate"]!.string!]!, cur)
            let what = "\(c["candidate"]!.string!) over \(c["current"]?.string ?? "nil")"
            XCTAssertEqual(r.adopt, c["adopt"]?.bool, what)
            XCTAssertEqual(r.reason, c["reason"]?.string, what)
        }
    }

    func testAChainIsAdoptedLinkByLinkAsThePilarDoes() throws {
        let a = try actas(), c = try XCTUnwrap(try v()["chain"])
        let order = (c["order"]?.array ?? []).compactMap { $0.string.flatMap { a[$0] } }
        let won = Acta.adoptChain(order, a[c["from"]!.string!]!)
        XCTAssertEqual(try won.map(Acta.hash), try Acta.hash(a[c["won"]!.string!]!))
        XCTAssertNil(Acta.adoptChain([a["g2"]!, a["g1"]!], a["g3"]!), "nothing newer than mine")
    }
}
