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

/// El identicon es el MISMO que el de la web: estos valores salen de `avatarSvg` de
/// `@dotrino/identity/avatar` (tono y casillas llenas "col,fila"). Los mismos que `AvatarTest.kt`.
final class AvatarTests: XCTestCase {
    func testTheIdenticonIsTheWebOne() {
        let vectors: [(String, Int, String)] = [
            ("dotrino", 298, "0,0 0,1 0,2 0,3 0,4 1,0 1,2 1,3 3,0 3,2 3,3 4,0 4,1 4,2 4,3 4,4"),
            ("{\"crv\":\"P-256\",\"ext\":true,\"key_ops\":[\"verify\"],\"kty\":\"EC\",\"x\":\"abc\",\"y\":\"déf\"}", 136, "0,1 0,2 0,3 1,1 2,0 2,3 3,1 4,1 4,2 4,3"),
            ("ñandú 🐦", 72, "0,0 0,1 0,2 0,4 1,0 3,0 4,0 4,1 4,2 4,4"),
        ]
        for (seed, hue, cells) in vectors {
            let p = DotrinoAvatar.pattern(seed)
            XCTAssertEqual(p.hue, hue, seed)
            let got = (0..<5).flatMap { c in (0..<5).filter { p.cells[c][$0] }.map { "\(c),\($0)" } }.sorted().joined(separator: " ")
            XCTAssertEqual(got, cells, seed)
        }
    }
}
