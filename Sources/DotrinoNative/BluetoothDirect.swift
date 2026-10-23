import CoreBluetooth
import CryptoKit
import Foundation

/// THE BLUETOOTH STEP of the transport (`dotrino-proxy-client/docs/bluetooth.md`): after
/// direct WebRTC, before TURN. The `BluetoothManager` of the JS pilar over CoreBluetooth, with
/// the same wire (`BleWire`). Plug it in through `DirectLadder` next to WebRTC, or alone.
///
/// Everything runs on ONE serial queue. The app needs `NSBluetoothAlwaysUsageDescription` in its
/// Info.plist; without permission, or with Bluetooth off, `available` is false and the step is
/// simply not there.
public final class BluetoothDirect: NSObject, DirectTransport, @unchecked Sendable {
    private let q = DispatchQueue(label: "dotrino-ble")
    /// Arrivals go to the app from HERE, in order: answering from inside `q` would deadlock.
    private let out = DispatchQueue(label: "dotrino-ble-out")
    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var centralOn = false, peripheralOn = false, serviceAdded = false

    private var selfToken: () -> String? = { nil }
    private var signalSend: (String, JSON) -> Void = { _, _ in }
    private var deliverText: (String, String) -> Void = { _, _ in }
    private var deliverBinary: (String, Data) -> Void = { _, _ in }

    /// Who may make me negotiate (nil = anyone who can reach me, like the JS default).
    public var acceptFrom: ((String) -> Bool)?
    public var onOpen: (String) -> Void = { _ in }
    public var onClose: (String) -> Void = { _ in }

    private var bid = BleWire.randomBid()
    private var beacon = Data()
    private var rotate: DispatchSourceTimer?

    private var peers: [String: Peer] = [:]
    private var centrals: [UUID: CentralState] = [:]       // peripheral role: who connected to us
    private var wanted: [String: Peer] = [:]                // central role: bid → peer we look for
    private var links: [UUID: Link] = [:]                   // central role: our connections
    private var seen: [UUID: (bid: String?, at: Date)] = [:]
    private var probing = Set<UUID>()
    private var probeQueue: [CBPeripheral] = []
    private var probeBusy = false
    private var sessions: [UUID: DiscoverSession] = [:]
    private var scanning = false

    private var txChar: CBMutableCharacteristic?
    private var notifyQueue: [(CBCentral, Data)] = []

    private let uService = CBUUID(string: BleWire.serviceUUID)
    private let uHello = CBUUID(string: BleWire.helloUUID)
    private let uBeacon = CBUUID(string: BleWire.beaconUUID)
    private let uRx = CBUUID(string: BleWire.rxUUID)
    private let uTx = CBUUID(string: BleWire.txUUID)

    enum State { case new, offered, keyed, open, failed, closed }
    enum Role { case central, peripheral }

    final class Peer {
        let token: String
        var state: State = .new
        var role: Role?
        var eph: P256.KeyAgreement.PrivateKey?
        var key: SymmetricKey?
        var theirBid: String?
        var myBid: String?
        var tag = Data()
        var link: Link?
        var central: CBCentral?
        var chunk = 20
        var sendCtr: UInt64 = 0
        var recvCtr: UInt64 = 0
        var rx = BleWire.Reassembler(skip: BleWire.tagBytes)
        var timer: DispatchWorkItem?
        var failedAt = Date.distantPast
        init(_ token: String) { self.token = token }
    }

    final class CentralState {
        let central: CBCentral
        var rx = BleWire.Reassembler()
        var peer: Peer?
        var pendingHello: Data?
        init(_ c: CBCentral) { central = c }
    }

    /// A connection we made (central role).
    final class Link {
        let p: CBPeripheral
        var chars: [CBUUID: CBCharacteristic] = [:]
        var onReady: ((Bool) -> Void)?
        var reads: [CBUUID: [(Data?) -> Void]] = [:]
        var writes: [Data] = []
        var writing = false
        var onValue: ((Data) -> Void)?
        var onNotify: ((Bool) -> Void)?
        var onClose: (() -> Void)?
        var closed = false
        var owner: Peer?
        init(_ p: CBPeripheral) { self.p = p }
    }

