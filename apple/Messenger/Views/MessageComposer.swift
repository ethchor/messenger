import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// The box under a chat or a thread: text, the files going with it, and a
/// button to hold for a voice message.
struct MessageComposer: View {
    @Binding var text: String
    let placeholder: String
    /// Files for the next message; nil where none can be added.
    var tray: AttachmentTray?
    /// The chat, so the attach sheet can share a live location. Nil in a thread.
    var conversationId: String?
    let onSend: () -> Void
    /// A voice message was recorded: send it.
    var onVoiceNote: ((VoiceNote) -> Void)?

    @State private var recorder = VoiceRecorder()
    @GestureState private var holding = false
    @State private var cancelling = false
    @State private var hint: String?
    @State private var lineHeight: CGFloat = 46

    private var hasText: Bool {
        !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var canSend: Bool {
        (hasText || tray?.hasFiles == true) && tray?.isPreparing != true
    }

    /// The microphone takes the send button's place while there's nothing to send.
    private var showsMicrophone: Bool {
        onVoiceNote != nil && !hasText && (tray?.isEmpty ?? true)
    }

    var body: some View {
        VStack(spacing: 0) {
            if let tray, !tray.isEmpty {
                TrayStrip(tray: tray)
            }
            if let hint {
                Text(hint)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.top, 6)
                    .transition(.opacity)
            }
            HStack(alignment: .bottom, spacing: 8) {
                if let tray, !recorder.isRecording {
                    AttachMenu(tray: tray, conversationId: conversationId)
                        .frame(height: lineHeight)
                }
                if recorder.isRecording {
                    RecordingBar(recorder: recorder, cancelling: cancelling)
                } else {
                    // A cream field with dark text, in both modes.
                    TextField(text: $text, prompt: Text(tray?.isEmpty == false ? "Add a caption" : placeholder).foregroundStyle(Color(rgb: 0x6B6960)), axis: .vertical) {
                        Text(placeholder)
                    }
                        .lineLimit(1...6)
                        .textFieldStyle(.plain)
                        .foregroundStyle(Palette.ink)
                        .tint(Color(rgb: 0x7A5600))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .background(Palette.cream, in: RoundedRectangle(cornerRadius: 23, style: .continuous))
                        .environment(\.colorScheme, .light)
                        .background {
                            // One line of the field, measured: the buttons
                            // beside it are this tall, so they sit centred on
                            // its last line however many lines it grows to.
                            Text(" ")
                                .padding(.vertical, 12)
                                .hidden()
                                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { lineHeight = $0 }
                        }
                        .onSubmit(send)
                }
                if showsMicrophone {
                    microphone
                        .frame(height: lineHeight)
                } else {
                    Button(action: send) {
                        Image(systemName: "arrow.up")
                            .font(.system(size: 19, weight: .bold))
                            .foregroundStyle(Palette.ink)
                            .frame(width: 46, height: 46)
                            .background(Circle().fill(Palette.marigold))
                            .opacity(canSend ? 1 : 0.45)
                    }
                    .buttonStyle(PressScale())
                    .disabled(!canSend)
                    .keyboardShortcut(.return, modifiers: .command)
                    .accessibilityLabel("Send")
                    .frame(height: lineHeight)
                }
            }
            .padding(.horizontal, 10)
            .padding(.top, 6)
            .padding(.bottom, 8)
        }
        .animation(.easeInOut(duration: 0.15), value: hint)
        .animation(.timingCurve(0.25, 1.25, 0.4, 1, duration: 0.42), value: tray?.isEmpty)
        .animation(.spring(duration: 0.3, bounce: 0.3), value: showsMicrophone)
        .onChange(of: holding) { _, held in
            if held { startRecording() } else { stopRecording() }
        }
        .onDisappear {
            if recorder.isRecording { recorder.cancel() }
        }
    }

    private func send() {
        guard canSend else { return }
        onSend()
    }

    // MARK: - Voice messages

