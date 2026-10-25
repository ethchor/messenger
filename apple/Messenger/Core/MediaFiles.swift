import AVFoundation
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers
#if os(iOS)
import UIKit
#endif

/// The core's limits on files (crates/core/src/media.rs).
enum MediaLimits {
    static let files = 10
    static let bytes: Int64 = 96 * 1024 * 1024
    static let thumbnailBytes = 8 * 1024
    static let waveformSteps = 256
}

/// Why a file can't be sent, in words for the person sending it.
enum MediaError: LocalizedError {
    case unreadable
    case tooBig
    case videoTooLong
    case folder

    var errorDescription: String? {
        switch self {
        case .unreadable: "This file can't be opened."
        case .tooBig: "Files can be at most 96 MB."
        case .videoTooLong: "This video is too long to send."
        case .folder: "Folders can't be sent. Send the files in it instead."
        }
    }
}

/// A file made ready to send, and a picture of it for the composer.
struct PreparedFile {
    let attachment: NewAttachment
    /// The photo, or a video's first frame, small.
    let preview: CGImage?
}

/// Turns what people pick into files to send. Photos are made smaller and
/// re-encoded, and videos re-encoded, so neither carries where or when it
/// was taken, or on what. Each gets a tiny thumbnail that travels inside the
/// message. Documents and voice notes go as they are.
///
/// Everything here is slow; call it off the main thread.
enum MediaPreparation {
    private static let photoSide: CGFloat = 2048
    private static let previewSide: CGFloat = 240

