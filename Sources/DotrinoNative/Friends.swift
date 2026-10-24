import Foundation

/// Lo que los amigos necesitan del transporte: la sesión sellada de dotrino-native. Un protocolo
/// para probarlo con dos instancias cableadas entre sí, sin proxio.
public protocol FriendsTransport: AnyObject {
    /// `peerPubkey`: de quién es el token — si está muerto (reinició), el mismo sobre va a su cola.
    func sendSealed(toToken token: String, _ payload: JSON, recipientEncPubs: [String], peerPubkey: String?) async throws
    func sendSealed(toPubkey pubkey: String, _ payload: JSON, recipientEncPubs: [String], quiet: Bool) async throws
    func encPubOf(_ publickey: String) async throws -> String
    func whoIs(_ token: String) async throws -> String?
    func requestPairingCode() async throws -> ProxyConnection.PairingCode
    func redeemPairingCode(_ code: String) async throws -> String
    var isOnline: Bool { get }
    func onMessage(_ l: @escaping (SealedSession.Message) -> Void) -> () -> Void
    func onOnline(_ l: @escaping () -> Void) -> () -> Void
    func onPeerGone(_ l: @escaping (String) -> Void) -> () -> Void
}

// Los métodos de la sesión ya cumplen casi todo el protocolo. NO se redeclara aquí uno con la misma
// firma: lo taparía y se llamaría a sí mismo para siempre (eso colgó la primera solicitud).
extension SealedSession: FriendsTransport {
    public func whoIs(_ token: String) async throws -> String? { try await whoIs(token, timeout: 10) }
    public func requestPairingCode() async throws -> ProxyConnection.PairingCode { try await requestPairingCode(ttlMs: nil) }
    public func onPeerGone(_ l: @escaping (String) -> Void) -> () -> Void {
        onEvent { e in if case .peerGone(let t, _) = e { l(t) } }
    }
}

/// Valores pequeños de la cuenta (las solicitudes, el apodo de la app).
public protocol KeyValueStore: AnyObject {
    func get(_ key: String) -> String?
    func set(_ key: String, _ value: String?)
}

public final class MemoryKeyValueStore: KeyValueStore, @unchecked Sendable {
    private var m: [String: String] = [:]
    private let lock = NSLock()
    public init() {}
    public func get(_ key: String) -> String? { lock.withLock { m[key] } }
    public func set(_ key: String, _ value: String?) { lock.withLock { m[key] = value } }
}

