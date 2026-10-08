import DotrinoNative
import SwiftUI

/// The Dotrino bar for NATIVE screens: what `<dotrino-topbar>` is on the web (brand, the app's
/// buttons, ES/EN and the support coin). Same piece as `DotrinoTopbar.kt`; CONVENCIONES §16.2:
/// the ecosystem's components exist ONCE, here, and no app redraws them.
///
///     DotrinoTopbar(repo: "imdotrino/dotrino-padel-contador",
///                   brand: .init(name: "Padel", image: Image("Brand"))) {
///         Button("Results") { … }
///     }
public struct DotrinoTopbar<Actions: View>: View {
    public struct Brand {
        let name: String
        let image: Image
        public init(name: String, image: Image) { self.name = name; self.image = image }
    }

    @ObservedObject private var lang = DotrinoLang.shared
    private let repo: String
    private let brand: Brand?
    private let actions: Actions
    private let profile: Profile?
    @State private var support = false
    @State private var profileOpen = false
    @State private var netOpen = false
    /// The network button shows only when this app has a live transport (`DotrinoNetwork`).
    @State private var hasNet = !DotrinoNetwork.sources().isEmpty

    /// The PROFILE BUTTON (CONVENCIONES §6.1): the active profile's initial and key. Nil = it
    /// still shows. On a phone the profiles are managed in the Dotrino app, with the phone's
    /// identity — a browser would show another one.
    /// Outside the generic type, so it is ONE type whatever the actions are.
    public typealias Profile = DotrinoTopbarProfile
    /// false = WITHOUT the profile button. Only for a screen that is not of ONE profile (the
    /// Dotrino app's Requests list every profile of the phone): there a single avatar says the wrong thing.
    private let showProfile: Bool
    /// The active profile changed (switched, created, adopted, signed in): the app starts again
    /// with it (changing profile is not reactive, as on the web). Nil = nothing to redo.
    private let onProfileChanged: (() -> Void)?
    @State private var profiles: [PhoneProfiles.Entry] = []
    @State private var webURL: URL?
    @State private var pidBeforeWeb: String?
    @Environment(\.openURL) private var openURL

    /// [repo]: the GitHub repo where «Report a bug» goes. [brand]: nil = «Dotrino».
    public init(repo: String, brand: Brand? = nil, profile: Profile? = nil, showProfile: Bool = true,
                onProfileChanged: (() -> Void)? = nil, @ViewBuilder actions: () -> Actions) {
        self.showProfile = showProfile
        self.onProfileChanged = onProfileChanged
        self.repo = repo
        self.brand = brand
        self.profile = profile
        self.actions = actions()
    }



    public var body: some View {
        HStack(spacing: 10) {
            Button { openURL(URL(string: "https://dotrino.com/")!) } label: {
                HStack(spacing: 8) {
                    (brand?.image ?? Image("DotrinoBrand", bundle: .module))
                        .resizable().frame(width: 28, height: 28).clipShape(RoundedRectangle(cornerRadius: 8))
                    Text(brand?.name ?? "Dotrino").font(.headline).foregroundColor(DotrinoPalette.fg).lineLimit(1)
                }
            }
            .accessibilityIdentifier("topbar-brand")
            Spacer(minLength: 4)
            actions
            if hasNet {
                // THE NETWORK BUTTON, like the web topbar's: each connection, its traffic and its road.
                Button { netOpen = true } label: {
                    Image(systemName: "arrow.up.arrow.down").font(.system(size: 14, weight: .semibold))
                        .foregroundColor(DotrinoPalette.fg).frame(width: 32, height: 32)
                        .overlay(Circle().stroke(DotrinoPalette.muted.opacity(0.4)))
                }
                .accessibilityLabel(lang.text("dotrino_net_cta", in: .module))
                .accessibilityIdentifier("net-stats")
            }
            // Las DOS opciones siempre a la vista, la activa resaltada (CONVENCIONES §9).
            HStack(spacing: 0) {
                ForEach(["es", "en"], id: \.self) { l in
                    Button(l.uppercased()) { lang.set(l) }
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 10).padding(.vertical, 6)
                        .foregroundColor(lang.code == l ? DotrinoPalette.bg : DotrinoPalette.muted)
                        .background(lang.code == l ? DotrinoPalette.fg : Color.clear)
                        .accessibilityIdentifier("lang-\(l)")
                }
            }
            .clipShape(Capsule())
            .overlay(Capsule().stroke(DotrinoPalette.muted.opacity(0.4)))
            if showProfile {
                // The PROFILE'S AVATAR, like the web topbar: its photo or its identicon.
                Button { profileOpen = true } label: {
                    Group {
                        if let p = profile { DotrinoAvatarView(seed: p.key, photo: p.avatar) }
                        else { Circle().fill(DotrinoPalette.muted).overlay(Text("👤").font(.system(size: 15))) }
                    }.frame(width: 34, height: 34)
                }
                .accessibilityLabel(lang.text("dotrino_profile_cta", in: .module))
                .accessibilityIdentifier("profile-button")
            }
            Button { support = true } label: {
                Image("DotrinoCoin", bundle: .module).resizable().frame(width: 34, height: 34)
            }
            .accessibilityLabel(lang.text("dotrino_support_cta", in: .module))
            .accessibilityIdentifier("support-coin")
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(DotrinoPalette.card)
        .sheet(isPresented: $support) { SupportSheet(repo: repo, onClose: { support = false }) }
        .sheet(isPresented: $netOpen) { NetSheet(onClose: { netOpen = false }) }
        .onReceive(NotificationCenter.default.publisher(for: DotrinoNetwork.changed)) { _ in
            hasNet = !DotrinoNetwork.sources().isEmpty
        }
        .sheet(isPresented: $profileOpen) { profileMenu.presentationDetents([.medium, .large]) }
        .fullScreenCover(item: Binding(get: { webURL.map { WebTarget(url: $0) } }, set: { webURL = $0?.url })) { t in
            DotrinoWebSheet(url: t.url) { closeWeb() }
        }
    }
}