    final class DiscoverSession {
        let kind: String
        var done = Set<UUID>()
        var found: [String] = []
        let onFound: ((String) -> Void)?
        init(kind: String, onFound: ((String) -> Void)?) { self.kind = kind; self.onFound = onFound }
    }

    public override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: q)
        peripheralManager = CBPeripheralManager(delegate: self, queue: q)
        let t = DispatchSource.makeTimerSource(queue: q)
        t.schedule(deadline: .now() + BleWire.bidRotateSeconds, repeating: BleWire.bidRotateSeconds)
        t.setEventHandler { [weak self] in self?.bid = BleWire.randomBid() }
        t.resume()
        rotate = t
    }

    deinit { rotate?.cancel() }

    /// Bluetooth is on, allowed, and serving.
    public var available: Bool { q.sync { centralOn && peripheralOn && serviceAdded } }

    /// Stops everything: channels, scanning and advertising. The service comes back when
    /// Bluetooth turns on again or with a new `BluetoothDirect`.
    public func stop() {
        q.async {
            for p in Array(self.peers.values) { self.closed(p) }
            self.sessions.removeAll()
            self.wanted.removeAll()
            self.updateScan()
            self.peripheralManager.stopAdvertising()
            self.peripheralManager.removeAllServices()
            self.serviceAdded = false
            self.centrals.removeAll()
            self.notifyQueue.removeAll()
            self.rotate?.cancel()
        }
    }
    private var ready: Bool { centralOn && peripheralOn && serviceAdded }

    // MARK: DirectTransport

    public func bind(selfToken: @escaping () -> String?, signalSend: @escaping (String, JSON) -> Void,
                     deliver: @escaping (String, String) -> Void, deliverBinary: @escaping (String, Data) -> Void) {
        self.selfToken = selfToken; self.signalSend = signalSend; self.deliverText = deliver; self.deliverBinary = deliverBinary
    }

    public func setIceServers(_ servers: [IceServer]) {}

    public func isOpen(_ token: String) -> Bool { q.sync { peers[token]?.state == .open } }

    public func route(_ token: String) -> String? {
        q.sync {
            guard let p = peers[token] else { return nil }
            switch p.state {
            case .open: return "bluetooth"
            case .failed: return "failed"
            case .closed: return nil
            default: return "connecting"
            }
        }
    }

    public func sendBinary(_ token: String, _ bytes: Data) -> Bool {
        q.sync {
            guard let p = peers[token], p.state == .open else { return false }
            send(p, BleWire.typeBinary, bytes)
            return true
        }
    }

    /// The same for a text message (what an older JS client reads as text).
    public func sendText(_ token: String, _ text: String) -> Bool {
        q.sync {
            guard let p = peers[token], p.state == .open else { return false }
            send(p, BleWire.typeText, Data(text.utf8))
            return true
        }
    }

    public func upgrade(_ token: String) {
        q.async {
            guard self.ready, self.selfToken() != nil else { return }
            if let p = self.peers[token] {
                if p.state != .failed { return }
                if Date().timeIntervalSince(p.failedAt) < 60 { return }
            }
            let p = Peer(token)
            self.peers[token] = p
            let eph = BleWire.makeEphemeral()
            p.eph = eph.privateKey
            p.state = .offered
            p.myBid = self.bid
            self.timer(p, BleWire.answerTimeout)
            self.signalSend(token, ["t": .string(BleWire.tag), "kind": "offer", "bid": .string(self.bid), "epub": .string(eph.epub)])
        }
    }

    public func handleSignal(from: String, _ msg: JSON) {
        if let accept = acceptFrom, !accept(from) { return }
        guard let kind = msg["kind"]?.string, let theirBid = msg["bid"]?.string, let epub = msg["epub"]?.string else { return }
        q.async {
            guard self.ready else { return }
            var p = self.peers[from]
            if kind == "offer" {
                if let existing = p, existing.state == .offered {
                    // Crossed offers: theirs works as the answer (ECDH is symmetric).
                } else {
                    if let existing = p { self.release(existing); existing.state = .closed }
                    let np = Peer(from)
                    self.peers[from] = np
                    let eph = BleWire.makeEphemeral()
                    np.eph = eph.privateKey
                    np.myBid = self.bid
                    self.signalSend(from, ["t": .string(BleWire.tag), "kind": "answer", "bid": .string(self.bid), "epub": .string(eph.epub)])
                    p = np
                }
            } else if kind == "answer" {
                guard let existing = p, existing.state == .offered else { return }
            } else { return }
            guard let peer = p, let me = self.selfToken(), let eph = peer.eph, let myBid = peer.myBid,
                  let key = try? BleWire.deriveKey(eph, theirEpub: epub, me, from) else {
                if let peer = p { self.fail(peer) }
                return
            }
            peer.key = key
            peer.eph = nil
            peer.theirBid = theirBid
            peer.role = me > from ? .central : .peripheral
            peer.tag = (try? BleWire.tagOf(peer.role == .central ? myBid : theirBid)) ?? Data()
            peer.state = .keyed
            self.timer(peer, BleWire.findTimeout)
            if peer.role == .peripheral { self.retryHellos() }
            else { self.wanted[theirBid] = peer; self.updateScan() }
        }
    }

    public func close(_ token: String) { q.async { if let p = self.peers[token] { self.closed(p) } } }
    public func closeAll() { q.async { for p in Array(self.peers.values) { self.closed(p) } } }

    // MARK: announce and discover

    /// Puts `{kind, data}` in `beacon` (docs §3) until the returned closure is called. Throws
    /// `no-bluetooth` when there is no Bluetooth here: whoever announces must know.
    public func announce(kind: String, data: String) throws -> () -> Void {
        let b = try BleWire.encodeBeacon(kind: kind, data: data)
        try q.sync {
            guard ready else { throw BleWire.WireError(code: "no-bluetooth", description: "bluetooth: not available here") }
            beacon = b
        }
        return { [weak self] in self?.q.async { if self?.beacon == b { self?.beacon = Data() } } }
    }

    /// What devices around announce with `kind`, for `timeout` seconds. Needs no proxy.
    public func discover(kind: String, timeout: TimeInterval = 8, onFound: ((String) -> Void)? = nil) async throws -> [String] {
        let id = UUID()
        try q.sync {
            guard ready else { throw BleWire.WireError(code: "no-bluetooth", description: "bluetooth: not available here") }
            sessions[id] = DiscoverSession(kind: kind, onFound: onFound)
            updateScan()
        }
        try await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
        return q.sync {
            let s = sessions.removeValue(forKey: id)
            updateScan()
            return s?.found ?? []
        }
    }

    // MARK: central role

    private func updateScan() {
        let want = centralOn && (!wanted.isEmpty || !sessions.isEmpty)
        if want && !scanning {
            scanning = true
            central.scanForPeripherals(withServices: [uService], options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        } else if !want && scanning {
            scanning = false
            central.stopScan()
        }
    }

    private func found(_ p: CBPeripheral) {
        let id = p.identifier
        if probing.contains(id) || links[id] != nil { return }
        let needsSession = sessions.values.contains { !$0.done.contains(id) }
        var needsPeer = !wanted.isEmpty
        if let s = seen[id], Date().timeIntervalSince(s.at) < 60, !(s.bid.map { wanted[$0] != nil } ?? false) { needsPeer = false }
        guard needsSession || needsPeer else { return }
        probing.insert(id)
        probeQueue.append(p)
        pumpProbes()
    }

    /// One connection attempt at a time: read `hello` and `beacon`, keep the link if it is a peer
    /// we are looking for, give each discover session what it announces, and let go otherwise.
    private func pumpProbes() {
        guard !probeBusy, !probeQueue.isEmpty else { return }
        probeBusy = true
        let p = probeQueue.removeFirst()
        let link = Link(p)
        links[p.identifier] = link
        let finish: (Bool) -> Void = { [weak self] keep in
            guard let self else { return }
            self.probing.remove(p.identifier)
            if !keep { self.dropLink(link) }
            self.probeBusy = false
            self.pumpProbes()
        }
        let deadline = DispatchWorkItem { [weak link] in if let link, link.onReady != nil { link.onReady?(false) } }
        q.asyncAfter(deadline: .now() + 15, execute: deadline)
        link.onReady = { [weak self] ok in
            deadline.cancel()
            link.onReady = nil
            guard let self, ok else { finish(false); return }
            self.read(link, self.uHello) { helloData in
                self.read(link, self.uBeacon) { beaconData in
                    let id = p.identifier
                    let bid = helloData.flatMap(BleWire.decodeHello)
                    self.seen[id] = (bid, Date())
                    if let b = beaconData.flatMap(BleWire.decodeBeacon) {
                        for s in self.sessions.values where !s.done.contains(id) && s.kind == b.kind && !s.found.contains(b.data) {
                            s.found.append(b.data); s.onFound?(b.data)
                        }
                    }
                    for s in self.sessions.values { s.done.insert(id) }
                    if let bid, let peer = self.wanted[bid], peer.state == .keyed {
                        self.wanted.removeValue(forKey: bid)
                        self.updateScan()
                        self.attach(peer, link)
                        finish(true)
                    } else {
                        finish(false)
                    }
                }
            }
        }
        central.connect(p)
    }

    private func read(_ link: Link, _ uuid: CBUUID, _ done: @escaping (Data?) -> Void) {
        guard let c = link.chars[uuid], !link.closed else { done(nil); return }
        link.reads[uuid, default: []].append(done)
        link.p.readValue(for: c)
    }

    private func attach(_ peer: Peer, _ link: Link) {
        peer.link = link
        link.owner = peer
        peer.chunk = min(link.p.maximumWriteValueLength(for: .withResponse), 512)
        link.onClose = { [weak self, weak peer] in if let self, let peer { self.closed(peer) } }
        link.onValue = { [weak self, weak peer] chunk in
            guard let self, let peer, BleWire.hasTag(chunk, peer.tag) else { return }
            self.onChunk(peer, chunk)
        }
        link.onNotify = { [weak self, weak peer] ok in
            guard let self, let peer else { return }
            if ok { self.send(peer, BleWire.typeHello, Data((self.selfToken() ?? "").utf8)) } else { self.closed(peer) }
        }
        guard let tx = link.chars[uTx] else { closed(peer); return }
        link.p.setNotifyValue(true, for: tx)
    }

    private func onChunk(_ peer: Peer, _ chunk: Data) {
        do { if let rec = try peer.rx.push(chunk) { onRecord(peer, rec) } } catch { closed(peer) }
    }

    private func dropLink(_ link: Link) {
        guard !link.closed else { return }
        link.closed = true
        links.removeValue(forKey: link.p.identifier)
        central.cancelPeripheralConnection(link.p)
        let cb = link.onClose
        link.onClose = nil
        cb?()
    }

    private func pumpWrites(_ link: Link) {
        guard !link.writing, !link.closed, let rx = link.chars[uRx], !link.writes.isEmpty else { return }
        link.writing = true
        link.p.writeValue(link.writes.removeFirst(), for: rx, type: .withResponse)
    }

    // MARK: peripheral role

    private func setUpService() {
        guard peripheralOn, !serviceAdded else { return }
        let hello = CBMutableCharacteristic(type: uHello, properties: [.read], value: nil, permissions: [.readable])
        let bcn = CBMutableCharacteristic(type: uBeacon, properties: [.read], value: nil, permissions: [.readable])
        let rx = CBMutableCharacteristic(type: uRx, properties: [.write, .writeWithoutResponse], value: nil, permissions: [.writeable])
        let tx = CBMutableCharacteristic(type: uTx, properties: [.notify], value: nil, permissions: [.readable])
        txChar = tx
        let svc = CBMutableService(type: uService, primary: true)
        svc.characteristics = [hello, bcn, rx, tx]
        peripheralManager.add(svc)
    }

    private func onWrite(_ c: CBCentral, _ bytes: Data) {
        let st = centrals[c.identifier] ?? { let s = CentralState(c); centrals[c.identifier] = s; return s }()
        let rec: Data?
        do { rec = try st.rx.push(bytes) } catch {
            centrals.removeValue(forKey: c.identifier)
            if let p = st.peer { closed(p) }
            return
        }
        guard let rec else { return }
        if let p = st.peer { onRecord(p, rec) } else { identify(st, rec) }
    }

    /// Who connected: the key that opens their greeting says which negotiation it is.
    private func identify(_ st: CentralState, _ rec: Data) {
        for p in peers.values where p.role == .peripheral && p.state == .keyed && p.central == nil {
            guard let key = p.key, let r = try? BleWire.openRecord(key, dir: BleWire.dirToPeripheral, counter: 0, record: rec),
                  r.type == BleWire.typeHello, String(decoding: r.body, as: UTF8.self) == p.token else { continue }
            p.central = st.central
            p.chunk = min(st.central.maximumUpdateValueLength, 512)
            p.recvCtr = 1
            st.peer = p
            st.pendingHello = nil
            send(p, BleWire.typeHello, Data((selfToken() ?? "").utf8))
            open(p)
            return
        }
        // Nobody expected it YET: the central can write before our key is ready. Kept and tried
        // again when a negotiation is ready. Nothing is said to who was not negotiated.
        st.pendingHello = rec
    }

    private func retryHellos() {
        for st in centrals.values where st.peer == nil {
            if let rec = st.pendingHello { identify(st, rec) }
        }
    }

    private func pumpNotify() {
        guard let tx = txChar else { return }
        while let (c, chunk) = notifyQueue.first {
            if !peripheralManager.updateValue(chunk, for: tx, onSubscribedCentrals: [c]) { return }   // wait for isReady
            notifyQueue.removeFirst()
        }
    }

    // MARK: records

    private func onRecord(_ p: Peer, _ rec: Data) {
        guard p.state != .closed, p.state != .failed, let key = p.key else { return }
        let dir = p.role == .central ? BleWire.dirToCentral : BleWire.dirToPeripheral
        guard let r = try? BleWire.openRecord(key, dir: dir, counter: p.recvCtr, record: rec) else { closed(p); return }
        p.recvCtr += 1
        switch r.type {
        case BleWire.typeHello:
            if String(decoding: r.body, as: UTF8.self) != p.token { closed(p); return }
            if p.role == .central { open(p) }
        case BleWire.typeText:
            guard p.state == .open else { closed(p); return }
            let (t, text, f) = (p.token, String(decoding: r.body, as: UTF8.self), deliverText)
            out.async { f(t, text) }
        case BleWire.typeBinary:
            guard p.state == .open else { closed(p); return }
            let (t, body, f) = (p.token, r.body, deliverBinary)
            out.async { f(t, body) }
        default: break
        }
    }

    private func send(_ p: Peer, _ type: UInt8, _ body: Data) {
        guard let key = p.key else { return }
        let dir = p.role == .central ? BleWire.dirToPeripheral : BleWire.dirToCentral
        guard let rec = try? BleWire.sealRecord(key, dir: dir, counter: p.sendCtr, type: type, body: body) else { closed(p); return }
        p.sendCtr += 1
        if p.role == .central, let link = p.link {
            link.writes.append(contentsOf: BleWire.toChunks(rec, chunk: p.chunk))
            pumpWrites(link)
        } else if let c = p.central {
            notifyQueue.append(contentsOf: BleWire.toChunks(rec, chunk: p.chunk, tag: p.tag).map { (c, $0) })
            pumpNotify()
        }
    }

    // MARK: state

    private func timer(_ p: Peer, _ seconds: TimeInterval) {
        p.timer?.cancel()
        let w = DispatchWorkItem { [weak self, weak p] in if let self, let p, p.state != .open { self.fail(p) } }
        p.timer = w
        q.asyncAfter(deadline: .now() + seconds, execute: w)
    }

    private func open(_ p: Peer) {
        p.timer?.cancel(); p.timer = nil
        p.state = .open
        let t = p.token
        DispatchQueue.main.async { self.onOpen(t) }
    }

    private func fail(_ p: Peer) {
        if p.state == .closed { return }
        release(p)
        p.state = .failed
        p.failedAt = Date()
    }

    private func closed(_ p: Peer) {
        if p.state == .closed { return }
        let wasOpen = p.state == .open
        release(p)
        p.state = .closed
        if peers[p.token] === p { peers.removeValue(forKey: p.token) }
        if wasOpen { let t = p.token; DispatchQueue.main.async { self.onClose(t) } }
    }

    private func release(_ p: Peer) {
        p.timer?.cancel(); p.timer = nil
        if let b = p.theirBid, wanted[b] === p { wanted.removeValue(forKey: b); updateScan() }
        if let link = p.link { p.link = nil; link.owner = nil; link.onClose = nil; dropLink(link) }
        if let c = p.central {
            p.central = nil
            if let st = centrals[c.identifier], st.peer === p { st.peer = nil }
            notifyQueue.removeAll { $0.0.identifier == c.identifier }
        }
    }
}

