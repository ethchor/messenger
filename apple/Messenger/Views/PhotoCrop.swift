import SwiftUI
import ImageIO
import UniformTypeIdentifiers

// Profile photos: crop before setting (pinch and drag inside the circle,
// as people will see it), and view one full size.

extension ProfilePhotoMaker {
    /// The picture, upright, at most 2048 px, for cropping.
    static func upright(_ data: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 2048,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    /// A square part of `image` as a profile photo JPEG.
    static func jpeg(from image: CGImage, crop: CGRect) -> Data? {
        guard let square = image.cropping(to: crop.integral) else { return nil }
        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, square, [kCGImageDestinationLossyCompressionQuality: 0.95] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        // Shrunk to 512 px and written fresh, with no metadata.
        return jpeg(from: data as Data)
    }
}

/// The picked picture under a circle: pinch to zoom, drag to place it,
/// then "Use photo". What's in the circle is what others see.
struct PhotoCropView: View {
    let image: CGImage
    let done: (Data) -> Void
    let cancel: () -> Void

    @State private var zoom: CGFloat = 1
    @State private var lastZoom: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var lastOffset: CGSize = .zero

    private var size: CGSize { CGSize(width: image.width, height: image.height) }

    var body: some View {
        GeometryReader { geo in
            let side = min(geo.size.width - 32, 380)
            let base = side / min(size.width, size.height)
            let scale = base * zoom
            ZStack {
                Color.black.ignoresSafeArea()
                Image(decorative: image, scale: 1)
                    .resizable()
                    .frame(width: size.width * scale, height: size.height * scale)
                    .offset(offset)
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)
                // Dims all but the circle.
                Rectangle()
                    .fill(.black.opacity(0.6))
                    .mask {
                        Rectangle()
                            .overlay(Circle().frame(width: side, height: side).position(x: geo.size.width / 2, y: geo.size.height / 2).blendMode(.destinationOut))
                            .compositingGroup()
                    }
                    .allowsHitTesting(false)
                Circle()
                    .strokeBorder(.white.opacity(0.85), lineWidth: 1.5)
                    .frame(width: side, height: side)
                    .position(x: geo.size.width / 2, y: geo.size.height / 2)
                    .allowsHitTesting(false)
                VStack {
                    Text("Move and pinch")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.8))
                        .padding(.top, 24)
                    Spacer()
                    HStack(spacing: 10) {
                        Button(action: cancel) {
                            Text("Cancel")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .overlay(Capsule().strokeBorder(.white.opacity(0.7), lineWidth: 1.5))
                        }
                        .buttonStyle(PressScale())
                        Button {
                            let rect = cropRect(side: side, scale: scale)
                            if let jpeg = ProfilePhotoMaker.jpeg(from: image, crop: rect) { done(jpeg) }
                        } label: {
                            Text("Use photo")
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(Palette.ink)
                                .frame(maxWidth: .infinity, minHeight: 52)
                                .background(Palette.marigold, in: Capsule())
                        }
                        .buttonStyle(PressScale())
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
            }
            .contentShape(Rectangle())
            .gesture(
                SimultaneousGesture(
                    MagnifyGesture()
                        .onChanged { value in
                            zoom = min(4, max(1, lastZoom * value.magnification))
                            offset = clamp(offset, side: side, scale: base * zoom)
                        }
                        .onEnded { _ in lastZoom = zoom; lastOffset = offset },
                    DragGesture()
                        .onChanged { value in
                            let proposed = CGSize(width: lastOffset.width + value.translation.width, height: lastOffset.height + value.translation.height)
                            offset = clamp(proposed, side: side, scale: base * zoom)
                        }
                        .onEnded { _ in lastOffset = offset }
                )
            )
        }
        #if os(macOS)
        .frame(minWidth: 480, minHeight: 560)
        #endif
    }

    /// Keeps the picture covering the circle.
    private func clamp(_ proposed: CGSize, side: CGFloat, scale: CGFloat) -> CGSize {
        let maxX = max(0, (size.width * scale - side) / 2)
        let maxY = max(0, (size.height * scale - side) / 2)
        return CGSize(width: min(maxX, max(-maxX, proposed.width)), height: min(maxY, max(-maxY, proposed.height)))
    }

    /// The circle's square, in the picture's pixels.
    private func cropRect(side: CGFloat, scale: CGFloat) -> CGRect {
        let px = side / scale
        let x = (size.width * scale / 2 - offset.width - side / 2) / scale
        let y = (size.height * scale / 2 - offset.height - side / 2) / scale
        return CGRect(x: max(0, x), y: max(0, y), width: min(px, size.width), height: min(px, size.height))
    }
}

/// Someone's profile photo, full size, on black. Tap or swipe down to close.
struct ProfilePhotoViewer: View {
    let path: String
    let name: String
    @Environment(\.dismiss) private var dismiss
    @State private var drag: CGFloat = 0

    var body: some View {
        ZStack {
            Color.black.opacity(1 - min(0.6, abs(drag) / 400)).ignoresSafeArea()
            if let image = ProfilePhotoCache.image(at: path) {
                image
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
                    .padding(16)
                    .offset(y: drag)
                    .accessibilityLabel("\(name)'s profile photo")
            }
            VStack {
                HStack {
                    Text(name).font(.system(size: 17, weight: .semibold)).foregroundStyle(.white)
                    Spacer()
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 40, height: 40)
                            .background(.white.opacity(0.16), in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Close")
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                Spacer()
            }
        }
        .gesture(
            DragGesture()
                .onChanged { drag = $0.translation.height }
                .onEnded { value in
                    if abs(value.translation.height) > 120 { dismiss() } else { withAnimation(.spring) { drag = 0 } }
                }
        )
        .onTapGesture { dismiss() }
        #if os(macOS)
        .frame(minWidth: 480, minHeight: 520)
        #endif
    }
}
