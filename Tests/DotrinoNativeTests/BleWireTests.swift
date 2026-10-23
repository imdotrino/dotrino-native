import CryptoKit
import XCTest
@testable import DotrinoNative

/// The Bluetooth wire has to be byte-identical to `@dotrino/proxy-client` (`src/ble/wire.js`):
/// the vector (`vectors.json` → `ble`) is made by the JS pilar itself.
final class BleWireTests: XCTestCase {
    private var v: JSON!

    override func setUpWithError() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "vectors", withExtension: "json"))
        v = try XCTUnwrap(try JSON.parse(Data(contentsOf: url))["ble"])
    }

    private func priv(_ side: String) throws -> P256.KeyAgreement.PrivateKey {
        try P256.KeyAgreement.PrivateKey(rawRepresentation: Crypto.fromB64url(v[side]!["privateJwk"]!["d"]!.string!))
    }

    func testUuidsMatch() {
        XCTAssertEqual(v["uuids"]!["service"]!.string, BleWire.serviceUUID)
        XCTAssertEqual(v["uuids"]!["hello"]!.string, BleWire.helloUUID)
        XCTAssertEqual(v["uuids"]!["beacon"]!.string, BleWire.beaconUUID)
        XCTAssertEqual(v["uuids"]!["rx"]!.string, BleWire.rxUUID)
        XCTAssertEqual(v["uuids"]!["tx"]!.string, BleWire.txUUID)
    }

    func testBothSidesDeriveTheJsKey() throws {
        let a = v["a"]!, b = v["b"]!
        let ka = try BleWire.deriveKey(try priv("a"), theirEpub: b["epub"]!.string!, a["token"]!.string!, b["token"]!.string!)
        let kb = try BleWire.deriveKey(try priv("b"), theirEpub: a["epub"]!.string!, b["token"]!.string!, a["token"]!.string!)
        let want = try Crypto.fromB64(v["key"]!.string!)
        XCTAssertEqual(ka.withUnsafeBytes { Data($0) }, want)
        XCTAssertEqual(kb.withUnsafeBytes { Data($0) }, want)
        XCTAssertEqual(try BleWire.b64u(Crypto.fromB64url(a["epub"]!.string!)), a["epub"]!.string!)
    }

    func testRecordIsTheSameBytes() throws {
        let key = SymmetricKey(data: try Crypto.fromB64(v["key"]!.string!))
        let r = v["record"]!
        let sealed = try BleWire.sealRecord(key, dir: UInt8(r["dir"]!.int!), counter: UInt64(r["counter"]!.int!),
                                            type: UInt8(r["type"]!.int!), body: Data(r["plain"]!.string!.utf8))
        XCTAssertEqual(sealed, try Crypto.fromB64(r["sealed"]!.string!))
        let opened = try BleWire.openRecord(key, dir: UInt8(r["dir"]!.int!), counter: UInt64(r["counter"]!.int!), record: sealed)
        XCTAssertEqual(opened.type, BleWire.typeText)
        XCTAssertEqual(String(decoding: opened.body, as: UTF8.self), r["plain"]!.string!)
        XCTAssertThrowsError(try BleWire.openRecord(key, dir: UInt8(r["dir"]!.int!), counter: 6, record: sealed))
    }

    func testChunksAndTagAreTheSameBytes() throws {
        let sealed = try Crypto.fromB64(v["record"]!["sealed"]!.string!)
        let tag = try BleWire.tagOf(v["bid"]!.string!)
        XCTAssertEqual(tag, try Crypto.fromB64(v["tag"]!.string!))
        let tx = BleWire.toChunks(sealed, chunk: 20, tag: tag)
        XCTAssertEqual(tx.map(Crypto.b64), v["chunks"]!.array!.map { $0.string! })
        XCTAssertEqual(BleWire.toChunks(sealed, chunk: 20).map(Crypto.b64), v["rxChunks"]!.array!.map { $0.string! })
        let r = BleWire.Reassembler(skip: BleWire.tagBytes)
        var out: Data?
        for c in tx { XCTAssertTrue(BleWire.hasTag(c, tag)); out = try r.push(c) ?? out }
        XCTAssertEqual(out, sealed)
    }

    func testHelloAndBeacon() throws {
        XCTAssertEqual(BleWire.encodeHello(v["bid"]!.string!), try Crypto.fromB64(v["hello"]!.string!))
        XCTAssertEqual(BleWire.decodeHello(BleWire.encodeHello("abc")), "abc")
        let beacon = try Crypto.fromB64(v["beacon"]!.string!)
        XCTAssertEqual(BleWire.decodeBeacon(beacon)?.kind, "pair")
        XCTAssertEqual(BleWire.decodeBeacon(beacon)?.data, "cINVITE")
        XCTAssertThrowsError(try BleWire.encodeBeacon(kind: "pair", data: String(repeating: "x", count: 600)))
    }

    func testContactCardIsTheSameText() throws {
        for e in v["contact"]!.array! {
            let pk = e["publickey"]!.string!, nick = e["nickname"]!.string!, data = e["data"]!.string!
            XCTAssertEqual(try BleWire.encodeContact(publickey: pk, nickname: nick), data)
            XCTAssertEqual(BleWire.decodeContact(data), BleWire.Contact(publickey: pk, nickname: e["decodedNick"]!.string!))
        }
        XCTAssertNil(BleWire.decodeContact("to=bm9wZQ"))
        XCTAssertNil(BleWire.decodeContact("nick=a"))
    }
}