private struct WebTarget: Identifiable { let url: URL; var id: String { url.absoluteString } }

extension DotrinoTopbar {
    /// THE PROFILE MENU, like the web topbar's: this phone's profiles (avatar, name, the active
    /// one ticked) to switch, and «Open my profile», «Create», «Adopt», «Sign in» (or «Sign out»).
    /// Everything happens INSIDE this app, with the phone's identity: no other app is needed
    /// (CONVENCIONES §16.2). Same as the Android menu.
    @ViewBuilder var profileMenu: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                Text(lang.text("dotrino_profiles", in: .module)).font(.title3.weight(.bold)).foregroundColor(DotrinoPalette.fg)
                    .frame(maxWidth: .infinity).padding(.bottom, 8)
                ForEach(profiles) { e in
                    Button { if !e.current { switchTo(e.id) } } label: {
                        HStack(spacing: 12) {
                            DotrinoAvatarView(seed: e.seed, photo: e.avatar).frame(width: 36, height: 36)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(e.name ?? lang.text("dotrino_profile_unnamed", in: .module)).font(e.current ? .body.weight(.bold) : .body)
                                if let l = e.login { Text(l).font(.caption).foregroundColor(DotrinoPalette.muted) }
                            }
                            Spacer()
                            if e.current { Image(systemName: "checkmark").foregroundColor(DotrinoPalette.accent) }
                        }.padding(.vertical, 6).foregroundColor(DotrinoPalette.fg)
                    }
                    .accessibilityIdentifier("profile-\(e.id)")
                }
                Divider().padding(.vertical, 6)
                menuLink(lang.text("dotrino_profile_open_mine", in: .module), DotrinoTopbarURLs.profile)
                menuLink("＋ " + lang.text("dotrino_profile_new", in: .module), DotrinoTopbarURLs.create)
                menuLink("↧ " + lang.text("dotrino_profile_adopt", in: .module), DotrinoTopbarURLs.adopt)
                // «Sign out» of an account entered with a password is done on its page.
                if profiles.contains(where: { $0.current && $0.login != nil }) {
                    menuLink("⇥ " + lang.text("dotrino_profile_logout", in: .module), DotrinoTopbarURLs.profile)
                } else {
                    menuLink("⇤ " + lang.text("dotrino_profile_login", in: .module), DotrinoTopbarURLs.login)
                }
                Button(lang.text("dotrino_support_close", in: .module)) { profileOpen = false }
                    .foregroundColor(DotrinoPalette.fg).frame(maxWidth: .infinity).padding(.top, 12)
            }
            .padding(24)
        }
        .onAppear { profiles = (try? PhoneProfiles.load()) ?? [] }
    }

    private func menuLink(_ label: String, _ url: URL) -> some View {
        Button { profileOpen = false; pidBeforeWeb = PhoneProfiles.currentPid(); webURL = url } label: {
            Text(label).font(.body).foregroundColor(DotrinoPalette.fg).frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 10)
        }
    }

    private func switchTo(_ pid: String) {
        guard (try? PhoneProfiles.switchTo(pid)) != nil else { return }
        profileOpen = false
        onProfileChanged?()
    }

    private func closeWeb() {
        webURL = nil
        if PhoneProfiles.currentPid() != pidBeforeWeb { onProfileChanged?() }
    }
}

