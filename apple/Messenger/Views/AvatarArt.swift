import SwiftUI

/// One shape of an avatar drawing in `AvatarDrawing`, as scripts/artwork.py
/// writes it: path data in a 100-unit square, a fill, and an outline.
struct AvatarShape {
    let data: String
    let fill: UInt32?
    let stroke: UInt32?
    let width: CGFloat

    init(_ data: String, fill: UInt32?, stroke: UInt32?, width: CGFloat) {
        self.data = data
        self.fill = fill
        self.stroke = stroke
        self.width = width
    }
}

extension AvatarDrawing {
    /// The drawing someone's profile names ("fox"), if it's one we have.
    init?(named name: String?) {
        guard let name, !name.isEmpty, let drawing = AvatarDrawing(rawValue: name) else { return nil }
        self = drawing
    }
}

/// An avatar drawing on a circle of `background`, cropped round: the drawings
/// run off the edge on purpose.
struct AvatarArt: View {
    let drawing: AvatarDrawing
    let background: Color

    var body: some View {
        let shapes = AvatarCache.shapes(for: drawing)
        Canvas { context, size in
            let side = min(size.width, size.height)
            let origin = CGPoint(x: (size.width - side) / 2, y: (size.height - side) / 2)
            let circle = Path(ellipseIn: CGRect(origin: origin, size: CGSize(width: side, height: side)))
            context.clip(to: circle)
            context.fill(circle, with: .color(background))
            let scale = side / 100
            let transform = CGAffineTransform(translationX: origin.x, y: origin.y).scaledBy(x: scale, y: scale)
            for (shape, path) in shapes {
                let drawn = path.applying(transform)
                if let fill = shape.fill {
                    context.fill(drawn, with: .color(Color(rgb: fill)))
                }
                if let stroke = shape.stroke, shape.width > 0 {
                    context.stroke(
                        drawn,
                        with: .color(Color(rgb: stroke)),
                        style: StrokeStyle(lineWidth: shape.width * scale, lineCap: .round, lineJoin: .round)
                    )
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// Parsed paths, so a list of chats doesn't parse the same drawing per row.
enum AvatarCache {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var parsed: [AvatarDrawing: [(AvatarShape, Path)]] = [:]

    static func shapes(for drawing: AvatarDrawing) -> [(AvatarShape, Path)] {
        lock.lock()
        defer { lock.unlock() }
        if let shapes = parsed[drawing] { return shapes }
        let shapes = drawing.shapes.map { ($0, artworkPath($0.data)) }
        parsed[drawing] = shapes
        return shapes
    }
}

extension Color {
    /// An sRGB colour from 0xRRGGBB.
    init(rgb: UInt32) {
        self.init(
            .sRGB,
            red: Double(rgb >> 16 & 0xFF) / 255,
            green: Double(rgb >> 8 & 0xFF) / 255,
            blue: Double(rgb & 0xFF) / 255
        )
    }
}
