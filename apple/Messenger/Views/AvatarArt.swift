import ImageIO
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
    /// Cropped to a rounded square, a stamp, instead of a circle.
    var stamp = false

    var body: some View {
        let shapes = AvatarCache.shapes(for: drawing)
        let stamp = stamp
        Canvas { context, size in
            let side = min(size.width, size.height)
            let origin = CGPoint(x: (size.width - side) / 2, y: (size.height - side) / 2)
            let square = CGRect(origin: origin, size: CGSize(width: side, height: side))
            let circle = stamp
                ? Path(roundedRect: square, cornerRadius: side / 3, style: .continuous)
                : Path(ellipseIn: square)
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

/// Profile photos, decoded once per file.
@MainActor
enum ProfilePhotoCache {
    private static var images: [String: Image] = [:]

    static func image(at path: String) -> Image? {
        if let hit = images[path] { return hit }
        #if os(iOS)
        guard let ui = UIImage(contentsOfFile: path) else { return nil }
        let image = Image(uiImage: ui)
        #else
        guard let ns = NSImage(contentsOfFile: path) else { return nil }
        let image = Image(nsImage: ns)
        #endif
        images[path] = image
        return image
    }
}

/// Square-crops and shrinks a picture to a profile photo: 512 px, JPEG.
enum ProfilePhotoMaker {
    static func jpeg(from data: Data) -> Data? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        // Downsampled and turned upright by ImageIO.
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 1024,
        ]
        guard let full = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let side = min(full.width, full.height)
        let crop = CGRect(x: (full.width - side) / 2, y: (full.height - side) / 2, width: side, height: side)
        guard let square = full.cropping(to: crop) else { return nil }
        let out = 512
        guard let context = CGContext(data: nil, width: out, height: out, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return nil }
        context.interpolationQuality = .high
        context.draw(square, in: CGRect(x: 0, y: 0, width: out, height: out))
        guard let scaled = context.makeImage() else { return nil }
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, scaled, [kCGImageDestinationLossyCompressionQuality: 0.8] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return data as Data
    }
}

#if os(iOS)
typealias PlatformImage = UIImage
extension Image {
    init(platformImage: UIImage) { self.init(uiImage: platformImage) }
}
#else
typealias PlatformImage = NSImage
extension Image {
    init(platformImage: NSImage) { self.init(nsImage: platformImage) }
}
#endif
