import CoreImage.CIFilterBuiltins
import SwiftUI
#if os(iOS)
import UIKit
#endif

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(\.dismiss) private var dismiss
    @Environment(\.isPresented) private var isPresented

    @State private var newKey: String?
    @State private var confirmNewKey = false
    @State private var confirmRemove = false
    @State private var showDelete = false
    @State private var working = false
    @State private var error: String?

    var body: some View {
        Group {
            if let account = model.account, model.phase == .ready {
                form(account)
            } else {
                ContentUnavailableView("No account on this device", systemImage: "person.crop.circle.badge.questionmark")
            }
        }
        .navigationTitle("Settings")
        #if os(iOS)
        .toolbar {
            // In a sheet; as the You tab there's nothing to close.
            if isPresented {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        #endif
        .sheet(item: newKeyItem) { item in
            NavigationStack {
                RecoveryKeyView(recoveryKey: item.key, isReplacement: true) {
                    newKey = nil
                }
            }
            #if os(macOS)
            .frame(minWidth: 520, minHeight: 560)
            #endif
        }
        .sheet(isPresented: $showDelete) {
            DeleteAccountView { lock.reset() }
        }
    }

    private func form(_ account: AccountInfo) -> some View {
        Form {
            Section {
                NavigationLink {
                    profilePage(account)
                } label: {
                    ProfileHeader(username: account.username, online: model.isOnline)
                }
            }

            Section {
                NavigationLink {
                    profilePage(account)
                } label: {
                    SettingsRow("Profile", detail: "Avatar, ink, username", systemImage: "person.crop.circle")
                }
                NavigationLink {
                    SettingsPage("Account") { accountSections(account) }
                } label: {
                    SettingsRow("Account", detail: "Recovery key, account details", systemImage: "key")
                }
                NavigationLink {
                    SettingsPage("Privacy") { privacySections }
                } label: {
                    SettingsRow("Privacy", detail: "Receipts, typing, app lock, panic wipe", systemImage: "lock")
                }
                NavigationLink {
                    SettingsPage("Chats") { AppearanceSection(part: .chats) }
                } label: {
                    SettingsRow("Chats", detail: "Chat paper, wallpapers", systemImage: "bubble.left.and.bubble.right")
                }
                NavigationLink {
                    SettingsPage("Notifications") { NotificationsSection() }
                } label: {
                    SettingsRow("Notifications", detail: "Messages, what they show", systemImage: "bell")
                }
                NavigationLink {
                    SettingsPage("About") { aboutSection }
                } label: {
                    SettingsRow("About", detail: "Encryption, version", systemImage: "info.circle")
                }
            }

            Section {
                Button(role: .destructive) { confirmRemove = true } label: {
                    Label("Remove from this device", systemImage: "rectangle.portrait.and.arrow.right")
                }
                .disabled(working)
                Button(role: .destructive) { showDelete = true } label: {
                    Label("Delete account", systemImage: "trash")
                }
                .disabled(working)
                if working {
                    ProgressView()
                }
                if let error {
                    Text(error).foregroundStyle(.red).font(.footnote)
                }
            } footer: {
                Text("Removing the account from this device leaves your chats here and signs you out; your recovery key brings the account back, without the old messages. Deleting it also frees your username for good.")
            }
        }
        .formStyle(.grouped)
        .confirmationDialog("Create a new recovery key?", isPresented: $confirmNewKey, titleVisibility: .visible) {
            Button("Create new key") { Task { await createNewKey() } }
        } message: {
            Text("Your current recovery key will stop working.")
        }
        .confirmationDialog("Remove your account from this device?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { Task { await removeFromDevice() } }
        } message: {
            Text("You'll leave all your chats on this device. To sign in again you need your recovery key, and old messages won't come back.")
        }
    }

    private func profilePage(_ account: AccountInfo) -> some View {
        SettingsPage("Profile") {
            AppearanceSection(part: .profile)
            Section {
                LabeledContent("Username", value: account.username)
            } footer: {
                Text("Your username is how people start a chat with you. It can't be changed; deleting the account frees it.")
            }
        }
    }

    @ViewBuilder
    private func accountSections(_ account: AccountInfo) -> some View {
        Section("Account") {
            LabeledContent("Username", value: account.username)
            LabeledContent("Account ID") {
                Text(account.accountId)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
            }
            LabeledContent("Server", value: account.serverUrl)
        }
        Section {
            if model.unconfirmedRecoveryKey != nil {
                Label("Not confirmed as written down", systemImage: "exclamationmark.triangle.fill")
                    .foregroundStyle(.orange)
                Button("Show recovery key") { newKey = model.unconfirmedRecoveryKey }
            } else {
                Label("Written down", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
            }
            Button("Create a new recovery key") { confirmNewKey = true }
                .disabled(working)
        } header: {
            Text("Recovery key")
        } footer: {
            Text("Your recovery key moves your account to a new device. Nobody else has it, not even the server. If you lost it, create a new one; the old one stops working.")
        }
    }

    @ViewBuilder
    private var privacySections: some View {
        Section {
            Toggle("Read receipts", isOn: privacyBinding(\.readReceipts))
            Toggle("Typing indicators", isOn: privacyBinding(\.typingIndicators))
        } header: {
            Text("Privacy")
        } footer: {
            Text("These work both ways: if you turn one off, you won't see it from others either.")
        }
        Section {
            Toggle("Lock with \(lock.methodName)", isOn: lockBinding)
                .disabled(!lock.isAvailable && !lock.enabled)
            if lock.enabled {
                Picker("Lock", selection: timeoutBinding) {
                    ForEach(AppLock.Timeout.allCases) { timeout in
                        Text(timeout.label).tag(timeout)
                    }
                }
            }
            #if os(iOS)
            Toggle("Hide in app switcher", isOn: Binding(get: { lock.hideContent }, set: { lock.setHideContent($0) }))
            #else
            Toggle("Block screenshots and screen sharing", isOn: Binding(get: { lock.blockScreenCapture }, set: { lock.setBlockScreenCapture($0) }))
            #endif
            Toggle("Panic wipe", isOn: Binding(get: { lock.panicWipe }, set: { lock.setPanicWipe($0) }))
            NavigationLink("Safety numbers") { SafetyNumbersView() }
        } header: {
            Text("Security")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if !lock.isAvailable && !lock.enabled {
                    Text("Set a passcode on this device to use app lock.")
                }
                Text(panicWipeHint)
                Text("Safety numbers let you check, in person, that nobody is in the middle of a chat.")
            }
        }
    }

    private var aboutSection: some View {
        Section {
            LabeledContent("Encryption", value: "MLS (RFC 9420)")
            LabeledContent("Key exchange", value: "X-Wing: ML-KEM-768 + X25519")
            LabeledContent("Core version", value: coreVersion())
        } footer: {
            Text("Messages are end-to-end encrypted, and the server stores only ciphertext. Without notifications it can't tell who talks to whom; with them, it learns which accounts each message wakes.")
        }
    }

    private var panicWipeHint: String {
        #if os(macOS)
        return "Panic wipe: press ⌥⇧⌘⌫ to erase everything on this Mac at once. There's no confirmation and nothing is sent; your recovery key brings the account back, without the old messages."
        #else
        return "Panic wipe: triple-tap “Chats” to erase everything on this device at once. There's no confirmation and nothing is sent; your recovery key brings the account back, without the old messages."
        #endif
    }

    // MARK: - Bindings

    private func privacyBinding(_ keyPath: WritableKeyPath<PrivacySettings, Bool>) -> Binding<Bool> {
        Binding(
            get: { model.privacy[keyPath: keyPath] },
            set: { value in
                var changed = model.privacy
                changed[keyPath: keyPath] = value
                let settings = changed
                Task { await model.setPrivacy(settings) }
            }
        )
    }

    private var lockBinding: Binding<Bool> {
        Binding(get: { lock.enabled }, set: { on in Task { await lock.setEnabled(on) } })
    }

    private var timeoutBinding: Binding<AppLock.Timeout> {
        Binding(get: { lock.timeout }, set: { lock.setTimeout($0) })
    }

    private var newKeyItem: Binding<KeyItem?> {
        Binding(get: { newKey.map(KeyItem.init) }, set: { if $0 == nil { newKey = nil } })
    }

    // MARK: - Actions

    private func createNewKey() async {
        working = true
        defer { working = false }
        do {
            newKey = try await model.newRecoveryKey()
            error = nil
        } catch {
            self.error = error.userMessage
        }
    }

    private func removeFromDevice() async {
        working = true
        defer { working = false }
        do {
            try await model.removeFromDevice()
            lock.reset()
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}

/// Notifications: on or off, how they reach this device, and what they show.
private struct NotificationsSection: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(\.openURL) private var openURL

    private var notifications: MessageNotifications { model.notifications }

    var body: some View {
        Section {
            Toggle("Notifications", isOn: Binding(
                get: { notifications.enabled },
                set: { on in Task { await model.setNotificationsEnabled(on) } }
            ))
            if notifications.enabled {
                if notifications.authorization == .denied {
                    Label("Turned off in \(systemSettingsName)", systemImage: "bell.slash.fill")
                        .foregroundStyle(.orange)
                    Button("Open \(systemSettingsName)") { openSystemSettings() }
                }
                delivery
                Toggle("Show names and messages", isOn: Binding(
                    get: { notifications.previews },
                    set: { notifications.setPreviews($0) }
                ))
            }
        } header: {
            Text("Notifications")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if notifications.enabled && notifications.previews && lock.enabled {
                    Text("App lock doesn't cover notifications: anyone holding your unlocked device can read them. Turn off names and messages to keep your chats behind the lock.")
                }
                Text("A push carries nothing readable: no names, no text. This device fetches and decrypts new messages itself. To wake you, the server learns when a message arrives for you and who else the same message wakes. Turn notifications off to keep that from it.")
            }
        }
        .task { await notifications.refreshAuthorization() }
    }

    /// How notifications reach this device.
    @ViewBuilder private var delivery: some View {
        switch notifications.push {
        case .on:
            Label("Instant, through Apple's push service", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .registering:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Connecting…")
            }
        case .serverCantPush:
            note("Your server isn't set up to wake Apple devices. \(withoutPush)")
        case .notSigned:
            note("Waking this device takes Apple's push service, and this copy of Messenger isn't signed for it (that needs a paid Apple developer account). \(withoutPush)")
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
            Button("Try again") { Task { await model.setUpNotifications() } }
        case .unknown:
            EmptyView()
        }
    }

    private var withoutPush: String {
        #if os(macOS)
        return "Notifications come while Messenger is running, also with its window closed."
        #else
        return "Notifications come while Messenger is open or sharing your location, and when iOS lets it check in the background now and then."
        #endif
    }

    private func note(_ text: String) -> some View {
        Label {
            Text(text).font(.callout)
        } icon: {
            Image(systemName: "info.circle").foregroundStyle(.secondary)
        }
    }

    private var systemSettingsName: String {
        #if os(macOS)
        return "System Settings"
        #else
        return "Settings"
        #endif
    }

    private func openSystemSettings() {
        #if os(iOS)
        let url = URL(string: UIApplication.openNotificationSettingsURLString)
        #else
        let id = Bundle.main.bundleIdentifier ?? ""
        let url = URL(string: "x-apple.systempreferences:com.apple.Notifications-Settings.extension?id=\(id)")
        #endif
        if let url { openURL(url) }
    }
}

private struct KeyItem: Identifiable {
    let key: String
    var id: String { key }
}

/// Deleting the account asks for the username, so it can't happen by accident.
struct DeleteAccountView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    var onDeleted: () -> Void

    @State private var typed = ""
    @State private var working = false
    @State private var error: String?

    private var username: String { model.account?.username ?? "" }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Label("This can't be undone", systemImage: "exclamationmark.octagon.fill")
                        .foregroundStyle(.red)
                        .font(.headline)
                    Text("You'll leave every chat and group, your messages on this device are erased, and the username \(username) becomes free for anyone to take. Your recovery key stops working.")
                }
                Section {
                    TextField("Username", text: $typed)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                } header: {
                    Text("Type \(username) to confirm")
                } footer: {
                    if let error { Text(error).foregroundStyle(.red) }
                }
                Section {
                    Button(role: .destructive) {
                        Task { await delete() }
                    } label: {
                        HStack {
                            Spacer()
                            if working { ProgressView() } else { Text("Delete account").bold() }
                            Spacer()
                        }
                    }
                    .disabled(working || typed.trimmingCharacters(in: .whitespaces).lowercased() != username)
                }
            }
            .formStyle(.grouped)
            .navigationTitle("Delete account")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }.disabled(working)
                }
            }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 420)
        #endif
    }

    private func delete() async {
        working = true
        defer { working = false }
        do {
            try await model.deleteAccount()
            onDeleted()
            dismiss()
        } catch {
            self.error = error.userMessage
        }
    }
}

