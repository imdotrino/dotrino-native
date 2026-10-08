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

    func testADeviceThatIsNotTheVaultIsNamedByItsLabelOrItsId() throws {
        let body = try JSON.parse(#"""
        {"notices":[
          {"id":"a","ev":"updated","version":"0.30.0","from":"0.29.0","ts":1,"product":"@dotrino/terminal-agent","deviceId":"AB12-CD34","label":"laptop"},
          {"id":"b","ev":"updated","version":"0.30.0","from":"0.29.0","ts":2,"product":"@dotrino/terminal-agent","deviceId":"AB12-CD34"},
          {"id":"c","ev":"updated","version":"0.147.0","from":"0.146.0","ts":3,"product":"@dotrino/vaultd","deviceId":"EF56-7890","label":"vault"},
          {"id":"d","ev":"updated","version":"0.147.0","from":"0.146.0","ts":4}
        ]}
        """#)
        let list = VaultNotice.list(from: body)
        XCTAssertEqual(list.map(\.device), ["laptop", "AB12-CD34", nil, nil])
        XCTAssertEqual(list[0].product, "@dotrino/terminal-agent")
        XCTAssertEqual(list[0].deviceId, "AB12-CD34")
    }
}
