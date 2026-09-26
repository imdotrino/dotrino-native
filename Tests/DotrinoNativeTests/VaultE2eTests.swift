import CryptoKit
import XCTest
@testable import DotrinoNative

/// Against a REAL proxy and vault: the same harness as Android
/// (`test-vectors/e2e-vault.mjs`), running on another machine of the
/// LAN, with its output served over HTTP. Runs only when `DOTRINO_E2E_URL` is set (pass it to
/// xcodebuild as `TEST_RUNNER_DOTRINO_E2E_URL`); otherwise it is skipped and says so.
///
///   E2E_HOST=<ip> node test-vectors/e2e-vault.mjs /tmp/e2e/e2e.json &
///   (cd /tmp/e2e && python3 -m http.server 8765) &
///   TEST_RUNNER_DOTRINO_E2E_URL=http://<ip>:8765/e2e.json xcodebuild … test
final class VaultE2eTests: XCTestCase {
    private struct SoftKeys: DeviceKeys {
        let s: P256.Signing.PrivateKey
        let e: P256.KeyAgreement.PrivateKey
        /// The vault names this device by the pubkey string it enrolled with (the JS JWK carries extra fields).
        let publickey: String
        var encPub: String { Crypto.jwk(raw: e.publicKey.rawRepresentation) }
        func signBytes(_ bytes: Data) throws -> String { Crypto.b64(try s.signature(for: bytes).rawRepresentation) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data {
            try e.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
        }
    }

    private func harness() async throws -> (JSON, URL) {
        guard let s = ProcessInfo.processInfo.environment["DOTRINO_E2E_URL"], let url = URL(string: s) else {
            throw XCTSkip("DOTRINO_E2E_URL not set: start test-vectors/e2e-vault.mjs to run this")
        }
        let (data, _) = try await URLSession.shared.data(from: url)
        return (try JSON.parse(data), url)
    }

    private func keys(_ n: JSON) throws -> SoftKeys {
        try SoftKeys(s: .init(rawRepresentation: Crypto.fromB64url(n["privateJwk"]!["d"]!.string!)),
                     e: .init(rawRepresentation: Crypto.fromB64url(n["encPrivateJwk"]!["d"]!.string!)),
                     publickey: n["cert"]!["sub"]!.string!)
    }

    func testApprovesAPendingWriteOnARealVault() async throws {
        let (f, url) = try await harness()
        let k = try keys(f)
        let cert = f["cert"]!
        let account = Account(id: "e2e", name: "E2E", profileId: nil, vault: f["vault"]!.string!, proxy: f["proxyUrl"]!.string!,
                              cert: cert, deviceId: f["deviceId"]!.string!)
        XCTAssertFalse(Delegation.scope(cert).contains("vault:approve"), "the enrolled paper does not carry approve yet")
        XCTAssertEqual(f["account"]?.string, "Cuenta E2E")

        let conn = try ProxyConnection(account.proxy)
        try await conn.connect()
        try await conn.identify(k)
        let renewed = OneShot<Account>()
        let vc = VaultClient(account: account, keys: k, conn: conn) { renewed.finish(.success($0)) }

        let list = try await vc.approvals()
        let r = try await renewed.wait(timeout: 1, onTimeout: VaultError("the paper was never renewed", code: "no-renew"))
        XCTAssertTrue(Delegation.scope(r.cert).contains("vault:approve"), "it renewed its paper to get approve")
        let p = try XCTUnwrap(list.first { $0.id == f["pending"]!.string! })
        XCTAssertEqual(p.kind, "write")
        XCTAssertEqual(p.ns, f["ns"]!.string!)
        XCTAssertEqual(p.ctx?["keys"]?.array?.compactMap { $0.string }, ["API_TOKEN"])

        try await vc.approve(p.id)
        let written = URL(string: url.absoluteString + ".written")!
        var ok = false
        for _ in 0..<50 where !ok {
            let (_, res) = try await URLSession.shared.data(from: written)
            ok = (res as? HTTPURLResponse)?.statusCode == 200
            if !ok { try await Task.sleep(nanoseconds: 100_000_000) }
        }
        XCTAssertTrue(ok, "the vault wrote the approved variable")
        let after = try await vc.approvals()
        XCTAssertFalse(after.contains { $0.id == p.id })
        conn.close()
    }

    /// A phone the owner has not given `+aprueba` must be told so (NO_APPROVE), not «invalid renewed paper: scope».
    func testAPhoneWithoutApproveIsToldSoNotInvalidPaper() async throws {
        let (f, _) = try await harness()
        let n = f["noApprove"]!
        let k = try keys(n)
        let account = Account(id: "e2e-2", name: "E2E", profileId: nil, vault: f["vault"]!.string!, proxy: f["proxyUrl"]!.string!,
                              cert: n["cert"]!, deviceId: n["deviceId"]!.string!)
        let conn = try ProxyConnection(account.proxy)
        try await conn.connect()
        try await conn.identify(k)
        let vc = VaultClient(account: account, keys: k, conn: conn)
        do {
            _ = try await vc.approvals()
            XCTFail("it must fail: this phone cannot approve")
        } catch let e as VaultError {
            XCTAssertEqual(e.code, VaultClient.noApprove)
        }
        conn.close()
    }
}
