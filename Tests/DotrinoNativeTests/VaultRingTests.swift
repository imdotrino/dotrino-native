import XCTest
import CryptoKit
@testable import DotrinoNative

/// The subject of a ring: sealed to this phone, opens here and nowhere else.
final class VaultRingTests: XCTestCase {
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

    private func sealed(_ subject: String, to keys: SoftKeys) throws -> String {
        let cek = Crypto.makeContentKey()
        let wrap = try Crypto.wrapForMember(cek, memberEncPub: keys.encPub)
        let envelope = try Crypto.encryptWithCek(cek, gen: 0, subject)
        return JSON.object(["wrap": wrap, "envelope": envelope]).text
    }

    func testAnApprovalSubjectOpensAsTheRequestItAnnounces() throws {
        let me = SoftKeys(), other = SoftKeys()
        let s = try sealed(#"{"ev":"approval","id":"p1","ns":"proxy","kind":"read","deviceId":"904C-1002","label":"proxy1","exp":9,"ctx":null}"#, to: me)
        let subject = try XCTUnwrap(VaultRing.open(s, keys: me))
        let a = try XCTUnwrap(VaultRing.approval(subject))
        XCTAssertEqual([a.id, a.ns, a.kind, a.deviceId, a.label], ["p1", "proxy", "read", "904C-1002", "proxy1"])
        XCTAssertNil(VaultRing.notice(subject))
        XCTAssertNil(VaultRing.open(s, keys: other), "another account's key does not open it")
        XCTAssertNil(VaultRing.open("not json", keys: me))
    }

    func testADeliveredSubjectOpensAsTheNotice() throws {
        let me = SoftKeys()
        let s = try sealed(#"{"ev":"delivered","id":"n1","ts":5,"kind":"passwords","ns":"passwords","deviceId":"AB12-CD34","label":"servidor"}"#, to: me)
        let n = try XCTUnwrap(VaultRing.notice(try XCTUnwrap(VaultRing.open(s, keys: me))))
        XCTAssertEqual([n.ev, n.kind, n.ns, n.device], ["delivered", "passwords", "passwords", "servidor"])
    }
}
