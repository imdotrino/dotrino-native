import XCTest
@testable import DotrinoNative

/// The peer book against what the JS identity makes. The same cases as `PeerBookTest.kt`.
final class PeerBookTests: XCTestCase {
    typealias SoftKeys = ProfileTests.SoftKeys

    private func peers() throws -> JSON {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        return try XCTUnwrap(try JSON.parse(Data(contentsOf: url))["peers"])
    }
    private func book(_ s: PeerBook.MemoryStorage = .init()) -> PeerBook { PeerBook(storage: s, profile: Profile.of(SoftKeys.fresh())) }

    func testAdoptsAJsCardAndRefusesAForgedOne() throws {
        let card = try XCTUnwrap(try peers()["card"])
        XCTAssertTrue(PeerBook.verifyCard(card))
        let b = book()
        XCTAssertEqual(try b.adoptPeerCard(card), .init(adopted: true, reason: "primera-vez", devices: 1))
        var forged = card.object!; forged["seq"] = 99
        XCTAssertFalse(PeerBook.verifyCard(.object(forged)))
        XCTAssertEqual(try b.adoptPeerCard(.object(forged)).reason, "firma-invalida")
        XCTAssertEqual(try b.adoptPeerCard(card).reason, "igual")
    }

    func testAContactAddedByOneDeviceIsFoundThroughTheCard() throws {
        let p = try peers()
        let dev = p["cardDevPub"]!.string!
        let b = book()
        try b.addContact(dev, nickname: "Beto", encryptionPubkey: "ENC-1")
        try b.adoptPeerCard(p["card"]!)
        XCTAssertEqual(try b.cardOf(dev), p["card"])
        XCTAssertEqual(try b.encPubsOf(dev), ["ENC-1", p["cardDevEncPub"]!.string!])
        XCTAssertEqual(try b.contacts().count, 1)
        try b.removeContact(dev)
        XCTAssertEqual(try b.contacts().count, 0)
        XCTAssertEqual(try b.get(dev)?["nickname"]?.string, "Beto")
    }

    func testMergesAJsEndorsementOnlyIfItsSignatureHolds() throws {
        let p = try peers()
        let e = p["endorsement"]!, subject = p["subject"]!.string!
        let b = book()
        XCTAssertEqual(try b.mergeEndorsements(subject, [e]), 1)
        XCTAssertEqual(try b.mergeEndorsements(subject, [e]), 0)
        var t = e.object!; t["rating"] = 5; t["issuedAt"] = .int(9_999_999_999_999)
        XCTAssertEqual(try b.mergeEndorsements(subject, [.object(t)]), 0)
        XCTAssertEqual(try b.ratingsFor(subject).endorsements, [e])
    }

    func testMyRatingIsSignedAndOthersCanVerifyIt() throws {
        let mine = book()
        try mine.setRating("SUBJ", 4, notes: "ok")
        let env = try XCTUnwrap(try mine.ratingsFor("SUBJ").mine)
        XCTAssertEqual(env["rating"], .int(4))
        XCTAssertEqual(try book().mergeEndorsements("SUBJ", [env]), 1)
    }
}
