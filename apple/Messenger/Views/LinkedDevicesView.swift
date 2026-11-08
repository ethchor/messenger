import AVFoundation
import CoreImage.CIFilterBuiltins
import SwiftUI

// MARK: - On a new device

/// "Link to an existing account": shows a QR code for a device that's
/// already signed in, and waits for it to approve.
struct LinkThisDeviceView: View {
    @Environment(AppModel.self) private var model
    @State private var code: String?
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Text("Link to your account")
                    .font(.largeTitle.weight(.semibold))
                Text("On a device where you're signed in, open Settings → Linked devices → Link a device, and scan this code.")
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
                if let code {
                    QRCodeImage(text: code)
                        .frame(width: 260, height: 260)
                        .padding(12)
                        .background(.white, in: RoundedRectangle(cornerRadius: 16))
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Waiting for approval…").foregroundStyle(.secondary)
                    }
                    Button {
                        Clipboard.copy(code)
                    } label: {
                        Label("Copy the code instead", systemImage: "doc.on.doc")
                    }
                    .font(.footnote)
                } else if let error {
                    Text(error).foregroundStyle(.red)
                } else {
                    ProgressView()
                }
                Text("Your chats, history and settings come across, end-to-end encrypted. You can link up to five devices.")
                    .font(.footnote)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.secondary)
            }
            .padding(24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Link a device")
        .task {
            do {
                code = try await model.beginLink()
                await model.waitForLink()
            } catch {
                self.error = error.userMessage
            }
        }
    }
}

/// A QR code for `text`, drawn sharp at any size.
struct QRCodeImage: View {
    let text: String

    var body: some View {
        if let image = Self.render(text) {
            Image(decorative: image, scale: 1)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .accessibilityLabel("Link code")
        }
    }

    static func render(_ text: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        return CIContext().createCGImage(output, from: output.extent)
    }
}

// MARK: - On a device already signed in

/// Settings → Linked devices.
struct LinkedDevicesView: View {
    @Environment(AppModel.self) private var model
    @State private var devices: [LinkedDevice] = []
    @State private var loading = true
    @State private var error: String?
    @State private var linking = false
    @State private var removing: LinkedDevice?

    var body: some View {
        Form {
            Section {
                ForEach(devices, id: \.deviceId) { device in
                    HStack {
                        Image(systemName: icon(for: device.name))
                            .frame(width: 28)
                            .foregroundStyle(.tint)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(device.name.isEmpty ? "Device" : device.name)
                            Text(device.thisDevice ? "This device" : lastSeen(device))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if !device.thisDevice {
                            Button("Remove", role: .destructive) { removing = device }
                                .buttonStyle(.borderless)
                        }
                    }
                }
                if loading {
                    ProgressView()
                }
            } header: {
                Text("Your devices")
            } footer: {
                Text("Removing a device signs it out and takes it out of every chat. Up to five devices.")
            }
            Section {
                Button {
                    linking = true
                } label: {
                    Label("Link a device", systemImage: "qrcode.viewfinder")
                }
                .disabled(devices.count >= 5)
                if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Linked devices")
        .task { await reload() }
        .refreshable { await reload() }
        .sheet(isPresented: $linking, onDismiss: { Task { await reload() } }) {
            NavigationStack { LinkDeviceFlow() }
                #if os(macOS)
                .frame(minWidth: 480, minHeight: 520)
                #endif
        }
        .confirmationDialog(
            "Remove \(removing?.name ?? "this device")?",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) {
                if let device = removing { Task { await remove(device) } }
            }
        } message: {
            Text("It's signed out and taken out of every chat. If it was lost or stolen, also consider Reset identity in Account.")
        }
    }

    private func reload() async {
        loading = true
        defer { loading = false }
        do {
            devices = try await model.linkedDevices().sorted { a, b in
                a.thisDevice != b.thisDevice ? a.thisDevice : a.addedAtMs < b.addedAtMs
            }
            error = nil
        } catch {
            self.error = error.userMessage
        }
    }

    private func remove(_ device: LinkedDevice) async {
        do {
            try await model.removeDevice(device.deviceId)
            await reload()
        } catch {
            self.error = error.userMessage
        }
    }

    private func lastSeen(_ device: LinkedDevice) -> String {
        guard device.lastSeenMs > 0 else { return "Not active yet" }
        let date = Date(timeIntervalSince1970: TimeInterval(device.lastSeenMs) / 1000)
        return "Active " + date.formatted(.relative(presentation: .named))
    }

    private func icon(for name: String) -> String {
        let lower = name.lowercased()
        if lower.contains("mac") { return "laptopcomputer" }
        if lower.contains("ipad") || lower.contains("tablet") { return "ipad" }
        return "iphone"
    }
}

/// Scan (or paste) the new device's code, then approve it.
struct LinkDeviceFlow: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(\.dismiss) private var dismiss
    @State private var code: String?
    @State private var name = ""
    @State private var pasted = ""
    @State private var withHistory = true
    @State private var working = false
    @State private var error: String?

