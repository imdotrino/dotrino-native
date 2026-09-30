import DotrinoNative
import SwiftUI
import WebKit

/// A PAGE OF THE ECOSYSTEM INSIDE THE APP, with the phone's identity: open your profile, create
/// one, adopt one, sign in. They are web (profile.dotrino.com / vault.dotrino.com, the same for
/// every app), so a WebView is right here (CONVENCIONES §16.2: WebView only for content that IS
/// web). [IdentityWebBridge] makes it the SAME identity and not another one. Same as
/// `DotrinoWebActivity.kt`.
public struct DotrinoWebSheet: View {
    let url: URL
    let onClose: () -> Void
    @ObservedObject private var lang = DotrinoLang.shared

    public init(url: URL, onClose: @escaping () -> Void) { self.url = url; self.onClose = onClose }

    public var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { onClose() } label: { Image(systemName: "xmark").font(.system(size: 18, weight: .semibold)) }
                    .foregroundColor(DotrinoPalette.fg).frame(width: 44, height: 44)
                    .accessibilityLabel(lang.text("dotrino_support_close", in: .module))
                    .accessibilityIdentifier("web-close")
                Text(lang.text("dotrino_profile_cta", in: .module)).font(.headline).foregroundColor(DotrinoPalette.fg)
                Spacer()
            }
            .padding(.horizontal, 8).frame(height: 52).background(DotrinoPalette.card)
            IdentityWebView(url: url)
        }
        .background(DotrinoPalette.bg.ignoresSafeArea())
    }
}

/// The WKWebView with the identity bridge; outside `*.dotrino.com` goes to the system browser.
struct IdentityWebView: UIViewRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> WKWebView {
        let cfg = WKWebViewConfiguration()
        cfg.websiteDataStore = .default()
        cfg.allowsInlineMediaPlayback = true
        IdentityWebBridge.install(cfg)
        let w = WKWebView(frame: .zero, configuration: cfg)
        w.navigationDelegate = context.coordinator
        w.uiDelegate = context.coordinator
        w.load(URLRequest(url: url))
        return w
    }

    func updateUIView(_ w: WKWebView, context: Context) {}

    final class Coordinator: NSObject, WKNavigationDelegate, WKUIDelegate {
        static func inside(_ u: URL?) -> Bool {
            guard let h = u?.host else { return false }
            return h == "dotrino.com" || h.hasSuffix(".dotrino.com")
        }
        func webView(_ w: WKWebView, decidePolicyFor a: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let u = a.request.url else { decisionHandler(.cancel); return }
            if u.scheme == "about" || u.scheme == "blob" || u.scheme == "data" || Self.inside(u) { decisionHandler(.allow); return }
            decisionHandler(.cancel)
            UIApplication.shared.open(u)
        }
        /// The camera (scanning a pairing QR), only for the ecosystem's pages.
        func webView(_ w: WKWebView, requestMediaCapturePermissionFor origin: WKSecurityOrigin, initiatedByFrame frame: WKFrameInfo,
                     type: WKMediaCaptureType, decisionHandler: @escaping (WKPermissionDecision) -> Void) {
            decisionHandler(origin.host == "dotrino.com" || origin.host.hasSuffix(".dotrino.com") ? .grant : .deny)
        }
    }
}
