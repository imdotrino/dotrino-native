import CryptoKit
import XCTest
@testable import DotrinoNative

final class StoreDiagTests: XCTestCase {
    func testSteps() async throws {
        guard let path = ProcessInfo.processInfo.environment["DOTRINO_E2E_STORE"] else { throw XCTSkip("diag") }
        let f = try JSON.parse(Data(contentsOf: URL(fileURLWithPath: path)))
        let keys = VaultBackupE2eTests.SameKeys(s: try .init(rawRepresentation: Crypto.fromB64url(f["privateJwk"]!["d"]!.string!)),
                            e: try .init(rawRepresentation: Crypto.fromB64url(f["encPrivateJwk"]!["d"]!.string!)), publickey: f["publickey"]!.string!)
        let profile = Profile.of(keys, acta: f["acta"])
        print("DIAG contentKey", profile.contentKey() != nil)
        let conn = try ProxyConnection(f["proxyUrl"]!.string!)
        print("DIAG connecting", f["proxyUrl"]!.string!)
        let t = try await conn.connect(); print("DIAG connected", t)
        do { try await conn.identifyAs(profile.publickey) { try profile.signData($0) }; print("DIAG identified") } catch { print("DIAG identify failed", error) }
        _ = conn.onMessage { m in print("DIAG msg", m.payload["type"]?.string ?? "?", String(m.payload.text.prefix(200))) }
        let account = Account(id: "b", name: "", profileId: nil, vault: f["vault"]!.string!, proxy: f["proxyUrl"]!.string!, cert: f["cert"]!, deviceId: f["deviceId"]!.string!)
        let vc = VaultClient(account: account, keys: keys, conn: conn)
        do { let r = try await vc.store(profile, "getThreadDigests", [:]); print("DIAG result", r.text.prefix(300)) } catch { print("DIAG store failed", error) }
    }
}
