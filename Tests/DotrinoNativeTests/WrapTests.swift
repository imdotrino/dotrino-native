import XCTest
import CryptoKit
@testable import DotrinoNative

/// `wrapForMember` / `openWrap`: what one device wraps, only the member it was wrapped to opens.
final class WrapTests: XCTestCase {
    private struct SoftKeys: DeviceKeys {
        let s = P256.Signing.PrivateKey()
        let e = P256.KeyAgreement.PrivateKey()
        var publickey: String { Crypto.jwk(raw: s.publicKey.rawRepresentation) }
        var encPub: String { Crypto.jwk(raw: e.publicKey.rawRepresentation) }
        func signBytes(_ bytes: Data) throws -> String { Crypto.b64(try s.signature(for: bytes).rawRepresentation) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data {
            try e.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
        }
    }

    func testAWrapOpensWithTheMembersKeyAndNoOther() throws {
        let member = SoftKeys(), other = SoftKeys()
        let cek = Crypto.makeContentKey()
        let wrap = try Crypto.wrapForMember(cek, memberEncPub: member.encPub)
        XCTAssertEqual(try Crypto.openWrap(wrap, keys: member), cek)
        XCTAssertThrowsError(try Crypto.openWrap(wrap, keys: other))
    }

    func testTheProfileOpensWhatWasSealedToIt() throws {
        let keys = SoftKeys()
        let cek = Crypto.makeContentKey()
        let envelope = try Crypto.encryptWithCek(cek, gen: 3, "hunter2")
        let wrap = try Crypto.wrapForMember(cek, memberEncPub: keys.encPub)
        XCTAssertEqual(try Profile.of(keys).openSealed(wrap: wrap, envelope: envelope), "hunter2")
    }
}