    var body: some View {
        Group {
            if code != nil {
                approval
            } else {
                scanner
            }
        }
        .navigationTitle("Link a device")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private var scanner: some View {
        Form {
            #if os(iOS)
            Section {
                QRScanner { scanned in Task { await read(scanned) } }
                    .frame(height: 320)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    .listRowInsets(EdgeInsets())
            } footer: {
                Text("On the new device, choose \"Link to an existing account\" and point the camera at its code.")
            }
            #endif
            Section {
                TextField("messenger-link:…", text: $pasted, axis: .vertical)
                    .font(.caption.monospaced())
                    .lineLimit(2...4)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                Button("Continue") { Task { await read(pasted) } }
                    .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            } header: {
                Text("Or paste the code")
            } footer: {
                Text("The new device can copy its code. Copy it there and paste it here.")
            }
            if let error {
                Text(error).foregroundStyle(.red).font(.footnote)
            }
        }
        .formStyle(.grouped)
    }

    private var approval: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Link \u{201C}\(name)\u{201D}?")
                        .font(.title2.weight(.semibold))
                    Text("It will be signed in as you, and get every message from now on. Only link devices you own.")
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 6)
            }
            Section {
                Toggle("Bring my chat history", isOn: $withHistory)
            } footer: {
                Text(withHistory
                     ? "Your chats and messages come across, encrypted. Photos and files download when opened, while they're still on the server."
                     : "The new device starts fresh: your chats are there, but empty.")
            }
            Section {
                Button {
                    Task { await approve() }
                } label: {
                    HStack {
                        Spacer()
                        if working { ProgressView() } else { Text("Link").bold() }
                        Spacer()
                    }
                }
                .disabled(working)
                if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
            }
        }
        .formStyle(.grouped)
    }

    private func read(_ text: String) async {
        guard code == nil else { return }
        let text = text.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            name = try await model.linkRequestName(text)
            if name.isEmpty { name = "New device" }
            code = text
            error = nil
        } catch {
            self.error = error.userMessage
        }
    }

    private func approve() async {
        guard let code else { return }
        guard await lock.confirmOwner(reason: "Link \(name) to your account") else { return }
        working = true
        defer { working = false }
        do {
            try await model.approveLink(code, withHistory: withHistory)
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}

#if os(iOS)
/// The camera, reading QR codes.
struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var preview: AVCaptureVideoPreviewLayer?
        private var done = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard granted else { return }
                DispatchQueue.main.async { self?.configure() }
            }
        }

        private func configure() {
            guard let camera = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: camera),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            let layer = AVCaptureVideoPreviewLayer(session: session)
            layer.videoGravity = .resizeAspectFill
            layer.frame = view.bounds
            view.layer.addSublayer(layer)
            preview = layer
            let session = session
            DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            preview?.frame = view.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            let session = session
            DispatchQueue.global(qos: .userInitiated).async { session.stopRunning() }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !done,
                  let text = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue,
                  text.hasPrefix("messenger-link:") else { return }
            done = true
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            onCode?(text)
        }
    }
}
#endif

// MARK: - Reset identity

/// For a lost or stolen device: a new account key, with the recovery key.
struct ResetIdentityView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(\.dismiss) private var dismiss
    @State private var recoveryKey = ""
    @State private var working = false
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                Text("If one of your devices was lost or stolen, this signs out every device except this one and takes them out of every chat.")
                Text("Your chats end, and carry on when you or the other person start them again. Everyone sees a new safety number for you.")
                    .foregroundStyle(.secondary)
            }
            Section("Recovery key") {
                TextField("64 characters", text: $recoveryKey, axis: .vertical)
                    .font(.body.monospaced())
                    .lineLimit(2...4)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
            }
            Section {
                Button(role: .destructive) {
                    Task { await reset() }
                } label: {
                    HStack {
                        Spacer()
                        if working { ProgressView() } else { Text("Reset identity").bold() }
                        Spacer()
                    }
                }
                .disabled(working || recoveryKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Reset identity")
        .toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private func reset() async {
        guard await lock.confirmOwner(reason: "Reset your identity") else { return }
        working = true
        defer { working = false }
        do {
            try await model.resetIdentity(recoveryKey: recoveryKey)
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}
