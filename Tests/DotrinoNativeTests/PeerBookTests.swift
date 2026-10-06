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

    /// Blocking is private and travels with the book: the newer change wins on another device, unblocking too.
    func testABlockTravelsAndTheNewerChangeWins() throws {
        let pk = "{\"kty\":\"EC\",\"x\":\"b\"}"
        let a = book(), b = book()
        try a.addContact(pk, nickname: "Beto"); try b.addContact(pk, nickname: "Beto")
        Thread.sleep(forTimeInterval: 0.005) // distinct milliseconds: the newer change has to be newer
        let blocked = try a.setBlocked(pk, true)
        XCTAssertTrue(try a.isBlocked(pk))
        XCTAssertNil(blocked["myRating"])
        Thread.sleep(forTimeInterval: 0.005)
        _ = try b.mergeFrom([try a.get(pk)!])
        XCTAssertTrue(try b.isBlocked(pk))
        Thread.sleep(forTimeInterval: 0.005)
        try b.setBlocked(pk, false)
        _ = try a.mergeFrom([try b.get(pk)!])
        XCTAssertFalse(try a.isBlocked(pk))
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

/// El libro de contactos en la bóveda: los mismos casos que `peerSync.test.mjs` y `PeerBookBackupTest.kt`.
final class PeerBookBackupTests: XCTestCase {
    final class FakeVault: @unchecked Sendable {
        var t: [String: JSON] = [:]
        func call(_ method: String, _ args: JSON) async throws -> JSON {
            let th = PeerBookBackup.thread
            switch method {
            case "getThreadIndexes":
                return ["indexes": [th: ["items": .array(t.values.map { .array([$0["id"]!, $0["ts"]!]) }), "tombs": []]], "next": .null]
            case "getEntries":
                let ids = args["refs"]?[th]?.array?.compactMap(\.string) ?? []
                return ["threads": [th: .array(ids.compactMap { t[$0] })]]
            case "importThreads":
                for e in args["threads"]?[th]?.array ?? [] {
                    let id = e["id"]!.string!
                    if (t[id]?["ts"]?.int ?? -1) < (e["ts"]?.int ?? 0) { t[id] = e }
                }
                return ["ok": true]
            default: throw NSError(domain: method, code: 0)
            }
        }
    }

    private func book() -> (PeerBook, PeerBookBackup) {
        let keys = ProfileTests.SoftKeys.fresh()
        let b = PeerBook(storage: PeerBook.MemoryStorage(), profile: Profile.of(keys))
        return (b, PeerBookBackup(profile: Profile.of(keys), book: b))
    }

    func testAContactReachesTheOtherDeviceAndRemovingItToo() async throws {
        let v = FakeVault()
        let (a, sa) = book(); let (b, sb) = book()
        _ = try a.addContact("X", nickname: "Ana")
        _ = try await sa.reconcile(v.call)
        let got = try await sb.reconcile(v.call)
        XCTAssertEqual(got, 1)
        XCTAssertEqual(try b.contacts().first?["nickname"]?.string, "Ana")

        try await Task.sleep(nanoseconds: 5_000_000)
        _ = try b.removeContact("X")
        _ = try await sb.reconcile(v.call)
        _ = try await sa.reconcile(v.call)
        XCTAssertTrue(try a.contacts().isEmpty, "the removed contact does not come back")

        let again1 = try await sa.reconcile(v.call), again2 = try await sb.reconcile(v.call)
        XCTAssertEqual(again1, 0); XCTAssertEqual(again2, 0)
    }

    func testTheEntryIdIsTheWebOne() {
        XCTAssertEqual(PeerBookBackup.idOf(#"{"crv":"P-256","kty":"EC","x":"ñ"}"#), "988a93c5b4a24186e320c9615b0f85f9")
    }
}
