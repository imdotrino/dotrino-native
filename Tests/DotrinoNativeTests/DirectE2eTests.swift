import XCTest
@testable import DotrinoNative
import DotrinoNativeWebRTC

/// THE DIRECT ROAD against the JS pillar (`test-vectors/e2e-direct.mjs`). Same case as
/// `DirectE2eTest.kt`: first message by the proxy, the channel opens underneath, the next goes
/// DIRECT. Runs only with `TEST_RUNNER_DOTRINO_DIRECT_URL` / `TEST_RUNNER_DOTRINO_DIRECT_CODE`.
final class DirectE2eTests: XCTestCase {
    func testFirstByProxyThenDirect() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let url = env["DOTRINO_DIRECT_URL"], let code = env["DOTRINO_DIRECT_CODE"] else {
            throw XCTSkip("run with TEST_RUNNER_DOTRINO_DIRECT_URL/CODE (test-vectors/e2e-direct.mjs)")
        }
        let direct = WebRTCDirect()
        let session = SealedSession(urls: [url], profile: Profile.of(ProfileTests.SoftKeys.fresh()), app: "messenger")
        session.useDirect(direct)
        let pongs = AsyncStream<JSON>.makeStream()
        _ = session.onMessage { m in if m.payload["type"]?.string == "PONG" { pongs.continuation.yield(m.payload) } }
        var it = pongs.stream.makeAsyncIterator()
        defer { session.close() }
        session.start()
        let online = await session.awaitOnline()
        XCTAssertTrue(online, "offline: \(session.status)")
        let token = try await session.redeemPairingCode(code)
        let who = try await session.whoIs(token)
        XCTAssertNotNil(who)

        try await session.sendSealed(toToken: token, ["type": "PING", "n": 1])
        let first = await it.next()
        XCTAssertEqual(first?["gotVia"]?.string, "proxy")

        let deadline = Date().addingTimeInterval(30)
        while !direct.isOpen(token) && Date() < deadline { try await Task.sleep(nanoseconds: 200_000_000) }
        XCTAssertTrue(direct.isOpen(token), "the direct channel did not open")
        try await session.sendSealed(toToken: token, ["type": "PING", "n": 2])
        let second = await it.next()
        XCTAssertEqual(second?["n"]?.int, 2)
        XCTAssertEqual(second?["gotVia"]?.string, "webrtc", "the second message did not go direct")
    }
}
