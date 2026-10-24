import DotrinoNative
import PhotosUI
import SwiftUI

/// «AÑADIR CONTACTO», la hoja de todas las apps nativas: por código (tecleado, pegado, escaneado o
/// de una foto) → una SOLICITUD; mi código y su QR; y «CERCA» (solo nativo: un navegador no puede
/// anunciar): mientras está abierta, mi tarjeta de contacto sale por Bluetooth y por la red local, y
/// entran las de quien esté cerca (`dotrino-vault/docs/pairing-protocol.md` §1.8). «Añadir» manda la
/// misma solicitud. Que lleguen varios a la vez es lo normal: aquí no hay incidente.
/// La pareja web es `<dotrino-add-friend>` (`@dotrino/profile/friends-ui`).
public struct AddFriendSheet: View {
    public enum Tab: String, Sendable { case add, mine, nearby }

    private let friends: Friends
    private let nearby: Nearby?
    private let me: String?
    private let shareBase: String
    private let prepareNearby: (() async -> Void)?
    private let peer: FriendLink?
    @ObservedObject private var lang = DotrinoLang.shared
    @Environment(\.dismiss) private var dismiss

    @State private var tab: Tab
    @State private var code: String
    @State private var alias: String
    @State private var myCode: String?
    @State private var error: String?
    @State private var sending = false
    @State private var scanning = false
    @State private var photo: PhotosPickerItem?
    @State private var people: [Person] = []
    @State private var nearbyError = false
    @State private var nearbyOn = false

    /// - Parameters:
    ///   - nearby: la búsqueda cerca (Bluetooth + red local); sin ella, no hay pestaña «Cerca».
    ///   - me: mi llave pública (la de la tarjeta de contacto que se anuncia en «Cerca»).
    ///   - shareBase: la página a la que lleva el QR de mi código (`<shareBase>#add=CODE`).
    ///   - prepareNearby: lo que la app hace antes de anunciar (pedir el permiso de Bluetooth y encenderlo).
    ///   - peer: llegó un enlace `#to=`: ya se sabe a quién, solo queda el apodo.
    public init(friends: Friends, nearby: Nearby? = nil, me: String? = nil, shareBase: String,
                initialCode: String = "", tab: Tab = .add, peer: FriendLink? = nil, prepareNearby: (() async -> Void)? = nil) {
        self.friends = friends; self.nearby = nearby; self.me = me; self.shareBase = shareBase
        self.peer = peer; self.prepareNearby = prepareNearby
        _tab = State(initialValue: tab == .nearby && nearby == nil ? .add : tab)
        _code = State(initialValue: initialCode)
        _alias = State(initialValue: peer?.nickname ?? "")
    }

