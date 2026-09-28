import XCTest
@testable import DotrinoNative

final class StoreThreadsTests: XCTestCase {
    private func entry(_ id: String, _ v: String) -> JSON { .object(["id": .string(id), "v": .string(v)]) }

    func testAppendReplacesBySameIdInPlace() throws {
        var t = try StoreThreads.decode(nil)
        try t.append("a", entry("1", "x"))
        try t.append("a", entry("2", "y"))
        try t.append("a", entry("1", "z"))
        XCTAssertEqual(t.list("a").map { $0["v"]?.string }, ["z", "y"])
    }

    func testRoundTripsThroughText() throws {
        var t = try StoreThreads.decode(nil)
        try t.append("b", entry("1", "x"))
        try t.append("a", entry("2", "y"))
        let back = try StoreThreads.decode(Data(t.encode().utf8))
        XCTAssertEqual(back, t)
    }

    func testRemoveSaysWhetherSomethingWent() throws {
        var t = try StoreThreads.decode(nil)
        try t.append("a", entry("1", "x"))
        XCTAssertFalse(t.remove("a", id: "nope"))
        XCTAssertTrue(t.remove("a", id: "1"))
        XCTAssertEqual(t.list("a").count, 0)
    }

    func testBadShapeIsAnErrorNotEmpty() {
        XCTAssertThrowsError(try StoreThreads.decode(Data("[]".utf8)))
        XCTAssertThrowsError(try StoreThreads.decode(Data(#"{"order":["a"],"threads":{"a":[{"v":1}]}}"#.utf8)))
    }
}
