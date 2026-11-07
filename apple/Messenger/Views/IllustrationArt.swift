import SwiftUI

/// One shape of a scene in `Illustration`, as scripts/artwork.py writes it:
/// path data in the drawing's own units, a fill, an outline and its dashes.
struct IllustrationShape {
    let data: String
    let fill: UInt32?
    let stroke: UInt32?
    let width: CGFloat
    let dash: [CGFloat]
    /// The circle behind the scene, painted in the pastel of the ink.
    let backdrop: Bool

    init(_ data: String, fill: UInt32?, stroke: UInt32?, width: CGFloat, dash: [CGFloat] = [], backdrop: Bool = false) {
        self.data = data
        self.fill = fill
        self.stroke = stroke
        self.width = width
        self.dash = dash
        self.backdrop = backdrop
    }
}

/// A scene from `Illustration` in its own colours, scaled to fit, on a
/// backdrop in the pastel of the ink. Like the avatars, it's the same in dark
/// mode.
struct IllustrationArt: View {
    let illustration: Illustration
    @Environment(Appearance.self) private var appearance: Appearance?

    var body: some View {
        let shapes = IllustrationCache.shapes(for: illustration)
        let art = illustration.size
        let backdrop = appearance?.ink.pastel
        Canvas { context, size in
            let scale = min(size.width / art.width, size.height / art.height)
            let transform = CGAffineTransform(
                translationX: (size.width - art.width * scale) / 2,
                y: (size.height - art.height * scale) / 2
            ).scaledBy(x: scale, y: scale)
            for (shape, path) in shapes {
                let drawn = path.applying(transform)
                if shape.backdrop, let backdrop {
                    context.fill(drawn, with: .color(backdrop))
                } else if let fill = shape.fill {
                    context.fill(drawn, with: .color(Color(rgb: fill)))
                }
                if let stroke = shape.stroke, shape.width > 0 {
                    context.stroke(
                        drawn,
                        with: .color(Color(rgb: stroke)),
                        style: StrokeStyle(
                            lineWidth: shape.width * scale,
                            lineCap: .round,
                            lineJoin: .round,
                            dash: shape.dash.map { $0 * scale }
                        )
                    )
                }
            }
        }
        .aspectRatio(art, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// Parsed paths, so a scene isn't parsed again each time it's drawn.
enum IllustrationCache {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var parsed: [Illustration: [(IllustrationShape, Path)]] = [:]

    static func shapes(for illustration: Illustration) -> [(IllustrationShape, Path)] {
        lock.lock()
        defer { lock.unlock() }
        if let shapes = parsed[illustration] { return shapes }
        let shapes = illustration.shapes.map { ($0, artworkPath($0.data)) }
        parsed[illustration] = shapes
        return shapes
    }
}