    /// A photo from its bytes. GIFs go as they are, so they keep moving.
    static func photo(_ data: Data) throws -> PreparedFile {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil), CGImageSourceGetCount(source) > 0 else {
            throw MediaError.unreadable
        }
        if CGImageSourceGetType(source) as String? == UTType.gif.identifier {
            return try gif(data, source: source)
        }
        guard var image = downscaled(source, maxSide: photoSide) else { throw MediaError.unreadable }
        let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        if properties?[kCGImagePropertyHasAlpha] as? Bool == true {
            image = onWhite(image)
        }
        let url = AppFiles.outgoingFile(extension: "jpg")
        // No properties: none of the original's EXIF, TIFF or GPS goes along.
        guard let jpeg = jpegData(image, quality: 0.8) else { throw MediaError.unreadable }
        try jpeg.write(to: url)
        return PreparedFile(
            attachment: NewAttachment(
                path: url.path,
                kind: .photo,
                contentType: "image/jpeg",
                fileName: "photo.jpg",
                width: UInt32(image.width),
                height: UInt32(image.height),
                durationMs: 0,
                thumbnail: thumbnail(of: image),
                waveform: Data()
            ),
            preview: scaled(image, maxSide: previewSide)
        )
    }

    private static func gif(_ data: Data, source: CGImageSource) throws -> PreparedFile {
        guard Int64(data.count) <= MediaLimits.bytes else { throw MediaError.tooBig }
        guard let first = CGImageSourceCreateImageAtIndex(source, 0, nil) else { throw MediaError.unreadable }
        let url = AppFiles.outgoingFile(extension: "gif")
        try data.write(to: url)
        return PreparedFile(
            attachment: NewAttachment(
                path: url.path,
                kind: .photo,
                contentType: "image/gif",
                fileName: "animation.gif",
                width: UInt32(first.width),
                height: UInt32(first.height),
                durationMs: 0,
                thumbnail: thumbnail(of: first),
                waveform: Data()
            ),
            preview: scaled(first, maxSide: previewSide)
        )
    }

    /// A video, re-encoded at 720p into an MP4 without the original's metadata.
    /// The source file is left where it is.
    static func video(at input: URL) async throws -> PreparedFile {
        let asset = AVURLAsset(url: input)
        guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPreset1280x720) else {
            throw MediaError.unreadable
        }
        export.metadata = []
        export.metadataItemFilter = .forSharing()
        export.shouldOptimizeForNetworkUse = true
        export.outputFileType = .mp4
        // Long videos would only fail after minutes of work.
        if let estimate = try? await export.estimatedOutputFileLengthInBytes, estimate > MediaLimits.bytes * 11 / 10 {
            throw MediaError.videoTooLong
        }
        let output = AppFiles.outgoingFile(extension: "mp4")
        do {
            if #available(iOS 18, macOS 15, *) {
                try await export.export(to: output, as: .mp4)
            } else {
                export.outputURL = output
                await withCheckedContinuation { done in
                    export.exportAsynchronously { done.resume() }
                }
                if export.status != .completed { throw export.error ?? MediaError.unreadable }
            }
        } catch {
            try? FileManager.default.removeItem(at: output)
            throw MediaError.unreadable
        }
        let size = (try? output.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard Int64(size) <= MediaLimits.bytes else {
            try? FileManager.default.removeItem(at: output)
            throw MediaError.videoTooLong
        }
        let exported = AVURLAsset(url: output)
        let duration = (try? await exported.load(.duration)).map(CMTimeGetSeconds) ?? 0
        var width = 0
        var height = 0
        if let track = try? await exported.loadTracks(withMediaType: .video).first,
           let loaded = try? await track.load(.naturalSize, .preferredTransform) {
            let shown = loaded.0.applying(loaded.1)
            width = Int(abs(shown.width).rounded())
            height = Int(abs(shown.height).rounded())
        }
        let frame = await firstFrame(of: exported, maxSide: previewSide)
        return PreparedFile(
            attachment: NewAttachment(
                path: output.path,
                kind: .video,
                contentType: "video/mp4",
                fileName: "video.mp4",
                width: UInt32(width),
                height: UInt32(height),
                durationMs: milliseconds(duration),
                thumbnail: frame.flatMap(thumbnail(of:)),
                waveform: Data()
            ),
            preview: frame
        )
    }

    /// A document as it is, copied so the original stays put.
    static func file(at source: URL) throws -> PreparedFile {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let values = try source.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey, .contentTypeKey])
        if values.isDirectory == true { throw MediaError.folder }
        if let size = values.fileSize, Int64(size) > MediaLimits.bytes { throw MediaError.tooBig }
        let type = values.contentType ?? UTType(filenameExtension: source.pathExtension)
        let copy = AppFiles.outgoingFile(extension: source.pathExtension.isEmpty ? "bin" : source.pathExtension)
        try FileManager.default.copyItem(at: source, to: copy)
        return PreparedFile(
            attachment: NewAttachment(
                path: copy.path,
                kind: .file,
                contentType: type?.preferredMIMEType ?? "application/octet-stream",
                fileName: source.lastPathComponent,
                width: 0,
                height: 0,
                durationMs: 0,
                thumbnail: nil,
                waveform: Data()
            ),
            preview: nil
        )
    }

    /// A voice note just recorded.
    static func voiceNote(_ note: VoiceNote) -> NewAttachment {
        NewAttachment(
            path: note.url.path,
            kind: .voice,
            contentType: "audio/mp4",
            fileName: "voice-message.m4a",
            width: 0,
            height: 0,
            durationMs: milliseconds(note.duration),
            thumbnail: nil,
            waveform: Data(note.waveform)
        )
    }

    // MARK: - Pictures

    /// The picture at most `maxSide` pixels on its long side, the right way up.
    static func downscaled(_ source: CGImageSource, maxSide: CGFloat) -> CGImage? {
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }

    static func firstFrame(of asset: AVAsset, maxSide: CGFloat) async -> CGImage? {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: maxSide, height: maxSide)
        return try? await generator.image(at: .zero).image
    }

    /// The JPEG that travels inside the message, so the picture shows (blurred)
    /// before the file is fetched: as big as fits in 8 KiB, up to 96 px.
    static func thumbnail(of image: CGImage) -> Data? {
        var side: CGFloat = 96
        while side >= 24 {
            if let small = scaled(image, maxSide: side), let data = jpegData(small, quality: 0.6),
               data.count <= MediaLimits.thumbnailBytes {
                return data
            }
            side = (side * 0.8).rounded()
        }
        return nil
    }

    static func scaled(_ image: CGImage, maxSide: CGFloat) -> CGImage? {
        let longest = CGFloat(max(image.width, image.height))
        guard longest > maxSide else { return image }
        let scale = maxSide / longest
        let width = max(1, Int((CGFloat(image.width) * scale).rounded()))
        let height = max(1, Int((CGFloat(image.height) * scale).rounded()))
        guard let context = CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return nil }
        context.interpolationQuality = .high
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return context.makeImage()
    }

    /// JPEG has no transparency: see-through parts go white rather than black.
    private static func onWhite(_ image: CGImage) -> CGImage {
        let rect = CGRect(x: 0, y: 0, width: image.width, height: image.height)
        guard let context = CGContext(
            data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return image }
        context.setFillColor(CGColor(gray: 1, alpha: 1))
        context.fill(rect)
        context.draw(image, in: rect)
        return context.makeImage() ?? image
    }

    static func jpegData(_ image: CGImage, quality: CGFloat) -> Data? {
        let data = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) else {
            return nil
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { return nil }
        return data as Data
    }

    private static func milliseconds(_ seconds: Double) -> UInt32 {
        guard seconds.isFinite, seconds > 0 else { return 0 }
        return UInt32(min(seconds * 1000, Double(UInt32.max)).rounded())
    }
}

