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
    let onSend: () -> Void
    /// A voice message was recorded: send it.
    var onVoiceNote: ((VoiceNote) -> Void)?

    @State private var recorder = VoiceRecorder()
    @GestureState private var holding = false
    @State private var cancelling = false
    @State private var hint: String?
    @State private var lineHeight: CGFloat = 36

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
                    AttachMenu(tray: tray)
                        .frame(height: lineHeight)
                }
                if recorder.isRecording {
                    RecordingBar(recorder: recorder, cancelling: cancelling)
                } else {
                    TextField(placeholder, text: $text, axis: .vertical)
                        .lineLimit(1...6)
                        .textFieldStyle(.plain)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 18))
                        .background {
                            // One line of the field, measured: the buttons
                            // beside it are this tall, so they sit centred on
                            // its last line however many lines it grows to.
                            Text(" ")
                                .padding(.vertical, 8)
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
                        Image(systemName: "arrow.up.circle.fill")
                            .font(.system(size: 30))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(.tint)
                    .disabled(!canSend)
                    .keyboardShortcut(.return, modifiers: .command)
                    .accessibilityLabel("Send")
                    .frame(height: lineHeight)
                }
            }
            .padding(8)
        }
        .animation(.easeInOut(duration: 0.15), value: hint)
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
            .font(.system(size: 18, weight: .medium))
            .foregroundStyle(recorder.isRecording ? AnyShapeStyle(.white) : AnyShapeStyle(.tint))
            .frame(width: 34, height: 34)
            .background(Circle().fill(recorder.isRecording ? (cancelling ? Color.gray : Color.red) : Color.clear))
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
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, minHeight: 36)
        .background(Color.incomingBubble, in: RoundedRectangle(cornerRadius: 18))
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
    @State private var choosingPhotos = false
    @State private var choosingFiles = false
    @State private var picked: [PhotosPickerItem] = []
    #if os(iOS)
    @State private var usingCamera = false
    #endif

    var body: some View {
        Menu {
            Button { choosingPhotos = true } label: { Label("Photos and videos", systemImage: "photo.on.rectangle") }
            #if os(iOS)
            if CameraPicker.isAvailable {
                Button { usingCamera = true } label: { Label("Camera", systemImage: "camera") }
            }
            #endif
            Button { choosingFiles = true } label: { Label("Files", systemImage: "doc") }
        } label: {
            Image(systemName: "paperclip")
                .font(.system(size: 20))
                .frame(width: 28)
        }
        .menuStyle(.button)
        .buttonStyle(.borderless)
        .menuIndicator(.hidden)
        .fixedSize()
        .disabled(tray.room == 0)
        .accessibilityLabel("Attach")
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

/// What's going with the next message, each with a button to take it out.
private struct TrayStrip: View {
    let tray: AttachmentTray

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(tray.items) { item in
                    TrayTile(item: item)
                        .overlay(alignment: .topTrailing) {
                            Button { tray.remove(item.id) } label: {
                                Image(systemName: "xmark.circle.fill")
                                    .symbolRenderingMode(.palette)
                                    .foregroundStyle(.white, .black.opacity(0.6))
                                    .font(.system(size: 20))
                            }
                            .buttonStyle(.plain)
                            .padding(3)
                            .accessibilityLabel("Remove")
                        }
                }
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
        }
    }
}

private struct TrayTile: View {
    let item: AttachmentTray.Item

    var body: some View {
        ZStack {
            Color.incomingBubble
            switch item.state {
            case .preparing:
                ProgressView()
            case .ready(let file):
                if let preview = file.preview {
                    Image(decorative: preview, scale: 1)
                        .resizable()
                        .scaledToFill()
                    if file.attachment.kind == .video {
                        Image(systemName: "play.fill")
                            .font(.caption)
                            .foregroundStyle(.white)
                            .padding(6)
                            .background(.black.opacity(0.45), in: Circle())
                    }
                } else {
                    VStack(spacing: 2) {
                        Image(systemName: "doc")
                        Text(file.attachment.fileName)
                            .font(.caption2)
                            .lineLimit(2)
                            .truncationMode(.middle)
                    }
                    .padding(4)
                }
            case .failed(let message):
                Label(message, systemImage: "exclamationmark.triangle")
                    .font(.caption2)
                    .foregroundStyle(.red)
                    .padding(6)
            }
        }
        .frame(width: isFailed ? 150 : 64, height: 64)
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var isFailed: Bool {
        if case .failed = item.state { return true }
        return false
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
