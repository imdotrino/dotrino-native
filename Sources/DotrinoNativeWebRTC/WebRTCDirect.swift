import DotrinoNative
import Foundation
import WebRTC

/// THE DIRECT ROAD on iOS: the `WebRTCManager` of `@dotrino/proxy-client` over Google's
/// libwebrtc. Same piece as `WebRtcDirect.kt`: one peer connection + data channel `cc`
/// (ordered) per remote token; the GREATER token opens the channel and offers, the smaller one
/// is «polite» (yields on an offer collision). Everything on ONE serial queue.
public final class WebRTCDirect: NSObject, DirectTransport, @unchecked Sendable {
    private static let initialized: Void = { RTCInitializeSSL() }()
    fileprivate let q = DispatchQueue(label: "dotrino-webrtc")
    private let factory: RTCPeerConnectionFactory
    private var iceServers: [IceServer] = IceServer.defaultStun
    private var selfToken: () -> String? = { nil }
    private var signalSend: (String, JSON) -> Void = { _, _ in }
    private var deliverText: (String, String) -> Void = { _, _ in }
    private var peers: [String: Peer] = [:]
    private let peersLock = NSLock()

    /// Who may make me negotiate (nil = anyone who can reach me, like the JS default).
    public var acceptFrom: ((String) -> Bool)?
    public var onWarn: (String) -> Void = { _ in }
    public var onOpen: (String) -> Void = { _ in }

    fileprivate final class Peer: NSObject {
        let remote: String
        var pc: RTCPeerConnection?
        var dc: RTCDataChannel?
        var makingOffer = false, ignoreOffer = false, negotiating = false, failed = false
        /// `direct` or `turn`, from the candidate pair ICE chose; nil = not known yet.
        var route: String?
        var pending: [RTCIceCandidate] = []
        init(_ remote: String) { self.remote = remote }
    }

    public override init() {
        _ = Self.initialized
        factory = RTCPeerConnectionFactory()
        super.init()
    }

    public func bind(selfToken: @escaping () -> String?, signalSend: @escaping (String, JSON) -> Void, deliver: @escaping (String, String) -> Void) {
        self.selfToken = selfToken; self.signalSend = signalSend; self.deliverText = deliver
    }

    public func setIceServers(_ servers: [IceServer]) { if !servers.isEmpty { q.async { self.iceServers = servers } } }

    private func peerIfAny(_ t: String) -> Peer? { peersLock.withLock { peers[t] } }
    private func peer(_ t: String) -> Peer {
        peersLock.withLock { if let p = peers[t] { return p }; let p = Peer(t); peers[t] = p; return p }
    }
    private func polite(_ p: Peer) -> Bool { selfToken().map { $0 < p.remote } ?? false }

    public func isOpen(_ token: String) -> Bool { peerIfAny(token)?.dc?.readyState == .open }

    public func route(_ token: String) -> String? {
        guard let p = peerIfAny(token) else { return nil }
        if p.dc?.readyState == .open { probe(p); return peersLock.withLock { p.route } ?? "webrtc" }
        return p.failed ? "failed" : p.negotiating ? "connecting" : nil
    }

    /// Asks ICE which pair it chose and notes it in `route` (the answer comes later).
    private func probe(_ p: Peer) {
        p.pc?.statistics { report in
            let r = routeOf(report.statistics.mapValues { (type: $0.type, values: $0.values) })
            if let r { self.peersLock.withLock { p.route = r } }
        }
    }

    public func send(_ token: String, _ text: String) -> Bool {
        guard let dc = peerIfAny(token)?.dc, dc.readyState == .open else { return false }
        return dc.sendData(RTCDataBuffer(data: Data(text.utf8), isBinary: false))
    }

    public func upgrade(_ token: String) {
        q.async {
            let p = self.peer(token)
            if p.negotiating || p.failed || self.isOpen(token) { return }
            p.negotiating = true
            guard let pc = p.pc ?? self.createPc(p) else { p.failed = true; return }
            if let me = self.selfToken(), me > token, p.dc == nil {
                let cfg = RTCDataChannelConfiguration(); cfg.isOrdered = true
                if let dc = pc.dataChannel(forLabel: "cc", configuration: cfg) { self.attach(p, dc) }
            }
        }
    }

