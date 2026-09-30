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
        let f = lib.appendingPathComponent("Sounds/dotrino-ring.caf")
        XCTAssertTrue(FileManager.default.fileExists(atPath: f.path), f.lastPathComponent)
        XCTAssertFalse(FileManager.default.fileExists(atPath: lib.appendingPathComponent("Sounds/dotrino-ring-1.caf").path))
    }
}

final class RingTests: XCTestCase {
    func testOneRingForEveryAlert() {
        XCTAssertEqual(DotrinoRing.soundName, "dotrino-ring.caf")
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

/// Los perfiles del teléfono, leídos del almacén de la identidad como los lista la web (igual que PhoneProfilesTest.kt).
final class PhoneProfilesTests: XCTestCase {
    func testListsThePhonesProfilesLikeTheWeb() {
        let items = [
            "kv:dotrino.identity.current": "p2",
            "kv:dotrino.identity.profiles": #"[{"id":"p1","name":"Casa","pubkey":"K1"},{"id":"p2","name":"","pubkey":"K2","login":{"address":"ana@AB12-CD34-EF56"}},{"id":"p3","name":"Sin me","pubkey":"K3"}]"#,
            "kv:dotrino.identity.p.p2.me": #"{"nickname":"Ana","avatar":"data:image/png;base64,AAAA"}"#,
            "kv:dotrino.identity.p.p1.me": #"{"avatar":"https://no-es-un-data-uri"}"#,
        ]
        let l = PhoneProfiles.list(items)
        XCTAssertEqual(l.map(\.id), ["p1", "p2", "p3"])
        XCTAssertEqual(l[0].name, "Casa"); XCTAssertNil(l[0].avatar)
        XCTAssertEqual(l[1].name, "Ana"); XCTAssertTrue(l[1].current); XCTAssertEqual(l[1].login, "ana@AB12-CD34-EF56")
        XCTAssertEqual(l[1].seed, "K2"); XCTAssertEqual(l[1].avatar, "data:image/png;base64,AAAA")
        XCTAssertEqual(l.map(\.current), [false, true, false])
        XCTAssertTrue(PhoneProfiles.list([:]).isEmpty)
    }
}
