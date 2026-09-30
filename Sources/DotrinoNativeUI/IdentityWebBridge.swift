import DotrinoNative
import Foundation
import WebKit

/// `window.DotrinoIdentityKeys` — the identity of a WebView (the `id.dotrino.com` iframe) keeps its
/// keys in THIS phone's Secure Enclave and its store in the team's shared store: one profile for
/// every page and every Dotrino app of the phone. ONE piece for every app (the Dotrino app and any
/// native app that opens a profile page, CONVENCIONES §16.2); the JS side is
/// `dotrino-identity/vault/externalKeys.js` + `nativeStore.js`. Same as `IdentityWebBridge.kt`.
///
/// ONLY `https://id.dotrino.com` gets an answer, checked on each frame's security origin (WebKit
/// shows the handler to every frame). The private halves never leave the enclave.
public final class IdentityWebBridge: NSObject, WKScriptMessageHandlerWithReply {
    static let name = "dotrinoIdentityKeys"
    static let origin = (proto: "https", host: "id.dotrino.com")

    /// What the app does after a device is saved (the Dotrino app registers its push token).
    public static var onSaved: ((Account) -> Void)?

    struct BridgeError: Error, CustomStringConvertible { let description: String; let code: String }

    /// Injected at document start in every frame; it only does something in the identity's.
    static let shim = """
    (function () {
      if (location.origin !== 'https://id.dotrino.com') return
      var h = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.\(name)
      if (!h) return
      var port = {
        storage: true,
        onmessage: null,
        postMessage: function (s) {
          h.postMessage(s).then(function (r) {
            if (port.onmessage) port.onmessage({ data: r })
          }, function (e) {
            var id = null
            try { id = JSON.parse(s).id } catch (_) {}
            if (port.onmessage) port.onmessage({ data: JSON.stringify({ id: id, error: String((e && e.message) || e), code: 'native-error' }) })
          })
        }
      }
      Object.defineProperty(window, 'DotrinoIdentityKeys', { value: port })
    })();
    """

    public static func install(_ cfg: WKWebViewConfiguration) {
        cfg.userContentController.addUserScript(WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        cfg.userContentController.addScriptMessageHandler(IdentityWebBridge(), contentWorld: .page, name: name)
    }

    public func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage,
                                      replyHandler: @escaping (Any?, String?) -> Void) {
        let o = message.frameInfo.securityOrigin
        // A wrong origin gets nothing but a bare rejection: no key, no result, no hint of what exists.
        guard o.protocol == Self.origin.proto, o.host == Self.origin.host, o.port == 0 else { replyHandler(nil, "forbidden"); return }
        guard let text = message.body as? String, let req = try? JSON.parse(text), let id = req["id"]?.string else {
            replyHandler(nil, "bad request"); return
        }
        let method = req["method"]?.string
        let params = req["params"] ?? [:]
        Task.detached {
            let out: JSON
            do { out = ["id": .string(id), "result": try Self.call(method, params)] }
            catch {
                out = ["id": .string(id), "error": .string(String(describing: error)),
                       "code": .string((error as? BridgeError)?.code ?? "native-error")]
            }
            await MainActor.run { replyHandler(out.text, nil) }
        }
    }

    private static func keys(_ kid: String) throws -> EnclaveKeys {
        guard EnclaveKeys.exists(kid) else { throw BridgeError(description: "that key is not on this phone", code: "native-key-gone") }
        return try EnclaveKeys.open(kid)
    }

    private static func str(_ p: JSON, _ k: String) throws -> String {
        guard let v = p[k]?.string else { throw BridgeError(description: "missing \(k)", code: "native-bad-request") }
        return v
    }

    static func call(_ method: String?, _ p: JSON) throws -> JSON {
        switch method {
        case "create":
            let kid = UUID().uuidString.lowercased()
            let k = try EnclaveKeys.create(kid)
            return ["kid": .string(kid), "publickey": .string(k.publickey), "encPub": .string(k.encPub)]
        case "open":
            let kid = try str(p, "kid")
            let k = try keys(kid)
            return ["kid": .string(kid), "publickey": .string(k.publickey), "encPub": .string(k.encPub)]
        case "sign":
            return ["signature": .string(try keys(str(p, "kid")).signBytes(Crypto.fromB64(str(p, "data"))))]
        case "deriveBits":
            let bits = try keys(str(p, "kid")).agree(Crypto.agreementKey(jwk: str(p, "peer")))
            return ["bits": .string(Crypto.b64(bits))]
        case "save":
            // After pairing: the account, with the SAME paper the identity got.
            let kid = try str(p, "kid")
            let k = try keys(kid)
            guard let cert = p["cert"], cert.object != nil else { throw BridgeError(description: "save: missing cert", code: "native-bad-request") }
            let vault = try str(p, "vault")
            if let why = Delegation.check(cert, vault: vault, sub: k.publickey, expectedScope: nil) {
                throw BridgeError(description: "the paper does not check out: \(why)", code: "bad-paper")
            }
            let name = p["name"]?.string ?? ""
            let account = Account(id: kid, name: name.isEmpty ? try Delegation.keyLabel(vault) : name,
                                  profileId: p["profileId"]?.string, vault: vault, proxy: try str(p, "proxy"),
                                  cert: cert, deviceId: try Delegation.keyLabel(k.publickey))
            try AccountStore.shared.save(account)
            onSaved?(account)
            return ["deviceId": .string(account.deviceId)]
        // The identity's store: ONE for every page and every app of the phone.
        case "storeLoad":
            return ["items": .object(try IdentityStore.shared.all().mapValues { .string($0) })]
        case "storeSet":
            guard let v = p["v"]?.string else { throw BridgeError(description: "missing v", code: "native-bad-request") }
            try IdentityStore.shared.set(str(p, "k"), v)
            return ["ok": true]
        case "storeRemove":
            try IdentityStore.shared.remove(str(p, "k"))
            return ["ok": true]
        case "remove":
            // The identity removed that profile: its key and its native account go with it.
            try AccountStore.shared.remove(str(p, "kid"))
            return ["ok": true]
        default:
            throw BridgeError(description: "unknown method: \(method ?? "nil")", code: "native-bad-request")
        }
    }
}
