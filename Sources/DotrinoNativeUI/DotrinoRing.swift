import AudioToolbox
import Foundation

/// EL TRINO: el sonido de TODOS los avisos de Dotrino, uno solo (dueño, 2026-09-30). El mismo en Android
/// (`DotrinoRing.kt`).
///
/// Con la app cerrada lo toca iOS: el proxio pone `dotrino-ring.caf` en el aviso y el
/// archivo está en `Library/Sounds` (`DotrinoPush.installRings`). Con la app ABIERTA no llega
/// ningún aviso de Apple —el pedido o el mensaje entra directo por la conexión—, así que lo
/// toca la app con [play]. Respeta el interruptor de silencio, como cualquier aviso.
public enum DotrinoRing {
    /// El nombre del trino, para un aviso local (`UNNotificationSound(named:)`).
    public static var soundName: String { DotrinoPush.ringName }

    private static let lock = NSLock()
    private static var id: SystemSoundID?

    /// Toca el trino ahora.
    public static func play() {
        let sid: SystemSoundID? = lock.withLock {
            if let id { return id }
            guard let url = Bundle.module.url(forResource: "dotrino-ring", withExtension: "caf")
                ?? Bundle.module.url(forResource: "dotrino-ring", withExtension: "caf", subdirectory: "Sounds") else { return nil }
            var s: SystemSoundID = 0
            guard AudioServicesCreateSystemSoundID(url as CFURL, &s) == kAudioServicesNoError else { return nil }
            id = s
            return s
        }
        if let sid { AudioServicesPlaySystemSound(sid) }
    }
}
