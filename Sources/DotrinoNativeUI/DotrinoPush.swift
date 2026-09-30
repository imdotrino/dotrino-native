import DotrinoNative
import Foundation
import UIKit
import UserNotifications

/// EL TIMBRE en iOS: pide permiso, registra el teléfono en APNs y convierte lo que Apple devuelve
/// en el `PushToken` que `SealedSession.setPushToken` sube al proxio (firmado por el perfil).
///
/// Lo que llega es una alerta SIN contenido: el proxio manda solo las claves
/// `DOTRINO_RING_TITLE` / `DOTRINO_RING_BODY`, y el texto lo pone el teléfono desde el
/// `Localizable.strings` de la app. Cada app que use el timbre declara esas dos claves (es/en)
/// y el entitlement `aps-environment`.
///
/// El token lo entrega el `UIApplicationDelegate` de la app, así que el cableado es:
///
///     // en el delegate (UIApplicationDelegateAdaptor en SwiftUI)
///     func application(_ a: UIApplication, didRegisterForRemoteNotificationsWithDeviceToken t: Data) {
///         session.setPushToken(DotrinoPush.token(t))
///     }
///     // al arrancar, cuando ya hay perfil
///     DotrinoPush.register()
public enum DotrinoPush {
    /// Pide permiso para avisar y, si lo dan, registra el teléfono en APNs. El token llega
    /// después al delegate de la app. Sin permiso no se registra: no habría nada que enseñar.
    @MainActor
    public static func register() {
        installRings()
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            guard granted else { return }
            DispatchQueue.main.async { UIApplication.shared.registerForRemoteNotifications() }
        }
    }

    /// EL TRINO, uno solo para todos los avisos: el proxio pone este nombre en cada aviso.
    /// Si cambia, cambia también `RING` en `dotrino-proxy/apns.js`.
    public static let ringName = "dotrino-ring.caf"

    /// EL TONO: iOS solo suena un sonido propio si está en el paquete de la app o en su
    /// `Library/Sounds`. El trino vive en esta librería, así que se copia ahí (y otra vez si
    /// cambia) y ninguna app lo tiene que traer. Los siete trinos de antes se borran.
    static func installRings(fm: FileManager = .default) {
        guard let lib = fm.urls(for: .libraryDirectory, in: .userDomainMask).first else { return }
        let dir = lib.appendingPathComponent("Sounds", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        for n in 1...7 { try? fm.removeItem(at: dir.appendingPathComponent("dotrino-ring-\(n).caf")) }
        let dest = dir.appendingPathComponent(ringName)
        guard let src = Bundle.module.url(forResource: "dotrino-ring", withExtension: "caf")
            ?? Bundle.module.url(forResource: "dotrino-ring", withExtension: "caf", subdirectory: "Sounds"),
              let data = try? Data(contentsOf: src) else { return }
        if (try? Data(contentsOf: dest)) == data { return }
        try? data.write(to: dest, options: .atomic)
    }

    /// Lo que Apple devolvió, listo para `SealedSession.setPushToken`.
    public static func token(_ deviceToken: Data) -> SealedSession.PushToken {
        SealedSession.PushToken(
            token: deviceToken.map { String(format: "%02x", $0) }.joined(),
            topic: Bundle.main.bundleIdentifier ?? "",
            env: environment()
        )
    }

    /// Dónde habla esta build con APNs. Lo dice el perfil de aprovisionamiento con el que se
    /// firmó (`aps-environment`): `development` es sandbox (lo instalado desde Xcode). Una
    /// build de TestFlight o de la App Store no lleva `embedded.mobileprovision`, y va a
    /// producción.
    public static func environment(bundle: Bundle = .main) -> String {
        guard let url = bundle.url(forResource: "embedded", withExtension: "mobileprovision"),
              let data = try? Data(contentsOf: url) else { return "production" }
        return environment(provisioning: String(decoding: data, as: UTF8.self))
    }

    /// El mismo cálculo sobre el texto del perfil (el plist va en claro dentro del CMS).
    static func environment(provisioning text: String) -> String {
        guard let r = text.range(of: #"<key>aps-environment</key>\s*<string>([a-z]+)</string>"#, options: .regularExpression) else {
            return "production"
        }
        return text[r].contains("development") ? "sandbox" : "production"
    }
}
