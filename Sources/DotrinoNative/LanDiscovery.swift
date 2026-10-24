import Foundation
import Network
import dnssd

/// THE LOCAL-NETWORK WIRE (`dotrino-proxy-client/docs/lan.md`): `{kind, data}` in the TXT of a
/// `_dotrino._tcp` service, the same strings as `src/lan/mdns.js` (golden vector `lan`). Pure.
public enum LanWire {
    public static let serviceType = "_dotrino._tcp"
    public static let fakePort: UInt16 = 9
    public static let maxData = 512
    static let chunk = 200

    /// The TXT strings, in order: `v=1`, `k=…`, `n=…`, `d0=…`. Chunks never split a character.
    public static func txtEntries(kind: String, data: String) throws -> [(String, Data)] {
        let bytes = Array(data.utf8)
        guard bytes.count <= maxData else { throw BleWire.WireError(code: "too-big", description: "lan: at most \(maxData) bytes") }
        var chunks: [Data] = []
        var i = 0
        while i < bytes.count {
            var end = min(i + chunk, bytes.count)
            while end < bytes.count && (bytes[end] & 0xc0) == 0x80 { end -= 1 }
            chunks.append(Data(bytes[i..<end]))
            i = end
        }
        return [("v", Data("1".utf8)), ("k", Data(kind.utf8)), ("n", Data(String(chunks.count).utf8))]
            + chunks.enumerated().map { ("d\($0.offset)", $0.element) }
    }

    /// `{kind, data}` back from the TXT attributes, or nil when something is missing.
    public static func fromTxt(_ attrs: [String: Data]) -> (kind: String, data: String)? {
        func str(_ k: String) -> String? { attrs[k].flatMap { String(data: $0, encoding: .utf8) } }
        guard str("v") == "1", let kind = str("k"), !kind.isEmpty, let n = str("n").flatMap(Int.init), (0...3).contains(n) else { return nil }
        var out = Data()
        for i in 0..<n { guard let d = attrs["d\(i)"] else { return nil }; out.append(d) }
        guard let data = String(data: out, encoding: .utf8) else { return nil }
        return (kind, data)
    }

    /// The TXT record bytes (RFC 6763 §6): each string with its length byte in front.
    static func txtRecord(_ entries: [(String, Data)]) -> Data {
        var out = Data()
        for (k, v) in entries {
            let s = Data((k + "=").utf8) + v
            out.append(UInt8(s.count))
            out.append(s)
        }
        return out
    }
}

/// ANNOUNCE AND DISCOVER ON THE LOCAL NETWORK, by Bonjour. Only `{kind, data}`, like the Bluetooth
/// `beacon`; no channel goes here and no port opens (the SRV port is a dummy). The app needs
/// `NSLocalNetworkUsageDescription` and `NSBonjourServices: [_dotrino._tcp]` in its Info.plist.
/// [Nearby] uses it together with [BluetoothDirect].
public final class LanDiscovery: @unchecked Sendable {
    private let q = DispatchQueue(label: "dotrino.lan")

    public init() {}

    /// Announces `{kind, data}` until the returned closure is called.
    public func announce(kind: String, data: String) throws -> () -> Void {
        let txt = LanWire.txtRecord(try LanWire.txtEntries(kind: kind, data: data))
        var id = [UInt8](repeating: 0, count: 8)
        _ = SecRandomCopyBytes(kSecRandomDefault, id.count, &id)
        let name = "dotrino-" + id.map { String(format: "%02x", $0) }.joined()
        var ref: DNSServiceRef?
        let err = txt.withUnsafeBytes { raw in
            DNSServiceRegister(&ref, 0, 0, name, LanWire.serviceType, nil, nil, LanWire.fakePort.bigEndian,
                               UInt16(txt.count), raw.baseAddress, { _, _, _, _, _, _, _ in }, nil)
        }
        guard err == kDNSServiceErr_NoError, let r = ref else {
            throw BleWire.WireError(code: "no-lan", description: "lan: could not announce (\(err))")
        }
        DNSServiceSetDispatchQueue(r, q)
        var done = false
        return { [q] in q.async { if !done { done = true; DNSServiceRefDeallocate(r) } } }
    }

    /// What devices on the network announce with `kind`, for `timeout` seconds; once per `data`.
    public func discover(kind: String, timeout: TimeInterval = 8, onFound: ((String) -> Void)? = nil) async -> [String] {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: LanWire.serviceType, domain: nil), using: .tcp)
        var found: [String] = []
        browser.browseResultsChangedHandler = { results, _ in
            for r in results {
                guard case let .bonjour(txt) = r.metadata else { continue }
                var attrs: [String: Data] = [:]
                for (k, _) in txt.dictionary {
                    switch txt.getEntry(for: k) {
                    case .string(let s)?: attrs[k] = Data(s.utf8)
                    case .data(let d)?: attrs[k] = d
                    default: break
                    }
                }
                guard let got = LanWire.fromTxt(attrs), got.kind == kind, !found.contains(got.data) else { continue }
                found.append(got.data)
                onFound?(got.data)
            }
        }
        browser.start(queue: q)
        try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
        browser.cancel()
        return q.sync { found }
    }
}

/// NEARBY: announce and discover `{kind, data}` by every medium at once, the Bluetooth `beacon`
/// ([BluetoothDirect]) and the local network ([LanDiscovery]). The same as `announceNearby` /
/// `discoverNearby` in `@dotrino/proxy-client`. What is seen by both comes out once.
public final class Nearby: @unchecked Sendable {
    private let bluetooth: BluetoothDirect?
    private let lan = LanDiscovery()

    public init(bluetooth: BluetoothDirect?) { self.bluetooth = bluetooth }

    /// Announces until the returned closure is called. Bluetooth only if it is on; the network always.
    public func announce(kind: String, data: String) throws -> () -> Void {
        var stops: [() -> Void] = []
        if let bt = bluetooth, bt.available, let s = try? bt.announce(kind: kind, data: data) { stops.append(s) }
        if let s = try? lan.announce(kind: kind, data: data) { stops.append(s) }
        guard !stops.isEmpty else { throw BleWire.WireError(code: "no-nearby", description: "nearby: neither Bluetooth nor the local network is available here") }
        return { stops.forEach { $0() } }
    }

    /// What is announced nearby with `kind`, for `timeout` seconds, by both media at once.
    public func discover(kind: String, timeout: TimeInterval = 8, onFound: ((String) -> Void)? = nil) async -> [String] {
        let lock = NSLock()
        var found: [String] = []
        let add: (String) -> Void = { d in
            lock.lock(); let fresh = !found.contains(d); if fresh { found.append(d) }; lock.unlock()
            if fresh { onFound?(d) }
        }
        await withTaskGroup(of: Void.self) { g in
            if let bt = bluetooth, bt.available {
                g.addTask { _ = try? await bt.discover(kind: kind, timeout: timeout, onFound: add) }
            }
            g.addTask { _ = await self.lan.discover(kind: kind, timeout: timeout, onFound: add) }
        }
        lock.lock(); defer { lock.unlock() }
        return found
    }
}
