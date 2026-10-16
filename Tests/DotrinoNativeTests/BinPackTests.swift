import CryptoKit
import XCTest
@testable import DotrinoNative

/// `binpack.js` and the binary sealed envelope of the transport pillar, from what the JS made.
/// The same cases as `BinPackTest.kt`.
final class BinPackTests: XCTestCase {
    typealias SoftKeys = ProfileTests.SoftKeys

    private func vectors() throws -> JSON {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        return try JSON.parse(Data(contentsOf: url))
    }

    func testOpensTheBinaryFrameTheJsPillarSealed() throws {
        let v = try vectors()
        let b = try XCTUnwrap(v["binSealed"]), p = try XCTUnwrap(v["profile"])
        let frame = try Crypto.fromB64(b["frame"]!.string!)
        let (header, body) = try BinPack.decodeFrame(frame)
        XCTAssertEqual(header["type"]?.string, "message")
        let inc = try XCTUnwrap(ProxyConnection.incomingPackage(from: "TOK-A", fromPubkey: "PK-A", body: body))
        let packed = try XCTUnwrap(inc.packed)
        XCTAssertTrue(BinPack.isBinSealed(packed))
        // The proxy cannot read it: the text of the message is not in the frame.
        XCTAssertNil(frame.range(of: Data("archivo".utf8)))

        let e = try P256.KeyAgreement.PrivateKey(rawRepresentation: Crypto.fromB64url(p["encPrivateJwk"]!["d"]!.string!))
        let phone = IdentitySealing(profile: Profile.of(SoftKeys(s: .init(), e: e)), app: "messenger")
        let opened = try phone.openBinary(packed)
        // The bytes arrive as base64, where the file was.
        XCTAssertEqual(opened.payload, b["resolved"])
        XCTAssertEqual(opened.senderEncPub, b["senderEncPub"]?.string)
        XCTAssertThrowsError(try IdentitySealing(profile: Profile.of(SoftKeys.fresh()), app: "messenger").openBinary(packed))
    }

    func testAnUnsealedPackageReachesTheAppWithItsBytesAsBase64() throws {
        let b = try XCTUnwrap(try vectors()["binSealed"])
        let inc = try XCTUnwrap(ProxyConnection.incomingPackage(from: "T", fromPubkey: nil, body: Crypto.fromB64(b["plainPacked"]!.string!)))
        XCTAssertEqual(inc.payload, b["plainResolved"])
        XCTAssertFalse(BinPack.isBinSealed(try XCTUnwrap(inc.packed)))
    }

    func testPackAndFrameRoundTrip() throws {
        let o: JSON = ["t": "x", "ñ": "✓"]
        let frame = BinPack.encodeFrame(["to": "A"], BinPack.pack(o))
        let (h, body) = try BinPack.decodeFrame(frame)
        XCTAssertEqual(h["to"]?.string, "A")
        XCTAssertEqual(try BinPack.unpack(body).json, o)
    }

    func testWhatIsNotAPackageOrAFrameIsSaidWithItsCode() {
        XCTAssertThrowsError(try BinPack.decodeFrame(Data([9, 0, 0, 0, 0]))) { XCTAssertEqual(($0 as? BinPack.BadPack)?.code, "bad-frame") }
        XCTAssertThrowsError(try BinPack.unpack(Data([1, 2]))) { XCTAssertEqual(($0 as? BinPack.BadPack)?.code, "bad-pack") }
        let head = Data(#"{"x":{"__dotrino_bin":[0,99]}}"#.utf8)
        let n = head.count
        let bad = Data([0xB1, UInt8(n >> 24 & 0xFF), UInt8(n >> 16 & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)]) + head + Data([1, 2, 3])
        XCTAssertThrowsError(try BinPack.unpack(bad)) { XCTAssertEqual(($0 as? BinPack.BadPack)?.code, "bad-pack") }
    }
}
