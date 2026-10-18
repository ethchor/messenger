import SwiftUI

struct OnboardingView: View {
    @Environment(AppModel.self) private var model
    @State private var username = ""
    @State private var recoveryKey = ""
    @State private var recovering = false

    private var canSubmit: Bool {
        let name = username.trimmingCharacters(in: .whitespaces)
        return !model.busy && name.count >= 3 && (!recovering || !recoveryKey.isEmpty)
    }

    var body: some View {
        @Bindable var model = model
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        Image(systemName: "lock.shield.fill")
                            .font(.system(size: 44))
                            .foregroundStyle(.tint)
                        Text("Private by design")
                            .font(.title2.bold())
                        Text("Messages are end-to-end encrypted with MLS and a post-quantum key exchange. The server only stores ciphertext and can't tell who talks to whom.")
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 8)
                }

                Section(recovering ? "Recover your account" : "Choose a username") {
                    TextField("Username", text: $username)
                        .textContentType(.username)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                    if recovering {
                        TextField("Recovery key (64 characters)", text: $recoveryKey, axis: .vertical)
                            .font(.body.monospaced())
                            .lineLimit(2...4)
                            .autocorrectionDisabled()
                            #if os(iOS)
                            .textInputAutocapitalization(.never)
                            #endif
                    }
                }

                Section {
                    TextField("Server", text: $model.serverURL)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        #endif
                } header: {
                    Text("Server")
                } footer: {
                    Text("The simulator and this Mac reach a server running on the Mac at http://localhost:8080. On a phone, use the Mac's address on your network.")
                }

                Section {
                    Button {
                        Task { await submit() }
                    } label: {
                        HStack {
                            Spacer()
                            if model.busy {
                                ProgressView()
                            } else {
                                Text(recovering ? "Recover account" : "Create account").bold()
                            }
                            Spacer()
                        }
                    }
                    .disabled(!canSubmit)

                    Button(recovering ? "Create a new account instead" : "I have a recovery key") {
                        recovering.toggle()
                    }
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Welcome")
        }
    }

    private func submit() async {
        if recovering {
            await model.recover(username: username, recoveryKey: recoveryKey)
        } else {
            await model.register(username: username)
        }
    }
}

struct RecoveryKeyView: View {
    @Environment(AppModel.self) private var model
    let recoveryKey: String
    /// Shown from Settings after creating a new key, rather than at sign-up.
    var isReplacement = false
    var onConfirmed: (() -> Void)?
    @State private var wroteItDown = false

    private var groups: [String] {
        recoveryKey.split(separator: " ").map(String.init)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "key.horizontal.fill")
                    .font(.system(size: 40))
                    .foregroundStyle(.tint)
                Text(isReplacement ? "Your new recovery key" : "Save your recovery key")
                    .font(.largeTitle.bold())
                Text(isReplacement
                     ? "Your old recovery key no longer works. Write this one down and keep it somewhere safe; it's the only way to move your account to a new device."
                     : "It's the only way to move your account to a new device. Write it down and keep it somewhere safe. It won't be shown again, and nobody, including the server, can recover it for you.")
                    .foregroundStyle(.secondary)

                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 12) {
                    ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                        Text(group)
                            .font(.title3.monospaced())
                    }
                }
                .padding()
                .background(Color.secondary.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
                .textSelection(.enabled)

                Button {
                    Clipboard.copy(recoveryKey)
                } label: {
                    Label("Copy", systemImage: "doc.on.doc")
                }

                Toggle("I wrote my recovery key down", isOn: $wroteItDown)

                Button {
                    Task {
                        await model.confirmRecoveryKeySaved()
                        if model.unconfirmedRecoveryKey == nil {
                            onConfirmed?()
                        }
                    }
                } label: {
                    Text("Continue").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!wroteItDown || model.busy)
            }
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
    }
}