    private func t(_ key: String, _ args: CVarArg...) -> String {
        let f = lang.text("dotrino_friends_\(key)", in: .module)
        return args.isEmpty ? f : String(format: f, arguments: args)
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if peer == nil {
                        Picker("", selection: $tab) {
                            Text(t("tab_add")).tag(Tab.add)
                            Text(t("tab_mine")).tag(Tab.mine)
                            if nearby != nil { Text(t("tab_nearby")).tag(Tab.nearby) }
                        }.pickerStyle(.segmented)
                    }
                    if peer != nil { toTab } else {
                        switch tab {
                        case .add: addTab
                        case .mine: mineTab
                        case .nearby: nearbyTab
                        }
                    }
                }.padding(20)
            }
            // Se anuncia y se busca solo mientras «Cerca» está a la vista: la tarea acaba con la pestaña o la hoja.
            .task(id: tab) { if tab == .nearby, peer == nil { await runNearby() } }
            // Mi código se renueva solo (y tras usarse): se mira cada segundo mientras la hoja está abierta.
            .task {
                while !Task.isCancelled {
                    let c = await friends.code
                    if c != myCode { myCode = c }
                    if c == nil { await friends.refreshCode() }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
            .navigationTitle(t("title")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(t("cancel")) { dismiss() } } }
        }
        .fullScreenCover(isPresented: $scanning) {
            ZStack(alignment: .bottom) {
                DotrinoQRScanner(onResult: { text in scanning = false; scanned(text) },
                                 onError: { _ in scanning = false; error = t("camera_denied") }).ignoresSafeArea()
                Button(t("close")) { scanning = false }.buttonStyle(FriendPill()).padding(.bottom, 40)
            }
        }
        .onChange(of: photo) { item in
            Task {
                guard let data = try? await item?.loadTransferable(type: Data.self), let img = UIImage(data: data) else { return }
                if let text = DotrinoQR.decode(img) { scanned(text) } else { error = t("err_no_code") }
            }
        }
    }

    private func field(_ label: String, _ placeholder: String, _ text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.footnote.bold()).foregroundColor(DotrinoPalette.muted)
            TextField(placeholder, text: text).padding(12).background(RoundedRectangle(cornerRadius: 16).stroke(DotrinoPalette.muted.opacity(0.4)))
        }
    }

    @ViewBuilder private var errorLine: some View {
        if let error { Text(error).font(.subheadline).foregroundColor(.red).accessibilityIdentifier("add-error") }
    }

    @ViewBuilder private var addTab: some View {
        Text(t("info")).font(.subheadline).foregroundColor(DotrinoPalette.muted)
        Text(t("field_code")).font(.footnote.bold()).foregroundColor(DotrinoPalette.muted)
        HStack {
            TextField(t("ph_code"), text: $code).font(.system(.title3, design: .monospaced)).multilineTextAlignment(.center)
                .textInputAutocapitalization(.characters).autocorrectionDisabled().padding(12)
                .background(RoundedRectangle(cornerRadius: 16).stroke(DotrinoPalette.muted.opacity(0.4))).accessibilityIdentifier("code-input")
            Button(t("paste")) { if let s = UIPasteboard.general.string { code = Friends.codeFrom(s) } }.buttonStyle(FriendPill())
        }
        HStack {
            Button(t("scan")) { scanning = true }.buttonStyle(FriendPill()).accessibilityIdentifier("scan-qr")
            PhotosPicker(selection: $photo, matching: .images) { Text(t("pick_photo")) }.buttonStyle(FriendPill())
        }
        field(t("field_alias"), t("ph_alias"), $alias)
        errorLine
        Text(t("hint")).font(.footnote).foregroundColor(DotrinoPalette.muted)
        Button(sending ? t("sending") : t("send")) { submit() }.buttonStyle(FriendPill(filled: true))
            .frame(maxWidth: .infinity).disabled(sending).accessibilityIdentifier("send-hello")
    }

    /// Una solicitud a la llave de un enlace: a quién va, el apodo y «enviar».
    @ViewBuilder private var toTab: some View {
        let nick = (peer?.nickname).flatMap { $0.isEmpty ? nil : $0 } ?? t("to_no_nick")
        Text(t("to_info", nick)).font(.subheadline).foregroundColor(DotrinoPalette.muted).accessibilityIdentifier("to-info")
        field(t("field_alias"), t("ph_alias"), $alias)
        errorLine
        Text(t("hint")).font(.footnote).foregroundColor(DotrinoPalette.muted)
        Button(sending ? t("sending") : t("send")) { submitToPeer() }.buttonStyle(FriendPill(filled: true))
            .frame(maxWidth: .infinity).disabled(sending).accessibilityIdentifier("send-hello")
    }

    @ViewBuilder private var mineTab: some View {
        Text(t("mine_info")).font(.subheadline).foregroundColor(DotrinoPalette.muted)
        HStack {
            Text(myCode ?? "…").font(.system(size: 28, weight: .bold, design: .monospaced)).kerning(4).accessibilityIdentifier("my-pairing-code")
            Spacer()
            Button(t("copy")) { UIPasteboard.general.string = myCode }.buttonStyle(FriendPill()).disabled(myCode == nil)
        }
        if let c = myCode {
            DotrinoQRView("\(shareBase)#add=\(c)", size: 220).frame(maxWidth: .infinity).accessibilityIdentifier("my-qr")
            Text(t("qr_hint")).font(.footnote).foregroundColor(DotrinoPalette.muted).frame(maxWidth: .infinity)
        }
    }

    // MARK: cerca

    struct Person: Identifiable, Equatable {
        var id: String { card.publickey }
        let card: BleWire.Contact
        var state: String   // new, sending, sent, contact
    }

    private func runNearby() async {
        guard let nearby, let me else { return }
        nearbyError = false
        nearbyOn = true
        defer { nearbyOn = false }
        await prepareNearby?()
        let nick = await friends.me(Friends.request)["nickname"]?.string ?? ""
        let stop: () -> Void
        do { stop = try nearby.announce(kind: BleWire.kindContact, data: try BleWire.encodeContact(publickey: me, nickname: nick)) }
        catch { print("friends: nearby announce:", error); nearbyError = true; return }
        defer { stop() }
        let mine = await friends.contacts().compactMap { $0["publickey"]?.string }
        while !Task.isCancelled {
            _ = await nearby.discover(kind: BleWire.kindContact, timeout: 6) { data in
                guard let c = BleWire.decodeContact(data), !Delegation.samePubkey(c.publickey, me) else { return }
                Task { @MainActor in
                    guard !people.contains(where: { Delegation.samePubkey($0.card.publickey, c.publickey) }) else { return }
                    people.append(Person(card: c, state: mine.contains { Delegation.samePubkey($0, c.publickey) } ? "contact" : "new"))
                }
            }
        }
    }

    @ViewBuilder private var nearbyTab: some View {
        Text(t("nearby_info")).font(.subheadline).foregroundColor(DotrinoPalette.muted)
        if nearbyError {
            Text(t("nearby_unavailable")).font(.subheadline).foregroundColor(.red).accessibilityIdentifier("nearby-error")
        } else if people.isEmpty {
            Text(t(nearbyOn ? "nearby_searching" : "nearby_none")).font(.subheadline).accessibilityIdentifier("nearby-searching")
            if nearbyOn { Text(t("nearby_none")).font(.footnote).foregroundColor(DotrinoPalette.muted) }
        } else {
            ForEach(people) { p in
                let name = p.card.nickname.isEmpty ? t("nearby_no_nick") : p.card.nickname
                HStack(spacing: 12) {
                    DotrinoAvatarView(seed: p.card.publickey).frame(width: 40, height: 40)
                    Text(name).font(.body.bold()).frame(maxWidth: .infinity, alignment: .leading)
                    switch p.state {
                    case "contact": Text(t("nearby_contact")).font(.footnote).foregroundColor(DotrinoPalette.muted)
                    case "sent": Text(t("nearby_sent")).font(.footnote).foregroundColor(DotrinoPalette.muted)
                    case "sending": Text(t("sending")).font(.footnote).foregroundColor(DotrinoPalette.muted)
                    default: Button(t("nearby_add")) { addNearby(p) }.buttonStyle(FriendPill(filled: true)).accessibilityIdentifier("nearby-add")
                    }
                }.accessibilityIdentifier("nearby-item")
            }
            errorLine
        }
    }

    private func setState(_ p: Person, _ s: String) {
        if let i = people.firstIndex(where: { $0.id == p.id }) { people[i].state = s }
    }

    private func addNearby(_ p: Person) {
        error = nil
        setState(p, "sending")
        Task {
            do { try await friends.request(pubkey: p.card.publickey, alias: p.card.nickname); setState(p, "sent") }
            catch { setState(p, "new"); self.error = message(error) }
        }
    }

    // MARK: enviar

    /// «Sin red» y «no vale» se arreglan distinto: se dice cuál.
    private func message(_ e: Error) -> String {
        switch (e as? Friends.FriendsError)?.code {
        case "own": return t("err_own")
        case "offline": return t("err_offline")
        case "invalid": return t("err_invalid")
        default: return t("err_send")
        }
    }

    private func scanned(_ text: String) {
        let c = Friends.codeFrom(text)
        guard Friends.isValidCode(c) else { error = t("err_no_code"); return }
        code = c; submit()
    }

    private func submit() {
        error = nil
        let c = Friends.normalizeCode(code)
        guard Friends.isValidCode(c) else { error = t("err_invalid"); return }
        sending = true
        Task {
            do { try await friends.requestByCode(c, alias: alias); dismiss() } catch { self.error = message(error) }
            sending = false
        }
    }

    private func submitToPeer() {
        guard let p = peer else { return }
        error = nil; sending = true
        Task {
            do { try await friends.request(pubkey: p.pubkey, alias: alias, card: p.card); dismiss() } catch { self.error = message(error) }
            sending = false
        }
    }
}

/// El botón de la hoja: la píldora de «Cool & Cozy». `filled` = la acción principal.
struct FriendPill: ButtonStyle {
    var filled = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.body.weight(.bold))
            .foregroundColor(filled ? .white : DotrinoPalette.fg)
            .padding(.horizontal, 20).padding(.vertical, 12)
            .background(Capsule().fill(filled ? DotrinoPalette.accent : DotrinoPalette.card))
            .overlay(Capsule().stroke(filled ? Color.clear : DotrinoPalette.muted.opacity(0.4)))
            .opacity(configuration.isPressed ? 0.8 : 1)
    }
}
