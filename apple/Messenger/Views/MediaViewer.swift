import AVKit
import QuickLook
import SwiftUI

/// A message's photos and videos, opened at one of them.
struct MediaSelection: Identifiable {
    let conversationId: String
    let messageId: String
    let position: UInt32

    var id: String { "\(messageId)/\(position)" }
}

/// The attachment open over a chat or thread: photos and videos in the
/// viewer, other files in Quick Look under the name they were sent with.
struct OpenedAttachment {
    var media: MediaSelection?
    var file: URL?

    mutating func open(_ attachment: AttachmentView, of message: MessageView, in conversationId: String) {
        guard let path = attachment.localPath else { return }
        if attachment.isVisual {
            media = MediaSelection(conversationId: conversationId, messageId: message.id, position: attachment.position)
        } else {
            file = AppFiles.shareable(path: path, fileName: attachment.fileName)
        }
    }
}

extension View {
    func attachmentViewers(_ opened: Binding<OpenedAttachment>) -> some View {
        self
            #if os(iOS)
            .fullScreenCover(item: opened.media) { MediaViewer(selection: $0) }
            #else
            .sheet(item: opened.media) { selection in
                MediaViewer(selection: selection)
                    .frame(minWidth: 640, idealWidth: 900, minHeight: 480, idealHeight: 680)
            }
            #endif
            .quickLookPreview(opened.file)
    }
}

