import CryptoKit
import XCTest
@testable import DotrinoNative

/// The remote-agent client against a REAL terminal agent enrolled in a real vault
/// (`test-vectors/e2e-remote-agent.mjs`, on another machine of the LAN, its output served over
/// HTTP). Same as `RemoteAgentE2eTest.kt`. Skipped without `DOTRINO_E2E_URL`.
///
///   E2E_HOST=<ip> node test-vectors/e2e-remote-agent.mjs /tmp/e2e/e2e.json &
///   (cd /tmp/e2e && python3 -m http.server 8765) &
///   TEST_RUNNER_DOTRINO_E2E_URL=http://<ip>:8765/e2e.json xcodebuild … test
final class RemoteAgentE2eTests: XCTestCase {
    private struct SoftKeys: DeviceKeys {
        let s: P256.Signing.PrivateKey
        let e: P256.KeyAgreement.PrivateKey
        let publickey: String
        var encPub: String { Crypto.jwk(raw: e.publicKey.rawRepresentation) }
        func signBytes(_ bytes: Data) throws -> String { Crypto.b64(try s.signature(for: bytes).rawRepresentation) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data { try e.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) } }
    }

    private func harness() async throws -> JSON {
        guard let s = ProcessInfo.processInfo.environment["DOTRINO_E2E_URL"], let url = URL(string: s) else {
            throw XCTSkip("DOTRINO_E2E_URL not set: start test-vectors/e2e-remote-agent.mjs to run this")
        }
        return try JSON.parse(try await URLSession.shared.data(from: url).0)
    }

    /// The phone's profile as the identity keeps it, with the record [actaField] of the harness.
    private func profile(_ f: JSON, _ actaField: String = "acta") throws -> Profile {
        let keys = try SoftKeys(s: .init(rawRepresentation: Crypto.fromB64url(f["privateJwk"]!["d"]!.string!)),
                                e: .init(rawRepresentation: Crypto.fromB64url(f["encPrivateJwk"]!["d"]!.string!)),
                                publickey: f["publickey"]!.string!)
        let pub = f["publickey"]!.string!, enc = f["encPub"]!.string!
        let items: [String: String] = [
            "kv:dotrino.identity.current": "p1",
            "key:dotrino.identity.p.p1.keypair": "{\"external\":\"k\",\"kind\":\"sign\",\"publicJwk\":\(pub)}",
            "key:dotrino.identity.p.p1.enc-keypair": "{\"external\":\"k\",\"kind\":\"enc\",\"publicJwk\":\(enc)}",
            "kv:dotrino.identity.p.p1.acta": f[actaField]!.text,
            "kv:dotrino.identity.p.p1.vault.cert": JSON.object(["master": f["master"]!, "proxy": f["proxyUrl"]!, "cert": f["cert"]!, "deviceId": "phone"]).text,
        ]
        return try Profile.load(items) { _ in keys }
    }

    private func connect(_ f: JSON, _ p: Profile) async throws -> ProxyConnection {
        let conn = try ProxyConnection(f["proxyUrl"]!.string!, app: "terminal")
        _ = try await conn.connect()
        try await conn.identifyAs(p.publickey) { try p.signData($0) }
        return conn
    }

    func testFindsTheMachineAndRunsACommandInAConsole() async throws {
        let f = try await harness(), p = try profile(f)
        let agent = f["agentPubkey"]!.string!
        let conn = try await connect(f, p)
        defer { conn.close() }
        let candidates = RemoteAgent.candidates(p)
        XCTAssertEqual(candidates.first { $0.0 == agent }?.1, "TerminalDePrueba")
        let found = try await RemoteAgent.probe(conn, candidates.map(\.0))
        XCTAssertEqual(found[agent], "terminal-agent")

        let session = try await RemoteAgent.open(p, conn, agent)
        let attached = OneShot<JSON>(), seen = OneShot<Void>()
        let out = Locked("")
        session.onMessage { m in
            switch m["type"]?.string {
            case "attached": attached.finish(.success(m))
            case "out", "replay":
                let all = out.update { $0 += m["data"]?.string ?? "" }
                if all.contains("dotrino-42") { seen.finish(.success(())) }
            default: break
            }
        }
        try session.send(["type": "open", "cols": 80, "rows": 24])
        let a = try await attached.wait(timeout: 10, onTimeout: XCTSkip("no attached"))
        XCTAssertEqual(a["console"]?["origin"]?.string, "remote")
        try session.send(["type": "input", "data": "echo dotrino-$((40+2))\r"])
        try await seen.wait(timeout: 10, onTimeout: RemoteAgent.RemoteAgentError("no output", code: "timeout"))
        try session.send(["type": "close"])
        session.close()
    }

    /// The phone holds a record OLDER than the agent's paper: without catching up it is
    /// `acta-vieja`; with [ActaSync] it adopts the vault's record, keeps it, and the session opens.
    func testAnOlderRecordIsBroughtUpToDateFromTheVault() async throws {
        let f = try await harness(), stale = try profile(f, "staleActa")
        let newest = f["acta"]!
        XCTAssertLessThan(stale.actaSeq!, newest["seq"]!.int!)
        let agent = f["agentPubkey"]!.string!
        let conn = try await connect(f, stale)
        defer { conn.close() }
        do {
            _ = try await RemoteAgent.open(stale, conn, agent)
            XCTFail("an older record must not open")
        } catch let e as RemoteAgent.RemoteAgentError { XCTAssertTrue(e.description.contains("acta-vieja"), e.description) }

        let saved = Locked<[String: String]>([:])
        let caught = Locked<Profile?>(nil)
        let session = try await RemoteAgent.open(stale, conn, agent, catchUp: {
            let p = try await ActaSync.update(stale, conn, save: { k, v in _ = saved.update { $0[k] = v } })
            _ = caught.update { $0 = p }
            return p
        })
        XCTAssertEqual(caught.value?.actaSeq, newest["seq"]!.int)
        XCTAssertEqual(try Acta.hash(JSON.parse(saved.value["kv:dotrino.identity.p.p1.acta"]!)), try Acta.hash(newest))
        session.close()
        let again = try await ActaSync.update(caught.value!, conn, save: { _, _ in XCTFail("nothing to save") })
        XCTAssertEqual(again.actaSeq, caught.value!.actaSeq)
    }
}

/// A value shared with callbacks that run on other threads.
final class Locked<T>: @unchecked Sendable {
    private let lock = NSLock()
    private var v: T
    init(_ v: T) { self.v = v }
    var value: T { lock.withLock { v } }
    @discardableResult func update(_ f: (inout T) -> Void) -> T { lock.withLock { f(&v); return v } }
}