    public func handleSignal(from: String, _ msg: JSON) {
        q.async {
            if let accept = self.acceptFrom, !accept(from) { return }
            self.onSignal(self.peer(from), msg)
        }
    }

    public func close(_ token: String) {
        q.async {
            guard let p = self.peersLock.withLock({ self.peers.removeValue(forKey: token) }) else { return }
            p.dc?.close(); p.pc?.close()
        }
    }

    public func closeAll() { for t in peersLock.withLock({ Array(peers.keys) }) { close(t) } }

    // MARK: internals (on q)

    private func signal(_ p: Peer, _ body: [String: JSON]) {
        var o = body; o["t"] = .string(rtcTag)
        signalSend(p.remote, .object(o))
    }

    private func sdpJSON(_ d: RTCSessionDescription) -> [String: JSON] {
        ["kind": "sdp", "sdp": ["type": .string(RTCSessionDescription.string(for: d.type)), "sdp": .string(d.sdp)]]
    }

    private func createPc(_ p: Peer) -> RTCPeerConnection? {
        let cfg = RTCConfiguration()
        cfg.sdpSemantics = .unifiedPlan
        cfg.iceServers = iceServers.map { RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential) }
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        guard let pc = factory.peerConnection(with: cfg, constraints: constraints, delegate: nil) else {
            onWarn("libwebrtc could not create a peer connection"); return nil
        }
        let d = PcDelegate(owner: self, peer: p)
        objc_setAssociatedObject(pc, &PcDelegate.key, d, .OBJC_ASSOCIATION_RETAIN)
        pc.delegate = d
        p.pc = pc
        return pc
    }

    fileprivate func makeOffer(_ p: Peer) {
        guard let pc = p.pc else { return }
        p.makingOffer = true
        pc.offer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { d, err in
            guard let d, err == nil else { self.q.async { p.makingOffer = false }; return }
            pc.setLocalDescription(d) { err in
                self.q.async {
                    p.makingOffer = false
                    if err == nil, let local = pc.localDescription { self.signal(p, self.sdpJSON(local)) }
                }
            }
        }
    }

    private func onSignal(_ p: Peer, _ msg: JSON) {
        guard let pc = p.pc ?? createPc(p) else { return }
        switch msg["kind"]?.string {
        case "sdp":
            guard let type = msg["sdp"]?["type"]?.string, let text = msg["sdp"]?["sdp"]?.string else { return }
            let collision = type == "offer" && (p.makingOffer || pc.signalingState != .stable)
            p.ignoreOffer = !polite(p) && collision
            if p.ignoreOffer { return }
            let desc = RTCSessionDescription(type: RTCSessionDescription.type(for: type), sdp: text)
            pc.setRemoteDescription(desc) { err in
                self.q.async {
                    if let err { self.onWarn("webrtc remote sdp: \(err)"); return }
                    for c in p.pending { pc.add(c) { _ in } }
                    p.pending.removeAll()
                    guard type == "offer" else { return }
                    pc.answer(for: RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)) { a, err in
                        guard let a, err == nil else { return }
                        pc.setLocalDescription(a) { err in
                            self.q.async { if err == nil, let local = pc.localDescription { self.signal(p, self.sdpJSON(local)) } }
                        }
                    }
                }
            }
        case "ice":
            guard let c = msg["candidate"], let cand = c["candidate"]?.string else { return }
            let ice = RTCIceCandidate(sdp: cand, sdpMLineIndex: Int32(c["sdpMLineIndex"]?.int ?? 0), sdpMid: c["sdpMid"]?.string)
            if pc.remoteDescription == nil { p.pending.append(ice) } else { pc.add(ice) { _ in } }
        default: break
        }
    }

    fileprivate func onCandidate(_ p: Peer, _ c: RTCIceCandidate) {
        var cand: [String: JSON] = ["candidate": .string(c.sdp), "sdpMLineIndex": .int(Int64(c.sdpMLineIndex))]
        if let mid = c.sdpMid { cand["sdpMid"] = .string(mid) }
        signal(p, ["kind": "ice", "candidate": .object(cand)])
    }

    fileprivate func fail(_ p: Peer) { p.failed = true; p.negotiating = false }

    fileprivate func attach(_ p: Peer, _ dc: RTCDataChannel) {
        p.dc = dc
        let d = DcDelegate(owner: self, peer: p)
        objc_setAssociatedObject(dc, &DcDelegate.key, d, .OBJC_ASSOCIATION_RETAIN)
        dc.delegate = d
    }

    fileprivate func dcState(_ p: Peer, _ dc: RTCDataChannel) {
        switch dc.readyState {
        case .open: probe(p); onOpen(p.remote)
        // A channel that closes is tried again with the next message (JS: webrtc_close).
        case .closed: q.async {
            if self.peerIfAny(p.remote) === p { _ = self.peersLock.withLock { self.peers.removeValue(forKey: p.remote) }; p.pc?.close() }
        }
        default: break
        }
    }

    fileprivate func dcMessage(_ p: Peer, _ b: RTCDataBuffer) {
        guard !b.isBinary, let text = String(data: b.data, encoding: .utf8) else { return }
        deliverText(p.remote, text)
    }

    private final class PcDelegate: NSObject, RTCPeerConnectionDelegate {
        static var key = 0
        weak var owner: WebRTCDirect?
        let peer: Peer
        init(owner: WebRTCDirect, peer: Peer) { self.owner = owner; self.peer = peer }
        func peerConnection(_ pc: RTCPeerConnection, didGenerate c: RTCIceCandidate) { owner?.q.async { self.owner?.onCandidate(self.peer, c) } }
        func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCPeerConnectionState) {
            if s == .failed || s == .closed { owner?.q.async { self.owner?.fail(self.peer) } }
        }
        func peerConnection(_ pc: RTCPeerConnection, didOpen dc: RTCDataChannel) { owner?.q.async { self.owner?.attach(self.peer, dc) } }
        func peerConnectionShouldNegotiate(_ pc: RTCPeerConnection) { owner?.q.async { self.owner?.makeOffer(self.peer) } }
        func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCSignalingState) {}
        func peerConnection(_ pc: RTCPeerConnection, didAdd s: RTCMediaStream) {}
        func peerConnection(_ pc: RTCPeerConnection, didRemove s: RTCMediaStream) {}
        func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCIceConnectionState) {}
        func peerConnection(_ pc: RTCPeerConnection, didChange s: RTCIceGatheringState) {}
        func peerConnection(_ pc: RTCPeerConnection, didRemove c: [RTCIceCandidate]) {}
    }

    private final class DcDelegate: NSObject, RTCDataChannelDelegate {
        static var key = 0
        weak var owner: WebRTCDirect?
        let peer: Peer
        init(owner: WebRTCDirect, peer: Peer) { self.owner = owner; self.peer = peer }
        func dataChannelDidChangeState(_ dc: RTCDataChannel) { owner?.dcState(peer, dc) }
        func dataChannel(_ dc: RTCDataChannel, didReceiveMessageWith b: RTCDataBuffer) { owner?.dcMessage(peer, b) }
    }
}