// MARK: delegates (all on `q`)

extension BluetoothDirect: CBCentralManagerDelegate, CBPeripheralDelegate {
    public func centralManagerDidUpdateState(_ c: CBCentralManager) {
        centralOn = c.state == .poweredOn
        if !centralOn { scanning = false; for p in Array(peers.values) where p.role == .central { closed(p) } }
        updateScan()
    }

    public func centralManager(_ c: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        found(p)
    }

    public func centralManager(_ c: CBCentralManager, didConnect p: CBPeripheral) {
        p.delegate = self
        p.discoverServices([uService])
    }

    public func centralManager(_ c: CBCentralManager, didFailToConnect p: CBPeripheral, error: Error?) {
        links[p.identifier]?.onReady?(false)
    }

    public func centralManager(_ c: CBCentralManager, didDisconnectPeripheral p: CBPeripheral, error: Error?) {
        guard let link = links[p.identifier] else { return }
        if let r = link.onReady { r(false); return }
        link.closed = true
        links.removeValue(forKey: p.identifier)
        for (_, waiting) in link.reads { waiting.forEach { $0(nil) } }
        link.reads = [:]
        let cb = link.onClose
        link.onClose = nil
        cb?()
    }

    public func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
        guard let link = links[p.identifier] else { return }
        guard let svc = p.services?.first(where: { $0.uuid == uService }) else { link.onReady?(false); return }
        p.discoverCharacteristics([uHello, uBeacon, uRx, uTx], for: svc)
    }

    public func peripheral(_ p: CBPeripheral, didDiscoverCharacteristicsFor s: CBService, error: Error?) {
        guard let link = links[p.identifier] else { return }
        for c in s.characteristics ?? [] { link.chars[c.uuid] = c }
        link.onReady?(link.chars[uHello] != nil && link.chars[uRx] != nil && link.chars[uTx] != nil)
    }

    public func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
        guard let link = links[p.identifier] else { return }
        if c.uuid == uTx { if let v = c.value { link.onValue?(v) }; return }
        guard var waiting = link.reads[c.uuid], !waiting.isEmpty else { return }
        let done = waiting.removeFirst()
        link.reads[c.uuid] = waiting
        done(error == nil ? (c.value ?? Data()) : nil)
    }

    public func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
        guard let link = links[p.identifier] else { return }
        link.writing = false
        if error != nil { if let peer = link.owner { closed(peer) }; return }
        pumpWrites(link)
    }

    public func peripheral(_ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?) {
        guard c.uuid == uTx, let link = links[p.identifier] else { return }
        let cb = link.onNotify
        link.onNotify = nil
        cb?(error == nil && c.isNotifying)
    }
}

