#if os(macOS)
import AppKit
import AVFoundation
import SwiftUI

/// The chat list's camera button on the Mac. It offers this Mac's camera and,
/// through Continuity Camera, a nearby iPhone's: macOS fills in the iPhone's
/// items itself when it finds one.
struct MacCameraButton: NSViewRepresentable {
    let onMacCamera: () -> Void
    let onPhoto: (Data) -> Void

    func makeNSView(context: Context) -> CameraMenuButton {
        let button = CameraMenuButton()
        button.onMacCamera = onMacCamera
        button.onPhoto = onPhoto
        return button
    }

    func updateNSView(_ button: CameraMenuButton, context: Context) {
        button.onMacCamera = onMacCamera
        button.onPhoto = onPhoto
    }
}

/// A button that opens the camera menu, and takes the picture an iPhone
/// sends back: Continuity Camera answers whatever view in the responder
/// chain asks for images.
final class CameraMenuButton: NSButton, NSServicesMenuRequestor {
    var onMacCamera: () -> Void = {}
    var onPhoto: (Data) -> Void = { _ in }

    init() {
        super.init(frame: .zero)
        image = NSImage(systemSymbolName: "camera", accessibilityDescription: "Camera")
        isBordered = false
        imagePosition = .imageOnly
        setAccessibilityLabel("Camera")
        toolTip = "Take a photo"
        target = self
        action = #selector(showMenu)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError() }

    override var acceptsFirstResponder: Bool { true }

    @objc private func showMenu() {
        let menu = NSMenu()
        let mac = NSMenuItem(title: "This Mac's Camera", action: #selector(useMacCamera), keyEquivalent: "")
        mac.target = self
        menu.addItem(mac)
        menu.addItem(.separator())
        // macOS puts a nearby iPhone's "Take Photo" and the like in its place;
        // with no iPhone around, this title shows, dimmed.
        let iPhone = NSMenuItem(title: "iPhone or iPad (Continuity Camera)", action: nil, keyEquivalent: "")
        iPhone.identifier = NSMenuItem.importFromDeviceIdentifier
        menu.addItem(iPhone)
        window?.makeFirstResponder(self)
        menu.popUp(positioning: nil, at: NSPoint(x: 0, y: bounds.height + 4), in: self)
    }

    @objc private func useMacCamera() {
        onMacCamera()
    }

    override func validRequestor(forSendType sendType: NSPasteboard.PasteboardType?, returnType: NSPasteboard.PasteboardType?) -> Any? {
        if sendType == nil, let returnType, NSImage.imageTypes.contains(returnType.rawValue) {
            return self
        }
        return super.validRequestor(forSendType: sendType, returnType: returnType)
    }

    func writeSelection(to pboard: NSPasteboard, types: [NSPasteboard.PasteboardType]) -> Bool {
        false
    }

    func readSelection(from pboard: NSPasteboard) -> Bool {
        guard let image = NSImage(pasteboard: pboard), let data = image.jpegData else { return false }
        onPhoto(data)
        return true
    }
}

extension NSImage {
    /// JPEG at high quality; preparing it for sending re-encodes it anyway.
    var jpegData: Data? {
        guard let tiff = tiffRepresentation, let bitmap = NSBitmapImageRep(data: tiff) else { return nil }
        return bitmap.representation(using: .jpeg, properties: [.compressionFactor: 0.95])
    }
}

/// This Mac's camera: a live picture and a shutter.
struct MacCameraView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var camera = MacCamera()
    let onPhoto: (Data) -> Void

    var body: some View {
        VStack(spacing: 14) {
            ZStack {
                Color.black
                switch camera.state {
                case .starting:
                    ProgressView()
                case .running:
                    CameraPreview(session: camera.session)
                case .denied:
                    ContentUnavailableView(
                        "Messenger can't use the camera",
                        systemImage: "video.slash",
                        description: Text("Allow it in System Settings → Privacy & Security → Camera.")
                    )
                    .foregroundStyle(.white)
                case .unavailable:
                    ContentUnavailableView("No camera found", systemImage: "video.slash")
                        .foregroundStyle(.white)
                }
            }
            .aspectRatio(4 / 3, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            HStack {
                Button("Cancel") { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button {
                    camera.takePhoto { data in
                        dismiss()
                        onPhoto(data)
                    }
                } label: {
                    Label("Take Photo", systemImage: "camera.fill")
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)
                .disabled(camera.state != .running)
            }
        }
        .padding()
        .frame(width: 560)
        .task { await camera.start() }
        .onDisappear { camera.stop() }
    }
}

/// The capture session behind the camera window.
@MainActor
@Observable
final class MacCamera: NSObject {
    enum State { case starting, running, denied, unavailable }

    private(set) var state = State.starting
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private var onPhoto: ((Data) -> Void)?

    func start() async {
        if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
            _ = await AVCaptureDevice.requestAccess(for: .video)
        }
        guard AVCaptureDevice.authorizationStatus(for: .video) == .authorized else {
            state = .denied
            return
        }
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input), session.canAddOutput(output)
        else {
            state = .unavailable
            return
        }
        session.beginConfiguration()
        session.sessionPreset = .photo
        session.addInput(input)
        session.addOutput(output)
        session.commitConfiguration()
        let session = session
        await Task.detached { session.startRunning() }.value
        state = .running
    }

    func stop() {
        let session = session
        Task.detached { session.stopRunning() }
    }

    func takePhoto(_ done: @escaping (Data) -> Void) {
        onPhoto = done
        output.capturePhoto(with: AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg]), delegate: self)
    }
}

extension MacCamera: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation() else { return }
        Task { @MainActor in
            self.onPhoto?(data)
            self.onPhoto = nil
        }
    }
}

/// The camera's live picture.
private struct CameraPreview: NSViewRepresentable {
    let session: AVCaptureSession

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer = layer
        view.wantsLayer = true
        return view
    }

    func updateNSView(_ view: NSView, context: Context) {}
}
#endif