/// BY WHICH ROAD a peer connection goes: `turn` if either end of the candidate pair ICE chose is a
/// `relay`, `direct` otherwise; nil when there is no chosen pair yet (not guessed). Same rule as
/// `routeOf` in `@dotrino/proxy-client` and `WebRtcDirect.kt`.
func routeOf(_ stats: [String: (type: String, values: [String: NSObject])]) -> String? {
    func v(_ s: (type: String, values: [String: NSObject])?, _ k: String) -> NSObject? { s?.values[k] }
    func str(_ o: NSObject?) -> String? { (o as? NSString).map { $0 as String } }
    func bool(_ o: NSObject?) -> Bool { (o as? NSNumber)?.boolValue ?? false }
    var pair = stats.values.first { $0.type == "transport" && v($0, "selectedCandidatePairId") != nil }
        .flatMap { t in str(v(t, "selectedCandidatePairId")).flatMap { stats[$0] } }
    if pair == nil {
        pair = stats.values.first { $0.type == "candidate-pair" && (bool(v($0, "selected")) || (bool(v($0, "nominated")) && str(v($0, "state")) == "succeeded")) }
    }
    guard let pair else { return nil }
    let local = str(v(pair, "localCandidateId")).flatMap { stats[$0] }
    let remote = str(v(pair, "remoteCandidateId")).flatMap { stats[$0] }
    if local == nil && remote == nil { return nil }
    return str(v(local, "candidateType")) == "relay" || str(v(remote, "candidateType")) == "relay" ? "turn" : "direct"
}
