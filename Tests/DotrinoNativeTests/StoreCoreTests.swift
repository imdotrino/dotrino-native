import XCTest
@testable import DotrinoNative

/// The store's rules against `@dotrino/store` itself (vectors.json). Same cases as `StoreCoreTest.kt`.
final class StoreCoreTests: XCTestCase {
    private func store() throws -> JSON {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        return try XCTUnwrap(try JSON.parse(Data(contentsOf: url))["store"])
    }

    func testTheDigestIsTheOneTheBrowserAndTheVaultCompute() throws {
        for c in try store()["digests"]!.array! {
            XCTAssertEqual(StoreThreads.digest(c["entries"]!.array!), c["digest"]!.string!)
        }
    }

    private func index(_ o: JSON) -> StoreThreads.Index {
        StoreThreads.Index(items: o["items"]!.array!.map { ($0.array![0].string!, $0.array![1].int!) },
                           tombs: o["tombs"]!.array!.map { ($0.array![0].string!, $0.array![1].int!, $0.array![2].int!) })
    }

    func testThePlanIsTheOneTheBrowserMakes() throws {
        for c in try store()["plans"]!.array! {
            let p = StoreThreads.plan(local: index(c["local"]!), remote: index(c["remote"]!), max: Int(c["max"]!.int!))
            let w = c["plan"]!
            XCTAssertEqual(p.pushIds, w["pushIds"]!.array!.map { $0.string! })
            XCTAssertEqual(p.pushTombs, w["pushTombs"]!.array!.map { $0.string! })
            XCTAssertEqual(p.pullIds, w["pullIds"]!.array!.map { $0.string! })
            XCTAssertEqual(p.pullTombs.map { "\($0.0)|\($0.1)|\($0.2)" }, w["pullTombs"]!.array!.map { "\($0.array![0].string!)|\($0.array![1].int!)|\($0.array![2].int!)" })
        }
    }
}

/// `cond ? x : nil` with JSON makes `JSON.null`, not «no value»: the backup looped forever on it.
final class JSONNilTests: XCTestCase {
    func testObjectValueIsNoValueForNull() {
        XCTAssertNil(JSON.null.objectValue)
        XCTAssertNil(JSON.string("x").objectValue)
        XCTAssertNotNil(JSON.object([:]).objectValue)
        let page: JSON = ["rest": .null]
        XCTAssertNil(page["rest"]?.objectValue)
    }
}