    private var microphone: some View {
        Image(systemName: "mic.fill")
            .font(.system(size: 18, weight: .semibold))
            .foregroundStyle(Palette.ink)
            .frame(width: 46, height: 46)
            .background(Circle().fill(recorder.isRecording ? (cancelling ? Color(rgb: 0xFF6B5E) : Palette.marigold) : Palette.green))
            .scaleEffect(recorder.isRecording ? 1.35 : 1)
            .animation(.spring(duration: 0.3, bounce: 0.35), value: recorder.isRecording)
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .updating($holding) { _, state, _ in state = true }
                    .onChanged { value in cancelling = value.translation.width < -80 }
            )
            .accessibilityElement()
            .accessibilityLabel(recorder.isRecording ? "Stop and send the voice message" : "Record a voice message")
            .accessibilityHint("Hold to record, and let go to send.")
            .accessibilityAddTraits(.isButton)
            // Holding isn't possible with VoiceOver: a tap starts, the next sends.
            .accessibilityAction {
                if recorder.isRecording { stopRecording() } else { startRecording() }
            }
    }

    private func startRecording() {
        cancelling = false
        switch VoiceRecorder.access {
        case .allowed:
            do {
                try recorder.start()
            } catch {
                show(error.userMessage)
            }
        case .notAsked:
            // Once allowed, they hold the button again.
            Task { _ = await VoiceRecorder.requestAccess() }
        case .denied:
            #if os(iOS)
            show("To record voice messages, allow Messenger to use the microphone in Settings.")
            #else
            show("To record voice messages, allow Messenger in System Settings → Privacy & Security → Microphone.")
            #endif
        }
    }

    private func stopRecording() {
        guard recorder.isRecording else { return }
        if cancelling {
            recorder.cancel()
            cancelling = false
        } else if let note = recorder.finish() {
            onVoiceNote?(note)
        } else {
            show("Hold to record, and let go to send.")
        }
    }

    private func show(_ message: String) {
        hint = message
        Task {
            try? await Task.sleep(for: .seconds(3))
            if hint == message { hint = nil }
        }
    }
}

/// While a voice message records: how long so far, how loud, and how to
/// cancel it.
private struct RecordingBar: View {
    let recorder: VoiceRecorder
    let cancelling: Bool

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(.red)
                .frame(width: 8, height: 8)
                .opacity(Int(recorder.elapsed * 2) % 2 == 0 ? 1 : 0.3)
            Text(durationText(seconds: recorder.elapsed))
                .font(.body.monospacedDigit())
            Waveform(levels: Data(recorder.levels.suffix(40).map { UInt8($0 * 255) }), progress: 1)
                .frame(height: 20)
                .foregroundStyle(.secondary)
            Text(cancelling ? "Let go to cancel" : "‹ Slide to cancel")
                .font(.caption)
                .foregroundStyle(cancelling ? Color.red : Color.secondary)
                .fixedSize()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, minHeight: 46)
        .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 23, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Recording, \(durationText(seconds: recorder.elapsed))")
    }
}

// MARK: - Files for the next message

/// Files picked for the next message, made ready to send as soon as they're
/// picked. They go with the message, its text as their caption.
@MainActor
@Observable
final class AttachmentTray {
    struct Item: Identifiable {
        let id = UUID()
        var state: State
    }

    enum State {
        case preparing
        case ready(PreparedFile)
        case failed(String)

        var file: PreparedFile? {
            if case .ready(let file) = self { return file }
            return nil
        }
    }

    private(set) var items: [Item] = []

    var isEmpty: Bool { items.isEmpty }
    var hasFiles: Bool { items.contains { $0.state.file != nil } }

    var isPreparing: Bool {
        items.contains { if case .preparing = $0.state { return true } else { return false } }
    }

    /// How many more fit in one message.
    var room: Int { max(0, MediaLimits.files - items.count) }

    func add(_ picked: [PhotosPickerItem]) {
        for item in picked.prefix(room) {
            prepare { try await Self.load(item) }
        }
    }

    func add(files urls: [URL]) {
        for url in urls.prefix(room) {
            prepare { try await Task.detached(priority: .userInitiated) { try MediaPreparation.file(at: url) }.value }
        }
    }

    #if os(iOS)
    func add(_ shot: CameraPicker.Shot) {
        guard room > 0 else { return }
        switch shot {
        case .photo(let image):
            prepare {
                try await Task.detached(priority: .userInitiated) {
                    guard let data = image.jpegData(compressionQuality: 0.95) else { throw MediaError.unreadable }
                    return try MediaPreparation.photo(data)
                }.value
            }
        case .video(let url):
            // The camera's file may go once the picker closes: keep it here.
            let copy = AppFiles.outgoingFile(extension: url.pathExtension.isEmpty ? "mov" : url.pathExtension)
            let moved = (try? FileManager.default.moveItem(at: url, to: copy)) != nil
            prepare {
                guard moved else { throw MediaError.unreadable }
                defer { try? FileManager.default.removeItem(at: copy) }
                return try await MediaPreparation.video(at: copy)
            }
        }
    }
    #endif

