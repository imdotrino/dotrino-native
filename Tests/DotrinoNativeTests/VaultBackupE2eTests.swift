import CryptoKit
import XCTest
@testable import DotrinoNative

/// The store's backup against a REAL vault (`test-vectors/e2e-store.mjs`, with `E2E_HOST` so the
/// simulator reaches it). Same case as `VaultBackupE2eTest.kt`. Only with `TEST_RUNNER_DOTRINO_E2E_STORE`.
final class VaultBackupE2eTests: XCTestCase {
    struct SameKeys: DeviceKeys {
        let s: P256.Signing.PrivateKey, e: P256.KeyAgreement.PrivateKey, publickey: String
        var encPub: String { Crypto.jwk(raw: e.publicKey.rawRepresentation) }
        func signBytes(_ bytes: Data) throws -> String { Crypto.b64(try s.signature(for: bytes).rawRepresentation) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data { try e.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) } }
    }

    func testPullPushAndAgreeWithTheVault() async throws {
        guard let path = ProcessInfo.processInfo.environment["DOTRINO_E2E_STORE"] else { throw XCTSkip("set TEST_RUNNER_DOTRINO_E2E_STORE") }
        let f = try JSON.parse(Data(contentsOf: URL(fileURLWithPath: path)))
        let keys = SameKeys(s: try .init(rawRepresentation: Crypto.fromB64url(f["privateJwk"]!["d"]!.string!)),
                            e: try .init(rawRepresentation: Crypto.fromB64url(f["encPrivateJwk"]!["d"]!.string!)),
                            publickey: f["publickey"]!.string!)
        let link = Profile.VaultLink(master: f["vault"]!.string!, proxy: f["proxyUrl"]!.string!, cert: f["cert"]!, deviceId: f["deviceId"]!.string!)
        let contact = f["contact"]!.string!
        let owns: (String) -> Bool = { $0.hasPrefix("{") }
        let profile = Profile.of(keys, acta: f["acta"], vault: link)

        let phone = VaultBackup.MemoryThreads()
        let backup = VaultBackup(profile: profile, store: phone, owns: owns)
        let r1 = try await backup.sync()
        XCTAssertEqual(r1.changed, [contact])
        XCTAssertEqual(phone.t.list(contact).compactMap { $0["id"]?.string }, ["w1", "w2"])
        XCTAssertTrue(phone.t.list("padel.results").isEmpty, "another app's thread came in")

        try phone.change { try $0.append(contact, ["id": "n1", "ts": 2000, "dir": "out", "text": "desde el iPhone"]) }
        _ = try phone.change { $0.remove(contact, id: "w2") }
        _ = try await backup.sync()

        let other = VaultBackup.MemoryThreads()
        _ = try await VaultBackup(profile: profile, store: other, owns: owns).sync()
        XCTAssertEqual(Set(other.t.list(contact).compactMap { $0["id"]?.string }), ["w1", "n1"])
        XCTAssertEqual(other.t.digest(of: contact), phone.t.digest(of: contact))
        let again = try await backup.sync()
        XCTAssertEqual(again.changed, [])
    }
}
