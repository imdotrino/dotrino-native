import Foundation

/// DÓNDE guarda esta librería: por defecto, solo para esta app. Las apps de Dotrino llaman a
/// `share(…)` al arrancar, ANTES de tocar cualquier llave o almacén, y entonces todas las del
/// equipo ven las mismas llaves (Keychain Access Group) y los mismos archivos (App Group): un
/// teléfono es UN aparato del acta, se empareje desde la app que se empareje (docs/DISENO.md §2.1).
///
/// Si los grupos no están en los entitlements de la app, se PARA con su error: seguir con el
/// almacén propio de la app haría un aparato aparte sin que nadie se enterara.
public enum SharedStorage {
    /// `P7G853375S.com.dotrino.shared` en las apps de Dotrino; nil = solo esta app.
    public private(set) static var keychainAccessGroup: String?
    /// La carpeta de los archivos sellados.
    public private(set) static var directory: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }()

    public static func share(keychainAccessGroup group: String, appGroup: String) throws {
        guard let base = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) else {
            throw CryptoError("app group \(appGroup) is not in this app's entitlements")
        }
        let dir = base.appendingPathComponent("Library/Application Support", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        keychainAccessGroup = group
        directory = dir
    }
}
