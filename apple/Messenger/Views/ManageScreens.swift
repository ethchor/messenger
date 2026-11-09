import SwiftUI

// Recovery key in Settings, using your own server, and the blocked list, as
// designed (R-More "Manage").

// MARK: - Recovery key

/// Settings → Recovery key: a dark card saying only you have it, and making
/// a new one behind Face ID (or the device's own unlock).
struct RecoveryKeyPage: View {
    @Environment(AppModel.self) private var model
    @Environment(AppLock.self) private var lock
    @State private var fresh: String?
    @State private var working = false
    @State private var error: String?

    var body: some View {
        DesignPage("Recovery key") {
            if let key = model.unconfirmedRecoveryKey {
                NewKeyCard(key: key)
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Written down")
                        .font(.system(size: 12.5, weight: .bold))
                        .foregroundStyle(Color(rgb: 0x0F3D24))
                        .padding(.horizontal, 10)
                        .frame(height: 26)
                        .background(Palette.green, in: Capsule())
                    Text("Only you have it")
                        .font(.display(24))
                        .padding(.trailing, 60)
                    Text("We can't show it again: this device stopped keeping it once you said you'd written it down. Lost it? Make a new one.")
                        .font(.system(size: 14))
                        .foregroundStyle(Color(rgb: 0xC9C6BC))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .foregroundStyle(Palette.cream)
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Palette.ink, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    ShapeSticker(kind: .seal, size: 54, delay: 0.05, tappable: true).padding(16)
                }
                Button { Task { await makeNew() } } label: {
                    Group {
                        if working { ProgressView() } else { Text("Make a new recovery key") }
                    }
                    .font(.system(size: 17, weight: .bold))
                    .foregroundStyle(Palette.ink)
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(Palette.marigold, in: Capsule())
                }
                .buttonStyle(PressScale())
                .disabled(working)
                .padding(.top, 6)
                KitFoot(error ?? "The new key works at once and the old one stops working. Your chats and safety number stay as they are.")
            }
        }
    }

    private func makeNew() async {
        guard await lock.confirmOwner(reason: "Confirm it's you to make a new recovery key") else { return }
        working = true
        defer { working = false }
        do {
            _ = try await model.newRecoveryKey()
            error = nil
        } catch {
            self.error = error.userMessage
        }
    }
}

/// A new key: 16 groups on a dark card, blurred until shown; copy it, say
/// it's written down, and Done.
private struct NewKeyCard: View {
    @Environment(AppModel.self) private var model
    let key: String
    @State private var shown = true
    @State private var copied = false
    @State private var saved = false

    private var groups: [String] {
        let clean = key.lowercased().filter { $0.isLetter || $0.isNumber }
        return stride(from: 0, to: clean.count, by: 4).map { start in
            let a = clean.index(clean.startIndex, offsetBy: start)
            let b = clean.index(a, offsetBy: min(4, clean.count - start))
            return String(clean[a..<b])
        }
    }

    var body: some View {
        KitFoot("Write this down now and keep it somewhere offline. Your old key no longer works.")
        VStack(alignment: .leading, spacing: 14) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 10) {
                ForEach(Array(groups.enumerated()), id: \.offset) { _, group in
                    Text(group)
                        .font(.system(size: 17, weight: .medium, design: .monospaced))
                        .tracking(0.8)
                }
            }
            .blur(radius: shown ? 0 : 7)
            .animation(.easeOut(duration: 0.25), value: shown)
            .accessibilityHidden(!shown)
            .textSelection(.enabled)
            HStack(spacing: 8) {
                Button {
                    Clipboard.copy(key)
                    copied = true
                    Task { try? await Task.sleep(for: .seconds(1.5)); copied = false }
                } label: {
                    Text(copied ? "Copied" : "Copy")
                        .font(.system(size: 14.5, weight: .bold))
                        .foregroundStyle(Palette.ink)
                        .padding(.horizontal, 16)
                        .frame(height: 42)
                        .background(copied ? Palette.green : Palette.marigold, in: Capsule())
                }
                .buttonStyle(PressScale())
                Button { shown.toggle() } label: {
                    Text(shown ? "Hide" : "Show")
                        .font(.system(size: 14.5, weight: .semibold))
                        .foregroundStyle(Palette.cream)
                        .padding(.horizontal, 16)
                        .frame(height: 42)
                        .overlay(Capsule().strokeBorder(Color(rgb: 0x4A4840), lineWidth: 1.5))
                }
                .buttonStyle(PressScale())
            }
        }
        .foregroundStyle(Palette.cream)
        .padding(.horizontal, 18)
        .padding(.top, 18)
        .padding(.bottom, 14)
        .background(Palette.ink, in: RoundedRectangle(cornerRadius: 28, style: .continuous))
        KitCard(radius: 22) {
            KitToggle("I've written it down", isOn: $saved)
        }
        .padding(.top, 6)
        Button {
            guard saved else {
                model.showToast("Write it down first, then flip the switch")
                return
            }
            Task {
                await model.confirmRecoveryKeySaved()
                model.showToast("New key saved. The old one stopped working")
            }
        } label: {
            Text("Done")
                .font(.system(size: 17, weight: .bold))
                .foregroundStyle(saved ? Palette.ink : Palette.secondary)
                .frame(maxWidth: .infinity, minHeight: 56)
                .background(saved ? Palette.marigold : Palette.line, in: Capsule())
        }
        .buttonStyle(PressScale())
        .padding(.top, 6)
    }
}