/// Every 1:1 chat, to compare safety numbers with.
struct SafetyNumbersView: View {
    @Environment(AppModel.self) private var model

    private var chats: [ConversationSummary] {
        model.conversations.filter { $0.kind == .direct && $0.active }
    }

    var body: some View {
        List(chats, id: \.id) { chat in
            NavigationLink {
                SafetyNumberDetail(conversationId: chat.id, title: chat.title)
            } label: {
                HStack {
                    Avatar(title: chat.title, size: 28)
                    Text(chat.title)
                }
            }
        }
        .overlay {
            if chats.isEmpty {
                EmptyState(illustration: .safety, title: "No one-to-one chats yet", text: "Safety numbers are for chats with one other person. Start one, then compare numbers in person.")
            }
        }
        .navigationTitle("Safety numbers")
    }
}

private struct SafetyNumberDetail: View {
    @Environment(AppModel.self) private var model
    let conversationId: String
    let title: String
    @State private var number: SafetyNumber?
    @State private var error: String?

    var body: some View {
        Form {
            Section {
                if let number {
                    SafetyNumberView(number: number)
                } else if let error {
                    Text(error).foregroundStyle(.secondary)
                } else {
                    ProgressView()
                }
            } footer: {
                Text("Compare these digits with \(title) in person, or scan each other's code. If they match, nobody is listening in the middle.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle(title)
        .task {
            do {
                number = try await model.safetyNumber(of: conversationId)
            } catch {
                self.error = error.userMessage
            }
        }
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

/// A page of settings, opened from a row on the first page.
private struct SettingsPage<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content

    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        Form { content }
            .formStyle(.grouped)
            .navigationTitle(title)
    }
}

/// A row on the first page: an icon, what's there, and a line about it.
private struct SettingsRow: View {
    let title: String
    let detail: String
    let systemImage: String

    init(_ title: String, detail: String, systemImage: String) {
        self.title = title
        self.detail = detail
        self.systemImage = systemImage
    }

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.title3)
                .frame(width: 28)
                .foregroundStyle(.tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 3)
        .accessibilityElement(children: .combine)
    }
}

/// You, at the top of settings: your avatar large, your name, whether you're connected.
private struct ProfileHeader: View {
    let username: String
    let online: Bool

    var body: some View {
        VStack(spacing: 8) {
            MyAvatar(size: 88)
            Text(username)
                .font(.title2.weight(.semibold))
            Label(online ? "Connected" : "Offline", systemImage: online ? "checkmark.circle.fill" : "wifi.slash")
                .font(.subheadline)
                .foregroundStyle(online ? AnyShapeStyle(.green) : AnyShapeStyle(.secondary))
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
    }
}
