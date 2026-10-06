import CryptoKit
import XCTest
@testable import DotrinoNative

/// The client of `@dotrino/remote-agent`: the session channel against a vector the JS wrote
/// (`e2e.js`), and the judging of an agent's ack against the record. Same as `RemoteAgentTest.kt`.
final class RemoteAgentTests: XCTestCase {
    private struct Keys {
        let s = P256.Signing.PrivateKey()
        var publickey: String { Crypto.jwk(raw: s.publicKey.rawRepresentation) }
        func sign(_ text: String) throws -> String { Crypto.b64(try s.signature(for: Data(text.utf8)).rawRepresentation) }
    }

    func testOpensWhatTheJsAgentSealed() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "remote-agent", withExtension: "json"))
        let v = try JSON.parse(Data(contentsOf: url))
        let priv = try P256.KeyAgreement.PrivateKey(rawRepresentation: Crypto.fromB64url(v["clientPrivateJwk"]!["d"]!.string!))
        let key = try RemoteAgent.deriveKey(priv, v["agentRaw"]!.string!, v["sid"]!.string!)
        XCTAssertEqual(try RemoteAgent.open(key, v["env"]!), v["msg"])
    }

    func testBothEndsDeriveTheSameKey() throws {
        let (a, aPub) = RemoteAgent.makeEphemeral(), (b, bPub) = RemoteAgent.makeEphemeral()
        let msg: JSON = ["type": "input", "data": "ls -la\r"]
        let env = try RemoteAgent.seal(try RemoteAgent.deriveKey(a, bPub, "s1"), msg)
        XCTAssertEqual(try RemoteAgent.open(try RemoteAgent.deriveKey(b, aPub, "s1"), env), msg)
        XCTAssertThrowsError(try RemoteAgent.open(try RemoteAgent.deriveKey(b, aPub, "s2"), env), "another session id is another key")
    }

    /// A record with [sealer] sealing and [device] a plain member, and the device's paper issued by [issuer] naming record [seq].
    private func world(_ sealer: Keys, _ device: Keys, _ issuer: Keys, seq: Int64, actaSeq: Int64) throws -> (JSON, JSON, JSON) {
        let acta: JSON = ["v": 5, "seq": .int(actaSeq), "members": [
            ["pub": .string(sealer.publickey), "caps": ["sealer", "sign"]],
            ["pub": .string(device.publickey), "caps": ["sign"]],
        ]]
        var cert: [String: JSON] = ["v": 1, "iss": .string(issuer.publickey), "sub": .string(device.publickey), "scope": ["vault:sign"], "iat": 1, "seq": .int(seq)]
        cert["sig"] = .string(try issuer.sign(Canonical.stringify(Delegation.body(.object(cert)))))
        let ack: JSON = ["op": .string(RemoteAgent.ack), "sid": "s", "publickey": .string(device.publickey)]
        return (acta, .object(cert), ack)
    }

    func testAnAgentOfMyRecordIsBelieved() throws {
        let sealer = Keys(), agent = Keys()
        let (acta, cert, ack) = try world(sealer, agent, sealer, seq: 40, actaSeq: 44)
        XCTAssertNil(RemoteAgent.judge(ack, try agent.sign(Canonical.stringify(ack)), cert, acta))
    }

    func testAndEachWayItIsNotSaysWhy() throws {
        let sealer = Keys(), agent = Keys(), stranger = Keys()
        let (acta, cert, ack) = try world(sealer, agent, sealer, seq: 40, actaSeq: 44)
        let sig = try agent.sign(Canonical.stringify(ack))
        XCTAssertEqual(RemoteAgent.judge(ack, sig, cert, nil), "no-acta")
        XCTAssertEqual(RemoteAgent.judge(ack, try stranger.sign(Canonical.stringify(ack)), cert, acta), "bad-action-signature")
        XCTAssertEqual(RemoteAgent.judge(ack, sig, nil, acta), "cert-device-mismatch")
        let (acta2, cert2, ack2) = try world(sealer, agent, sealer, seq: 50, actaSeq: 44)
        XCTAssertEqual(RemoteAgent.judge(ack2, try agent.sign(Canonical.stringify(ack2)), cert2, acta2), "acta-vieja")
        let (acta3, cert3, ack3) = try world(sealer, agent, stranger, seq: 40, actaSeq: 44)
        XCTAssertEqual(RemoteAgent.judge(ack3, try agent.sign(Canonical.stringify(ack3)), cert3, acta3), "untrusted-issuer")
    }

    // The tabs of a phone share one connection: an error that names ANOTHER session is not mine.
    func testAnErrorIsForTheSessionItNames() {
        let mine: JSON = ["type": .string(RemoteAgent.errorType), "code": "unknown-session", "sid": "a", "error": "sesión desconocida o expirada"]
        XCTAssertEqual(RemoteAgent.sessionError(mine, sid: "a")?.code, "unknown-session")
        XCTAssertNil(RemoteAgent.sessionError(mine, sid: "b"))
        // An agent older than remote-agent 0.14.0 sends neither code nor sid: still mine, as before.
        let old: JSON = ["type": .string(RemoteAgent.errorType), "error": "sesión desconocida o expirada"]
        XCTAssertEqual(RemoteAgent.sessionError(old, sid: "a")?.code, "agent-error")
    }
}