/// The same pages the web topbar's menu opens.
public enum DotrinoTopbarURLs {
    public static let profile = URL(string: "https://profile.dotrino.com/")!
    public static let create = URL(string: "https://profile.dotrino.com/create")!
    public static let adopt = URL(string: "https://vault.dotrino.com/d")!
    public static let login = URL(string: "https://profile.dotrino.com/login")!
}

extension DotrinoTopbar where Actions == EmptyView {
    public init(repo: String, brand: Brand? = nil, profile: Profile? = nil, showProfile: Bool = true, onProfileChanged: (() -> Void)? = nil) {
        self.init(repo: repo, brand: brand, profile: profile, showProfile: showProfile, onProfileChanged: onProfileChanged) { EmptyView() }
    }
}

/// The palette of the ecosystem's native components. By default the dark one (the same as
/// `dotrino_colors.xml`); an app with the home's look calls `DotrinoPalette.use(.coolAndCozy)`
/// at launch — on Android the app overrides the `dotrino_*` colour resources instead.
public enum DotrinoPalette {
    public struct Colors: Sendable {
        public let bg, card, fg, muted, accent: Color
        public init(bg: Color, card: Color, fg: Color, muted: Color, accent: Color) {
            self.bg = bg; self.card = card; self.fg = fg; self.muted = muted; self.accent = accent
        }
        private static func hex(_ v: UInt32) -> Color {
            Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
        }
        /// The dark palette of `dotrino-app`.
        public static let dark = Colors(bg: hex(0x0B1220), card: hex(0x131D31), fg: hex(0xDBE7F7), muted: hex(0x8A9BB5), accent: hex(0x4F8CFF))
        /// «Cool & Cozy», the home's (dotrino.com): light, blue #00658c.
        public static let coolAndCozy = Colors(bg: hex(0xF4F7F9), card: hex(0xFFFFFF), fg: hex(0x181C1E), muted: hex(0x4A5560), accent: hex(0x00658C))
    }

    nonisolated(unsafe) private static var current = Colors.dark
    /// Choose the palette once, at launch, before the first screen is drawn.
    public static func use(_ c: Colors) { current = c }

    public static var bg: Color { current.bg }
    public static var card: Color { current.card }
    public static var fg: Color { current.fg }
    public static var muted: Color { current.muted }
    public static var accent: Color { current.accent }
}

/// What the coin opens: the same texts and destinations as the `<dotrino-support>` modal.
private struct SupportSheet: View {
    let repo: String
    let onClose: () -> Void
    @ObservedObject private var lang = DotrinoLang.shared
    private static let kofi = URL(string: "https://ko-fi.com/dotrino")!
    private static let discord = URL(string: "https://discord.gg/D648uq7cth")!
    private static let home = URL(string: "https://dotrino.com/")!
    private func T(_ k: String) -> String { lang.text(k, in: .module) }

