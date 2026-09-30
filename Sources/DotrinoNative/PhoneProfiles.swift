import Foundation

/// LOS PERFILES DEL TELÉFONO, como los lista la identidad (`listProfiles`): los mismos que ve el
/// menú del topbar en la web. Viven en el almacén de la identidad (`kv:dotrino.identity.profiles`,
/// el activo en `kv:dotrino.identity.current` y el `me` de cada uno), así que una app nativa los
/// lee y cambia de perfil igual que la web: escribiendo cuál es el activo. Igual que `PhoneProfiles.kt`.
public enum PhoneProfiles {
    public static let current = "kv:dotrino.identity.current"
    static let listKey = "kv:dotrino.identity.profiles"

    /// `login`: la dirección con la que se entró con contraseña, o nil. `seed`: de lo que sale su identicon.
    public struct Entry: Equatable, Identifiable {
        public let id: String, name: String?, seed: String, avatar: String?, current: Bool, login: String?
    }

    /// La lista, del almacén de la identidad; vacía si el teléfono todavía no tiene perfil.
    public static func list(_ items: [String: String]) -> [Entry] {
        let cur = items[current]
        guard let arr = items[listKey].flatMap({ try? JSON.parse($0) })?.array else { return [] }
        return arr.compactMap { o in
            guard let id = o["id"]?.string else { return nil }
            let me = items["kv:dotrino.identity.p.\(id).me"].flatMap { try? JSON.parse($0) }
            let name = [me?["nickname"]?.string, o["name"]?.string].compactMap { $0 }.first { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            let avatar = me?["avatar"]?.string.flatMap { $0.hasPrefix("data:image/") ? $0 : nil }
            return Entry(id: id, name: name, seed: o["pubkey"]?.string ?? id, avatar: avatar, current: id == cur, login: o["login"]?["address"]?.string)
        }
    }

    /// Los del teléfono (el almacén compartido del equipo).
    public static func load() throws -> [Entry] { list(try IdentityStore.shared.all()) }
    public static func currentPid() -> String? { (try? IdentityStore.shared.all())?[current] }

    /// CAMBIAR DE PERFIL, como la web (`switchProfile`): solo cambia el activo, y la app arranca de nuevo con él.
    public static func switchTo(_ pid: String) throws { try IdentityStore.shared.set(current, pid) }
}
