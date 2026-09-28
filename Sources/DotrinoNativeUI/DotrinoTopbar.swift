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
    @State private var support = false
    @Environment(\.openURL) private var openURL

    /// [repo]: the GitHub repo where «Report a bug» goes. [brand]: nil = «Dotrino».
    public init(repo: String, brand: Brand? = nil, @ViewBuilder actions: () -> Actions) {
        self.repo = repo
        self.brand = brand
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
            Button { support = true } label: {
                Image("DotrinoCoin", bundle: .module).resizable().frame(width: 34, height: 34)
            }
            .accessibilityLabel(lang.text("dotrino_support_cta", in: .module))
            .accessibilityIdentifier("support-coin")
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(DotrinoPalette.card)
        .sheet(isPresented: $support) { SupportSheet(repo: repo, onClose: { support = false }) }
    }
}

extension DotrinoTopbar where Actions == EmptyView {
    public init(repo: String, brand: Brand? = nil) {
        self.init(repo: repo, brand: brand) { EmptyView() }
    }
}

/// The ecosystem's dark palette in native (the same as `dotrino_colors.xml`).
public enum DotrinoPalette {
    public static let bg = Color(red: 0x0B / 255, green: 0x12 / 255, blue: 0x20 / 255)
    public static let card = Color(red: 0x13 / 255, green: 0x1D / 255, blue: 0x31 / 255)
    public static let fg = Color(red: 0xDB / 255, green: 0xE7 / 255, blue: 0xF7 / 255)
    public static let muted = Color(red: 0x8A / 255, green: 0x9B / 255, blue: 0xB5 / 255)
    public static let accent = Color(red: 0x4F / 255, green: 0x8C / 255, blue: 0xFF / 255)
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