    func remove(_ id: UUID) {
        guard let index = items.firstIndex(where: { $0.id == id }) else { return }
        if let file = items[index].state.file {
            AppFiles.remove([file.attachment])
        }
        items.remove(at: index)
    }

    /// Hands the prepared files over for sending and empties the tray.
    func take() -> [NewAttachment] {
        let files = items.compactMap { $0.state.file?.attachment }
        items = []
        return files
    }

    /// Empties the tray, deleting what was prepared.
    func clear() {
        AppFiles.remove(take())
    }

    private func prepare(_ work: @escaping () async throws -> PreparedFile) {
        let item = Item(state: .preparing)
        items.append(item)
        Task {
            let state: State
            do {
                state = .ready(try await work())
            } catch {
                state = .failed(error.userMessage)
            }
            if let index = items.firstIndex(where: { $0.id == item.id }) {
                items[index].state = state
            } else if let file = state.file {
                // Taken out while it was being prepared.
                AppFiles.remove([file.attachment])
            }
        }
    }

    private static func load(_ item: PhotosPickerItem) async throws -> PreparedFile {
        if item.supportedContentTypes.contains(where: { $0.conforms(to: .movie) }) {
            guard let movie = try await item.loadTransferable(type: PickedMovie.self) else { throw MediaError.unreadable }
            defer { try? FileManager.default.removeItem(at: movie.url) }
            return try await Task.detached(priority: .userInitiated) { try await MediaPreparation.video(at: movie.url) }.value
        }
        guard let image = try await item.loadTransferable(type: PickedImage.self) else { throw MediaError.unreadable }
        return try await Task.detached(priority: .userInitiated) { try MediaPreparation.photo(image.data) }.value
    }
}

/// A picture from the photo library, in whatever format it's kept in.
private struct PickedImage: Transferable {
    let data: Data

    static var transferRepresentation: some TransferRepresentation {
        DataRepresentation(importedContentType: .image) { PickedImage(data: $0) }
    }
}

/// A video from the photo library, copied to a file of our own.
private struct PickedMovie: Transferable {
    let url: URL

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(importedContentType: .movie) { received in
            let ext = received.file.pathExtension
            let copy = AppFiles.outgoingFile(extension: ext.isEmpty ? "mov" : ext)
            try FileManager.default.copyItem(at: received.file, to: copy)
            return PickedMovie(url: copy)
        }
    }
}

/// The paper clip: photos and videos, the camera, or any file.
private struct AttachMenu: View {
    let tray: AttachmentTray
    /// The chat, for sharing a live location from here. Nil in a thread.
    var conversationId: String?
    @Environment(AppModel.self) private var model
    @State private var open = false
    @State private var choosingPhotos = false
    @State private var choosingFiles = false
    @State private var picked: [PhotosPickerItem] = []
    #if os(iOS)
    @State private var usingCamera = false
    #endif

    var body: some View {
        Button { open = true } label: {
            Image(systemName: "plus")
                .font(.system(size: 19, weight: .semibold))
                .frame(width: 46, height: 46)
                .overlay(Circle().strokeBorder(Color.secondary.opacity(0.6), lineWidth: 1.5))
                .contentShape(Circle())
        }
        .buttonStyle(PressScale())
        .foregroundStyle(.primary)
        .disabled(tray.room == 0)
        .accessibilityLabel("Attach")
        .sheet(isPresented: $open) {
            AttachSheet(conversationId: conversationId) { choice in
                open = false
                // Once the sheet has gone, so the picker can come up.
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.45) {
                    switch choice {
                    case .photos: choosingPhotos = true
                    case .files: choosingFiles = true
                    case .camera:
                        #if os(iOS)
                        usingCamera = true
                        #endif
                    }
                }
            }
            .environment(model)
            #if os(iOS)
            .presentationDetents([.height(conversationId == nil ? 230 : 250), .medium])
            .presentationCornerRadius(26)
            .presentationDragIndicator(.visible)
            #endif
        }
        .photosPicker(
            isPresented: $choosingPhotos,
            selection: $picked,
            maxSelectionCount: max(1, tray.room),
            matching: .any(of: [.images, .videos]),
            preferredItemEncoding: .current
        )
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            tray.add(items)
            picked = []
        }
        .fileImporter(isPresented: $choosingFiles, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { tray.add(files: urls) }
        }
        #if os(iOS)
        .fullScreenCover(isPresented: $usingCamera) {
            CameraPicker { shot in tray.add(shot) }
                .ignoresSafeArea()
        }
        #endif
    }
}

