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

    /// The PROFILE BUTTON (CONVENCIONES §6.1): the active profile's initial and key. Nil = it
    /// still shows. On a phone the profiles are managed in the Dotrino app, with the phone's
    /// identity — a browser would show another one.
    public struct Profile {
        let name: String?, key: String
        public init(name: String?, key: String) { self.name = name; self.key = key }
    }
    @Environment(\.openURL) private var openURL

    /// [repo]: the GitHub repo where «Report a bug» goes. [brand]: nil = «Dotrino».
    public init(repo: String, brand: Brand? = nil, profile: Profile? = nil, @ViewBuilder actions: () -> Actions) {
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
            Button { profileOpen = true } label: {
                Circle().fill(profile.map { AvatarPalette.colors[AvatarPalette.index($0.key)] } ?? DotrinoPalette.muted).frame(width: 34, height: 34)
                    .overlay(Text(profile?.name?.trimmingCharacters(in: .whitespaces).first.map { String($0).uppercased() } ?? "👤")
                        .font(.system(size: 15, weight: .bold)).foregroundColor(.white))
            }
            .accessibilityLabel(lang.text("dotrino_profile_cta", in: .module))
            .accessibilityIdentifier("profile-button")
            Button { support = true } label: {
                Image("DotrinoCoin", bundle: .module).resizable().frame(width: 34, height: 34)
            }
            .accessibilityLabel(lang.text("dotrino_support_cta", in: .module))
            .accessibilityIdentifier("support-coin")
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(DotrinoPalette.card)
        .sheet(isPresented: $support) { SupportSheet(repo: repo, onClose: { support = false }) }
        .sheet(isPresented: $profileOpen) {
            VStack(spacing: 14) {
                Text(profile?.name.flatMap { $0.isEmpty ? nil : $0 } ?? lang.text("dotrino_profile_cta", in: .module)).font(.title3.weight(.bold)).foregroundColor(DotrinoPalette.fg)
                Text(lang.text("dotrino_profile_message", in: .module)).font(.callout).foregroundColor(DotrinoPalette.muted).multilineTextAlignment(.center)
                // The Dotrino app's page: «Open» if it is installed, «Get» if not. (It has no URL
                // scheme yet for another app to open it directly: pending in dotrino-app iOS.)
                Button(lang.text("dotrino_profile_open", in: .module)) { openURL(URL(string: "https://apps.apple.com/app/id6817137047")!) }
                    .font(.body.weight(.bold)).foregroundColor(.white).frame(maxWidth: .infinity).padding(.vertical, 12)
                    .background(Capsule().fill(DotrinoPalette.accent))
                Button(lang.text("dotrino_support_close", in: .module)) { profileOpen = false }.foregroundColor(DotrinoPalette.fg)
            }
            .padding(24).presentationDetents([.medium])
        }
    }
}

extension DotrinoTopbar where Actions == EmptyView {
    public init(repo: String, brand: Brand? = nil, profile: Profile? = nil) {
        self.init(repo: repo, brand: brand, profile: profile) { EmptyView() }
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

/// The avatar colours (the same six as Android), chosen from the key.
enum AvatarPalette {
    static let colors: [Color] = [
        Color(red: 0x00 / 255, green: 0x65 / 255, blue: 0x8C / 255), Color(red: 0x00 / 255, green: 0x6B / 255, blue: 0x5C / 255),
        Color(red: 0x66 / 255, green: 0x55 / 255, blue: 0x90 / 255), Color(red: 0x8C / 255, green: 0x4A / 255, blue: 0x00 / 255),
        Color(red: 0x3F / 255, green: 0x6B / 255, blue: 0x00 / 255), Color(red: 0x7A / 255, green: 0x3E / 255, blue: 0x6B / 255),
    ]
    static func index(_ key: String) -> Int { abs(key.unicodeScalars.reduce(0) { ($0 &* 31) &+ Int($1.value) }) % colors.count }
}