    var body: some View {
        VStack(spacing: 14) {
            Image("DotrinoCoin", bundle: .module).resizable().frame(width: 72, height: 72)
            Text(T("dotrino_support_heading")).font(.title3.weight(.bold)).foregroundColor(DotrinoPalette.fg)
            Text(T("dotrino_support_message")).font(.callout).foregroundColor(DotrinoPalette.muted).multilineTextAlignment(.center)
            Link(T("dotrino_support_donate"), destination: Self.kofi)
                .frame(maxWidth: .infinity).padding(12).background(DotrinoPalette.accent).foregroundColor(.white)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            HStack(spacing: 10) {
                Link(T("dotrino_support_discord"), destination: Self.discord).frame(maxWidth: .infinity)
                Link(T("dotrino_support_bug"), destination: URL(string: "https://github.com/\(repo)/issues")!).frame(maxWidth: .infinity)
            }
            .font(.footnote).foregroundColor(DotrinoPalette.accent)
            ShareLink(item: Self.home) { Label(T("dotrino_support_share"), systemImage: "square.and.arrow.up") }
                .foregroundColor(DotrinoPalette.accent)
            Button(T("dotrino_support_close"), action: onClose).foregroundColor(DotrinoPalette.muted)
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(DotrinoPalette.bg.ignoresSafeArea())
        .presentationDetents([.medium, .large])
    }
}

/// THE NETWORK SHEET: each live transport (its proxy, whether it is connected, everything that went
/// through it) and each connection with another device — by which road it goes now (proxy, direct
/// WebRTC, WebRTC via TURN) and its bytes per road. Refreshed every second while open. Same as
/// the Android sheet and the web topbar's modal.
private struct NetSheet: View {
    let onClose: () -> Void
    @ObservedObject private var lang = DotrinoLang.shared
    @State private var all: [NetworkStats] = []
    @State private var copied = false
    private let tick = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    private func T(_ k: String) -> String { lang.text(k, in: .module) }
    private func T(_ k: String, _ a: CVarArg) -> String { lang.text(k, in: .module, a) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(T("dotrino_net_cta")).font(.title3.weight(.bold)).foregroundColor(DotrinoPalette.fg)
                    Spacer()
                    // «Copy»: the stats as text, to paste them into a chat (owner, 2026-10-07).
                    Button {
                        UIPasteboard.general.string = NetworkStats.report(all)
                        copied = true
                        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { copied = false }
                    } label: {
                        Text(T(copied ? "dotrino_net_copied" : "dotrino_net_copy")).font(.footnote.weight(.semibold)).foregroundColor(DotrinoPalette.fg)
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .overlay(RoundedRectangle(cornerRadius: 9).stroke(DotrinoPalette.muted.opacity(0.6)))
                    }
                    .accessibilityIdentifier("net-copy")
                }
                .padding(.bottom, 8)
                if all.isEmpty { Text(T("dotrino_net_none")).foregroundColor(DotrinoPalette.muted) }
                ForEach(Array(all.enumerated()), id: \.offset) { _, s in transport(s) }
                Text(T("dotrino_net_note")).font(.caption).foregroundColor(DotrinoPalette.muted).padding(.top, 8)
                Button(T("dotrino_support_close"), action: onClose).foregroundColor(DotrinoPalette.fg)
                    .frame(maxWidth: .infinity).padding(.top, 12)
            }
            .padding(24)
        }
        .background(DotrinoPalette.bg.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .onAppear(perform: refresh)
        .onReceive(tick) { _ in refresh() }
        .accessibilityIdentifier("net-sheet")
    }

    private func refresh() { all = DotrinoNetwork.sources().map { $0.networkStats() } }

    @ViewBuilder private func transport(_ s: NetworkStats) -> some View {
        let host = URL(string: s.url)?.host ?? s.url
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Circle().fill(s.connected ? Color.green : Color.red).frame(width: 9, height: 9)
                Text("Proxy · \(host)").font(.headline).foregroundColor(DotrinoPalette.fg)
            }
            Text([T(s.connected ? "dotrino_net_connected" : "dotrino_net_disconnected"), s.app,
                  T("dotrino_net_since", s.since.formatted(date: .omitted, time: .shortened))].compactMap { $0 }.joined(separator: " · "))
                .font(.caption).foregroundColor(DotrinoPalette.muted)
            HStack {
                Text(T("dotrino_net_all_proxy")).font(.footnote).foregroundColor(DotrinoPalette.muted)
                Spacer()
                Text("↓ \(fmt(s.proxy.bytesIn))  ↑ \(fmt(s.proxy.bytesOut))").font(.footnote.monospacedDigit()).foregroundColor(DotrinoPalette.fg)
            }
            .padding(10).overlay(RoundedRectangle(cornerRadius: 9).stroke(DotrinoPalette.muted.opacity(0.4)))
            // Only who answered is a connection; a device that just got a ping (the probe for which
            // machines are on) goes in one summary line (same split as NetworkStats.report).
            let unknown = s.peers.filter { $0.msgsIn > 0 && $0.msgsOut == 0 && $0.pubkey == nil }
            let talking = s.peers.filter { $0.msgsIn > 0 && !($0.msgsOut == 0 && $0.pubkey == nil) }
            let silent = s.peers.filter { $0.msgsIn == 0 }
            Text(T("dotrino_net_connections", talking.count).uppercased()).font(.caption2).foregroundColor(DotrinoPalette.muted).padding(.top, 6)
            if talking.isEmpty { Text(T("dotrino_net_none")).font(.footnote).foregroundColor(DotrinoPalette.muted) }
            ForEach(talking) { p in peer(p) }
            if !silent.isEmpty {
                Text(lang.text("dotrino_net_no_answer", in: .module, silent.count, fmt(silent.reduce(0) { $0 + $1.bytesOut.total })))
                    .font(.footnote).foregroundColor(DotrinoPalette.muted).accessibilityIdentifier("net-silent")
            }
            if !unknown.isEmpty {
                Text(lang.text("dotrino_net_unknown", in: .module, unknown.count, fmt(unknown.reduce(0) { $0 + $1.bytesIn.total })))
                    .font(.footnote).foregroundColor(DotrinoPalette.muted).accessibilityIdentifier("net-unknown")
            }
        }
        .accessibilityIdentifier("net-transport")
    }

    @ViewBuilder private func peer(_ p: NetworkStats.Peer) -> some View {
        let who = p.pubkey ?? p.token ?? ""
        HStack(alignment: .top, spacing: 10) {
            Group {
                if let k = p.pubkey { DotrinoAvatarView(seed: k, photo: nil) } else { Circle().fill(DotrinoPalette.muted) }
            }.frame(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(who.count > 14 ? "\(who.prefix(6))…\(who.suffix(6))" : who).font(.caption.monospaced()).foregroundColor(DotrinoPalette.fg).lineLimit(1)
                Text(T(routeKey(p.route))).font(.caption.weight(.semibold)).foregroundColor(routeColor(p.route))
                    .accessibilityIdentifier("net-route-\(p.route)")
                ForEach([("↓", p.bytesIn), ("↑", p.bytesOut)].compactMap { a, b in paths(b).map { "\(a) \($0)" } }, id: \.self) {
                    Text($0).font(.caption2).foregroundColor(DotrinoPalette.muted)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("↓ \(fmt(p.bytesIn.total))").font(.caption.monospacedDigit())
                Text("↑ \(fmt(p.bytesOut.total))").font(.caption.monospacedDigit())
                Text(T("dotrino_net_msgs", p.msgsIn + p.msgsOut)).font(.caption2).foregroundColor(DotrinoPalette.muted)
            }.foregroundColor(DotrinoPalette.fg)
        }
        .padding(.vertical, 6)
        .accessibilityIdentifier("net-peer")
    }

    private func routeKey(_ r: String) -> String {
        ["direct", "turn", "webrtc", "connecting", "failed"].contains(r) ? "dotrino_net_route_\(r)" : "dotrino_net_route_proxy"
    }
    private func routeColor(_ r: String) -> Color {
        switch r {
        case "direct": return .green
        case "turn", "webrtc": return .blue
        case "connecting", "failed": return .orange
        default: return DotrinoPalette.muted
        }
    }
    /// «proxy 1,1 KB · direct 190 B», only the roads that carried something.
    private func paths(_ b: TrafficStats.ByPath) -> String? {
        let parts = [("proxy", b.proxy), ("direct", b.direct), ("turn", b.turn), ("webrtc", b.webrtc)]
            .filter { $0.1 > 0 }.map { "\(T("dotrino_net_path_\($0.0)")) \(fmt($0.1))" }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
    private func fmt(_ n: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: n, countStyle: .binary)
    }
}

