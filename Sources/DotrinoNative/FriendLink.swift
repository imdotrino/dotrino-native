import Foundation

/// A quién pide añadir un enlace `#to=<llave>&nick=…&card=…` (el del correo de feedback, para que
/// Dotrino conteste, y el de la tarjeta de contacto): su llave, el apodo, y su tarjeta si vino.
/// Quien la recibe comprueba la tarjeta (`Friends.request`).
public struct FriendLink: Identifiable, Sendable {
    public let pubkey: String, nickname: String, card: JSON?
    public var id: String { pubkey }
    public init(pubkey: String, nickname: String, card: JSON? = nil) { self.pubkey = pubkey; self.nickname = nickname; self.card = card }

    /// Lo que dice el `#fragment` de un enlace, o nil si no es uno de estos.
    public static func from(_ fragment: String?) -> FriendLink? {
        guard let f = fragment else { return nil }
        func param(_ k: String) -> String? {
            f.split(separator: "&").first { $0.hasPrefix("\(k)=") }.map { String($0.dropFirst(k.count + 1)) }
        }
        func b64(_ s: String) -> Data? {
            var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            while t.count % 4 != 0 { t += "=" }
            return Data(base64Encoded: t)
        }
        guard let raw = param("to").flatMap(b64), let pubkey = String(data: raw, encoding: .utf8), (try? JSON.parse(pubkey))?.object != nil else { return nil }
        let nick = String((param("nick")?.removingPercentEncoding ?? "").prefix(40))
        let card = param("card").flatMap(b64).flatMap { String(data: $0, encoding: .utf8) }.flatMap { try? JSON.parse($0) }.flatMap { $0.object != nil ? $0 : nil }
        return FriendLink(pubkey: pubkey, nickname: nick, card: card)
    }
}
