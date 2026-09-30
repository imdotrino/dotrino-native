import Foundation

/// EL AVATAR del perfil: el identicon de `@dotrino/identity/avatar`, el mismo dibujo que enseña la
/// web para la misma semilla (normalmente la llave del perfil). Es determinista: si cada app
/// llevara su copia, el mismo usuario vería un avatar distinto según dónde lo mire — por eso vive
/// aquí, una vez, y se comprueba contra lo que dibuja la web. Igual que `DotrinoAvatar.kt`.
///
/// Esto es solo el patrón (rejilla 5×5 simétrica y el tono); lo pinta `DotrinoAvatarView`.
public enum DotrinoAvatar {
    public struct Pattern: Equatable {
        /// 0..359
        public let hue: Int
        /// `cells[col][row]`
        public let cells: [[Bool]]
    }

    /// FNV-1a + xorshift sobre las unidades UTF-16, con la aritmética de 32 bits de JS.
    public static func pattern(_ seed: String?) -> Pattern {
        let s = (seed?.isEmpty == false ? seed! : "dotrino")
        var h: UInt32 = 2166136261
        for c in s.utf16 { h ^= UInt32(c); h = h &* 16777619 }
        var x = h ^ 0x9e3779b9
        var bytes = [UInt32](repeating: 0, count: 16)
        for i in 0..<16 { x ^= x << 13; x ^= x >> 17; x ^= x << 5; bytes[i] = x & 0xff }
        var cells = [[Bool]](repeating: [Bool](repeating: false, count: 5), count: 5)
        for col in 0..<3 { for row in 0..<5 where bytes[col * 5 + row] & 1 == 1 {
            if col == 2 { cells[2][row] = true } else { cells[col][row] = true; cells[4 - col][row] = true }
        } }
        return Pattern(hue: Int(h % 360), cells: cells)
    }

    /// `hsl()` de CSS a RGB (0…1).
    public static func hsl(_ h: Int, _ s: Double, _ l: Double) -> (r: Double, g: Double, b: Double) {
        let c = (1 - abs(2 * l - 1)) * s
        let hp = Double(h) / 60
        let x = c * (1 - abs(hp.truncatingRemainder(dividingBy: 2) - 1))
        let (r, g, b): (Double, Double, Double)
        switch hp {
        case ..<1: (r, g, b) = (c, x, 0)
        case ..<2: (r, g, b) = (x, c, 0)
        case ..<3: (r, g, b) = (0, c, x)
        case ..<4: (r, g, b) = (0, x, c)
        case ..<5: (r, g, b) = (x, 0, c)
        default: (r, g, b) = (c, 0, x)
        }
        let m = l - c / 2
        return (r + m, g + m, b + m)
    }
}
