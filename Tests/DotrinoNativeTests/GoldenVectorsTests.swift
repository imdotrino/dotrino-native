import CryptoKit
import XCTest
@testable import DotrinoNative

/// Everything the phone signs or opens has to be byte-identical to what the JS pilar does.
/// The vectors are the SAME file the Android tests read
/// (`android/dotrino-native/src/test/resources/vectors.json`, made by `test-vectors/gen.mjs`
/// from `@dotrino/identity` itself): a mismatch here is a signature the vault would reject or
/// an envelope the phone could not open.
final class GoldenVectorsTests: XCTestCase {
    private var v: JSON!

    override func setUpWithError() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        v = try JSON.parse(Data(contentsOf: url))
    }

    /// A key from its private JWK, in software. Test-only: production keys live in the enclave.
    private struct SoftwareKeys: DeviceKeys {
        let sign: P256.Signing.PrivateKey?
        let enc: P256.KeyAgreement.PrivateKey?
        var publickey: String { sign.map { Crypto.jwk(raw: $0.publicKey.rawRepresentation) } ?? "" }
        var encPub: String { enc.map { Crypto.jwk(raw: $0.publicKey.rawRepresentation) } ?? "" }

        init(sign: JSON?, enc: JSON?) throws {
            self.sign = try sign.map { try P256.Signing.PrivateKey(rawRepresentation: Crypto.fromB64url($0["d"]!.string!)) }
            self.enc = try enc.map { try P256.KeyAgreement.PrivateKey(rawRepresentation: Crypto.fromB64url($0["d"]!.string!)) }
        }

        func signBytes(_ bytes: Data) throws -> String { Crypto.b64(try sign!.signature(for: bytes).rawRepresentation) }
        func agree(_ peer: P256.KeyAgreement.PublicKey) throws -> Data {
            try enc!.sharedSecretFromKeyAgreement(with: peer).withUnsafeBytes { Data($0) }
        }
    }

    func testCanonicalMatchesThePilar() throws {
        for c in v["canon"]!.array! {
            XCTAssertEqual(try Canonical.stringify(c["input"]!), c["canonical"]!.string!)
        }
    }

    /// Decimals as JS writes them (a rating of 4.5); what it would write differently is refused.
    func testCanonicalDecimalsOnlyAsJsWritesThem() throws {
        XCTAssertEqual(try Canonical.stringify(["a": .double(1.5)]), #"{"a":1.5}"#)
        XCTAssertThrowsError(try Canonical.stringify(["a": .double(1e-7)]))
    }

    func testASignatureMadeByThePilarVerifiesHere() {
        let s = v["sign"]!
        let data = s["data"]!
        XCTAssertTrue(Crypto.verify(publickey: s["publickey"]!.string!, data: data, signature: s["signature"]!.string!))
        var tampered = data.object!
        tampered["ts"] = 1
        XCTAssertFalse(Crypto.verify(publickey: s["publickey"]!.string!, data: .object(tampered), signature: s["signature"]!.string!))
    }

    func testWhatThePhoneSignsVerifiesAsThePilarWould() throws {
        let s = v["sign"]!
        let keys = try SoftwareKeys(sign: s["privateJwk"], enc: nil)
        XCTAssertTrue(Delegation.samePubkey(keys.publickey, s["publickey"]!.string!), "same JWK shape as the pilar")
        let data = s["data"]!
        let sig = try keys.sign(Canonical.stringify(data))
        XCTAssertEqual(try Crypto.fromB64(sig).count, 64)
        XCTAssertTrue(Crypto.verify(publickey: keys.publickey, data: data, signature: sig))
    }

    func testOpensWhatTheVaultSealedToThisDevice() throws {
        let s = v["sealed"]!
        let keys = try SoftwareKeys(sign: nil, enc: s["encPrivateJwk"])
        XCTAssertEqual(try Crypto.openSealed(wrap: s["ctxWrap"]!, envelope: s["ctxEnvelope"]!, keys: keys), s["ctxPlain"]!.string!)
    }

    func testAnEnvelopeForAnotherKeyDoesNotOpen() throws {
        let s = v["sealed"]!
        let other = try SoftwareKeys(sign: nil, enc: v["sign"]!["privateJwk"])
        XCTAssertThrowsError(try Crypto.openSealed(wrap: s["ctxWrap"]!, envelope: s["ctxEnvelope"]!, keys: other))
    }

    func testThePaperChecksAgainstThePinnedVault() {
        let c = v["cert"]!
        let cert = c["cert"]!
        let master = c["master"]!.string!
        let sub = c["sub"]!.string!
        XCTAssertNil(Delegation.check(cert, vault: master, sub: sub, expectedScope: "vault:approve"))
        XCTAssertEqual(Delegation.check(cert, vault: master, sub: sub, expectedScope: "vault:admin"), "scope")
        XCTAssertEqual(Delegation.check(cert, vault: master, sub: v["sealed"]!["encPub"]!.string!, expectedScope: nil), "sub")
        XCTAssertEqual(Delegation.check(cert, vault: sub, sub: sub, expectedScope: nil), "paper-from-another-vault")
        var widened = cert.object!
        widened["scope"] = ["vault:admin"]
        XCTAssertEqual(Delegation.check(.object(widened), vault: master, sub: sub, expectedScope: nil), "bad-signature")
    }

    func testKeyIdAndLabelMatchThePilar() throws {
        let k = v["keyid"]!
        XCTAssertEqual(try Delegation.pubkeyId(k["publickey"]!.string!), k["id"]!.string!)
        XCTAssertEqual(try Delegation.keyLabel(k["publickey"]!.string!), k["label"]!.string!)
    }

    func testUnauthorizedReasonIsTheCode() {
        XCTAssertEqual(VaultClient.codeOf("unauthorized: acta-vieja (seq 3)"), "acta-vieja")
        XCTAssertEqual(VaultClient.codeOf("something else"), "vault-error")
    }
}
