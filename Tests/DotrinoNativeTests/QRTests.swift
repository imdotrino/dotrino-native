import XCTest
@testable import DotrinoNativeUI

/// The QR the phone shows, the phone reads. The same cases as `QrTest.kt`.
final class QRTests: XCTestCase {
    func testShowsAndReadsTheMessengerPairingLink() throws {
        let link = "https://messenger.dotrino.com/#add=K7M2Q9"
        let img = try XCTUnwrap(DotrinoQR.image(link, size: 300, scale: 1))
        XCTAssertEqual(DotrinoQR.decode(img), link)
    }

    func testUnicodeSurvives() throws {
        let t = "ñandú ✓ 🔑"
        XCTAssertEqual(DotrinoQR.decode(try XCTUnwrap(DotrinoQR.image(t, size: 240, scale: 1))), t)
    }
}
