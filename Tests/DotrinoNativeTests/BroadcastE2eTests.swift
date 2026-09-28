import CryptoKit
import XCTest
@testable import DotrinoNative

/// The native HOST of a broadcast against a real proxy, watched by `@dotrino/lobby` itself: the
/// harness `test-vectors/e2e-broadcast.mjs` runs on another machine of the LAN and serves its
/// files over HTTP. Runs only when `DOTRINO_E2E_BCAST_URL` is set (as
/// `TEST_RUNNER_DOTRINO_E2E_BCAST_URL` for xcodebuild); otherwise it is skipped and says so.
///
///   E2E_HOST=<ip> E2E_HTTP=8766 node test-vectors/e2e-broadcast.mjs /tmp/bcast.json &
///   TEST_RUNNER_DOTRINO_E2E_BCAST_URL=http://<ip>:8766 xcodebuild … test
final class BroadcastE2eTests: XCTestCase {
    private func get(_ base: URL, _ path: String) async throws -> Data? {
        let (d, r) = try await URLSession.shared.data(from: base.appendingPathComponent(path))
        return (r as? HTTPURLResponse)?.statusCode == 200 ? d : nil
    }

    private func key<K>(_ j: JSON, _ make: (Data) throws -> K) throws -> K { try make(Crypto.fromB64url(j["d"]!.string!)) }

    func testALobbyViewerWatchesTheNativeHost() async throws {
        guard let s = ProcessInfo.processInfo.environment["DOTRINO_E2E_BCAST_URL"], let base = URL(string: s) else {
            throw XCTSkip("DOTRINO_E2E_BCAST_URL not set: start test-vectors/e2e-broadcast.mjs to run this")
        }
        let f = try JSON.parse(try XCTUnwrap(try await get(base, "config")))
        let profile = Profile.of(ProfileTests.SoftKeys(s: try key(f["sign"]!) { try .init(rawRepresentation: $0) },
                                                       e: try key(f["enc"]!) { try .init(rawRepresentation: $0) }))
        let transport = ProfileTests.SoftKeys(s: try key(f["transport"]!) { try .init(rawRepresentation: $0) },
                                              e: try key(f["transportEnc"]!) { try .init(rawRepresentation: $0) })
        let host = BroadcastHost(url: f["proxyUrl"]!.string!, gameId: "padel", profile: profile, transport: transport)
        try await host.start()
        try await host.publish(["name": "Torneo ñandú", "round": 1])
        var req = URLRequest(url: base.appendingPathComponent("ref"))
        req.httpMethod = "POST"
        req.httpBody = Data(try BroadcastHost.encodeRef(host.linkRef!, hostPubkey: profile.publickey).utf8)
        _ = try await URLSession.shared.data(for: req)

        func seen() async throws -> [JSON] {
            guard let d = try await get(base, "seen"), let t = String(data: d, encoding: .utf8) else { return [] }
            return try t.split(separator: "\n").map { try JSON.parse(String($0)) }
        }
        var tries = 0
        while try await seen().isEmpty { tries += 1; XCTAssertLessThan(tries, 300); try await Task.sleep(nanoseconds: 200_000_000) }
        XCTAssertGreaterThanOrEqual(host.viewers, 1)
        try await host.publish(["name": "Torneo ñandú", "round": 2])
        tries = 0
        while try await seen().count < 2 { tries += 1; XCTAssertLessThan(tries, 150); try await Task.sleep(nanoseconds: 200_000_000) }
        let states = try await seen().map { $0["state"]! }
        XCTAssertEqual(states.first?["name"]?.string, "Torneo ñandú")
        XCTAssertEqual(states.last?["round"]?.int, 2)
        tries = 0
        var denied: Data?
        while denied == nil { denied = try await get(base, "denied"); tries += 1; XCTAssertLessThan(tries, 150); if denied == nil { try await Task.sleep(nanoseconds: 200_000_000) } }
        XCTAssertEqual(try JSON.parse(denied!)["reason"]?.string, "bad-secret")
        await host.close()
        var done = URLRequest(url: base.appendingPathComponent("done"))
        done.httpMethod = "POST"
        _ = try await URLSession.shared.data(for: done)
    }
}