#if os(iOS)
/// Asks iOS for time to finish something after the app leaves the screen.
/// It ends by itself if the time runs out.
@MainActor
final class BackgroundTime {
    private var id = UIBackgroundTaskIdentifier.invalid

    init(_ name: String) {
        id = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
            MainActor.assumeIsolated { self?.end() }
        }
    }

    func end() {
        guard id != .invalid else { return }
        UIApplication.shared.endBackgroundTask(id)
        id = .invalid
    }
}
#endif

extension AppFiles {
    /// Files on their way to the core, which moves them into its own folder
    /// when the message is sent. Whatever is left here is thrown away.
    static var outgoingDirectory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("Outgoing", isDirectory: true)
    }

    /// Files linked under the names they were sent with, for sharing, saving
    /// and previewing.
    static var sharedDirectory: URL {
        FileManager.default.temporaryDirectory.appendingPathComponent("Shared", isDirectory: true)
    }

    static func outgoingFile(extension ext: String) -> URL {
        let folder = outgoingDirectory
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        return folder.appendingPathComponent(UUID().uuidString).appendingPathExtension(ext)
    }

    /// A file of ours under the name it was sent with. A hard link, so nothing
    /// is copied; the file itself if that fails.
    static func shareable(path: String, fileName: String) -> URL {
        let source = URL(fileURLWithPath: path)
        var name = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty || name.hasPrefix(".") { name = "file" + name }
        if (name as NSString).pathExtension.isEmpty, !source.pathExtension.isEmpty {
            name += "." + source.pathExtension
        }
        let folder = sharedDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let link = folder.appendingPathComponent(name)
            try FileManager.default.linkItem(at: source, to: link)
            return link
        } catch {
            return source
        }
    }

    /// Clears out files on their way to the core and links made for sharing.
    /// The links matter most: they would keep a file readable after the core
    /// erased it.
    static func removeTemporaryFiles() {
        for folder in [outgoingDirectory, sharedDirectory] {
            try? FileManager.default.removeItem(at: folder)
        }
    }

    static func remove(_ attachments: [NewAttachment]) {
        for attachment in attachments {
            try? FileManager.default.removeItem(atPath: attachment.path)
        }
    }
}
