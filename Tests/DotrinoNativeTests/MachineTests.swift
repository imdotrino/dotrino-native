import XCTest
@testable import DotrinoNative

/// Where the phone runs, in the shape of `@dotrino/identity/machine`. Same as `MachineTest.kt`.
final class MachineTests: XCTestCase {
    private func line(_ m: JSON?) -> String? {
        guard let m else { return nil }
        return ["kind", "os", "name"].compactMap { m[$0]?.string }.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    func testIPhoneWithTheModel() {
        XCTAssertEqual(line(Machine.of(osMajor: 18, model: "iPhone12,1")), "ios · iOS 18 · iPhone12,1")
    }

    func testIPadSaysIPadOS() {
        XCTAssertEqual(line(Machine.of(osMajor: 17, model: "iPad13,4")), "ios · iPadOS 17 · iPad13,4")
    }

    func testNothingToSayIsNil() {
        XCTAssertNil(Machine.of(osMajor: 0, model: ""))
    }

    func testTheCurrentOneIsIOS() {
        XCTAssertEqual(Machine.current?["kind"]?.string, "ios")
    }
}