extension BluetoothDirect: CBPeripheralManagerDelegate {
    public func peripheralManagerDidUpdateState(_ m: CBPeripheralManager) {
        peripheralOn = m.state == .poweredOn
        if peripheralOn { setUpService() } else {
            serviceAdded = false
            for p in Array(peers.values) where p.role == .peripheral { closed(p) }
        }
    }

    public func peripheralManager(_ m: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else { return }
        serviceAdded = true
        m.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [uService]])
    }

    public func peripheralManager(_ m: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        let all = request.characteristic.uuid == uHello ? BleWire.encodeHello(bid)
            : request.characteristic.uuid == uBeacon ? beacon : Data()
        guard request.offset <= all.count else { m.respond(to: request, withResult: .invalidOffset); return }
        request.value = all.subdata(in: request.offset..<all.count)
        m.respond(to: request, withResult: .success)
    }

    public func peripheralManager(_ m: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for r in requests where r.characteristic.uuid == uRx { if let v = r.value { onWrite(r.central, v) } }
        if let first = requests.first { m.respond(to: first, withResult: .success) }
    }

    public func peripheralManager(_ m: CBPeripheralManager, central: CBCentral, didSubscribeTo c: CBCharacteristic) {}

    /// CoreBluetooth has no «disconnected» for the peripheral role: unsubscribing is the signal.
    public func peripheralManager(_ m: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom c: CBCharacteristic) {
        guard let st = centrals.removeValue(forKey: central.identifier) else { return }
        if let p = st.peer { closed(p) }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers m: CBPeripheralManager) { pumpNotify() }
}
