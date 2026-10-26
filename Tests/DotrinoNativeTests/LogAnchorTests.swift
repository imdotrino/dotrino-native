import XCTest
@testable import DotrinoNative

/// The approver's side of the log anchor. Same as `LogAnchorTest.kt` and `test/log-anchor.test.mjs`.
final class LogAnchorTests: XCTestCase {
    let a = String(repeating: "a", count: 64)
    let b = String(repeating: "b", count: 64)

    func testTheApproverSeesTheCut() {
        XCTAssertEqual(LogAnchor.compare(lastSeq: nil, lastHead: nil, seq: 5, head: a), .first)
        XCTAssertEqual(LogAnchor.compare(lastSeq: 5, lastHead: a, seq: 9, head: b), .advanced)
        XCTAssertEqual(LogAnchor.compare(lastSeq: 5, lastHead: a, seq: 5, head: a), .same)
        XCTAssertEqual(LogAnchor.compare(lastSeq: 9, lastHead: b, seq: 5, head: a), .truncated)
        XCTAssertEqual(LogAnchor.compare(lastSeq: 9, lastHead: b, seq: 9, head: a), .forked)
        XCTAssertTrue(LogAnchor.Verdict.truncated.isAlarm)
        XCTAssertTrue(LogAnchor.Verdict.forked.isAlarm)
        XCTAssertFalse(LogAnchor.Verdict.advanced.isAlarm)
    }

    func testTheAnchorIsReadFromTheNoticesAndTheNewestWins() throws {
        let body = try JSON.parse("""
        {"notices":[
          {"id":"u","ev":"updated","version":"0.1.0","from":"0.0.9","ts":1},
          {"id":"x","ev":"log-anchor","logSeq":7,"head":"\(a)","ts":2,"tsa":{"signature":"s"}},
          {"id":"y","ev":"log-anchor","logSeq":9,"head":"\(b)","ts":3,"tsa":{"signature":"s"}}
        ]}
        """)
        let last = LogAnchor.latest(VaultNotice.list(from: body))
        XCTAssertEqual(last?.logSeq, 9)
        XCTAssertEqual(last?.head, b)
    }
}