/// Photos and videos full screen: move between the ones in a message, zoom
/// photos, play videos, and share or save them.
struct MediaViewer: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let selection: MediaSelection
    @State private var position: UInt32
    @State private var shareURL: URL?
    @State private var chrome = true
    @State private var appeared = false
    @State private var forwarding = false
    #if os(macOS)
    @State private var saving = false
    @State private var saves = 0
    #endif

    init(selection: MediaSelection) {
        self.selection = selection
        _position = State(initialValue: selection.position)
    }

    /// The message as it is now, so files fetched meanwhile show.
    private var items: [AttachmentView] {
        model.message(selection.messageId, in: selection.conversationId)?.attachments.filter(\.isVisual) ?? []
    }

    private var current: AttachmentView? {
        items.first { $0.position == position }
    }

    private var index: Int? {
        items.firstIndex { $0.position == position }
    }

    private func ref(_ item: AttachmentView) -> AttachmentRef {
        AttachmentRef(conversationId: selection.conversationId, messageId: selection.messageId, position: item.position)
    }

    var body: some View {
        #if os(iOS)
        ZStack {
            Color.black.ignoresSafeArea()
            pager
                .scaleEffect(appeared ? 1 : 0.4)
                .opacity(appeared ? 1 : 0.4)
                .onTapGesture { withAnimation(.easeOut(duration: 0.25)) { chrome.toggle() } }
            VStack(spacing: 0) {
                topBar
                Spacer()
                bottomBar
            }
            .opacity(chrome ? 1 : 0)
            .allowsHitTesting(chrome)
        }
        .foregroundStyle(.white)
        .statusBarHidden(!chrome)
        .onAppear {
            // Zooms out of the bubble: 0.4 s, the design's ease.
            withAnimation(.timingCurve(0.2, 0.9, 0.25, 1, duration: 0.4)) { appeared = true }
        }
        .task(id: shareKey) { makeShareURL() }
        .sheet(isPresented: $forwarding) {
            if let message { ForwardSheet(conversationId: selection.conversationId, message: message) }
        }
        #else
        // A sheet's toolbar on the Mac has no room for arrows and sharing:
        // its own bar instead, with arrows on the picture.
        VStack(spacing: 0) {
            pager
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Color.black)
                .overlay(alignment: .leading) { arrow(-1) }
                .overlay(alignment: .trailing) { arrow(1) }
            HStack(spacing: 12) {
                Text(title)
                    .foregroundStyle(.secondary)
                Spacer()
                if let shareURL {
                    ShareLink(item: shareURL) { Label("Share", systemImage: "square.and.arrow.up") }
                    Button("Save…") { saving = true }
                }
                Button("Done") { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(12)
        }
        .task(id: shareKey) { makeShareURL() }
        .fileMover(isPresented: $saving, file: shareURL) { _ in
            // The link went to wherever it was saved: make another.
            saves += 1
        }
        #endif
    }

    private var title: String {
        guard items.count > 1, let index else { return "" }
        return "\(index + 1) of \(items.count)"
    }

    private var message: MessageView? {
        model.message(selection.messageId, in: selection.conversationId)
    }

    private var loved: Bool {
        message?.reactions.contains { $0.emoji == "❤️" && $0.mine } ?? false
    }

    #if os(iOS)
    /// Who sent it, when, and which of how many; close and share.
    private var topBar: some View {
        HStack(spacing: 10) {
            Button { dismiss() } label: {
                Image(systemName: "xmark")
                    .font(.system(size: 16, weight: .bold))
                    .frame(width: 44, height: 44)
                    .background(.white.opacity(0.14), in: Circle())
            }
            .buttonStyle(PressScale())
            .accessibilityLabel("Close")
            VStack(alignment: .leading, spacing: 1) {
                Text(message.map { $0.outgoing ? "You" : $0.sender } ?? "")
                    .font(.system(size: 16, weight: .bold))
                Text([message.map { Date(timeIntervalSince1970: TimeInterval($0.sentAtMs) / 1000).formatted(.relative(presentation: .named)) }, title.isEmpty ? nil : title]
                    .compactMap { $0 }.joined(separator: " · "))
                    .font(.system(size: 12.5))
                    .opacity(0.75)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let shareURL {
                ShareLink(item: shareURL) {
                    Image(systemName: "square.and.arrow.up")
                        .font(.system(size: 16, weight: .bold))
                        .frame(width: 44, height: 44)
                        .background(.white.opacity(0.14), in: Circle())
                }
                .accessibilityLabel("Save or share")
            }
        }
        .padding(.horizontal, 14)
        .padding(.top, 8)
        .padding(.bottom, 14)
        .background(LinearGradient(colors: [.black.opacity(0.6), .clear], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
    }

    /// The caption, where we are in the album, and Reply, Love and Forward.
    private var bottomBar: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let text = message?.text, !text.isEmpty {
                Text(text).font(.system(size: 15)).lineLimit(4)
            }
            if items.count > 1 {
                HStack(spacing: 8) {
                    roundStep("‹", by: -1)
                    HStack(spacing: 6) {
                        ForEach(items, id: \.position) { item in
                            Capsule()
                                .fill(item.position == position ? Palette.marigold : .white.opacity(0.4))
                                .frame(width: item.position == position ? 18 : 6, height: 6)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .animation(.easeOut(duration: 0.3), value: position)
                    roundStep("›", by: 1)
                }
            }
            HStack(spacing: 8) {
                pill("Reply") {
                    model.replyRequest = selection.messageId
                    dismiss()
                }
                pill(loved ? "❤️ Loved" : "♡ Love") {
                    guard let message else { return }
                    Task { await model.react(to: message, in: selection.conversationId, emoji: loved ? nil : "❤️") }
                }
                if message?.vanish == false {
                    pill("Forward") { forwarding = true }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 14)
        .padding(.bottom, 12)
        .background(LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom).ignoresSafeArea())
    }

    private func pill(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14.5, weight: .semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(.white.opacity(0.14), in: Capsule())
        }
        .buttonStyle(PressScale())
    }

    private func roundStep(_ glyph: String, by: Int) -> some View {
        Button {
            guard let index, items.indices.contains(index + by) else { return }
            withAnimation(.easeOut(duration: 0.25)) { position = items[index + by].position }
        } label: {
            Text(glyph)
                .font(.system(size: 18))
                .frame(width: 44, height: 44)
                .background(.white.opacity(0.14), in: Circle())
        }
        .buttonStyle(PressScale())
        .accessibilityLabel(by < 0 ? "Previous" : "Next")
    }
    #endif

    @ViewBuilder
    private var pager: some View {
        #if os(iOS)
        TabView(selection: $position) {
            ForEach(items, id: \.position) { item in
                MediaPage(item: item, ref: ref(item))
                    .tag(item.position)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        #else
        ZStack {
            if let current {
                MediaPage(item: current, ref: ref(current))
                    .id(current.position)
            }
        }
        #endif
    }

    #if os(macOS)
    /// To the previous or next photo or video, by click or arrow key.
    @ViewBuilder
    private func arrow(_ by: Int) -> some View {
        if let index, items.indices.contains(index + by) {
            Button { step(by) } label: {
                Image(systemName: by < 0 ? "chevron.left" : "chevron.right")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 36, height: 36)
                    .background(.black.opacity(0.45), in: Circle())
            }
            .buttonStyle(.plain)
            .keyboardShortcut(by < 0 ? .leftArrow : .rightArrow, modifiers: [])
            .accessibilityLabel(by < 0 ? "Previous" : "Next")
            .padding(12)
        }
    }

    private func step(_ by: Int) {
        guard let index, items.indices.contains(index + by) else { return }
        position = items[index + by].position
    }
    #endif

    /// Changes when another file shows, or one arrives or is saved.
    private var shareKey: String {
        var key = "\(position)|\(current?.localPath ?? "")"
        #if os(macOS)
        key += "|\(saves)"
        #endif
        return key
    }

    private func makeShareURL() {
        guard let current, let path = current.localPath else {
            shareURL = nil
            return
        }
        shareURL = AppFiles.shareable(path: path, fileName: current.fileName)
    }
}

/// One photo or video in the viewer. A file that isn't here yet is fetched
/// as soon as it's on screen.
private struct MediaPage: View {
    @Environment(AppModel.self) private var model
    let item: AttachmentView
    let ref: AttachmentRef

    var body: some View {
        Group {
            if let path = item.localPath {
                if item.kind == .video {
                    VideoPage(url: URL(fileURLWithPath: path))
                } else if item.contentType == "image/gif" {
                    AnimatedImage(url: URL(fileURLWithPath: path))
                } else {
                    ZoomablePhoto(path: path)
                }
            } else {
                waiting
            }
        }
        .task(id: item.state == .remote) {
            if item.state == .remote { model.download(ref, first: true) }
        }
    }

    private var waiting: some View {
        ZStack {
            if let data = item.thumbnail, let image = ImageCache.thumbnail(data, key: "\(ref.messageId)/\(ref.position)") {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .scaledToFit()
                    .blur(radius: 16)
                    .opacity(0.6)
            }
            if item.state == .failed {
                Label(item.error ?? "This file can't be fetched.", systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.white)
                    .padding()
            } else {
                ProgressView()
                    .tint(.white)
                    .controlSize(.large)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct VideoPage: View {
    let url: URL
    @State private var player: AVPlayer?

    var body: some View {
        VideoPlayer(player: player)
            .onAppear {
                VoicePlayer.shared.stop()
                #if os(iOS)
                try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
                #endif
                if player == nil { player = AVPlayer(url: url) }
                player?.play()
            }
            .onDisappear { player?.pause() }
    }
}

/// A photo to pinch or double-tap into, and to drag around once zoomed.
private struct ZoomablePhoto: View {
    let path: String
    @State private var image: CGImage?
    @State private var zoom: CGFloat = 1
    @State private var settledZoom: CGFloat = 1
    @State private var offset: CGSize = .zero
    @State private var settledOffset: CGSize = .zero

    var body: some View {
        GeometryReader { geometry in
            Group {
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .scaleEffect(zoom)
                        .offset(offset)
                } else {
                    ProgressView().tint(.white)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .contentShape(Rectangle())
            .gesture(
                MagnifyGesture()
                    .onChanged { value in zoom = min(max(settledZoom * value.magnification, 1), 6) }
                    .onEnded { _ in settle() }
            )
            // Only once zoomed, so swiping still moves between photos.
            .simultaneousGesture(
                DragGesture()
                    .onChanged { value in
                        offset = CGSize(
                            width: settledOffset.width + value.translation.width,
                            height: settledOffset.height + value.translation.height
                        )
                    }
                    .onEnded { _ in settledOffset = offset },
                including: zoom > 1 ? .all : .subviews
            )
            .onTapGesture(count: 2) {
                withAnimation(.easeInOut(duration: 0.2)) {
                    zoom = zoom > 1 ? 1 : 2.5
                    settle()
                }
            }
        }
        .clipped()
        .task(id: path) {
            image = await ImageCache.image(path: path, video: false, maxPixel: 3072)
        }
    }

    private func settle() {
        settledZoom = zoom
        if zoom <= 1 {
            offset = .zero
            settledOffset = .zero
        }
    }
}

/// A GIF, moving. Frames are decoded as they're shown, not all at once.
#if os(iOS)
private struct AnimatedImage: UIViewRepresentable {
    let url: URL

    final class Playback {
        var on = true
    }

    func makeCoordinator() -> Playback { Playback() }

    func makeUIView(context: Context) -> UIImageView {
        let view = UIImageView()
        view.contentMode = .scaleAspectFit
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        let playback = context.coordinator
        // ImageIO calls this on the main queue, once per frame.
        CGAnimateImageAtURLWithBlock(url as CFURL, nil) { [weak view] _, frame, stop in
            MainActor.assumeIsolated {
                guard let view, playback.on else {
                    stop.pointee = true
                    return
                }
                view.image = UIImage(cgImage: frame)
            }
        }
        return view
    }

    func updateUIView(_ view: UIImageView, context: Context) {}

    static func dismantleUIView(_ view: UIImageView, coordinator: Playback) {
        coordinator.on = false
    }
}
#else
private struct AnimatedImage: NSViewRepresentable {
    let url: URL

    func makeNSView(context: Context) -> NSImageView {
        let view = NSImageView()
        view.image = NSImage(contentsOf: url)
        view.animates = true
        view.imageScaling = .scaleProportionallyUpOrDown
        view.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        view.setContentCompressionResistancePriority(.defaultLow, for: .vertical)
        return view
    }

    func updateNSView(_ view: NSImageView, context: Context) {}
}
#endif
