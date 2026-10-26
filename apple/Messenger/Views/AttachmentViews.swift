import AVFoundation
import ImageIO
import SwiftUI

/// A file in a message, for fetching it and showing that it's on its way.
struct AttachmentRef: Hashable {
    let conversationId: String
    let messageId: String
    let position: UInt32
}

extension AttachmentView {
    var isVisual: Bool { kind == .photo || kind == .video }

    /// Here to show or play: ours, or theirs once fetched.
    var localPath: String? {
        state == .ready || state == .uploading ? path : nil
    }
}

extension MessageView {
    /// The text, or what was sent when there's none: "Photo", "Voice message"…
    /// (the same words the core uses in the chat list).
    var previewText: String {
        guard text.isEmpty, let first = attachments.first else { return text }
        let n = attachments.count
        guard attachments.allSatisfy({ $0.kind == first.kind }) else { return "\(n) attachments" }
        switch first.kind {
        case .photo: return n == 1 ? "Photo" : "\(n) photos"
        case .video: return n == 1 ? "Video" : "\(n) videos"
        case .voice: return n == 1 ? "Voice message" : "\(n) voice messages"
        case .file: return n == 1 ? (first.fileName.isEmpty ? "File" : first.fileName) : "\(n) files"
        }
    }
}

/// "0:42", or "1:02:03" for the long ones.
func durationText(seconds: TimeInterval) -> String {
    let total = Int(seconds.rounded())
    let (hours, minutes, rest) = (total / 3600, total / 60 % 60, total % 60)
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, rest)
        : String(format: "%d:%02d", minutes, rest)
}

func durationText(milliseconds: UInt32) -> String {
    durationText(seconds: TimeInterval(milliseconds) / 1000)
}

func sizeText(_ bytes: UInt64) -> String {
    ByteCountFormatter.string(fromByteCount: Int64(clamping: bytes), countStyle: .file)
}

// MARK: - Pictures

/// Pictures decoded from files on this device, made only as big as they're
/// shown, and kept a while so scrolling back doesn't decode them again.
enum ImageCache {
    private static let cache: NSCache<NSString, CGImage> = {
        let cache = NSCache<NSString, CGImage>()
        cache.totalCostLimit = 96 * 1024 * 1024
        return cache
    }()

    private static func cacheKey(_ path: String, _ maxPixel: CGFloat) -> NSString {
        "\(path)#\(Int(maxPixel))" as NSString
    }

    static func cached(path: String, maxPixel: CGFloat) -> CGImage? {
        cache.object(forKey: cacheKey(path, maxPixel))
    }

    /// The picture in a photo, or a video's first frame.
    static func image(path: String, video: Bool, maxPixel: CGFloat) async -> CGImage? {
        let key = cacheKey(path, maxPixel)
        if let hit = cache.object(forKey: key) { return hit }
        let url = URL(fileURLWithPath: path)
        let image: CGImage?
        if video {
            image = await MediaPreparation.firstFrame(of: AVURLAsset(url: url), maxSide: maxPixel)
        } else {
            image = await Task.detached(priority: .userInitiated) {
                CGImageSourceCreateWithURL(url as CFURL, nil).flatMap { MediaPreparation.downscaled($0, maxSide: maxPixel) }
            }.value
        }
        if let image {
            cache.setObject(image, forKey: key, cost: image.bytesPerRow * image.height)
        }
        return image
    }

    /// A thumbnail that came inside a message. They're tiny, so this is quick.
    static func thumbnail(_ data: Data, key: String) -> CGImage? {
        let thumbnailKey = "thumbnail#\(key)" as NSString
        if let hit = cache.object(forKey: thumbnailKey) { return hit }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        cache.setObject(image, forKey: thumbnailKey, cost: image.bytesPerRow * image.height)
        return image
    }
}

/// A photo or video frame from a file on this device, filling its frame.
/// Until it's decoded, the message's thumbnail shows, blurred.
struct LocalImage: View {
    let path: String
    var isVideo = false
    let maxPixel: CGFloat
    var placeholder: CGImage?
    @State private var image: CGImage?

