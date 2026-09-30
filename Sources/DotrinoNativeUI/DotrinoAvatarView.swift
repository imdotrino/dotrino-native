import DotrinoNative
import SwiftUI
import UIKit

/// El avatar del perfil, redondo: la FOTO que subió la persona (`me.avatar`) o, sin foto, el
/// identicon de `seed` — el mismo que dibuja `avatarSvg` en la web (moneda con degradado y la
/// rejilla 5×5 al 76 %, con un margen del 12 %). Igual que `DotrinoAvatarView.kt`.
public struct DotrinoAvatarView: View {
    private let pattern: DotrinoAvatar.Pattern
    private let photo: UIImage?

    public init(seed: String?, photo: String? = nil) {
        pattern = DotrinoAvatar.pattern(seed)
        self.photo = photo.flatMap(Self.decode)
    }

    /// Un data-URI de imagen en base64; un SVG o algo que no se puede leer deja el identicon.
    static func decode(_ uri: String) -> UIImage? {
        guard uri.hasPrefix("data:image/"), let r = uri.range(of: ";base64,"),
              let data = Data(base64Encoded: String(uri[r.upperBound...]), options: .ignoreUnknownCharacters) else { return nil }
        return UIImage(data: data)
    }

    private func color(_ h: Int, _ s: Double, _ l: Double) -> Color {
        let c = DotrinoAvatar.hsl(h, s, l)
        return Color(red: c.r, green: c.g, blue: c.b)
    }

    public var body: some View {
        if let photo {
            Image(uiImage: photo).resizable().scaledToFill().clipShape(Circle())
        } else {
            Canvas { ctx, size in
                let side = min(size.width, size.height)
                let h = pattern.hue
                ctx.fill(Path(ellipseIn: CGRect(x: 0, y: 0, width: side, height: side)),
                         with: .linearGradient(Gradient(colors: [color(h, 0.48, 0.95), color((h + 40) % 360, 0.48, 0.90)]),
                                               startPoint: .zero, endPoint: CGPoint(x: side, y: side)))
                let unit = side * 0.76 / 5, off = side * 0.12
                var cells = Path()
                for col in 0..<5 { for row in 0..<5 where pattern.cells[col][row] {
                    cells.addRect(CGRect(x: off + CGFloat(col) * unit, y: off + CGFloat(row) * unit, width: unit, height: unit))
                } }
                ctx.fill(cells, with: .color(color(h, 0.62, 0.46)))
            }
        }
    }
}