/// The attach sheet: Photos, Camera, File and Location as coloured tiles,
/// and live location's durations one step in.
private struct AttachSheet: View {
    enum Choice { case photos, camera, files }
    let conversationId: String?
    let pick: (Choice) -> Void
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var choosingTime = false
    @State private var error: String?

    private var hasCamera: Bool {
        #if os(iOS)
        CameraPicker.isAvailable
        #else
        false
        #endif
    }

    var body: some View {
        VStack(spacing: 14) {
            if choosingTime, let conversationId {
                LiveLocationSetup(conversationId: conversationId) { dismiss() }
                    .padding(.horizontal, -18)
                    .transition(.opacity)
            } else {
                HStack(alignment: .top, spacing: 8) {
                    tile("Photos", "photo.on.rectangle", 0xFFD9E6, delay: 0) { pick(.photos) }
                    if hasCamera { tile("Camera", "camera", 0xFFE58A, delay: 0.04) { pick(.camera) } }
                    tile("File", "doc", 0xD9D2FF, delay: 0.08) { pick(.files) }
                    if conversationId != nil {
                        tile("Location", "location", 0xBDEBCB, delay: 0.12) { withAnimation { choosingTime = true } }
                    }
                }
                .padding(.top, 6)
                Text("Up to 10 at once. Photos and videos lose where, when and on what they were taken.")
                    .font(.system(size: 12.5))
                    .foregroundStyle(Palette.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 20)
        .padding(.bottom, 12)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Palette.surface)
        #if os(macOS)
        .frame(width: 380)
        #endif
    }

    private func tile(_ label: String, _ symbol: String, _ color: UInt32, delay: Double, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 8) {
                Image(systemName: symbol)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Palette.ink)
                    .frame(width: 58, height: 58)
                    .background(Color(rgb: color), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                Text(label).font(.system(size: 13, weight: .medium)).foregroundStyle(Palette.text)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(PressScale())
    }

    private func duration(_ label: String, until: Date?, in conversationId: String) -> some View {
        Button {
            Task {
                do {
                    try await model.startSharingLocation(in: conversationId, until: until)
                    dismiss()
                } catch {
                    self.error = error.userMessage
                }
            }
        } label: {
            HStack {
                Text(label).font(.system(size: 16, weight: .semibold))
                Spacer()
                Text(until.map { "until " + $0.formatted(date: .omitted, time: .shortened) } ?? "you decide")
                    .font(.system(size: 13.5)).foregroundStyle(Palette.secondary)
            }
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 16)
            .frame(height: 54)
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Palette.line, lineWidth: 1.5))
            .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(PressScale())
    }
}

/// What's going with the next message, as designed: a card over the
/// composer with a count, Clear all, a tile for each file with ×, a dashed
/// + for more, and a line on what's stripped. It rises 30 pt from 0.96.
private struct TrayStrip: View {
    let tray: AttachmentTray
    @State private var picking = false
    @State private var picked: [PhotosPickerItem] = []

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("\(tray.items.count) of \(MediaLimits.files)")
                    .font(.system(size: 14, weight: .bold))
                Spacer()
                Button("Clear all") { withAnimation(.easeOut(duration: 0.25)) { tray.clear() } }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color(rgb: 0xA3A1A9))
                    .buttonStyle(.plain)
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(tray.items) { item in
                        TrayTile(item: item)
                            .overlay(alignment: .topTrailing) {
                                Button { withAnimation(.easeOut(duration: 0.2)) { tray.remove(item.id) } } label: {
                                    Text("×")
                                        .font(.system(size: 15, weight: .bold))
                                        .foregroundStyle(.white)
                                        .frame(width: 24, height: 24)
                                        .background(Color(rgb: 0x141414).opacity(0.75), in: Circle())
                                }
                                .buttonStyle(.plain)
                                .padding(4)
                                .accessibilityLabel("Remove")
                            }
                            .transition(.scale(scale: 0.6).combined(with: .opacity))
                    }
                    if tray.room > 0 {
                        Button { picking = true } label: {
                            Text("+")
                                .font(.system(size: 28))
                                .foregroundStyle(Color(rgb: 0xA3A1A9))
                                .frame(width: 76, height: 92)
                                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                                    .strokeBorder(Color(rgb: 0xA3A1A9), style: StrokeStyle(lineWidth: 1.5, dash: [5, 4])))
                        }
                        .buttonStyle(PressScale())
                        .accessibilityLabel("Add more")
                    }
                }
            }
            Label("Location, date and camera details are stripped before sending", systemImage: "lock.fill")
                .font(.system(size: 12))
                .foregroundStyle(Color(rgb: 0xA3A1A9))
        }
        .foregroundStyle(Color(rgb: 0xF3F2EE))
        .padding(12)
        .background(Color(rgb: 0x2A2A2E), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, 10)
        .padding(.top, 10)
        .transition(.asymmetric(
            insertion: .modifier(active: TrayRise(progress: 0), identity: TrayRise(progress: 1)),
            removal: .opacity.animation(.easeIn(duration: 0.25))
        ))
        .photosPicker(isPresented: $picking, selection: $picked, maxSelectionCount: tray.room, matching: .any(of: [.images, .videos]))
        .onChange(of: picked) { _, items in
            guard !items.isEmpty else { return }
            tray.add(items)
            picked = []
        }
    }
}

