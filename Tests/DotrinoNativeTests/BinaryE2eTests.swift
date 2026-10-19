import CryptoKit
import XCTest
@testable import DotrinoNative

/// The phone READS AND SENDS BINARY with the real pillar, through a real proxy — the Swift twin of
/// `BinaryE2eTest.kt`: a file sealed in binary by token and another by pubkey arrive and open; and
/// the phone's answers (sealed by token, sealed by pubkey, and an unsealed package like the vault's)
/// reach the JS as binary frames. Runs only with `DOTRINO_E2E_BIN_URL` (as
/// `TEST_RUNNER_DOTRINO_E2E_BIN_URL` for xcodebuild): the harness runs on another machine of the LAN
/// and its files are served over HTTP.
///
///   E2E_HOST=<ip> node test-vectors/e2e-binary.mjs /tmp/e2e/bin.json &
///   (cd /tmp/e2e && python3 -m http.server 8765) &
///   TEST_RUNNER_DOTRINO_E2E_BIN_URL=http://<ip>:8765/bin.json xcodebuild … -only-testing:DotrinoNativeTests/BinaryE2eTests test
final class BinaryE2eTests: XCTestCase {
    private struct SoftKeys: DeviceKeys {
        let s: P256.Signing.PrivateKey
        let e: P256.KeyAgreement.PrivateKey
        var publickey: String { Crypto.jwk(raw: s.publicKey.rawRepresentation) }
        var encPub: String { Crypto.jwk(raw: e.publicKey.rawRepresentation) }
        func signBytes(_ bytes: Data) throws -> String { Crypto.b64(try s.signature(for: bytes).rawRepresentation) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data {
            try e.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
        }
    }

    private func fetch(_ url: URL) async -> JSON? {
        guard let (data, r) = try? await URLSession.shared.data(from: url), (r as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try? JSON.parse(data)
    }

    private func poll(_ url: URL, timeout: TimeInterval, until: (JSON) -> Bool = { _ in true }) async -> JSON? {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if let j = await fetch(url), until(j) { return j }
            try? await Task.sleep(nanoseconds: 300_000_000)
        }
        return nil
    }

    func testAJsClientAndThePhoneTalkInBinary() async throws {
        guard let s = ProcessInfo.processInfo.environment["DOTRINO_E2E_BIN_URL"], let url = URL(string: s) else {
            throw XCTSkip("DOTRINO_E2E_BIN_URL not set: start test-vectors/e2e-binary.mjs to run this")
        }
        let fetched = await fetch(url)
        let f = try XCTUnwrap(fetched, "the harness is not ready")
        let keys = SoftKeys(s: try .init(rawRepresentation: Crypto.fromB64url(f["sign"]!["d"]!.string!)),
                            e: try .init(rawRepresentation: Crypto.fromB64url(f["enc"]!["d"]!.string!)))
        let profile = Profile.of(keys)
        let proxyUrl = try XCTUnwrap(f["proxyUrl"]?.string)
        let jsToken = try XCTUnwrap(f["jsToken"]?.string), jsPub = try XCTUnwrap(f["jsPubkey"]?.string)
        let session = SealedSession(urls: [proxyUrl], profile: profile, app: "messenger")
        var got: [SealedSession.Message] = []
        let lock = NSLock()
        _ = session.onMessage { m in lock.withLock { got.append(m) } }
        session.start()
        defer { session.close() }
        let online = await session.awaitOnline()
        XCTAssertTrue(online, "phone offline")
        let who = try await session.whoIs(jsToken)
        XCTAssertTrue(Delegation.samePubkey(who, jsPub), "the greeting did not say who")

        // What the JS sends: a file by token, a blob by pubkey.
        let sentJson = await poll(URL(string: s + ".sent")!, timeout: 30)
        let sent = try XCTUnwrap(sentJson, "the JS did not send")
        XCTAssertNil(sent["error"], "the JS could not send: \(sent["error"]?.string ?? "")")
        let end = Date().addingTimeInterval(30)
        while Date() < end, lock.withLock({ got.count }) < 2 { try await Task.sleep(nanoseconds: 200_000_000) }
        let msgs = lock.withLock { got }
        let dm = try XCTUnwrap(msgs.first { $0.payload["type"]?.string == "DM" }, "the file by token did not arrive")
        XCTAssertEqual(dm.payload["file"]?["data"]?.string, sent["file"]?.string)
        let q = try XCTUnwrap(msgs.first { $0.payload["type"]?.string == "QUEUED" }, "the blob by pubkey did not arrive")
        XCTAssertEqual(q.payload["blob"]?.string, sent["blob"]?.string)

        // What the phone sends, in binary: sealed by token, sealed by pubkey, unsealed to the `vault` app.
        try await session.sendSealed(toToken: jsToken, ["type": "ACK", "n": 2])
        try await session.sendSealed(toPubkey: jsPub, ["type": "ACK_Q"])
        let bare = try ProxyConnection(proxyUrl, app: "vault")
        _ = try await bare.connect()
        try await bare.identifyAs(profile.publickey) { try profile.signData($0) }
        try bare.sendByPubkey(jsPub, ["type": "PLAIN", "ok": true], toApp: "vault")
        defer { bare.close() }
        let reply = await poll(URL(string: s + ".reply")!, timeout: 15) { r in ["ACK", "ACK_Q", "PLAIN"].allSatisfy { r[$0] != nil } }
        let partial = await fetch(URL(string: s + ".reply")!)?.text ?? "nothing"
        let r = try XCTUnwrap(reply, "the JS did not get the three answers: \(partial)")
        for (type, sealed) in [("ACK", true), ("ACK_Q", true), ("PLAIN", false)] {
            XCTAssertEqual(r[type]?["sealed"]?.bool, sealed, "\(type) sealed")
            XCTAssertEqual(r[type]?["binary"]?.bool, true, "\(type) arrived as a binary frame")
        }
    }
}