/// The avatar colours (the same six as Android), chosen from the key.
enum AvatarPalette {
    static let colors: [Color] = [
        Color(red: 0x00 / 255, green: 0x65 / 255, blue: 0x8C / 255), Color(red: 0x00 / 255, green: 0x6B / 255, blue: 0x5C / 255),
        Color(red: 0x66 / 255, green: 0x55 / 255, blue: 0x90 / 255), Color(red: 0x8C / 255, green: 0x4A / 255, blue: 0x00 / 255),
        Color(red: 0x3F / 255, green: 0x6B / 255, blue: 0x00 / 255), Color(red: 0x7A / 255, green: 0x3E / 255, blue: 0x6B / 255),
    ]
    static func index(_ key: String) -> Int { abs(key.unicodeScalars.reduce(0) { ($0 &* 31) &+ Int($1.value) }) % colors.count }
}

/// What the topbar shows of a profile. `name`: its name. `key`: what its identicon is drawn from
/// (the profile's key). `avatar`: the photo the person uploaded (data-URI), if any.
public struct DotrinoTopbarProfile {
    let name: String?, key: String, avatar: String?
    public init(name: String?, key: String, avatar: String? = nil) { self.name = name; self.key = key; self.avatar = avatar }
}

extension DotrinoNative.Profile {
    /// What the topbar shows of this profile: its name and its avatar, as the web does.
    public var topbar: DotrinoTopbarProfile { .init(name: name, key: avatarSeed, avatar: avatar) }
}