/// LOS AMIGOS: pedir, aceptar y escribirle a un amigo en todos sus aparatos. El puerto de
/// `@dotrino/profile/friends` (`dotrino-profile/src/friends/`), sacado del messenger para que
/// cualquier app nativa añada y acepte amigos. Mismo cable que la web:
///
///  - `CONTACT_REQUEST` va a la bandeja del otro, NUNCA a un chat; nadie es amigo de nadie hasta
///    que el otro acepta. `CONTACT_ACCEPT` solo cuenta si yo lo pedí.
///  - QUIÉN ESCRIBIÓ lo dice la llave que selló el sobre, comparada con las que conozco de ese
///    amigo o con la que esa identidad anunció firmada. El token no dice nada.
///  - Los amigos son por CUENTA (su tarjeta de perfil): se le escribe a todos sus aparatos.
///
/// La libreta es la de la identidad (`PeerBook`), la misma de todas las apps. Lo que es de cada
/// app (hilos, avisos, versión) entra por los ganchos. Un actor: una cosa a la vez.
public actor Friends {
    public static let requestTTL: Int64 = 24 * 60 * 60 * 1000
    public static let request = "CONTACT_REQUEST"
    public static let accept = "CONTACT_ACCEPT"

    /// Una solicitud: entrante (alguien quiere añadirme) o saliente (esperando a que acepte).
    public struct Request: Equatable, Sendable {
        public let pubkey: String, dir: String, nickname: String, token: String?, encryptionPubkey: String?, ts: Int64
        public var vouched = false
        public init(pubkey: String, dir: String, nickname: String, token: String?, encryptionPubkey: String?, ts: Int64, vouched: Bool = false) {
            self.pubkey = pubkey; self.dir = dir; self.nickname = nickname; self.token = token
            self.encryptionPubkey = encryptionPubkey; self.ts = ts; self.vouched = vouched
        }
    }
    /// Quién escribió: `pubkey`, la llave con la que se le guarda; `device`, el aparato que escribió.
    /// `contact` solo si es amigo.
    public struct Who: Sendable { public let pubkey: String; public let contact: JSON?; public let encPub: String; public let device: String }
    /// Lo que devuelve `receive`: `handled` si era del protocolo de amigos; si no, la app sigue con `who`.
    public struct Received: Sendable { public let who: Who; public let handled: Bool }
    /// Un contacto fijo: no se quita ni se bloquea, y vuelve si falta.
    public struct Pinned: Sendable { public let publickey: String, nickname: String; public init(publickey: String, nickname: String) { self.publickey = publickey; self.nickname = nickname } }
    public struct FriendsError: Error, CustomStringConvertible { public let description: String; public let code: String }

    /// Lo de la app. Todos opcionales; se llaman desde el actor.
    public struct Hooks: Sendable {
        /// Llegó su versión (y su tarjeta, que ya adopta la libreta).
        public var onProfile: (@Sendable (String, JSON) async -> Void)?
        /// Un amigo guardado por la llave de un aparato pasó a su cuenta: lo de la app (su hilo) se mueve.
        public var onMoved: (@Sendable (String, String) async -> Void)?
        /// Ya sois amigos (la app saca lo que esperaba).
        public var onFriend: (@Sendable (String) async -> Void)?
        /// Llegó una solicitud a la bandeja (la app avisa).
        public var onRequest: (@Sendable (Request) async -> Void)?
        /// ¿Alguien de tu red lo avala?
        public var isVouched: (@Sendable (String) async -> Bool)?
        /// Cambió algo que la pantalla enseña.
        public var onChange: (@Sendable () -> Void)?
        public init() {}
    }

    nonisolated public static func sanitizeNickname(_ s: String?) -> String {
        (s ?? "").trimmingCharacters(in: .whitespacesAndNewlines).filter { !"<>\"'`".contains($0) }
    }

    /// El alfabeto de los códigos del proxio, sin lo que se confunde al leer o al dictar.
    public static let validCode = #"^[1-9ACDEFHJKMNOPQRTUVWXY]{6}$"#
    private static let confusables: [Character: Character] = ["I": "1", "L": "1", "S": "5", "Z": "2", "B": "8", "G": "6", "0": "O"]
    /// Lo tecleado en mayúsculas, sin separadores, y lo que nunca se emite traducido.
    nonisolated public static func normalizeCode(_ raw: String) -> String {
        String(raw.uppercased().filter { $0 != " " && $0 != "-" && $0 != "_" }.map { confusables[$0] ?? $0 })
    }
    /// El código de lo que devolvió un escáner: un enlace con `#add=` o el código pelado.
    nonisolated public static func codeFrom(_ text: String) -> String {
        if let r = text.range(of: #"[#&?]add=([^&\s]+)"#, options: [.regularExpression, .caseInsensitive]) {
            return String(normalizeCode(String(text[r].split(separator: "=", maxSplits: 1)[1])).prefix(6))
        }
        return String(normalizeCode(text).prefix(6))
    }
    nonisolated public static func isValidCode(_ c: String) -> Bool { c.range(of: validCode, options: .regularExpression) != nil }

    private let transport: FriendsTransport
    private let profile: Profile
    private let peers: PeerBook
    private let kv: KeyValueStore
    private let nickname: @Sendable () -> String
    private let version: JSON?
    nonisolated public let pinned: [Pinned]
    private let now: @Sendable () -> Int64
    private var hooks = Hooks()

    private var online: [String: String] = [:]        // cuenta → token vivo (esta sesión)
    private var deviceTokens: [String: String] = [:]  // pubkey de APARATO → token vivo
    private var codeTask: Task<Void, Never>?
    public private(set) var code: String?

    public init(transport: FriendsTransport, profile: Profile, peers: PeerBook, kv: KeyValueStore,
                nickname: @escaping @Sendable () -> String, version: JSON? = nil, pinned: [Pinned] = [],
                now: @escaping @Sendable () -> Int64 = { nowMs() }) {
        self.transport = transport; self.profile = profile; self.peers = peers; self.kv = kv
        self.nickname = nickname; self.version = version; self.pinned = pinned; self.now = now
    }

    public func setHooks(_ h: Hooks) { hooks = h }
    private func changed() { hooks.onChange?() }

    /// Deja de renovar el código (la app arranca otra vez con otro perfil).
    public func stop() { codeTask?.cancel(); codeTask = nil }

    // MARK: la libreta

    private func same(_ a: String?, _ b: String?) -> Bool { Delegation.samePubkey(a, b) }
    nonisolated public func isPinned(_ pk: String?) -> Bool { pinned.contains { Delegation.samePubkey(pk, $0.publickey) } }

    /// Los amigos (con los fijos: si alguno falta, vuelve).
    public func contacts() -> [JSON] { ensurePinned(); return (try? peers.contacts()) ?? [] }

    /// Un fijo que falta vuelve, con la llave que traiga su tarjeta si ya llegó (mismo registro).
    private func ensurePinned() {
        guard !pinned.isEmpty, let cs = try? peers.contacts() else { return }
        for p in pinned where !cs.contains(where: { same($0["publickey"]?.string, p.publickey) }) {
            let key = ((try? peers.all()) ?? [:]).values.compactMap { $0["card"] }.first { same($0["profileId"]?.string, p.publickey) }?["profileId"]?.string ?? p.publickey
            _ = try? peers.addContact(key, nickname: p.nickname)
        }
    }

    public func isBlocked(_ pk: String) -> Bool { !isPinned(pk) && ((try? peers.isBlocked(pk)) ?? false) }

    private func find(_ pk: String) -> JSON? { ((try? peers.contacts()) ?? []).first { same($0["publickey"]?.string, pk) } }

    /// El amigo que escribe, aunque lo haga desde OTRO de sus aparatos (lo dice su tarjeta).
    private func findBySender(_ pk: String) -> JSON? {
        let cs = contacts()
        if let c = cs.first(where: { same($0["publickey"]?.string, pk) }) { return c }
        return cs.first { c in ((try? peers.cardOf(c["publickey"]?.string ?? ""))??["keys"]?.array ?? []).contains { same($0["pub"]?.string, pk) } }
    }

    /// A qué aparatos se le escribe: los de su CUENTA (su tarjeta), cada uno con su llave de cifrado;
    /// sin tarjeta todavía, la llave con la que se añadió con todas las que conozco. Un fijo sin
    /// tarjeta no tiene ninguno.
    public func recipientsOf(_ pk: String) -> [(String, [String])] {
        let devices = (try? peers.devicesOf(pk)) ?? []
        if !devices.isEmpty { return devices.map { ($0.pub, [$0.encPub]) } }
        if isPinned(pk) { return [] }
        return [(pk, (try? peers.encPubsOf(pk)) ?? [])]
    }

    // MARK: presencia

    public func markOnline(_ pk: String, _ token: String) { online[pk] = token; changed() }
    public func markOffline(_ token: String) {
        online = online.filter { $0.value != token }
        deviceTokens = deviceTokens.filter { $0.value != token }
        changed()
    }
    public func isOnline(_ pk: String) -> Bool { online[pk] != nil }
    public func liveTokenFor(_ pk: String) -> String? { online[pk] }

    // MARK: tu código

    /// Pide al proxio un código (una CITA de 6 caracteres que caduca en minutos) y lo renueva antes
    /// de que caduque. Es de UN uso: cuando alguien lo canjea, se pide otro solo.
    public func refreshCode() async {
        codeTask?.cancel(); codeTask = nil
        do {
            let c = try await transport.requestPairingCode()
            code = c.code
            let left = c.expiresAt - now()
            if left > 10_000 {
                codeTask = Task { [weak self] in
                    try? await Task.sleep(nanoseconds: UInt64(left - 5_000) * 1_000_000)
                    if !Task.isCancelled { await self?.renewalFired() }
                }
            }
        } catch { code = nil }
        changed()
    }

    /// El temporizador se olvida PRIMERO de sí mismo, para que renovar no cancele la tarea en la
    /// que corre (eso dejó el código vacío en Android).
    private func renewalFired() async { codeTask = nil; await refreshCode() }

    /// Canjea el código de otro y le pide ser amigos. Lanza `own`, `offline` (sin red: esperar) o
    /// `invalid` (pedir otro): se arreglan distinto.
    public func requestByCode(_ raw: String, alias: String) async throws {
        let c = Self.normalizeCode(raw)
        guard Self.isValidCode(c) else { throw FriendsError(description: "that is not a code", code: "invalid") }
        if c == code { throw FriendsError(description: "that is your own code", code: "own") }
        if !transport.isOnline { throw FriendsError(description: "not connected", code: "offline") }
        let token: String
        do { token = try await transport.redeemPairingCode(c) } catch {
            let ec = (error as? ProxyError)?.code ?? (error as? SealedSession.SessionError)?.code
            throw FriendsError(description: "\(error)", code: ec == "disconnected" || ec == "timeout" ? "offline" : "invalid")
        }
        guard let pubkey = try await transport.whoIs(token) else { throw FriendsError(description: "the other side did not say who it is", code: "offline") }
        try await request(pubkey: pubkey, alias: alias, card: nil, token: token)
    }

    // MARK: quién soy

    /// Lo que mando de mí: apodo, versión y mi tarjeta de perfil (para que me escriban a TODOS mis aparatos).
    public func me(_ type: String) -> JSON {
        var o: [String: JSON] = ["type": .string(type), "nickname": .string(nickname())]
        if let version { o["v"] = version }
        if let card = profile.card { o["card"] = card }
        return .object(o)
    }

    // MARK: solicitudes

    /// Pide ser amigos. Le llega a su BANDEJA. `token` sale de canjear su código; con su `card` (un
    /// enlace `#to=`) se le escribe a todos sus aparatos con las llaves que ella misma lista.
    public func request(pubkey: String, alias: String, card: JSON? = nil, token: String? = nil) async throws {
        if !transport.isOnline { throw FriendsError(description: "not connected", code: "offline") }
        // Si ya me lo pidió, esto es aceptar: los dos lo quieren.
        if requestOf(pubkey, "in") != nil { await accept(pubkey); return }
        let payload = me(Self.request)
        if let card {
            if (try? peers.adoptPeerCard(card)) == nil { print("friends: the card in the link could not be adopted") }
            if let account = try? peers.accountFromCard(card, device: pubkey) {
                try await sendToDevices(account, payload, ((try? peers.devicesOf(account)) ?? []).map { ($0.pub, [$0.encPub]) })
                upsertRequest(Request(pubkey: account, dir: "out", nickname: Self.sanitizeNickname(alias), token: nil, encryptionPubkey: nil, ts: now()))
                changed(); return
            }
            print("friends: the card in the link does not prove that key: sending to the key alone")
        }
        if let token { try await transport.sendSealed(toToken: token, payload, recipientEncPubs: [], peerPubkey: pubkey) }
        else { try await transport.sendSealed(toPubkey: pubkey, payload, recipientEncPubs: [], quiet: false) }
        upsertRequest(Request(pubkey: pubkey, dir: "out", nickname: Self.sanitizeNickname(alias), token: token, encryptionPubkey: nil, ts: now()))
        changed()
    }

    /// Todas las solicitudes vivas (24 h), en los dos sentidos.
    public var requests: [Request] { loadRequests() }
    public var incoming: [Request] { loadRequests().filter { $0.dir == "in" } }
    public var outgoing: [Request] { loadRequests().filter { $0.dir == "out" } }

    private func loadRequests() -> [Request] {
        guard let raw = kv.get("requests"), let arr = (try? JSON.parse(raw))?.array else { return [] }
        let t = now()
        return arr.compactMap { o -> Request? in
            guard let pk = o["pubkey"]?.string else { return nil }
            return Request(pubkey: pk, dir: o["dir"]?.string ?? "in", nickname: o["nickname"]?.string ?? "", token: o["token"]?.string,
                           encryptionPubkey: o["encryptionPubkey"]?.string, ts: o["ts"]?.int ?? 0, vouched: o["vouched"]?.bool ?? false)
        }.filter { t - $0.ts < Self.requestTTL }
    }

    private func saveRequests(_ l: [Request]) {
        kv.set("requests", JSON.array(l.map { r in
            var o: [String: JSON] = ["pubkey": .string(r.pubkey), "dir": .string(r.dir), "nickname": .string(r.nickname), "ts": .int(r.ts), "vouched": .bool(r.vouched)]
            if let t = r.token { o["token"] = .string(t) }
            if let e = r.encryptionPubkey { o["encryptionPubkey"] = .string(e) }
            return .object(o)
        }).text)
    }

    /// Inserta o actualiza (conserva el `ts` original para el TTL).
    private func upsertRequest(_ r: Request) {
        var l = loadRequests()
        if let i = l.firstIndex(where: { same($0.pubkey, r.pubkey) && $0.dir == r.dir }) {
            l[i] = Request(pubkey: r.pubkey, dir: r.dir, nickname: r.nickname, token: r.token, encryptionPubkey: r.encryptionPubkey, ts: l[i].ts, vouched: r.vouched)
        } else { l.insert(r, at: 0) }
        saveRequests(l)
    }

    private func removeRequests(_ pubkey: String, dir: String? = nil) {
        saveRequests(loadRequests().filter { !(same($0.pubkey, pubkey) && (dir == nil || $0.dir == dir)) })
    }

    private func requestOf(_ pubkey: String?, _ dir: String) -> Request? {
        guard let pubkey else { return nil }
        return loadRequests().first { same($0.pubkey, pubkey) && $0.dir == dir }
    }

    /// Acepta una solicitud: amigos en los dos lados. Nada entra a ningún chat.
    public func accept(_ pubkey: String) async {
        guard let r = requestOf(pubkey, "in") else { return }
        await becomeFriends(pubkey, nickname: r.nickname, token: r.token, encPub: r.encryptionPubkey)
        if let t = r.token { online[pubkey] = t }
        await replyAccept(pubkey)
        changed()
    }

    /// Descarta una que llegó, o cancela una que mandé. Al otro no se le dice.
    public func dismiss(_ pubkey: String, dir: String) { removeRequests(pubkey, dir: dir); changed() }

    /// `pubkey`: la llave con la que se le guarda (su cuenta si su tarjeta lo prueba); `device`: el que escribió.
    private func becomeFriends(_ pubkey: String, nickname: String, token: String?, encPub: String?, device: String? = nil) async {
        // La misma llave puede venir escrita de otra forma: se queda en el registro que ya hay.
        var existing: JSON? = find(pubkey)
        if existing == nil { existing = try? peers.get(pubkey) }
        let key = existing?["publickey"]?.string ?? pubkey
        let nick = existing?["nickname"]?.string.flatMap { $0.isEmpty ? nil : $0 } ?? (nickname.isEmpty ? String(pubkey.prefix(8)) : nickname)
        _ = try? peers.addContact(key, nickname: nick, encryptionPubkey: encPub ?? existing?["encryptionPubkey"]?.string, lastToken: token)
        removeRequests(key)
        if let device, !same(device, key) { removeRequests(device) }
        await hooks.onFriend?(key)
    }

    private func replyAccept(_ pubkey: String) async { try? await send(pubkey, me(Self.accept)) }

    // MARK: escribirle a un amigo

    /// Sellado a TODOS los aparatos de su cuenta, cada uno por su camino más directo — por token si
    /// está en línea esta sesión, por pubkey si no (cada aparato tiene su cola). Falla solo si no
    /// llegó a ninguno; el mismo mensaje dos veces lo descarta el otro lado por su id.
    public func send(_ pubkey: String, _ payload: JSON, quiet: Bool = false) async throws {
        try await sendToDevices(pubkey, payload, recipientsOf(pubkey), quiet: quiet)
    }

    private func sendToDevices(_ contact: String, _ payload: JSON, _ to: [(String, [String])], quiet: Bool = false) async throws {
        if to.isEmpty { throw FriendsError(description: "no device of this account is known yet", code: "no-devices") }
        var sent = 0
        var last: Error?
        for (device, keys) in to {
            let token = deviceTokens[device] ?? (to.count == 1 ? online[contact] : nil)
            do {
                if let token { try await transport.sendSealed(toToken: token, payload, recipientEncPubs: keys, peerPubkey: device) }
                else { try await transport.sendSealed(toPubkey: device, payload, recipientEncPubs: keys, quiet: quiet) }
                sent += 1
            } catch { last = error }
        }
        if sent == 0 { throw last ?? FriendsError(description: "nothing could be sent", code: "send-failed") }
    }

    // MARK: lo que llega

    private func authenticate(_ m: SealedSession.Message) async -> Who? {
        // Por token tras un reinicio, la pubkey de quien manda puede no saberse (ese token nuevo no
        // ha saludado). La llave que SELLÓ dice qué amigo es; y si no es de nadie conocido, se le
        // pregunta al token de quién es.
        var claimed = m.fromPubkey
        if claimed == nil {
            claimed = contacts().first { c in ((try? peers.encPubsOf(c["publickey"]?.string ?? "")) ?? []).contains { same($0, m.senderEncPub) } }?["publickey"]?.string
        }
        if claimed == nil, let t = m.fromToken { claimed = try? await transport.whoIs(t) }
        guard let claimed else { print("friends: message from a token nobody said whose it is: dropped"); return nil }
        let contact = findBySender(claimed)
        if let c = contact, let pk = c["publickey"]?.string, ((try? peers.encPubsOf(pk)) ?? []).contains(where: { same($0, m.senderEncPub) }) {
            return Who(pubkey: pk, contact: c, encPub: m.senderEncPub, device: claimed)
        }
        guard let announced = try? await transport.encPubOf(claimed), same(announced, m.senderEncPub) else {
            print("friends: message sealed with a key that is not the sender's: dropped"); return nil
        }
        return Who(pubkey: contact?["publickey"]?.string ?? claimed, contact: contact, encPub: m.senderEncPub, device: claimed)
    }

    /// LO QUE LLEGA: autentica, descarta lo de quien bloqueaste y atiende las solicitudes. `nil` si no
    /// hay que mirarlo más; si no, `who` y si era del protocolo de amigos (`handled`).
    public func receive(_ m: SealedSession.Message) async -> Received? {
        guard let type = m.payload["type"]?.string else { return nil }
        ensurePinned()   // un fijo tiene que ser amigo ANTES de juzgar su primer mensaje
        guard let who = await authenticate(m) else { return nil }
        if let t = m.fromToken { deviceTokens[who.device] = t }
        // BLOQUEADO: no entra nada suyo —ni mensajes ni solicitudes— y no se le contesta nada.
        if isBlocked(who.pubkey) || (m.fromPubkey.map { isBlocked($0) } ?? false) { return nil }
        switch type {
        case Self.request: await onRequest(m, who); return Received(who: who, handled: true)
        case Self.accept: await onAccept(m, who); return Received(who: who, handled: true)
        default: return Received(who: who, handled: false)
        }
    }

    /// Su versión (para la app) y su tarjeta de perfil, venga en el mensaje que venga.
    public func noteProfile(_ pk: String, _ payload: JSON) async {
        await hooks.onProfile?(pk, payload)
        if let card = payload["card"], card.object != nil {
            if let r = try? peers.adoptPeerCard(card), !r.adopted, r.reason == "master-changed" {
                print("friends: the card of \(pk.prefix(24)) is signed by another device: not adopted silently")
            }
        }
    }

    /// La CUENTA de quien escribe: la de su tarjeta si la firmó su perfil y la trajo el propio aparato.
    private func accountOf(_ who: Who, _ payload: JSON) -> String {
        who.contact?["publickey"]?.string ?? (try? peers.accountFromCard(payload["card"], device: who.device)) ?? who.pubkey
    }

    /// Un amigo guardado por la llave de un aparato pasa a su cuenta en cuanto ese aparato trae la
    /// tarjeta que lo prueba (apodo, notas y mi calificación van con él; lo de la app, por `onMoved`).
    public func upgradeToAccount(_ who: Who, _ payload: JSON) async -> JSON? {
        guard let c = who.contact, let old = c["publickey"]?.string else { return who.contact }
        guard same(old, who.device), let account = try? peers.accountFromCard(payload["card"], device: who.device), !same(account, old) else { return c }
        do {
            _ = try peers.addContact(account, nickname: c["nickname"]?.string, encryptionPubkey: c["encryptionPubkey"]?.string,
                                     lastToken: c["lastToken"]?.string, notes: c["contactNotes"]?.string)
            let rating: Double? = { switch c["myRating"]?["rating"] { case .int(let n)?: return Double(n); case .double(let d)?: return d; default: return nil } }()
            if let r = rating, (try? peers.get(account))?["myRating"] == nil {
                _ = try peers.setRating(account, r, notes: c["myRating"]?["notes"]?.string ?? "")
            }
            await hooks.onMoved?(old, account)
            _ = try peers.removeContact(old)
            if let t = online.removeValue(forKey: old) { online[account] = t }
            print("friends: contact moved from a device key to its account")
        } catch { print("friends: could not move the contact to its account: \(error)"); return c }
        return (try? peers.get(account)) ?? c
    }

    private func onRequest(_ m: SealedSession.Message, _ who: Who) async {
        let nick = Self.sanitizeNickname(m.payload["nickname"]?.string)
        await noteProfile(who.pubkey, m.payload)
        let contact = await upgradeToAccount(who, m.payload)
        let account = contact?["publickey"]?.string ?? accountOf(who, m.payload)
        if isBlocked(account) { return }
        await refreshCode()   // un código es de UN uso: el de la pantalla ya no vale
        if let t = m.fromToken { online[account] = t }
        // Ya es amigo por su cuenta (un fijo lo es siempre, o lo añadí desde otro de sus aparatos),
        // o yo también se lo pedí: se acepta sin más.
        let known = contact ?? find(account)
        if known != nil || requestOf(who.device, "out") != nil || requestOf(account, "out") != nil {
            await becomeFriends(account, nickname: nick, token: m.fromToken, encPub: who.encPub, device: who.device)
            await replyAccept(account)
            changed(); return
        }
        let vouched = await hooks.isVouched?(account) ?? false
        let r = Request(pubkey: account, dir: "in", nickname: nick, token: m.fromToken, encryptionPubkey: who.encPub, ts: now(), vouched: vouched)
        upsertRequest(r)
        changed()
        await hooks.onRequest?(r)
    }

    private func onAccept(_ m: SealedSession.Message, _ who: Who) async {
        // Solo cuenta si lo pedí —a ese aparato o a su cuenta entera— o si ya es amigo. Nadie se añade solo.
        await noteProfile(who.pubkey, m.payload)
        let contact = await upgradeToAccount(who, m.payload)
        let account = contact?["publickey"]?.string ?? accountOf(who, m.payload)
        let asked = requestOf(who.device, "out") ?? requestOf(account, "out")
        let known = contact ?? find(account)
        if asked == nil && known == nil { return }
        let nick = (asked?.nickname).flatMap { $0.isEmpty ? nil : $0 } ?? Self.sanitizeNickname(m.payload["nickname"]?.string)
        await becomeFriends(account, nickname: nick, token: m.fromToken, encPub: who.encPub, device: who.device)
        if let t = m.fromToken { online[account] = t }
        changed()
    }
}
