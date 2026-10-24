import XCTest
@testable import DotrinoNative

/// THE LOCAL NETWORK, live, against another machine on the same network (the simulator shares the
/// Mac's). Skipped without it: `TEST_RUNNER_LAN_EXPECT=<data>` discovers what the other announces
/// as a `pair`; `TEST_RUNNER_LAN_ANNOUNCE=<data>` announces it for `LAN_HOLD_S` seconds.
final class LanLiveTests: XCTestCase {
    private let env = ProcessInfo.processInfo.environment

    func testDiscoversWhatTheNetworkAnnounces() async throws {
        let want = try XCTSkipUnless(env["LAN_EXPECT"], "needs LAN_EXPECT")
        let found = await Nearby(bluetooth: nil).discover(kind: BleWire.kindPair, timeout: 8)
        XCTAssertTrue(found.contains(want), "found: \(found)")
    }

    func testAnnouncesOnTheNetwork() async throws {
        let text = try XCTSkipUnless(env["LAN_ANNOUNCE"], "needs LAN_ANNOUNCE")
        let stop = try Nearby(bluetooth: nil).announce(kind: BleWire.kindPair, data: text)
        try await Task.sleep(nanoseconds: UInt64((Double(env["LAN_HOLD_S"] ?? "") ?? 20) * 1_000_000_000))
        stop()
    }
}

private func XCTSkipUnless(_ v: String?, _ why: String) throws -> String {
    guard let v else { throw XCTSkip(why) }
    return v
}
