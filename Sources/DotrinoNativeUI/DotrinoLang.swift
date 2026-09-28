import SwiftUI

/// The app's language (ES/EN), the same way in every Dotrino native app: the one chosen in the
/// topbar or, if none was chosen, the system's. Same role as `DotrinoLocale.kt`.
///
/// An app reads its own texts with `DotrinoLang.shared.text("key")` (its `<code>.lproj`) and
/// observes `DotrinoLang.shared` so a change of language repaints the screen.
public final class DotrinoLang: ObservableObject {
    public static let shared = DotrinoLang()
    private static let key = "dotrino.lang"
    @Published public private(set) var code: String

    private init() {
        let saved = UserDefaults.standard.string(forKey: Self.key)
        let sys = (Locale.preferredLanguages.first ?? "es").hasPrefix("en") ? "en" : "es"
        code = saved == "en" || saved == "es" ? saved! : sys
    }

    public func set(_ c: String) {
        guard c == "es" || c == "en", c != code else { return }
        code = c
        UserDefaults.standard.set(c, forKey: Self.key)
    }

    /// A text of [bundle] in the chosen language, with `%1$@`-style arguments. A missing
    /// `.lproj` is a build mistake, and it stops here instead of showing the keys.
    public func text(_ key: String, in bundle: Bundle = .main, _ args: CVarArg...) -> String {
        guard let p = bundle.path(forResource: code, ofType: "lproj"), let b = Bundle(path: p) else {
            preconditionFailure("missing \(code).lproj in \(bundle.bundlePath)")
        }
        let f = b.localizedString(forKey: key, value: nil, table: nil)
        return args.isEmpty ? f : String(format: f, arguments: args)
    }
}