/// The tray's way in: up 30 pt, from 0.96, fading in.
private struct TrayRise: ViewModifier {
    let progress: CGFloat
    func body(content: Content) -> some View {
        content
            .opacity(progress)
            .scaleEffect(0.96 + 0.04 * progress, anchor: .bottom)
            .offset(y: 30 * (1 - progress))
    }
}

private struct TrayTile: View {
    let item: AttachmentTray.Item

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Color.incomingBubble
            switch item.state {
            case .preparing:
                ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity)
            case .ready(let file):
                if let preview = file.preview {
                    Image(decorative: preview, scale: 1)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 76, height: 92)
                        .clipped()
                    if file.attachment.kind == .video {
                        Text("▶ " + durationText(milliseconds: file.attachment.durationMs))
                            .font(.system(size: 10.5, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .frame(height: 18)
                            .background(.black.opacity(0.55), in: Capsule())
                            .padding(6)
                    }
                } else {
                    VStack(spacing: 4) {
                        Image(systemName: "doc.text")
                            .font(.system(size: 22, weight: .semibold))
                        Text(fileBadge(file.attachment.fileName))
                            .font(.system(size: 10.5, weight: .bold))
                    }
                    .foregroundStyle(Color(rgb: 0x141414))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Palette.cream)
                }
            case .failed(let message):
                Text(message)
                    .font(.system(size: 10.5, weight: .semibold))
                    .foregroundStyle(Color(rgb: 0xFF8A7A))
                    .padding(6)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(width: 76, height: 92)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// "PDF", "ZIP": what kind of file, from its name.
    private func fileBadge(_ name: String) -> String {
        let ext = (name as NSString).pathExtension.uppercased()
        return ext.isEmpty ? "FILE" : String(ext.prefix(4))
    }
}

#if os(iOS)
/// The camera, for a photo or a video to send.
struct CameraPicker: UIViewControllerRepresentable {
    enum Shot {
        case photo(UIImage)
        case video(URL)
    }

    let onShot: (Shot) -> Void
    @Environment(\.dismiss) private var dismiss

    static var isAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.mediaTypes = [UTType.image.identifier, UTType.movie.identifier]
        picker.videoQuality = .typeHigh
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        private let parent: CameraPicker

        init(_ parent: CameraPicker) {
            self.parent = parent
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
        ) {
            if let url = info[.mediaURL] as? URL {
                parent.onShot(.video(url))
            } else if let image = info[.originalImage] as? UIImage {
                parent.onShot(.photo(image))
            }
            parent.dismiss()
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            parent.dismiss()
        }
    }
}
#endif

/// A photo or video from the chats' camera button (or, with no camera, the
/// library), on its way to the chat it was sent to.
struct PendingCapture {
    enum Source {
        #if os(iOS)
        case camera(CameraPicker.Shot)
        case library([PhotosPickerItem])
        #endif
        /// A photo as JPEG: the Mac's camera, or an iPhone's through Continuity Camera.
        case photo(Data)
    }

    let conversationId: String
    let source: Source
}

extension AttachmentTray {
    func add(_ source: PendingCapture.Source) {
        switch source {
        #if os(iOS)
        case .camera(let shot): add(shot)
        case .library(let items): add(items)
        #endif
        case .photo(let data):
            guard room > 0 else { return }
            prepare { try await Task.detached(priority: .userInitiated) { try MediaPreparation.photo(data) }.value }
        }
    }
}
