import XCTest
@testable import DotrinoNative

/// The local-network TXT, string by string as `@dotrino/proxy-client` writes it (`src/lan/mdns.js`).
final class LanWireTests: XCTestCase {
    func testTxtIsTheSameStrings() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        let v = try XCTUnwrap(try JSON.parse(Data(contentsOf: url))["lan"])
        XCTAssertEqual(v["service"]!.string, LanWire.serviceType + ".local")
        for e in v["cases"]!.array! {
            let kind = e["kind"]!.string!, data = e["data"]!.string!
            let want = e["txt"]!.array!.map { String(data: Data(base64Encoded: $0.string!)!, encoding: .utf8)! }
            let got = try LanWire.txtEntries(kind: kind, data: data)
            XCTAssertEqual(want, got.map { $0.0 + "=" + String(data: $0.1, encoding: .utf8)! })
            let back = LanWire.fromTxt(Dictionary(uniqueKeysWithValues: got))
            XCTAssertEqual(back?.kind, kind)
            XCTAssertEqual(back?.data, data)
        }
    }

    func testIncompleteTxtIsIgnored() throws {
        var m = Dictionary(uniqueKeysWithValues: try LanWire.txtEntries(kind: "pair", data: String(repeating: "x", count: 300)))
        m["d1"] = nil
        XCTAssertNil(LanWire.fromTxt(m))
    }
}
