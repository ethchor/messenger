import CoreImage.CIFilterBuiltins
import SwiftUI
#if os(iOS)
import UIKit
#endif

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @Environment(Appearance.self) private var appearance
    @Environment(\.dismiss) private var dismiss
    @Environment(\.isPresented) private var isPresented

    @State private var newKey: String?
    @State private var confirmNewKey = false
    @State private var confirmRemove = false
    @State private var showDelete = false
    @State private var showReset = false
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
        .navigationTitle("You")
        #if os(iOS)
        // The design's title: "You", large, in the display face.
        .safeAreaInset(edge: .top, spacing: 0) {
            HStack {
                Text("You")
                    .font(.display(30))
                    .displayTracking(30)
                    .foregroundStyle(Palette.text)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                // In a sheet; as the You tab there's nothing to close.
                if isPresented {
                    Button("Done") { dismiss() }
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Palette.text)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 8)
            .background(Palette.home.opacity(0.92))
        }
        .toolbar(.hidden, for: .navigationBar)
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
        .sheet(isPresented: $showReset) {
            NavigationStack { ResetIdentityView() }
                #if os(macOS)
                .frame(minWidth: 480, minHeight: 460)
                #endif
        }
    }

    private func form(_ account: AccountInfo) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                MeCard(username: account.username, online: model.isOnline) {
                    profilePage
                }
                KitLabel("Settings").padding(.horizontal, 2)
                KitCard(radius: 24) {
                    HomeRow(title: "Chats and appearance", hint: "Theme, ink, paper", value: appearance.ink.name,
                            systemImage: "paintpalette.fill", tint: Palette.tileYellow) { lookPage }
                    HomeRow(title: "Notifications", hint: notificationsHint, systemImage: "bell.fill", tint: Palette.tilePeach) {
                        DesignPage("Notifications") { NotificationsSection() }
                    }
                    HomeRow(title: "Privacy", hint: "Read receipts, typing", systemImage: "eye.fill", tint: Palette.tileLavender) {
                        privacyPage
                    }
                    HomeRow(title: "Security", hint: "App lock, panic wipe", value: lock.enabled ? lock.methodName : "Off",
                            systemImage: "checkmark.shield.fill", tint: Palette.tileMint) { securityPage }
                    HomeRow(title: "Linked devices", hint: "Your phone, Mac and other devices", systemImage: "laptopcomputer.and.iphone",
                            tint: Palette.tileApricot) { LinkedDevicesView() }
                    HomeRow(title: "Account", hint: "Username, recovery key", systemImage: "key.fill", tint: Palette.tileSky, last: true) {
                        accountPage(account)
                    }
                }
                KitLabel("Account").padding(.horizontal, 2)
                KitCard(radius: 24) {
                    NavigationLink { accountPage(account) } label: {
                        HStack(spacing: 12) {
                            Text("Recovery key").font(.system(size: 16)).frame(maxWidth: .infinity, alignment: .leading)
                            recoveryPill
                        }
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, 16)
                        .frame(height: 56)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    KitSeparator()
                    KitValue("Server", value: URL(string: account.serverUrl)?.host() ?? account.serverUrl)
                    KitSeparator()
                    KitLink(label: "Safety numbers", value: safetyCount) { SafetyNumbersView() }
                }
                KitCard(radius: 24) {
                    KitButton(label: "Remove from this device", weight: .medium, centred: true, height: 56) { confirmRemove = true }
                    KitSeparator()
                    KitButton(label: "Delete account", color: Palette.danger, weight: .semibold, centred: true, height: 56) { showDelete = true }
                }
                .disabled(working)
                if working {
                    ProgressView().frame(maxWidth: .infinity)
                }
                if let error {
                    Text(error).foregroundStyle(Palette.danger).font(.footnote).frame(maxWidth: .infinity)
                }
                Text("Messenger \(appVersion) · MLS encryption with a post-quantum key exchange")
                    .font(.system(size: 12.5))
                    .foregroundStyle(Palette.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 20)
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            .padding(.bottom, 24)
            .frame(maxWidth: 640)
            .frame(maxWidth: .infinity)
        }
        .background { HomeGround().ignoresSafeArea() }
        #if os(iOS)
        .contentMargins(.bottom, 90, for: .scrollContent)
        #endif
        .confirmationDialog("Create a new recovery key?", isPresented: $confirmNewKey, titleVisibility: .visible) {
            Button("Create new key") { Task { await createNewKey() } }
        } message: {
            Text("Your current key stops working the moment you make a new one.")
        }
        .confirmationDialog("Remove your account from this device?", isPresented: $confirmRemove, titleVisibility: .visible) {
            Button("Remove", role: .destructive) { Task { await removeFromDevice() } }
        } message: {
            Text("You'll leave all your chats on this device. To sign in again you need your recovery key, and old messages won't come back.")
        }
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? coreVersion()
    }

    private var notificationsHint: String {
        let n = model.notifications
        guard n.enabled else { return "Off" }
        return n.previews ? "Names and messages shown" : "Nothing readable on the lock screen"
    }

    private var safetyCount: String {
        let n = model.conversations.filter { $0.kind == .direct && $0.active }.count
        return n == 1 ? "1 chat" : "\(n) chats"
    }

    @ViewBuilder private var recoveryPill: some View {
        if model.unconfirmedRecoveryKey != nil {
            KitPill(text: "Not written down", dot: Color(rgb: 0xE5484D))
        } else {
            KitPill(text: "Written down")
        }
    }

    private var profilePage: some View {
        DesignPage("Profile") { ProfilePage() }
    }

    private var lookPage: some View {
        DesignPage("Chats and appearance") { LookPage() }
    }

    private var privacyPage: some View {
        DesignPage("Privacy") {
            KitCard {
                KitToggle("Read receipts", isOn: privacyBinding(\.readReceipts))
                KitSeparator()
                KitToggle("Typing indicators", isOn: privacyBinding(\.typingIndicators))
            }
            KitFoot("These go both ways: turn one off and you won’t see it from others either.")
        }
    }

    private var securityPage: some View {
        DesignPage("Security") {
            KitCard {
                KitToggle("Lock with \(lock.methodName)", hint: !lock.isAvailable && !lock.enabled ? "Set a passcode on this device first" : nil, isOn: lockBinding)
                    .disabled(!lock.isAvailable && !lock.enabled)
                if lock.enabled {
                    KitSeparator()
                    HStack {
                        Text("Lock after").font(.system(size: 16))
                        Spacer()
                        Picker("Lock after", selection: timeoutBinding) {
                            ForEach(AppLock.Timeout.allCases) { timeout in
                                Text(timeout.label).tag(timeout)
                            }
                        }
                        .labelsHidden()
                        .tint(Palette.secondary)
                    }
                    .padding(.horizontal, 14)
                    .frame(minHeight: 54)
                }
                KitSeparator()
                #if os(iOS)
                KitToggle("Hide in app switcher", isOn: Binding(get: { lock.hideContent }, set: { lock.setHideContent($0) }))
                #else
                KitToggle("Block screenshots and screen sharing", isOn: Binding(get: { lock.blockScreenCapture }, set: { lock.setBlockScreenCapture($0) }))
                #endif
            }
            KitCard {
                KitToggle("Panic wipe", hint: panicWipeShort, isOn: Binding(get: { lock.panicWipe }, set: { lock.setPanicWipe($0) }))
            }
            .padding(.top, 8)
            KitFoot("No confirmation, no network, nothing sent. Your recovery key brings the account back, without the old messages.")
            KitCard {
                KitLink(label: "Safety numbers", value: safetyCount) { SafetyNumbersView() }
            }
            KitFoot("Compare a safety number with a friend in person to be sure nobody is in the middle of your chat.")
        }
    }

    private func accountPage(_ account: AccountInfo) -> some View {
        DesignPage("Account") {
            KitCard {
                KitValue("Username", value: "@\(account.username)", hint: "Yours for good. Deleting the account frees it")
                KitSeparator()
                if let key = model.unconfirmedRecoveryKey {
                    Button { newKey = key } label: {
                        KitValue(label: "Recovery key", hint: "Not confirmed as written down. Tap to see it") { recoveryPill }
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                } else {
                    KitValue(label: "Recovery key") { recoveryPill }
                }
                KitSeparator()
                KitValue("Server", value: URL(string: account.serverUrl)?.host() ?? account.serverUrl)
                KitSeparator()
                KitValue(label: "Account ID") {
                    Text(account.accountId).font(.caption.monospaced()).lineLimit(1).truncationMode(.middle).textSelection(.enabled)
                }
            }
            KitCard {
                KitButton(label: "Create a new recovery key", color: appearance.ink.color, weight: .medium) { confirmNewKey = true }
                    .disabled(working)
            }
            .padding(.top, 8)
            KitFoot("Your current key stops working the moment you make a new one. Nobody else has it, not even the server.")
            KitCard {
                KitButton(label: "Reset identity", color: Palette.danger, weight: .semibold, centred: true) { showReset = true }
            }
            .padding(.top, 8)
            KitFoot("Only for a stolen device. Your contacts will see a new safety number afterwards.")
        }
    }

    private var panicWipeShort: String {
        #if os(macOS)
        return "Press ⌥⇧⌘⌫ to erase this Mac instantly"
        #else
        return "Triple-tap “Chats” to erase this phone instantly"
        #endif
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
        KitCard {
            KitToggle("Notifications", isOn: Binding(
                get: { notifications.enabled },
                set: { on in Task { await model.setNotificationsEnabled(on) } }
            ))
            if notifications.enabled {
                KitSeparator()
                KitToggle("Show names and messages", isOn: Binding(
                    get: { notifications.previews },
                    set: { notifications.setPreviews($0) }
                ))
                if notifications.authorization == .denied {
                    KitSeparator()
                    KitValue(label: "Turned off in \(systemSettingsName)") {
                        Button("Open") { openSystemSettings() }
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Palette.text)
                            .buttonStyle(.plain)
                    }
                }
            }
        }
        .task { await notifications.refreshAuthorization() }
        KitFoot("App lock doesn’t cover notifications. Hide names and messages to keep your chats behind the lock.")
        if notifications.enabled {
            KitCard {
                delivery
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .frame(minHeight: 54, alignment: .leading)
            }
            .padding(.top, 8)
        }
        KitFoot("A push carries nothing readable. This device fetches and decrypts new messages itself. To wake the right devices, the server learns who you chat with; with notifications off, it doesn’t.")
    }

    /// How notifications reach this device.
    @ViewBuilder private var delivery: some View {
        switch notifications.push {
        case .on:
            HStack {
                Text("Delivery").font(.system(size: 16))
                Spacer()
                Text("Instant").font(.system(size: 14.5)).foregroundStyle(Palette.secondary)
            }
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

/// A row on You: an icon tile, what's there, a line about it, and a value.
private struct HomeRow<Destination: View>: View {
    let title: String
    let hint: String
    var value: String = ""
    let systemImage: String
    let tint: Color
    var last = false
    @ViewBuilder var destination: () -> Destination

    var body: some View {
        NavigationLink(destination: destination) {
            HStack(spacing: 14) {
                IconTile(systemImage: systemImage, tint: tint)
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(title).font(.system(size: 16, weight: .medium))
                        Text(hint)
                            .font(.system(size: 12.5))
                            .foregroundStyle(Palette.secondary)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    if !value.isEmpty {
                        Text(value).font(.system(size: 14)).foregroundStyle(Palette.secondary)
                    }
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Palette.secondary)
                }
                .frame(maxHeight: .infinity)
                .overlay(alignment: .bottom) {
                    if !last { Rectangle().fill(Palette.line).frame(height: 1) }
                }
            }
            .foregroundStyle(Palette.text)
            .padding(.horizontal, 14)
            .frame(height: 62)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// You, at the top of the You tab: a card in your ink with your stamp,
/// your name, and ways to edit or share your profile.
private struct MeCard<Profile: View>: View {
    @Environment(Appearance.self) private var appearance
    let username: String
    let online: Bool
    @ViewBuilder var profile: () -> Profile
    @State private var boing = 0

    private var cardColor: Color {
        appearance.ink == .marigold ? Palette.marigold : appearance.ink.pastel
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 16) {
                MyAvatar(size: 92, stamp: true)
                    .background(RoundedRectangle(cornerRadius: 30, style: .continuous).fill(.white))
                    .overlay(RoundedRectangle(cornerRadius: 30, style: .continuous).strokeBorder(Palette.ink, lineWidth: 2))
                    .background(RoundedRectangle(cornerRadius: 30, style: .continuous).fill(Palette.ink).offset(y: 6))
                    .phaseAnimator([1.0, 0.86, 1.1, 1.0], trigger: boing) { view, scale in
                        view.scaleEffect(scale)
                    } animation: { _ in .spring(duration: 0.22, bounce: 0.5) }
                    .onTapGesture { boing += 1 }
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    Text("@\(username)")
                        .font(.system(size: 30, weight: .bold))
                        .displayTracking(30)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Label(online ? "Sealed · \(appearance.ink.name) ink" : "Offline", systemImage: online ? "lock.fill" : "wifi.slash")
                        .font(.system(size: 12.5, weight: .semibold))
                        .padding(.horizontal, 10)
                        .frame(height: 26)
                        .background(Capsule().fill(.white.opacity(0.7)))
                }
            }
            HStack(spacing: 8) {
                NavigationLink {
                    profile()
                } label: {
                    Label("Edit profile", systemImage: "pencil")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.cream)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(Capsule().fill(Palette.ink))
                }
                .buttonStyle(PressScale())
                ShareLink(item: "@\(username)", message: Text("Find me on Messenger: @\(username)")) {
                    Label("Share @\(username)", systemImage: "square.and.arrow.up")
                        .font(.system(size: 15, weight: .semibold))
                        .lineLimit(1)
                        .foregroundStyle(Palette.ink)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(Capsule().fill(.white))
                        .overlay(Capsule().strokeBorder(Palette.ink, lineWidth: 1.5))
                }
                .buttonStyle(PressScale())
            }
        }
        .foregroundStyle(Palette.ink)
        .padding(20)
        .background {
            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .fill(cardColor)
                .overlay {
                    // A faint dotted texture, fading in from the corner.
                    Canvas { context, size in
                        var dots = Path()
                        for x in stride(from: CGFloat(9), to: size.width, by: 18) {
                            for y in stride(from: CGFloat(9), to: size.height, by: 18) {
                                dots.addEllipse(in: CGRect(x: x - 1.6, y: y - 1.6, width: 3.2, height: 3.2))
                            }
                        }
                        context.fill(dots, with: .color(Palette.ink.opacity(0.11)))
                    }
                    .mask(LinearGradient(colors: [.clear, .black], startPoint: .topLeading, endPoint: .bottomTrailing))
                    .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
                }
        }
        // Your shapes: a heart, a sparkle and a squiggle. Tap one to squash it.
        .overlay(alignment: .topTrailing) {
            ZStack(alignment: .topTrailing) {
                ShapeSticker(kind: .heart, size: 60, delay: 0.08, tappable: true)
                    .padding(.top, 8).padding(.trailing, 12)
                ShapeSticker(kind: .sparkle, size: 36, delay: 0.2, phase: -1.2, tappable: true)
                    .padding(.top, 12).padding(.trailing, 84)
                ShapeSticker(kind: .squiggle, size: 40, delay: 0.3, tappable: true)
                    .padding(.top, 92).padding(.trailing, 16)
            }
        }
        .animation(.easeInOut(duration: 0.3), value: appearance.ink)
    }
}
