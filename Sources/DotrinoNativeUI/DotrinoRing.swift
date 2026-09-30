import AudioToolbox
import Foundation

/// EL TRINO: el sonido de los avisos de Dotrino, uno de siete al azar. El mismo en Android
/// (`DotrinoRing.kt`).
///
/// Con la app cerrada lo toca iOS: el proxio pone `dotrino-ring-<n>.caf` en el aviso y el
/// archivo está en `Library/Sounds` (`DotrinoPush.installRings`). Con la app ABIERTA no llega
/// ningún aviso de Apple —el pedido o el mensaje entra directo por la conexión—, así que lo
/// toca la app con [play]. Respeta el interruptor de silencio, como cualquier aviso.
public enum DotrinoRing {
    /// El nombre de un trino al azar, para un aviso local (`UNNotificationSound(named:)`).
    public static func randomName() -> String { "dotrino-ring-\(Int.random(in: 1...DotrinoPush.ringCount)).caf" }

    private static let lock = NSLock()
    private static var ids: [Int: SystemSoundID] = [:]

    /// Toca un trino al azar ahora.
    public static func play() {
        let n = Int.random(in: 1...DotrinoPush.ringCount)
        let id: SystemSoundID? = lock.withLock {
            if let id = ids[n] { return id }
            guard let url = Bundle.module.url(forResource: "dotrino-ring-\(n)", withExtension: "caf")
                ?? Bundle.module.url(forResource: "dotrino-ring-\(n)", withExtension: "caf", subdirectory: "Sounds") else { return nil }
            var sid: SystemSoundID = 0
            guard AudioServicesCreateSystemSoundID(url as CFURL, &sid) == kAudioServicesNoError else { return nil }
            ids[n] = sid
            return sid
        }
        if let id { AudioServicesPlaySystemSound(id) }
    }
}