    var body: some View {
        Group {
            if let image = image ?? ImageCache.cached(path: path, maxPixel: maxPixel) {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .scaledToFill()
            } else if let placeholder {
                BlurredThumbnail(image: placeholder)
            } else {
                Color.primary.opacity(0.08)
            }
        }
        .task(id: path) {
            image = await ImageCache.image(path: path, video: isVideo, maxPixel: maxPixel)
        }
    }
}

/// A message's tiny thumbnail, blurred so its few pixels don't show.
struct BlurredThumbnail: View {
    let image: CGImage

    var body: some View {
        Image(decorative: image, scale: 1)
            .resizable()
            .scaledToFill()
            .blur(radius: 10, opaque: true)
    }
}

// MARK: - Photos and videos

/// A message's photos and videos: one large; two side by side; three or
/// more in two columns, with "+N" on the fourth when there are more.
struct MediaGrid: View {
    let items: [AttachmentView]
    let conversationId: String
    let messageId: String
    let onOpen: (AttachmentView) -> Void

    #if os(macOS)
    static let width: CGFloat = 280
    #else
    static let width: CGFloat = 240
    #endif
    /// The bubble's column for text and other files; the grid reaches 9 pt
    /// past it on either side, nearly to the bubble's edge.
    static let columnWidth = width - 18
    private let gap: CGFloat = 2

