import SwiftUI

/// One line of a drawing in `Artwork`, as scripts/artwork.py writes it:
/// absolute M, L, C and Z commands in the drawing's own units.
struct ArtworkStroke {
    enum Kind {
        /// A pen line.
        case stroke
        /// Filled with the ink (eyes, dots), and stroked if `width` > 0.
        case fill
        /// Filled with the paper colour to hide what's behind, then stroked.
        case knockout
    }

    let kind: Kind
    let data: String
    let width: CGFloat
    let dash: [CGFloat]

    init(_ kind: Kind, _ data: String, width: CGFloat, dash: [CGFloat]) {
        self.kind = kind
        self.data = data
        self.width = width
        self.dash = dash
    }

    var path: Path {
        var path = Path()
        var command: Character = "M"
        var numbers: [CGFloat] = []
        for token in data.split(separator: " ") {
            var text = Substring(token)
            if let first = text.first, first.isLetter {
                command = first
                text = text.dropFirst()
                if command == "Z" {
                    path.closeSubpath()
                    numbers.removeAll()
                    continue
                }
            }
            guard !text.isEmpty, let value = Double(text) else { continue }
            numbers.append(CGFloat(value))
            switch (command, numbers.count) {
            case ("M", 2):
                path.move(to: CGPoint(x: numbers[0], y: numbers[1]))
                numbers.removeAll()
                command = "L" // further pairs are lines, as in SVG
            case ("L", 2):
                path.addLine(to: CGPoint(x: numbers[0], y: numbers[1]))
                numbers.removeAll()
            case ("C", 6):
                path.addCurve(
                    to: CGPoint(x: numbers[4], y: numbers[5]),
                    control1: CGPoint(x: numbers[0], y: numbers[1]),
                    control2: CGPoint(x: numbers[2], y: numbers[3])
                )
                numbers.removeAll()
            default:
                break
            }
        }
        return path
    }
}

/// A drawing from `Artwork` in a single pen line, drawn in the foreground
/// style (the ink), scaled to fit.
struct LineArt: View {
    let artwork: Artwork
    /// Paints knocked-out shapes, so lines behind them disappear. Match the
    /// colour behind the drawing.
    var paper: Color = .messengerBackground

    var body: some View {
        let strokes = ArtworkCache.strokes(for: artwork)
        let art = artwork.size
        Canvas { context, size in
            let scale = min(size.width / art.width, size.height / art.height)
            let transform = CGAffineTransform(
                translationX: (size.width - art.width * scale) / 2,
                y: (size.height - art.height * scale) / 2
            ).scaledBy(x: scale, y: scale)
            for (stroke, path) in strokes {
                let shape = path.applying(transform)
                let style = StrokeStyle(
                    lineWidth: stroke.width * scale,
                    lineCap: .round,
                    lineJoin: .round,
                    dash: stroke.dash.map { $0 * scale }
                )
                switch stroke.kind {
                case .stroke:
                    context.stroke(shape, with: .foreground, style: style)
                case .fill:
                    context.fill(shape, with: .foreground)
                    if stroke.width > 0 { context.stroke(shape, with: .foreground, style: style) }
                case .knockout:
                    context.fill(shape, with: .color(paper))
                    if stroke.width > 0 { context.stroke(shape, with: .foreground, style: style) }
                }
            }
        }
        .aspectRatio(art, contentMode: .fit)
        .accessibilityHidden(true)
    }
}

/// Parsed paths, so lists of avatars don't parse the same drawing per row.
enum ArtworkCache {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var parsed: [Artwork: [(ArtworkStroke, Path)]] = [:]

    static func strokes(for artwork: Artwork) -> [(ArtworkStroke, Path)] {
        lock.lock()
        defer { lock.unlock() }
        if let strokes = parsed[artwork] { return strokes }
        let strokes = artwork.strokes.map { ($0, $0.path) }
        parsed[artwork] = strokes
        return strokes
    }
}

extension Color {
    /// Behind everything: the window's own background.
    static var messengerBackground: Color {
        #if os(iOS)
        Color(uiColor: .systemBackground)
        #else
        Color(nsColor: .windowBackgroundColor)
        #endif
    }
}
