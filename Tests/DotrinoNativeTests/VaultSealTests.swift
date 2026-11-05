import XCTest
@testable import DotrinoNative

/// What goes to the vault goes sealed (`VaultSeal`); the answer opens only with the one-use key.
/// The same cases as `VaultSealTest.kt`.
final class VaultSealTests: XCTestCase {
    private let payload: JSON = ["type": "vault.secrets", "data": ["op": "approvals", "machine": "iPhone-XYZ"]]

    func testTheWholeRequestIsInsideTheEnvelope() throws {
        let vault = VaultSeal.ReplyKey()   // stands in for the vault's transport key
        let env = try VaultSeal.seal(payload, to: vault.encPub)
        for plain in ["vault.secrets", "approvals", "iPhone-XYZ"] { XCTAssertFalse(env.text.contains(plain), "in the clear: \(plain)") }
        XCTAssertTrue(VaultSeal.isSealed(env))
        XCTAssertEqual(vault.open(env), payload)
        XCTAssertNil(VaultSeal.ReplyKey().open(env), "sealed to someone else does not open")
    }

    func testOnlyTheVaultsOfTheRecord() {
        let acta: JSON = ["members": [["pub": "A", "cn": "vault"], ["pub": "PHONE"], ["pub": "A", "cn": "vault"]]]
        XCTAssertEqual(VaultSeal.vaultKeys(acta), ["A"])
        XCTAssertEqual(VaultSeal.vaultKeys(nil), [])
    }

    func testTheMigrationWindowCloses() {
        XCTAssertTrue(VaultSeal.plainAllowed(VaultSeal.plainUntilMs - 1))
        XCTAssertFalse(VaultSeal.plainAllowed(VaultSeal.plainUntilMs))
        XCTAssertEqual(VaultSeal.plainUntilMs, 1_797_292_800_000)
    }
}