    var body: some View {
        let width = Self.width
        let half = (width - gap) / 2
        Group {
            switch items.count {
            case 0:
                EmptyView()
            case 1:
                tile(0, maxPixel: 1000)
                    .frame(width: width, height: singleHeight(items[0], width: width))
            case 2:
                HStack(spacing: gap) {
                    tile(0).frame(width: half, height: half)
                    tile(1).frame(width: half, height: half)
                }
            case 3:
                VStack(spacing: gap) {
                    HStack(spacing: gap) {
                        tile(0).frame(width: half, height: half)
                        tile(1).frame(width: half, height: half)
                    }
                    tile(2, maxPixel: 1000).frame(width: width, height: half)
                }
            default:
                VStack(spacing: gap) {
                    HStack(spacing: gap) {
                        tile(0).frame(width: half, height: half)
                        tile(1).frame(width: half, height: half)
                    }
                    HStack(spacing: gap) {
                        tile(2).frame(width: half, height: half)
                        tile(3, more: items.count - 4).frame(width: half, height: half)
                    }
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    /// Tall pictures stay taller than wide ones, within reason.
    private func singleHeight(_ item: AttachmentView, width: CGFloat) -> CGFloat {
        guard item.width > 0, item.height > 0 else { return width * 0.75 }
        let height = width * CGFloat(item.height) / CGFloat(item.width)
        return min(max(height, width * 0.5), width * 1.35)
    }

    private func tile(_ index: Int, maxPixel: CGFloat = 600, more: Int = 0) -> some View {
        let item = items[index]
        return MediaTile(
            attachment: item,
            ref: AttachmentRef(conversationId: conversationId, messageId: messageId, position: item.position),
            maxPixel: maxPixel,
            more: more,
            onOpen: { onOpen(item) }
        )
    }
}

/// One photo or video in a grid, with what's going on with it: on its way
/// up or down, waiting for a tap, or gone.
struct MediaTile: View {
    @Environment(AppModel.self) private var model
    let attachment: AttachmentView
    let ref: AttachmentRef
    let maxPixel: CGFloat
    /// Pictures beyond this one that the grid has no room for.
    var more = 0
    let onOpen: () -> Void

    private var thumbnail: CGImage? {
        attachment.thumbnail.flatMap { ImageCache.thumbnail($0, key: "\(ref.messageId)/\(ref.position)") }
    }

    private var fetching: Bool { model.fetching.contains(ref) }

    var body: some View {
        Color.primary.opacity(0.08)
            .overlay { picture }
            .overlay { status }
            .overlay(alignment: .bottomLeading) { badge }
            .overlay {
                if more > 0 {
                    ZStack {
                        Color.black.opacity(0.45)
                        Text("+\(more)")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(.white)
                    }
                }
            }
            .clipped()
            .contentShape(Rectangle())
            .onTapGesture(perform: tap)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityText)
            .accessibilityAddTraits(.isButton)
    }

    @ViewBuilder
    private var picture: some View {
        if let path = attachment.localPath {
            LocalImage(path: path, isVideo: attachment.kind == .video, maxPixel: maxPixel, placeholder: thumbnail)
        } else if let thumbnail {
            BlurredThumbnail(image: thumbnail)
        } else {
            Image(systemName: attachment.kind == .video ? "film" : "photo")
                .font(.title2)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var status: some View {
        switch attachment.state {
        case .remote:
            if fetching {
                ProgressView()
                    .tint(.white)
                    .padding(10)
                    .background(.black.opacity(0.45), in: Circle())
            } else {
                Label(sizeText(attachment.size), systemImage: "arrow.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(.black.opacity(0.45), in: Capsule())
            }
        case .failed:
            Label(attachment.error ?? "This file can't be fetched.", systemImage: "exclamationmark.triangle")
                .font(.caption2)
                .multilineTextAlignment(.center)
                .foregroundStyle(.white)
                .padding(8)
                .background(.black.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
                .padding(8)
        case .uploading:
            ProgressView()
                .controlSize(.small)
                .tint(.white)
                .padding(8)
                .background(.black.opacity(0.35), in: Circle())
        case .ready:
            if attachment.kind == .video {
                Image(systemName: "play.fill")
                    .font(.title3)
                    .foregroundStyle(.white)
                    .padding(14)
                    .background(.black.opacity(0.45), in: Circle())
            }
        }
    }

    @ViewBuilder
    private var badge: some View {
        if attachment.kind == .video || attachment.contentType == "image/gif" {
            HStack(spacing: 3) {
                if attachment.kind == .video {
                    Image(systemName: "video.fill")
                    if attachment.durationMs > 0 { Text(durationText(milliseconds: attachment.durationMs)) }
                } else {
                    Text("GIF")
                }
            }
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.white)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(.black.opacity(0.45), in: Capsule())
            .padding(6)
        }
    }

    private func tap() {
        switch attachment.state {
        case .ready, .uploading: onOpen()
        case .remote: model.download(ref, first: true)
        case .failed: break
        }
    }

    private var accessibilityText: String {
        let what = attachment.kind == .video ? "Video" : "Photo"
        switch attachment.state {
        case .remote: return fetching ? "\(what), downloading" : "\(what), \(sizeText(attachment.size)), download"
        case .failed: return "\(what): \(attachment.error ?? "can't be fetched")"
        case .uploading: return "\(what), sending"
        case .ready: return more > 0 ? "\(what), and \(more) more" : what
        }
    }
}

// MARK: - Voice messages

/// A voice message: play or pause, the waveform with how much has played
/// (tap it to jump), and its length.
struct VoiceNoteRow: View {
    @Environment(AppModel.self) private var model
    let attachment: AttachmentView
    let ref: AttachmentRef
    let outgoing: Bool

    private var player: VoicePlayer { .shared }
    private var fetching: Bool { model.fetching.contains(ref) }

    private var isCurrent: Bool {
        attachment.localPath != nil && player.path == attachment.localPath
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 10) {
                button
                GeometryReader { geometry in
                    Waveform(levels: attachment.waveform, progress: isCurrent ? player.progress : 0)
                        .contentShape(Rectangle())
                        .onTapGesture { location in
                            guard let path = attachment.localPath else { return }
                            if !isCurrent { player.toggle(path) }
                            player.seek(path, to: location.x / max(1, geometry.size.width))
                        }
                }
                .frame(height: 28)
                .accessibilityHidden(true)
                Text(durationText(seconds: isCurrent ? player.currentTime : TimeInterval(attachment.durationMs) / 1000))
                    .font(.caption.monospacedDigit())
                    .opacity(0.8)
            }
            if attachment.state == .failed {
                Text(attachment.error ?? "This voice message can't be fetched.")
                    .font(.caption2)
                    .opacity(0.8)
            }
        }
        .frame(width: MediaGrid.columnWidth)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var button: some View {
        if let path = attachment.localPath {
            Button { player.toggle(path) } label: {
                Image(systemName: isCurrent && player.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                    .font(.system(size: 32))
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isCurrent && player.isPlaying ? "Pause voice message" : "Play voice message")
        } else if attachment.state == .failed {
            Image(systemName: "exclamationmark.circle")
                .font(.system(size: 28))
                .frame(width: 32)
        } else if fetching {
            ProgressView()
                .frame(width: 32, height: 32)
        } else {
            Button { model.download(ref, first: true) } label: {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.system(size: 32))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Download voice message")
        }
    }
}

/// Loudness over time as bars; the part already played is solid.
struct Waveform: View {
    let levels: Data
    var progress: Double = 0

    var body: some View {
        Canvas { context, size in
            let bar: CGFloat = 2.5
            let gap: CGFloat = 2
            let count = max(1, Int((size.width + gap) / (bar + gap)))
            var faded = context
            faded.opacity = 0.4
            for (index, level) in Self.bars(levels, count: count).enumerated() {
                let height = max(3, level * size.height)
                let rect = CGRect(x: CGFloat(index) * (bar + gap), y: (size.height - height) / 2, width: bar, height: height)
                let played = (Double(index) + 0.5) / Double(count) <= progress
                (played ? context : faded).fill(Path(roundedRect: rect, cornerRadius: bar / 2), with: .foreground)
            }
        }
    }

    /// `count` bars from the levels, 0 to 1, each the loudest of its stretch.
    static func bars(_ levels: Data, count: Int) -> [CGFloat] {
        let values = Array(levels)
        guard !values.isEmpty else { return Array(repeating: 0.1, count: count) }
        return (0..<count).map { index in
            let start = index * values.count / count
            let end = max(start + 1, (index + 1) * values.count / count)
            let loudest = values[start..<min(end, values.count)].max() ?? 0
            return CGFloat(loudest) / 255
        }
    }
}

// MARK: - Files

/// A document: its icon, name and size, and whether it's here yet. Tapping
/// opens it, or fetches it first.
struct FileRow: View {
    @Environment(AppModel.self) private var model
    let attachment: AttachmentView
    let ref: AttachmentRef
    let outgoing: Bool
    let onOpen: () -> Void

    private var fetching: Bool { model.fetching.contains(ref) }

    var body: some View {
        Button(action: tap) {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(outgoing ? Color.white.opacity(0.18) : Color.primary.opacity(0.08))
                    if fetching {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: icon)
                            .font(.title3)
                    }
                }
                .frame(width: 40, height: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Text(attachment.fileName.isEmpty ? "File" : attachment.fileName)
                        .font(.subheadline.weight(.medium))
                        .lineLimit(2)
                        .truncationMode(.middle)
                    Text(detail)
                        .font(.caption)
                        .opacity(0.75)
                        .lineLimit(2)
                }
                Spacer(minLength: 0)
            }
            .frame(width: MediaGrid.columnWidth, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(attachment.state == .failed)
    }

    private var detail: String {
        switch attachment.state {
        case .remote: fetching ? "Downloading…" : "\(sizeText(attachment.size)) · Download"
        case .failed: attachment.error ?? "This file can't be fetched."
        case .uploading: "\(sizeText(attachment.size)) · Sending…"
        case .ready: sizeText(attachment.size)
        }
    }

    private var icon: String {
        let type = attachment.contentType
        if type == "application/pdf" { return "doc.richtext" }
        if type.hasPrefix("image/") { return "photo" }
        if type.hasPrefix("video/") { return "film" }
        if type.hasPrefix("audio/") { return "waveform" }
        if type.hasPrefix("text/") { return "doc.text" }
        if type.contains("zip") || type.contains("compressed") { return "doc.zipper" }
        return "doc"
    }

    private func tap() {
        switch attachment.state {
        case .ready, .uploading: onOpen()
        case .remote: model.download(ref, first: true)
        case .failed: break
        }
    }
}
