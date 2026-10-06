import XCTest
@testable import DotrinoNative
@testable import DotrinoNativeWebRTC

/// The counters of the network stats (bytes by road and per connection, as `stats.js`) and the
/// rule that decides direct vs TURN from what ICE chose. Same as `TrafficStatsTest.kt`.
final class TrafficStatsTests: XCTestCase {
    func testBytesGoToTheRoadTheyTookAndToTheirConnection() {
        let t = TrafficStats()
        t.frame(incoming: false, bytes: 50)
        t.peer(incoming: false, path: "proxy", token: "A", pubkey: nil, bytes: 30)
        t.peer(incoming: false, path: "direct", token: "A", pubkey: nil, bytes: 10)
        t.peer(incoming: true, path: "turn", token: "A", pubkey: nil, bytes: 7)
        t.peer(incoming: false, path: "proxy", token: nil, pubkey: "PK", bytes: 5)
        let (proxy, peers) = t.snapshot(routeOf: { $0 == "A" ? "direct" : nil }, pubkeyOf: { $0 == "A" ? "PKA" : nil })
        XCTAssertEqual(proxy.bytesOut, 50); XCTAssertEqual(proxy.framesOut, 1)
        let a = peers.first { $0.token == "A" }!
        XCTAssertEqual(a.route, "direct"); XCTAssertEqual(a.pubkey, "PKA")
        XCTAssertEqual(a.bytesOut.proxy, 30); XCTAssertEqual(a.bytesOut.direct, 10); XCTAssertEqual(a.bytesIn.turn, 7)
        XCTAssertEqual(a.msgsOut, 2); XCTAssertEqual(a.msgsIn, 1)
        let k = peers.first { $0.pubkey == "PK" }!
        XCTAssertNil(k.token); XCTAssertEqual(k.route, "proxy"); XCTAssertEqual(k.bytesOut.total, 5)
        XCTAssertEqual(utf8Length("😀"), 4)
    }

    private func report(_ local: String, _ remote: String) -> [String: (type: String, values: [String: NSObject])] {
        [
            "T": ("transport", ["selectedCandidatePairId": "P" as NSString]),
            "P": ("candidate-pair", ["localCandidateId": "L" as NSString, "remoteCandidateId": "R" as NSString,
                                     "state": "succeeded" as NSString, "nominated": NSNumber(value: true)]),
            "L": ("local-candidate", ["candidateType": local as NSString]),
            "R": ("remote-candidate", ["candidateType": remote as NSString]),
        ]
    }

    func testRelayAtEitherEndIsTurnOtherwiseDirectAndNothingIsGuessed() {
        XCTAssertEqual(routeOf(report("host", "srflx")), "direct")
        XCTAssertEqual(routeOf(report("relay", "host")), "turn")
        XCTAssertEqual(routeOf(report("srflx", "relay")), "turn")
        XCTAssertNil(routeOf([:]))
    }
}