// MARK: - Your own server

/// From onboarding's "Use my own": an address, checked before you sign up.
struct OwnServerView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var address = ""
    @State private var state: Check = .idle
    @State private var shake = 0

    enum Check: Equatable { case idle, checking, ok, bad }

    private var cleaned: String {
        var a = address.trimmingCharacters(in: .whitespacesAndNewlines)
        for prefix in ["https://", "http://"] where a.lowercased().hasPrefix(prefix) { a = String(a.dropFirst(prefix.count)) }
        while a.hasSuffix("/") { a.removeLast() }
        return a
    }

    /// Local servers are plain http; everything else https.
    private var url: String {
        let local = cleaned.hasPrefix("localhost") || cleaned.hasPrefix("127.0.0.1") || cleaned.hasPrefix("10.0.2.2")
        return (local ? "http://" : "https://") + cleaned
    }

    var body: some View {
        VStack(spacing: 0) {
            ScreenHeader(title: "Your server") { dismiss() }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Use your own server")
                        .font(.display(32))
                        .displayTracking(32)
                        .padding(.horizontal, 20)
                        .padding(.top, 4)
                    Text("Run Messenger's server yourself and sign up on it. Everyone you want to chat with needs an account on the same server.")
                        .font(.system(size: 13))
                        .foregroundStyle(Palette.secondary)
                        .padding(.horizontal, 22)
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Server address").font(.system(size: 13, weight: .semibold)).foregroundStyle(Palette.secondary)
                        HStack(spacing: 6) {
                            Text("https://").font(.system(size: 16)).foregroundStyle(Palette.secondary)
                            TextField("chat.example.com", text: $address)
                                .font(.system(size: 16, weight: .semibold))
                                .autocorrectionDisabled()
                                #if os(iOS)
                                .keyboardType(.URL)
                                .textInputAutocapitalization(.never)
                                #endif
                                .onChange(of: address) { _, _ in state = .idle }
                                .onSubmit { Task { await go() } }
                        }
                        .padding(.leading, 16)
                        .padding(.trailing, 6)
                        .frame(height: 56)
                        .background(Palette.surface, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(borderColour, lineWidth: 1.5))
                        .modifier(ShakeEffect(amount: CGFloat(shake)))
                        HStack(spacing: 8) {
                            if state == .checking { ProgressView().controlSize(.small) }
                            Text(message).font(.system(size: 14)).foregroundStyle(messageColour)
                        }
                        .frame(minHeight: 22)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 4)
                    if state == .ok {
                        KitCard(radius: 22) {
                            KitValue("Server", value: cleaned)
                            KitSeparator()
                            KitValue("Connection", value: url.hasPrefix("https") ? "HTTPS" : "HTTP (this computer)")
                        }
                        .padding(.horizontal, 16)
                    }
                    VStack(spacing: 8) {
                        Button { Task { await go() } } label: {
                            Text(state == .ok ? "Continue on \(cleaned)" : "Check server")
                                .font(.system(size: 17, weight: .bold))
                                .foregroundStyle(cleaned.isEmpty ? Palette.secondary : state == .ok ? Palette.ink : Palette.cream)
                                .frame(maxWidth: .infinity, minHeight: 56)
                                .background(cleaned.isEmpty ? Palette.line : state == .ok ? Palette.marigold : Palette.ink, in: Capsule())
                        }
                        .buttonStyle(PressScale())
                        .disabled(cleaned.isEmpty || state == .checking)
                        Button("Use \(URL(string: AppModel.defaultServerURL)?.host() ?? "the usual server") instead") {
                            model.serverURL = AppModel.defaultServerURL
                            model.showToast("Using \(URL(string: AppModel.defaultServerURL)?.host() ?? "the usual server")")
                            dismiss()
                        }
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Palette.text)
                        .underline()
                        .buttonStyle(.plain)
                        .frame(minHeight: 44)
                    }
                    .padding(.horizontal, 16)
                    Text("Your server stores encrypted messages and files for up to 30 days and can't read them.")
                        .font(.system(size: 13))
                        .foregroundStyle(Palette.secondary)
                        .padding(.horizontal, 22)
                }
                .padding(.bottom, 30)
            }
        }
        .background(Palette.home.ignoresSafeArea())
        .designFields()
        .onAppear {
            if model.serverURL != AppModel.defaultServerURL { address = model.serverURL }
        }
        #if os(macOS)
        .frame(minWidth: 440, minHeight: 560)
        #endif
    }

    private var borderColour: Color {
        switch state {
        case .ok: Kit.okGreen
        case .bad: Color(rgb: 0xE5484D)
        default: Palette.text
        }
    }

    private var message: String {
        switch state {
        case .idle: ""
        case .checking: "Checking \(cleaned)…"
        case .ok: "Messenger server found"
        case .bad: "No Messenger server answered there. Check the address with whoever runs it."
        }
    }

    private var messageColour: Color {
        switch state {
        case .ok: Kit.okGreen
        case .bad: Palette.danger
        default: Palette.secondary
        }
    }

    private func go() async {
        guard !cleaned.isEmpty else { return }
        if state == .ok {
            model.serverURL = url
            model.showToast("Signing up on \(cleaned)")
            dismiss()
            return
        }
        state = .checking
        let ok = await Self.check(url)
        state = ok ? .ok : .bad
        if !ok { withAnimation(.linear(duration: 0.4)) { shake += 1 } }
    }

    /// A Messenger server answers /v1/health with "ok".
    static func check(_ base: String) async -> Bool {
        guard let url = URL(string: base + "/v1/health") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 6
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              (response as? HTTPURLResponse)?.statusCode == 200 else { return false }
        return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines) == "ok"
    }
}

