@testable import DotrinoNativeUI
import DotrinoNative
import XCTest

/// El timbre de iOS: el entorno de APNs sale del perfil con que se firmó la build, y el token
/// viaja en hexadecimal con el bundle id como `topic`.
final class PushTests: XCTestCase {
    func testTheEnvironmentComesFromTheProvisioningProfile() {
        let dev = "junk<plist><dict><key>aps-environment</key>\n\t<string>development</string></dict></plist>junk"
        let prod = "<key>aps-environment</key><string>production</string>"
        XCTAssertEqual(DotrinoPush.environment(provisioning: dev), "sandbox")
        XCTAssertEqual(DotrinoPush.environment(provisioning: prod), "production")
        XCTAssertEqual(DotrinoPush.environment(provisioning: "no entitlement here"), "production")
    }

    func testTheTokenIsHex() {
        let t = DotrinoPush.token(Data([0x00, 0xab, 0x10, 0xff]))
        XCTAssertEqual(t.token, "00ab10ff")
        XCTAssertEqual(t.env, "production")   // el bundle de los tests no lleva perfil
    }

    func testTheRingsAreInstalledWhereIOSLooksForThem() throws {
        DotrinoPush.installRings()
        let lib = try XCTUnwrap(FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first)
        for n in 1...DotrinoPush.ringCount {
            let f = lib.appendingPathComponent("Sounds/dotrino-ring-\(n).caf")
            XCTAssertTrue(FileManager.default.fileExists(atPath: f.path), f.lastPathComponent)
        }
    }
}

final class RingTests: XCTestCase {
    func testARandomRingNameIsOneOfTheShippedOnes() {
        for _ in 0..<50 {
            XCTAssertTrue(DotrinoRing.randomName().range(of: #"^dotrino-ring-[1-7]\.caf$"#, options: .regularExpression) != nil)
        }
    }
}
