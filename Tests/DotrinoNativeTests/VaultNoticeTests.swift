import XCTest
@testable import DotrinoNative

/// What the vault tells its approvers along with the list of requests (`notices`, vaultd ≥ 0.147.0).
/// Same as `VaultNoticeTest.kt`.
final class VaultNoticeTests: XCTestCase {
    func testReadsTheNoticesOfTheAnswer() throws {
        let body = try JSON.parse(#"{"op":"approvals","items":[],"notices":[{"id":"n1","ev":"updated","version":"0.147.0","from":"0.146.0","ts":1790000000000}]}"#)
        XCTAssertEqual(VaultNotice.list(from: body), [VaultNotice(id: "n1", ev: "updated", version: "0.147.0", from: "0.146.0", ts: 1790000000000)])
    }

    func testAnOlderVaultSendsNoFieldAndThatIsNoNotices() throws {
        XCTAssertTrue(VaultNotice.list(from: try JSON.parse(#"{"op":"approvals","items":[]}"#)).isEmpty)
    }

    func testAnEntryWithoutIdOrEventIsDropped() throws {
        let body = try JSON.parse(#"{"notices":[{"ev":"updated"},{"id":"n2"},"x",{"id":"n3","ev":"updated"}]}"#)
        XCTAssertEqual(VaultNotice.list(from: body), [VaultNotice(id: "n3", ev: "updated", version: "", from: "", ts: 0)])
    }
}