/// Side to side, twice: the field says no.
struct ShakeEffect: GeometryEffect {
    var amount: CGFloat
    var animatableData: CGFloat {
        get { amount }
        set { amount = newValue }
    }
    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 6 * sin(amount * .pi * 4), y: 0))
    }
}

// MARK: - Blocked

/// Everyone you've blocked, each with Unblock; or nobody, with shapes.
struct BlockedView: View {
    @Environment(AppModel.self) private var model
    @State private var blocked: [String] = []
    @State private var loaded = false

    var body: some View {
        DesignPage("Blocked", back: "Privacy") {
            if !blocked.isEmpty {
                KitCard(radius: 24) {
                    ForEach(Array(blocked.enumerated()), id: \.element) { i, name in
                        if i > 0 { KitSeparator() }
                        HStack(spacing: 14) {
                            Avatar(title: name, size: 42, stamp: true)
                            VStack(alignment: .leading, spacing: 1) {
                                Text(name).font(.system(size: 16, weight: .semibold))
                                Text("@\(name)").font(.system(size: 13)).foregroundStyle(Palette.secondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Button {
                                Task {
                                    await model.setBlocked(name, false)
                                    model.showToast("Unblocked \(name). They aren’t told")
                                    await reload()
                                }
                            } label: {
                                Text("Unblock")
                                    .font(.system(size: 13.5, weight: .semibold))
                                    .foregroundStyle(Palette.text)
                                    .padding(.horizontal, 12)
                                    .frame(height: 34)
                                    .overlay(Capsule().strokeBorder(Palette.line, lineWidth: 1.5))
                            }
                            .buttonStyle(PressScale())
                        }
                        .foregroundStyle(Palette.text)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .transition(.move(edge: .leading).combined(with: .opacity))
                    }
                }
            } else if loaded {
                VStack(spacing: 12) {
                    ZStack {
                        ShapeSticker(kind: .moon, size: 70, delay: 0.05).offset(x: -20, y: 6)
                        ShapeSticker(kind: .sparkle, size: 30, delay: 0.2, phase: -1).offset(x: 38, y: -22)
                    }
                    .frame(width: 120, height: 90)
                    Text("Nobody's blocked").font(.display(22))
                    Text("Block someone from the menu in your chat with them.")
                        .font(.system(size: 14.5))
                        .foregroundStyle(Palette.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
                .padding(.horizontal, 30)
            }
            KitFoot("Blocked people can't message you, and their messages in groups don't reach this device. They aren't told. Blocks follow you to every linked device.")
        }
        .animation(.easeOut(duration: 0.3), value: blocked)
        .task { await reload() }
    }

    private func reload() async {
        blocked = await model.blockedUsers()
        loaded = true
    }
}
