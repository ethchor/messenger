import CoreImage.CIFilterBuiltins
import SwiftUI

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        Form {
            Section("Account") {
                LabeledContent("Username", value: model.account?.username ?? "–")
                LabeledContent("Account ID") {
                    Text(model.account?.accountId ?? "–")
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                }
            }
            Section("Server") {
                LabeledContent("Address", value: model.account?.serverUrl ?? model.serverURL)
                LabeledContent("Status", value: model.isOnline ? "Connected" : "Offline")
            }
            Section {
                LabeledContent("Encryption", value: "MLS, X-Wing")
                LabeledContent("Key exchange", value: "ML-KEM-768 + X25519")
                LabeledContent("Core version", value: coreVersion())
            } header: {
                Text("About")
            } footer: {
                Text("Your recovery key was shown once when you signed up. Keep it safe: it's the only way to move this account to a new device.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Settings")
        #if os(iOS)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        #endif
    }
}

struct SafetyNumberView: View {
    let number: SafetyNumber

    /// 12 groups of five digits as three rows of four.
    private var rows: String {
        let groups = number.digits.split(separator: " ").map(String.init)
        return stride(from: 0, to: groups.count, by: 4)
            .map { groups[$0..<min($0 + 4, groups.count)].joined(separator: "  ") }
            .joined(separator: "\n")
    }

    var body: some View {
        VStack(spacing: 16) {
            if let image = qrCode(number.qrPayload) {
                Image(decorative: image, scale: 1)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 180, height: 180)
                    .accessibilityLabel("Safety number QR code")
            }
            Text(rows)
                .font(.body.monospaced())
                .multilineTextAlignment(.center)
                .textSelection(.enabled)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }

    private func qrCode(_ payload: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 8, y: 8)) else { return nil }
        return CIContext().createCGImage(output, from: output.extent)
    }
}
